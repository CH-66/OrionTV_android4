package com.oriontv.legacy.media;

import android.net.Uri;
import android.util.Log;

import com.oriontv.legacy.net.LegacyHttpCompat;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
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
    private final OkHttpClient client = LegacyHttpCompat.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private volatile boolean running;
    private int port = -1;

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
            Log.d(TAG, "proxyUrl " + originalUrl + " -> http://127.0.0.1:" + port + "/relay?..."); 
            return "http://127.0.0.1:" + port + "/relay?url=" + Uri.encode(originalUrl);
        } catch (IOException e) {
            Log.w(TAG, "Failed to start local relay for " + originalUrl, e);
            return originalUrl;
        }
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
            String upstream = extractUrl(path);
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
            if (playlist && !headRequest) {
                String body = response.body().string();
                String rewritten = rewritePlaylist(upstreamUrl, body);
                byte[] bytes = rewritten.getBytes("UTF-8");
                Log.d(TAG, "Playlist relay " + upstreamUrl + " bytes=" + bytes.length);
                sendHeaders(output, 200, statusText(200), "application/vnd.apple.mpegurl", bytes.length, null, null, false);
                output.write(bytes);
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

    private String rewritePlaylist(String upstreamUrl, String body) {
        String[] lines = body.replace("\r", "").split("\n");
        StringBuilder rewritten = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("#")) {
                rewritten.append(rewriteTagUris(upstreamUrl, line));
            } else if (line.trim().length() > 0) {
                rewritten.append(proxyUrl(resolve(upstreamUrl, line.trim())));
            }
            if (i < lines.length - 1) {
                rewritten.append('\n');
            }
        }
        return rewritten.toString();
    }

    private String rewriteTagUris(String baseUrl, String line) {
        String rewritten = line;
        int cursor = 0;
        while (true) {
            int start = rewritten.indexOf("URI=\"", cursor);
            if (start < 0) {
                return rewritten;
            }
            int valueStart = start + 5;
            int valueEnd = rewritten.indexOf('"', valueStart);
            if (valueEnd < 0) {
                return rewritten;
            }
            String original = rewritten.substring(valueStart, valueEnd);
            String resolved = proxyUrl(resolve(baseUrl, original));
            rewritten = rewritten.substring(0, valueStart) + resolved + rewritten.substring(valueEnd);
            cursor = valueStart + resolved.length();
        }
    }

    private String resolve(String baseUrl, String relative) {
        try {
            return URI.create(baseUrl).resolve(relative).toString();
        } catch (RuntimeException e) {
            return relative;
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
