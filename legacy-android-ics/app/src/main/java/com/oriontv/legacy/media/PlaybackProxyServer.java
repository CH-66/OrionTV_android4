package com.oriontv.legacy.media;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.oriontv.legacy.data.PreferencesStore;
import com.oriontv.legacy.net.LegacyHttpCompat;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PlaybackProxyServer implements Closeable {
    private static final String TAG = "PlaybackProxy";

    private static final int MAX_MAPPED_URLS = 4096;
    private static final int IO_BUFFER_SIZE = 64 * 1024;

    private static final int PREFETCH_THREADS = 3;
    private static final int PREFETCH_AHEAD_SEGMENTS = 4;
    private static final int MAX_PREFETCH_QUEUED = 8;
    private static final long PREFETCH_WAIT_MS = 350L;

    private static final long SEGMENT_CACHE_LIMIT_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_CACHEABLE_SEGMENT_BYTES = 16L * 1024L * 1024L;

    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private final OkHttpClient client = LegacyHttpCompat.newUnsafeMediaBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ExecutorService prefetchExecutor = Executors.newFixedThreadPool(PREFETCH_THREADS);

    private final Map<String, String> mappedUrls = new LinkedHashMap<String, String>();
    private final Map<String, SegmentPosition> segmentPositions = new LinkedHashMap<String, SegmentPosition>();

    private final Object cacheLock = new Object();
    private final LinkedHashMap<String, SegmentCacheEntry> segmentCache =
            new LinkedHashMap<String, SegmentCacheEntry>(16, 0.75f, true);
    private long segmentCacheBytes;

    private final Object prefetchLock = new Object();
    private final Set<String> prefetching = new HashSet<String>();

    private final File segmentCacheDir;
    private final PreferencesStore preferencesStore;

    private final Object adStatsLock = new Object();
    private final Set<String> reportedAdSignatures = new HashSet<String>();
    private volatile String activeSourceKey;
    private volatile String activeSourceName;
    private volatile int blockedAdSegments;
    private volatile long blockedAdDurationMs;
    private volatile int suppressedInterstitials;
    private volatile int detectedAdMarkers;
    private volatile boolean adFilteringBypassed;

    private ServerSocket serverSocket;
    private volatile boolean running;
    private int port = -1;
    private int nextStreamId = 1;
    private volatile String activeSegmentUrl;

    public PlaybackProxyServer() {
        this(null, null);
    }

    public PlaybackProxyServer(Context context) {
        this(context, null);
    }

    public PlaybackProxyServer(Context context, PreferencesStore preferencesStore) {
        this.preferencesStore = preferencesStore;
        File dir = null;
        if (context != null) {
            Context appContext = context.getApplicationContext();
            if (appContext == null) {
                appContext = context;
            }
            dir = new File(appContext.getCacheDir(), "hls-segments");
            resetCacheDirectory(dir);
        }
        segmentCacheDir = dir;
        Log.d(TAG, "HLS cache config prefetchThreads=" + PREFETCH_THREADS
                + " prefetchAhead=" + PREFETCH_AHEAD_SEGMENTS
                + " cacheLimitMb=" + (SEGMENT_CACHE_LIMIT_BYTES / 1024L / 1024L)
                + " cacheDir=" + (segmentCacheDir == null ? "disabled" : segmentCacheDir.getAbsolutePath()));
    }

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

    public static class CacheStats {
        public final boolean enabled;
        public final long cachedBytes;
        public final long cacheLimitBytes;
        public final int cachedSegments;
        public final int prefetchingSegments;
        public final int currentSegmentIndex;
        public final int totalSegments;
        public final int lookAheadReady;
        public final int lookAheadTarget;
        public final int lookAheadPercent;
        public final int bufferedUntilPermille;
        public final int blockedAdSegments;
        public final long blockedAdDurationMs;
        public final int suppressedInterstitials;
        public final int detectedAdMarkers;
        public final boolean adFilteringBypassed;

        CacheStats(boolean enabled, long cachedBytes, long cacheLimitBytes,
                   int cachedSegments, int prefetchingSegments,
                   int currentSegmentIndex, int totalSegments,
                   int lookAheadReady, int lookAheadTarget,
                   int lookAheadPercent, int bufferedUntilPermille,
                   int blockedAdSegments, long blockedAdDurationMs,
                   int suppressedInterstitials, int detectedAdMarkers,
                   boolean adFilteringBypassed) {
            this.enabled = enabled;
            this.cachedBytes = cachedBytes;
            this.cacheLimitBytes = cacheLimitBytes;
            this.cachedSegments = cachedSegments;
            this.prefetchingSegments = prefetchingSegments;
            this.currentSegmentIndex = currentSegmentIndex;
            this.totalSegments = totalSegments;
            this.lookAheadReady = lookAheadReady;
            this.lookAheadTarget = lookAheadTarget;
            this.lookAheadPercent = lookAheadPercent;
            this.bufferedUntilPermille = bufferedUntilPermille;
            this.blockedAdSegments = blockedAdSegments;
            this.blockedAdDurationMs = blockedAdDurationMs;
            this.suppressedInterstitials = suppressedInterstitials;
            this.detectedAdMarkers = detectedAdMarkers;
            this.adFilteringBypassed = adFilteringBypassed;
        }
    }

    public void setPlaybackContext(String sourceKey, String sourceName) {
        activeSourceKey = sourceKey;
        activeSourceName = sourceName;
    }

    public void resetPlaybackStats() {
        activeSegmentUrl = null;
        synchronized (adStatsLock) {
            blockedAdSegments = 0;
            blockedAdDurationMs = 0L;
            suppressedInterstitials = 0;
            detectedAdMarkers = 0;
            adFilteringBypassed = false;
            reportedAdSignatures.clear();
        }
    }

    public CacheStats cacheStats() {
        long bytes;
        int cachedCount;
        synchronized (cacheLock) {
            bytes = segmentCacheBytes;
            cachedCount = segmentCache.size();
        }

        int prefetchCount;
        synchronized (prefetchLock) {
            prefetchCount = prefetching.size();
        }

        String active = activeSegmentUrl;
        SegmentPosition position = null;
        if (active != null) {
            synchronized (segmentPositions) {
                position = segmentPositions.get(active);
            }
        }

        if (position == null || position.segments == null || position.segments.size() == 0) {
            return new CacheStats(
                    segmentCacheDir != null,
                    bytes,
                    SEGMENT_CACHE_LIMIT_BYTES,
                    cachedCount,
                    prefetchCount,
                    -1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    blockedAdSegments,
                    blockedAdDurationMs,
                    suppressedInterstitials,
                    detectedAdMarkers,
                    adFilteringBypassed
            );
        }

        int total = position.segments.size();
        int start = position.index + 1;
        int end = Math.min(total, start + PREFETCH_AHEAD_SEGMENTS);
        int target = Math.max(0, end - start);
        int ready = 0;
        int contiguous = 0;

        synchronized (cacheLock) {
            for (int i = start; i < end; i++) {
                SegmentCacheEntry entry = segmentCache.get(position.segments.get(i));
                boolean available = entry != null && entry.file != null && entry.file.exists();
                if (available) {
                    ready++;
                    if (i == start + contiguous) {
                        contiguous++;
                    }
                }
            }
        }

        int percent = target <= 0 ? 100 : (ready * 100 / target);
        int bufferedThrough = Math.min(total - 1, position.index + contiguous);
        int bufferedPermille = total <= 0
                ? 0
                : (int) (((bufferedThrough + 1L) * 1000L) / total);

        return new CacheStats(
                segmentCacheDir != null,
                bytes,
                SEGMENT_CACHE_LIMIT_BYTES,
                cachedCount,
                prefetchCount,
                position.index,
                total,
                ready,
                target,
                percent,
                bufferedPermille,
                blockedAdSegments,
                blockedAdDurationMs,
                suppressedInterstitials,
                detectedAdMarkers,
                adFilteringBypassed
        );
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
                    ParcelFileDescriptor.AutoCloseOutputStream output =
                            new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
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
                    Log.d(TAG, "Cache HLS playlist " + originalUrl + " bytes=" + body.length()
                            + " file=" + file.getAbsolutePath());
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
            String path = normalizeRequestPath(parts[1]);
            Map<String, String> headers = readHeaders(input);
            String upstream = resolveUpstream(path);
            if (upstream == null || upstream.length() == 0) {
                Log.w(TAG, "Missing upstream for " + method + " " + path);
                sendError(output, 400, "Missing url");
                return;
            }
            Log.d(TAG, "Incoming " + method + " " + path + " -> " + upstream
                    + " range=" + headers.get("range"));
            relay(method, upstream, headers, output);
        } catch (SocketException e) {
            Log.d(TAG, "Relay client disconnected: " + e.getMessage());
        } catch (SocketTimeoutException e) {
            Log.d(TAG, "Relay client timed out: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Relay request failed", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void relay(String method, String upstreamUrl, Map<String, String> requestHeaders,
                       OutputStream output) throws IOException {
        boolean headRequest = "HEAD".equalsIgnoreCase(method);
        String range = requestHeaders.get("range");
        boolean fullSegmentRequest = !headRequest
                && (range == null || range.length() == 0)
                && isKnownSegment(upstreamUrl);

        if (fullSegmentRequest) {
            activeSegmentUrl = upstreamUrl;
            SegmentCacheEntry cached = getCachedSegment(upstreamUrl);
            if (cached == null) {
                cached = waitForPrefetch(upstreamUrl);
            }
            if (cached != null) {
                if (serveCachedSegment(cached, output)) {
                    Log.d(TAG, "Segment cache hit bytes=" + cached.length + " url=" + upstreamUrl);
                    scheduleReadAhead(upstreamUrl);
                    return;
                }
                Log.d(TAG, "Segment cache entry disappeared before open; fallback upstream url="
                        + upstreamUrl);
            }
        }

        Request.Builder builder = new Request.Builder().url(upstreamUrl);
        if (headRequest) {
            builder.head();
        } else {
            builder.get();
        }

        if (range != null && range.length() > 0) {
            builder.header("Range", range);
        }

        Response response = null;
        File tempCacheFile = null;
        FileOutputStream cacheOutput = null;
        boolean cacheComplete = false;

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
                Log.d(TAG, "Playlist HEAD " + upstreamUrl);
                sendHeaders(output, 200, statusText(200), "application/vnd.apple.mpegurl",
                        -1, null, null, false);
                output.flush();
                return;
            }

            if (playlist && !headRequest) {
                String body = response.body().string();
                String rewritten = rewritePlaylist(upstreamUrl, body);
                byte[] bytes = rewritten.getBytes("UTF-8");
                Log.d(TAG, "Playlist rewrite " + upstreamUrl + " bytes=" + body.length()
                        + " rewritten=" + bytes.length);
                sendHeaders(output, 200, statusText(200), "application/vnd.apple.mpegurl",
                        bytes.length, null, null, false);
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

            Log.d(TAG, "Stream relay code=" + (partial ? 206 : response.code())
                    + " type=" + contentType + " len=" + contentLength
                    + " range=" + contentRange);

            if (headRequest) {
                output.flush();
                return;
            }

            if (response.body() == null) {
                Log.w(TAG, "Missing upstream body for " + upstreamUrl);
                sendError(output, 502, "Missing upstream body");
                return;
            }

            boolean cacheableSegment = fullSegmentRequest
                    && segmentCacheDir != null
                    && (contentLength < 0 || contentLength <= MAX_CACHEABLE_SEGMENT_BYTES);

            if (fullSegmentRequest) {
                scheduleReadAhead(upstreamUrl);
            }

            if (cacheableSegment) {
                tempCacheFile = createTempCacheFile(upstreamUrl);
                if (tempCacheFile != null) {
                    try {
                        cacheOutput = new FileOutputStream(tempCacheFile, false);
                    } catch (IOException e) {
                        Log.w(TAG, "Cannot open segment cache file " + tempCacheFile, e);
                        deleteQuietly(tempCacheFile);
                        tempCacheFile = null;
                    }
                }
            }

            InputStream upstream = response.body().byteStream();
            byte[] buffer = new byte[IO_BUFFER_SIZE];
            int count;
            long cachedBytes = 0L;

            while ((count = upstream.read(buffer)) != -1) {
                output.write(buffer, 0, count);

                if (cacheOutput != null) {
                    cachedBytes += count;
                    if (cachedBytes > MAX_CACHEABLE_SEGMENT_BYTES) {
                        Log.d(TAG, "Segment exceeds cache size limit; continue direct relay url="
                                + upstreamUrl);
                        closeQuietly(cacheOutput);
                        cacheOutput = null;
                        deleteQuietly(tempCacheFile);
                        tempCacheFile = null;
                    } else {
                        try {
                            cacheOutput.write(buffer, 0, count);
                        } catch (IOException cacheError) {
                            Log.w(TAG, "Segment cache write failed; continue playback url="
                                    + upstreamUrl, cacheError);
                            closeQuietly(cacheOutput);
                            cacheOutput = null;
                            deleteQuietly(tempCacheFile);
                            tempCacheFile = null;
                        }
                    }
                }
            }

            if (cacheOutput != null && tempCacheFile != null) {
                try {
                    cacheOutput.flush();
                    closeQuietly(cacheOutput);
                    cacheOutput = null;
                    SegmentCacheEntry stored = commitCacheFile(
                            upstreamUrl,
                            tempCacheFile,
                            emptyToDefault(contentType, guessContentType(upstreamUrl)),
                            cachedBytes
                    );
                    cacheComplete = stored != null;
                    if (cacheComplete) {
                        tempCacheFile = null;
                    }
                } catch (IOException cacheError) {
                    Log.w(TAG, "Segment cache finalization failed url=" + upstreamUrl, cacheError);
                }
            }

            output.flush();
        } finally {
            closeQuietly(cacheOutput);
            if (!cacheComplete) {
                deleteQuietly(tempCacheFile);
            }
            if (response != null) {
                response.close();
            }
        }
    }

    private void registerPlaylistSegments(List<String> segments) {
        if (segments == null || segments.size() == 0) {
            return;
        }

        List<String> shared = new ArrayList<String>(segments);
        synchronized (segmentPositions) {
            for (int i = 0; i < shared.size(); i++) {
                String url = shared.get(i);
                segmentPositions.put(url, new SegmentPosition(shared, i));
            }
            while (segmentPositions.size() > MAX_MAPPED_URLS) {
                String firstKey = segmentPositions.keySet().iterator().next();
                segmentPositions.remove(firstKey);
            }
        }

        Log.d(TAG, "Registered HLS media segments count=" + shared.size());
    }

    private boolean isKnownSegment(String url) {
        if (url == null) {
            return false;
        }
        synchronized (segmentPositions) {
            return segmentPositions.containsKey(url);
        }
    }

    private void scheduleReadAhead(String currentUrl) {
        SegmentPosition position;
        synchronized (segmentPositions) {
            position = segmentPositions.get(currentUrl);
        }
        if (position == null || position.segments == null) {
            return;
        }

        int end = Math.min(
                position.segments.size(),
                position.index + 1 + PREFETCH_AHEAD_SEGMENTS
        );

        for (int i = position.index + 1; i < end; i++) {
            schedulePrefetch(position.segments.get(i));
        }
    }

    private void schedulePrefetch(final String url) {
        if (segmentCacheDir == null || url == null || url.length() == 0) {
            return;
        }
        if (getCachedSegment(url) != null) {
            return;
        }

        synchronized (prefetchLock) {
            if (prefetching.contains(url)) {
                return;
            }
            if (prefetching.size() >= MAX_PREFETCH_QUEUED) {
                return;
            }
            prefetching.add(url);
        }

        try {
            prefetchExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    long startedAt = System.currentTimeMillis();
                    try {
                        SegmentCacheEntry entry = downloadSegmentToCache(url);
                        if (entry != null) {
                            Log.d(TAG, "Prefetch ready bytes=" + entry.length
                                    + " ms=" + (System.currentTimeMillis() - startedAt)
                                    + " url=" + url);
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "Prefetch skipped/failed url=" + url
                                + " error=" + e.getMessage());
                    } finally {
                        synchronized (prefetchLock) {
                            prefetching.remove(url);
                            prefetchLock.notifyAll();
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            synchronized (prefetchLock) {
                prefetching.remove(url);
                prefetchLock.notifyAll();
            }
            Log.d(TAG, "Prefetch executor rejected url=" + url);
        }
    }

    private SegmentCacheEntry waitForPrefetch(String url) {
        SegmentCacheEntry cached = getCachedSegment(url);
        if (cached != null) {
            return cached;
        }

        long deadline = System.currentTimeMillis() + PREFETCH_WAIT_MS;
        synchronized (prefetchLock) {
            if (!prefetching.contains(url)) {
                return null;
            }
            while (prefetching.contains(url)) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    prefetchLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return getCachedSegment(url);
    }

    private SegmentCacheEntry downloadSegmentToCache(String url) throws IOException {
        SegmentCacheEntry existing = getCachedSegment(url);
        if (existing != null) {
            return existing;
        }

        Response response = null;
        FileOutputStream output = null;
        File tempFile = null;
        boolean committed = false;

        try {
            response = client.newCall(new Request.Builder().url(url).build()).execute();
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }

            long expectedLength = bodyLength(response);
            if (expectedLength > MAX_CACHEABLE_SEGMENT_BYTES) {
                return null;
            }

            tempFile = createTempCacheFile(url);
            if (tempFile == null) {
                return null;
            }

            output = new FileOutputStream(tempFile, false);
            InputStream input = response.body().byteStream();
            byte[] buffer = new byte[IO_BUFFER_SIZE];
            long total = 0L;
            int count;

            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_CACHEABLE_SEGMENT_BYTES) {
                    return null;
                }
                output.write(buffer, 0, count);
            }

            output.flush();
            closeQuietly(output);
            output = null;

            SegmentCacheEntry stored = commitCacheFile(
                    url,
                    tempFile,
                    emptyToDefault(response.header("Content-Type"), guessContentType(url)),
                    total
            );
            committed = stored != null;
            if (committed) {
                tempFile = null;
            }
            return stored;
        } finally {
            closeQuietly(output);
            if (!committed) {
                deleteQuietly(tempFile);
            }
            if (response != null) {
                response.close();
            }
        }
    }

    private SegmentCacheEntry getCachedSegment(String url) {
        if (segmentCacheDir == null || url == null) {
            return null;
        }

        synchronized (cacheLock) {
            SegmentCacheEntry entry = segmentCache.get(url);
            if (entry == null) {
                return null;
            }
            if (entry.file == null || !entry.file.exists()) {
                segmentCache.remove(url);
                segmentCacheBytes -= entry.length;
                if (segmentCacheBytes < 0L) {
                    segmentCacheBytes = 0L;
                }
                return null;
            }
            return entry;
        }
    }

    private SegmentCacheEntry commitCacheFile(String url, File tempFile, String contentType,
                                              long length) {
        if (segmentCacheDir == null || tempFile == null || !tempFile.exists()) {
            return null;
        }
        if (length <= 0L || length > MAX_CACHEABLE_SEGMENT_BYTES) {
            return null;
        }

        synchronized (cacheLock) {
            SegmentCacheEntry existing = segmentCache.get(url);
            if (existing != null && existing.file != null && existing.file.exists()) {
                deleteQuietly(tempFile);
                return existing;
            }

            File finalFile = new File(segmentCacheDir, cacheFileName(url) + ".seg");
            if (finalFile.exists() && !finalFile.delete()) {
                Log.d(TAG, "Cannot replace stale cache file " + finalFile);
                return null;
            }
            if (!tempFile.renameTo(finalFile)) {
                Log.d(TAG, "Cannot promote segment cache file " + tempFile);
                return null;
            }

            SegmentCacheEntry entry = new SegmentCacheEntry(
                    url,
                    finalFile,
                    emptyToDefault(contentType, guessContentType(url)),
                    length
            );

            SegmentCacheEntry old = segmentCache.put(url, entry);
            if (old != null) {
                segmentCacheBytes -= old.length;
                if (old.file != null && !old.file.equals(finalFile)) {
                    deleteQuietly(old.file);
                }
            }
            segmentCacheBytes += length;

            trimSegmentCacheLocked();

            Log.d(TAG, "Segment cache store bytes=" + length
                    + " totalMb=" + (segmentCacheBytes / 1024L / 1024L)
                    + " url=" + url);
            return entry;
        }
    }

    private void trimSegmentCacheLocked() {
        Iterator<Map.Entry<String, SegmentCacheEntry>> iterator =
                segmentCache.entrySet().iterator();

        while (segmentCacheBytes > SEGMENT_CACHE_LIMIT_BYTES && iterator.hasNext()) {
            Map.Entry<String, SegmentCacheEntry> eldest = iterator.next();
            SegmentCacheEntry entry = eldest.getValue();
            iterator.remove();
            segmentCacheBytes -= entry.length;
            deleteQuietly(entry.file);
            Log.d(TAG, "Segment cache evict bytes=" + entry.length + " url=" + entry.url);
        }

        if (segmentCacheBytes < 0L) {
            segmentCacheBytes = 0L;
        }
    }

    private boolean serveCachedSegment(SegmentCacheEntry entry, OutputStream output) throws IOException {
        FileInputStream input;
        try {
            input = new FileInputStream(entry.file);
        } catch (IOException missingCacheFile) {
            return false;
        }

        try {
            sendHeaders(
                    output,
                    200,
                    statusText(200),
                    emptyToDefault(entry.contentType, "application/octet-stream"),
                    entry.length,
                    null,
                    "bytes",
                    false
            );

            byte[] buffer = new byte[IO_BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
            return true;
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
    }

    private File createTempCacheFile(String url) {
        if (segmentCacheDir == null) {
            return null;
        }
        if (!segmentCacheDir.exists() && !segmentCacheDir.mkdirs()) {
            return null;
        }
        return new File(
                segmentCacheDir,
                cacheFileName(url) + "-" + Thread.currentThread().getId() + ".tmp"
        );
    }

    private String cacheFileName(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(url.getBytes("UTF-8"));
            char[] hex = new char[bytes.length * 2];
            char[] alphabet = "0123456789abcdef".toCharArray();
            for (int i = 0; i < bytes.length; i++) {
                int value = bytes[i] & 0xff;
                hex[i * 2] = alphabet[value >>> 4];
                hex[i * 2 + 1] = alphabet[value & 0x0f];
            }
            return new String(hex);
        } catch (Exception ignored) {
            return Integer.toHexString(url.hashCode());
        }
    }

    private String guessContentType(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        int query = lower.indexOf('?');
        if (query >= 0) {
            lower = lower.substring(0, query);
        }

        if (lower.endsWith(".ts") || lower.contains(".ts/")) {
            return "video/mp2t";
        }
        if (lower.endsWith(".m4s") || lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".aac")) {
            return "audio/aac";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        return "application/octet-stream";
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
        return lowerUrl.contains(".m3u8")
                || lowerType.contains("mpegurl")
                || lowerType.contains("vnd.apple.mpegurl");
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

    private synchronized String localProxyUrl(String upstreamUrl) {
        String id = rememberUrl(upstreamUrl);
        return "http://127.0.0.1:" + port + "/stream/" + id
                + playbackExtension(upstreamUrl.toLowerCase(Locale.US));
    }

    private String playbackExtension(String lowerUrl) {
        String full = lowerUrl == null ? "" : lowerUrl;
        if (full.contains(".m3u8")) {
            return ".m3u8";
        }
        if (full.contains(".m4s")) {
            return ".m4s";
        }
        if (full.contains(".ts")) {
            return ".ts";
        }
        if (full.contains(".aac")) {
            return ".aac";
        }
        if (full.contains(".mp4")) {
            return ".mp4";
        }
        if (full.contains(".3gp")) {
            return ".3gp";
        }

        String path = full;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        return ".stream";
    }

    private String rewritePlaylist(String playlistUrl, String body) {
        if (body == null) {
            return "";
        }

        HlsAdFilter.Result adResult = HlsAdFilter.filter(playlistUrl, body);
        if (adResult.hasAdEvidence()) {
            noteAdFiltering(playlistUrl, adResult);
        }
        body = adResult.playlist;

        String normalized = body.replace("\r", "");
        String upperBody = normalized.toUpperCase(Locale.US);
        boolean mediaPlaylist = upperBody.contains("#EXTINF");
        boolean byteRangePlaylist = upperBody.contains("#EXT-X-BYTERANGE");

        String[] lines = normalized.split("\n", -1);
        StringBuilder rewritten = new StringBuilder(body.length() + 256);
        List<String> mediaSegments = mediaPlaylist && !byteRangePlaylist
                ? new ArrayList<String>()
                : null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line == null ? "" : line.trim();

            if (trimmed.length() == 0) {
                rewritten.append(line == null ? "" : line);
            } else if (trimmed.startsWith("#")) {
                rewritten.append(rewriteUriAttributes(playlistUrl, line));
            } else {
                String resolved = resolve(playlistUrl, trimmed);
                if (mediaSegments != null) {
                    mediaSegments.add(resolved);
                }
                rewritten.append(localProxyUrl(resolved));
            }

            if (i < lines.length - 1) {
                rewritten.append('\n');
            }
        }

        if (mediaSegments != null && mediaSegments.size() > 0) {
            registerPlaylistSegments(mediaSegments);
        } else if (mediaPlaylist && byteRangePlaylist) {
            Log.d(TAG, "Byte-range HLS detected; segment prefetch cache disabled for "
                    + playlistUrl);
        }

        return rewritten.toString();
    }

    private void noteAdFiltering(String playlistUrl, HlsAdFilter.Result result) {
        if (result == null || !result.hasAdEvidence()) return;

        String signature = (activeSourceKey == null ? "" : activeSourceKey)
                + "|" + playlistUrl + "|" + result.signature
                + "|" + result.removedSegments
                + "|" + result.suppressedInterstitials
                + "|" + result.byteRangeBypass;

        synchronized (adStatsLock) {
            if (reportedAdSignatures.contains(signature)) {
                return;
            }
            reportedAdSignatures.add(signature);

            blockedAdSegments += result.removedSegments;
            blockedAdDurationMs += result.removedDurationMs;
            suppressedInterstitials += result.suppressedInterstitials;
            detectedAdMarkers += result.detectedAdMarkers;
            if (result.byteRangeBypass && result.detectedAdMarkers > 0) {
                adFilteringBypassed = true;
            }

            while (reportedAdSignatures.size() > 128) {
                Iterator<String> iterator = reportedAdSignatures.iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }

        int evidence = result.removedSegments + result.suppressedInterstitials;
        if (result.byteRangeBypass && result.detectedAdMarkers > 0) {
            evidence++;
        }
        if (preferencesStore != null && activeSourceKey != null && evidence > 0) {
            preferencesStore.recordSourceAdDetection(activeSourceKey, evidence);
        }

        Log.i(TAG, "HLS ad filter source=" + activeSourceName
                + " removedSegments=" + result.removedSegments
                + " removedMs=" + result.removedDurationMs
                + " suppressedInterstitials=" + result.suppressedInterstitials
                + " markers=" + result.detectedAdMarkers
                + " byteRangeBypass=" + result.byteRangeBypass
                + " playlist=" + playlistUrl);
    }

    private String rewriteUriAttributes(String playlistUrl, String line) {
        Matcher matcher = URI_ATTRIBUTE.matcher(line);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String original = matcher.group(1);
            String replacement = "URI=\"" + localProxyUrl(resolve(playlistUrl, original)) + "\"";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private void streamPlaylist(String playlistUrl, String body, OutputStream output, int depth)
            throws IOException {
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

        Log.d(TAG, "Stream TS segments count=" + parts.segments.size()
                + " playlist=" + playlistUrl);
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
            if (response != null) {
                response.close();
            }
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
            byte[] buffer = new byte[IO_BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
        } finally {
            if (response != null) {
                response.close();
            }
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
        path = normalizeRequestPath(path);
        String mapped = extractMappedUrl(path);
        if (mapped != null && mapped.length() > 0) {
            return mapped;
        }
        return extractUrl(path);
    }

    private String normalizeRequestPath(String path) {
        if (path == null) {
            return null;
        }
        String lower = path.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return path;
        }
        try {
            URI uri = URI.create(path);
            String rawPath = uri.getRawPath();
            String rawQuery = uri.getRawQuery();
            if (rawPath == null || rawPath.length() == 0) {
                rawPath = "/";
            }
            return rawQuery == null || rawQuery.length() == 0
                    ? rawPath
                    : rawPath + "?" + rawQuery;
        } catch (RuntimeException ignored) {
            return path;
        }
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
        sendHeaders(output, code, statusText(code), "text/plain; charset=utf-8",
                body.length, null, "bytes", false);
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
        builder.append("Accept-Ranges: ")
                .append(emptyToDefault(acceptRanges, "bytes"))
                .append("\r\n");
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

    private void resetCacheDirectory(File dir) {
        if (dir == null) {
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Cannot create HLS cache directory " + dir);
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            deleteQuietly(files[i]);
        }
    }

    private void clearSegmentCache() {
        synchronized (cacheLock) {
            Iterator<Map.Entry<String, SegmentCacheEntry>> iterator =
                    segmentCache.entrySet().iterator();
            while (iterator.hasNext()) {
                SegmentCacheEntry entry = iterator.next().getValue();
                deleteQuietly(entry.file);
                iterator.remove();
            }
            segmentCacheBytes = 0L;
        }
    }

    private void closeQuietly(FileOutputStream output) {
        if (output == null) {
            return;
        }
        try {
            output.close();
        } catch (IOException ignored) {
        }
    }

    private void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            try {
                file.delete();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static class PlaylistParts {
        String masterPlaylistUrl;
        List<String> segments = new ArrayList<String>();
    }

    private static class SegmentPosition {
        final List<String> segments;
        final int index;

        SegmentPosition(List<String> segments, int index) {
            this.segments = segments;
            this.index = index;
        }
    }

    private static class SegmentCacheEntry {
        final String url;
        final File file;
        final String contentType;
        final long length;

        SegmentCacheEntry(String url, File file, String contentType, long length) {
            this.url = url;
            this.file = file;
            this.contentType = contentType;
            this.length = length;
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
        prefetchExecutor.shutdownNow();

        synchronized (prefetchLock) {
            prefetching.clear();
            prefetchLock.notifyAll();
        }

        clearSegmentCache();

        synchronized (segmentPositions) {
            segmentPositions.clear();
        }
        activeSegmentUrl = null;
        mappedUrls.clear();
    }
}
