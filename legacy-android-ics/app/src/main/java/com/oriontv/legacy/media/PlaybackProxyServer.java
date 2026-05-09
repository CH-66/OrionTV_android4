package com.oriontv.legacy.media;

import android.os.ParcelFileDescriptor;
import android.content.Context;
import android.util.Log;

import com.oriontv.legacy.net.LegacyHttpCompat;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PlaybackProxyServer implements Closeable {
    private static final String TAG = "PlaybackProxy";
    private static final int MAX_MAPPED_URLS = 128;
    private final OkHttpClient client = LegacyHttpCompat.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, String> mappedUrls = new LinkedHashMap<String, String>();
    private ServerSocket serverSocket;
    private volatile boolean running;
    private int port = -1;
    private int nextStreamId = 1;

    public static class PlaybackPipe implements Closeable {
        public final ParcelFileDescriptor readFd;
        public final String label;

        PlaybackPipe(ParcelFileDescriptor readFd, String label) {
            this.readFd = readFd;
            this.label = label;
        }

        @Override
        public void close() throws IOException {
            if (readFd != null) {
                readFd.close();
            }
        }
    }

    public interface CacheCallback {
        void onReady(String filePath);
        void onError(Exception error);
    }

    public synchronized void ensureStarted() throws IOException {
        if (running && serverSocket != null) {
            return;
        }
        serverSocket = new ServerSocket(0, 8);
        port = serverSocket.getLocalPort();
        running = true;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        });
    }

    public synchronized String proxyUrl(String originalUrl) {
        if (originalUrl == null || originalUrl.length() == 0) {
            return originalUrl;
        }
        String lower = originalUrl.toLowerCase(Locale.US);
        if (lower.startsWith("http://127.0.0.1:") || lower.startsWith("http://localhost:")) {
            return originalUrl;
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return originalUrl;
        }
        try {
            ensureStarted();
            String id = rememberUrl(originalUrl);
            String localUrl = "http://127.0.0.1:" + port + "/stream/" + id + playbackExtension(lower);
            Log.d(TAG, "proxyUrl mapped id=" + id + " local=" + localUrl + " upstream=" + originalUrl);
            return localUrl;
        } catch (IOException e) {
            Log.w(TAG, "Failed to start local relay for " + originalUrl, e);
            return originalUrl;
        }
    }

    public PlaybackPipe openHlsPipe(final String originalUrl) {
        if (!looksLikePlaylist(originalUrl)) {
            return null;
        }
        try {
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                    try {
                        String body = fetchText(originalUrl);
                        Log.d(TAG, "Pipe HLS playlist " + originalUrl + " bytes=" + body.length());
                        streamPlaylist(originalUrl, body, output, 0);
                        output.flush();
                    } catch (Exception e) {
                        Log.e(TAG, "Pipe HLS failed " + originalUrl, e);
                    } finally {
                        try {
                            output.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            });
            Log.d(TAG, "openHlsPipe " + originalUrl);
            return new PlaybackPipe(pipe[0], originalUrl);
        } catch (IOException e) {
            Log.w(TAG, "Failed to open HLS pipe for " + originalUrl, e);
            return null;
        }
    }

    public boolean cacheHlsToFile(final Context context, final String originalUrl, final CacheCallback callback) {
        if (!looksLikePlaylist(originalUrl)) {
            return false;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                FileOutputStream output = null;
                try {
                    File dir = new File(context.getCacheDir(), "playback");
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new IOException("Cannot create playback cache");
                    }
                    File file = new File(dir, "hls-" + Integer.toHexString(originalUrl.hashCode()) + ".ts");
                    output = new FileOutputStream(file, false);
                    String body = fetchText(originalUrl);
                    Log.d(TAG, "Cache HLS playlist " + originalUrl + " bytes=" + body.length() + " file=" + file.getAbsolutePath());
                    streamPlaylist(originalUrl, body, output, 0);
                    output.flush();
                    callback.onReady(file.getAbsolutePath());
                } catch (Exception e) {
                    Log.e(TAG, "Cache HLS failed " + originalUrl, e);
                    callback.onError(e);
                } finally {
                    if (output != null) {
                        try {
                            output.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        });
        return true;
    }

    private void acceptLoop() {
        while (running && serverSocket != null) {
            try {
                final Socket socket = serverSocket.accept();
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                });
            } catch (SocketException ignored) {
                running = false;
            } catch (IOException ignored) {
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            InputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.length() == 0) {
                sendError(output, 400, "Bad Request");
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendError(output, 400, "Bad Request");
                return;
            }
            String method = parts[0];
            String path = parts[1];
            Map<String, String> headers = readHeaders(input);
            String upstream = resolveUpstream(path);
            if (upstream == null || upstream.length() == 0) {
                sendError(output, 400, "Missing url");
                return;
            }
            Log.d(TAG, "Incoming " + method + " " + path + " -> " + upstream + " range=" + headers.get("range"));
            relay(method, upstream, headers, output);
        } catch (Exception e) {
            Log.e(TAG, "Relay request failed", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void relay(String method, String upstreamUrl, Map<String, String> requestHeaders, OutputStream output) throws IOException {
        boolean headRequest = "HEAD".equalsIgnoreCase(method);
        Request.Builder builder = new Request.Builder().url(upstreamUrl);
        if (headRequest) {
            builder.head();
        } else {
            builder.get();
        }

        String range = requestHeaders.get("range");
        if (range != null && range.length() > 0) {
            builder.header("Range", range);
        }

        Response response = null;
        try {
            response = client.newCall(builder.build()).execute();
            if (!response.isSuccessful()) {
                Log.w(TAG, "Upstream failed code=" + response.code() + " url=" + upstreamUrl);
                sendError(output, response.code(), "Upstream " + response.code());
                return;
            }
            String contentType = response.header("Content-Type", "");
            boolean playlist = isPlaylist(upstreamUrl, contentType);
            if (playlist && headRequest) {
                Log.d(TAG, "Playlist HEAD masked as TS " + upstreamUrl);
                sendHeaders(output, 200, statusText(200), "video/mp2t", -1, null, null, false);
                output.flush();
                return;
            }
            if (playlist && !headRequest) {
                String body = response.body().string();
                Log.d(TAG, "Playlist flatten " + upstreamUrl + " bytes=" + body.length());
                sendHeaders(output, 200, statusText(200), "video/mp2t", -1, null, null, false);
                streamPlaylist(upstreamUrl, body, output, 0);
                output.flush();
                return;
            }

            String contentRange = response.header("Content-Range");
            boolean partial = response.code() == 206 || contentRange != null;
            long contentLength = bodyLength(response);
            sendHeaders(
                    output,
                    partial ? 206 : response.code(),
                    statusText(partial ? 206 : response.code()),
                    emptyToDefault(contentType, "application/octet-stream"),
                    contentLength,
                    contentRange,
                    response.header("Accept-Ranges"),
                    partial
            );
            Log.d(TAG, "Stream relay code=" + (partial ? 206 : response.code()) + " type=" + contentType + " len=" + contentLength + " range=" + contentRange);
            if (headRequest) {
                output.flush();
                return;
            }

            if (response.body() == null) {
                Log.w(TAG, "Missing upstream body for " + upstreamUrl);
                sendError(output, 502, "Missing upstream body");
                return;
            }
            InputStream upstream = response.body().byteStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = upstream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private long bodyLength(Response response) {
        if (response == null) {
            return -1;
        }
        String header = response.header("Content-Length");
        if (header != null) {
            try {
                return Long.parseLong(header);
            } catch (NumberFormatException ignored) {
            }
        }
        return response.body() == null ? -1 : response.body().contentLength();
    }

    private Map<String, String> readHeaders(InputStream input) throws IOException {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        while (true) {
            String line = readLine(input);
            if (line == null || line.length() == 0) {
                break;
            }
            int split = line.indexOf(':');
            if (split <= 0) {
                continue;
            }
            String name = line.substring(0, split).trim().toLowerCase(Locale.US);
            String value = line.substring(split + 1).trim();
            headers.put(name, value);
        }
        return headers;
    }

    private boolean isPlaylist(String url, String contentType) {
        String lowerUrl = url == null ? "" : url.toLowerCase(Locale.US);
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        return lowerUrl.contains(".m3u8") || lowerType.contains("mpegurl") || lowerType.contains("vnd.apple.mpegurl");
    }

    private boolean looksLikePlaylist(String url) {
        return url != null && url.toLowerCase(Locale.US).contains(".m3u8");
    }

    private synchronized String rememberUrl(String originalUrl) {
        String id = String.valueOf(nextStreamId++);
        if (nextStreamId == Integer.MAX_VALUE) {
            nextStreamId = 1;
        }
        mappedUrls.put(id, originalUrl);
        while (mappedUrls.size() > MAX_MAPPED_URLS) {
            String firstKey = mappedUrls.keySet().iterator().next();
            mappedUrls.remove(firstKey);
        }
        return id;
    }

    private String playbackExtension(String lowerUrl) {
        String path = lowerUrl == null ? "" : lowerUrl;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        if (path.contains(".m3u8") || path.endsWith(".ts") || path.contains(".ts/")) {
            return ".ts";
        }
        if (path.endsWith(".mp4") || path.contains(".mp4/")) {
            return ".mp4";
        }
        if (path.endsWith(".3gp") || path.contains(".3gp/")) {
            return ".3gp";
        }
        return ".stream";
    }

    private void streamPlaylist(String playlistUrl, String body, OutputStream output, int depth) throws IOException {
        if (depth > 3) {
            throw new IOException("Playlist nesting too deep");
        }
        PlaylistParts parts = parsePlaylist(playlistUrl, body);
        if (parts.masterPlaylistUrl != null) {
            Log.d(TAG, "Follow master playlist " + playlistUrl + " -> " + parts.masterPlaylistUrl);
            String nestedBody = fetchText(parts.masterPlaylistUrl);
            streamPlaylist(parts.masterPlaylistUrl, nestedBody, output, depth + 1);
            return;
        }
        Log.d(TAG, "Stream TS segments count=" + parts.segments.size() + " playlist=" + playlistUrl);
        for (int i = 0; i < parts.segments.size(); i++) {
            String segment = parts.segments.get(i);
            Log.d(TAG, "Segment " + (i + 1) + "/" + parts.segments.size() + " " + segment);
            streamSegment(segment, output);
        }
    }

    private PlaylistParts parsePlaylist(String playlistUrl, String body) {
        PlaylistParts parts = new PlaylistParts();
        String[] lines = body.replace("\r", "").split("\n");
        boolean nextIsVariant = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.length() == 0) {
                continue;
            }
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                nextIsVariant = true;
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            String resolved = resolve(playlistUrl, line);
            if (nextIsVariant && parts.masterPlaylistUrl == null) {
                parts.masterPlaylistUrl = resolved;
                nextIsVariant = false;
                continue;
            }
            nextIsVariant = false;
            parts.segments.add(resolved);
        }
        return parts;
    }

    private String fetchText(String url) throws IOException {
        Response response = null;
        try {
            response = client.newCall(new Request.Builder().url(url).build()).execute();
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Playlist fetch failed " + response.code());
            }
            return response.body().string();
        } finally {
            if (response != null) response.close();
        }
    }

    private void streamSegment(String url, OutputStream output) throws IOException {
        Response response = null;
        try {
            response = client.newCall(new Request.Builder().url(url).build()).execute();
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Segment fetch failed " + response.code() + " " + url);
            }
            InputStream input = response.body().byteStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
        } finally {
            if (response != null) response.close();
        }
    }

    private String resolve(String baseUrl, String relative) {
        try {
            return URI.create(baseUrl).resolve(relative).toString();
        } catch (RuntimeException e) {
            return relative;
        }
    }

    private String resolveUpstream(String path) throws Exception {
        String mapped = extractMappedUrl(path);
        if (mapped != null && mapped.length() > 0) {
            return mapped;
        }
        return extractUrl(path);
    }

    private String extractMappedUrl(String path) {
        if (path == null) {
            return null;
        }
        int queryIndex = path.indexOf('?');
        String cleanPath = queryIndex >= 0 ? path.substring(0, queryIndex) : path;
        if (!cleanPath.startsWith("/stream/")) {
            return null;
        }
        String id = cleanPath.substring("/stream/".length());
        int dot = id.indexOf('.');
        if (dot >= 0) {
            id = id.substring(0, dot);
        }
        int slash = id.indexOf('/');
        if (slash >= 0) {
            id = id.substring(0, slash);
        }
        synchronized (this) {
            return mappedUrls.get(id);
        }
    }

    private String extractUrl(String path) throws Exception {
        int queryIndex = path.indexOf('?');
        if (queryIndex < 0) {
            return null;
        }
        String query = path.substring(queryIndex + 1);
        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int split = pair.indexOf('=');
            if (split <= 0) {
                continue;
            }
            String key = pair.substring(0, split);
            if ("url".equals(key)) {
                return URLDecoder.decode(pair.substring(split + 1), "UTF-8");
            }
        }
        return null;
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value == -1) {
                break;
            }
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                buffer.write(value);
            }
        }
        return buffer.toString("UTF-8");
    }

    private void sendError(OutputStream output, int code, String text) throws IOException {
        byte[] body = text.getBytes("UTF-8");
        sendHeaders(output, code, statusText(code), "text/plain; charset=utf-8", body.length, null, "bytes", false);
        output.write(body);
        output.flush();
    }

    private void sendHeaders(
            OutputStream output,
            int code,
            String status,
            String contentType,
            long contentLength,
            String contentRange,
            String acceptRanges,
            boolean partial
    ) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n");
        builder.append("Connection: close\r\n");
        builder.append("Content-Type: ").append(contentType).append("\r\n");
        if (contentLength >= 0) {
            builder.append("Content-Length: ").append(contentLength).append("\r\n");
        }
        builder.append("Accept-Ranges: ").append(emptyToDefault(acceptRanges, "bytes")).append("\r\n");
        if (contentRange != null && contentRange.length() > 0) {
            builder.append("Content-Range: ").append(contentRange).append("\r\n");
        } else if (partial) {
            builder.append("Content-Range: bytes */*\r\n");
        }
        builder.append("\r\n");
        output.write(builder.toString().getBytes("UTF-8"));
    }

    private String emptyToDefault(String value, String fallback) {
        return value == null || value.length() == 0 ? fallback : value;
    }

    private String statusText(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 206:
                return "Partial Content";
            case 301:
                return "Moved Permanently";
            case 302:
                return "Found";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 416:
                return "Range Not Satisfiable";
            case 500:
                return "Internal Server Error";
            case 502:
                return "Bad Gateway";
            case 504:
                return "Gateway Timeout";
            default:
                return "OK";
        }
    }

    private static class PlaylistParts {
        String masterPlaylistUrl;
        List<String> segments = new ArrayList<String>();
    }

    @Override
    public synchronized void close() throws IOException {
        running = false;
        if (serverSocket != null) {
            serverSocket.close();
            serverSocket = null;
        }
        executor.shutdownNow();
    }
}
