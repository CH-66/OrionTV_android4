package com.oriontv.legacy.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.oriontv.legacy.api.CookieStore;
import com.oriontv.legacy.data.PreferencesStore;
import com.oriontv.legacy.net.LegacyHttpCompat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Poster loader tuned for low-memory Android 4.0.4 televisions.
 *
 * Network bytes are written completely before decoding, common image formats
 * are checked for a valid terminator/declared length, decoding is downsampled
 * to the actual TV card size using RGB_565, and a compact JPEG is kept in a
 * bounded disk cache for subsequent launches.
 */
public final class LegacyPosterLoader {
    private static final String TAG = "LegacyPoster";

    private static final int MEMORY_CACHE_BYTES = 6 * 1024 * 1024;
    private static final long DISK_CACHE_BYTES = 48L * 1024L * 1024L;
    private static final long MAX_DOWNLOAD_BYTES = 8L * 1024L * 1024L;
    private static final int JPEG_CACHE_QUALITY = 82;
    private static final int DEFAULT_WIDTH_PX = 180;
    private static final int DEFAULT_HEIGHT_PX = 240;
    private static final long[] RETRY_DELAYS_MS = new long[]{0L, 300L, 800L};

    private final CookieStore cookieStore;
    private final OkHttpClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(3);
    private final ExecutorService decodeExecutor = Executors.newFixedThreadPool(2);
    private final WeakHashMap<ImageView, LoadToken> bindings =
            new WeakHashMap<ImageView, LoadToken>();
    private final File cacheDir;

    private int writesSinceTrim;
    private volatile boolean closed;

    private final LruCache<String, Bitmap> memoryCache =
            new LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    if (value == null || value.isRecycled()) return 0;
                    long bytes = (long) value.getRowBytes() * (long) value.getHeight();
                    return bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes;
                }
            };

    public LegacyPosterLoader(Context context, PreferencesStore preferencesStore) {
        Context appContext = context.getApplicationContext();
        this.cookieStore = new CookieStore(preferencesStore);
        this.client = LegacyHttpCompat.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        this.cacheDir = new File(appContext.getCacheDir(), "poster-cache-v3");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        cleanupTemporaryFiles();
        trimDiskCache();
    }

    public void load(String url,
                     ImageView target,
                     int placeholderResId,
                     int requestedWidthPx,
                     int requestedHeightPx) {
        if (target == null || closed) return;

        if (url == null || url.trim().length() == 0) {
            cancel(target);
            target.setImageResource(placeholderResId);
            return;
        }

        final String key = url.trim();
        Bitmap memory = memoryCache.get(key);
        if (memory != null && !memory.isRecycled()) {
            cancel(target);
            target.setImageBitmap(memory);
            Log.d(TAG, "MEM HIT " + shortUrl(key));
            return;
        }

        int width = requestedWidthPx > 0 ? requestedWidthPx : DEFAULT_WIDTH_PX;
        int height = requestedHeightPx > 0 ? requestedHeightPx : DEFAULT_HEIGHT_PX;

        final LoadToken token;
        synchronized (bindings) {
            LoadToken previous = bindings.get(target);
            if (previous != null && !previous.cancelled && key.equals(previous.url)) {
                return;
            }
            if (previous != null) {
                previous.cancel();
            }
            token = new LoadToken(key);
            bindings.put(target, token);
        }

        target.setImageResource(placeholderResId);
        WeakReference<ImageView> targetRef = new WeakReference<ImageView>(target);
        File cached = cacheFile(key);

        if (cached.exists() && cached.length() > 0L) {
            touch(cached);
            Log.d(TAG, "DISK HIT " + shortUrl(key) + " " + cached.length() + "B");
            decodeCached(token, targetRef, cached, placeholderResId, width, height);
        } else {
            Log.d(TAG, "MISS " + shortUrl(key));
            enqueueDownload(token, targetRef, placeholderResId, width, height, 0);
        }
    }

    public void cancel(ImageView target) {
        if (target == null) return;
        synchronized (bindings) {
            LoadToken token = bindings.remove(target);
            if (token != null) {
                token.cancel();
            }
        }
    }

    public void shutdown() {
        closed = true;
        synchronized (bindings) {
            for (LoadToken token : bindings.values()) {
                if (token != null) token.cancel();
            }
            bindings.clear();
        }
        downloadExecutor.shutdownNow();
        decodeExecutor.shutdownNow();
        memoryCache.evictAll();
    }

    private void decodeCached(final LoadToken token,
                              final WeakReference<ImageView> targetRef,
                              final File file,
                              final int placeholderResId,
                              final int width,
                              final int height) {
        decodeExecutor.execute(new Runnable() {
            @Override
            public void run() {
                setBackgroundPriority();
                if (!canContinue(token, targetRef)) return;

                Bitmap bitmap = decodeSampled(file, width, height, "DISK");
                if (bitmap == null) {
                    Log.w(TAG, "Corrupt disk poster; redownloading " + shortUrl(token.url));
                    file.delete();
                    enqueueDownload(token, targetRef, placeholderResId, width, height, 0);
                    return;
                }

                completeSuccess(token, targetRef, bitmap);
            }
        });
    }

    private void enqueueDownload(final LoadToken token,
                                 final WeakReference<ImageView> targetRef,
                                 final int placeholderResId,
                                 final int width,
                                 final int height,
                                 final int attempt) {
        if (closed || token.cancelled) return;

        downloadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                setBackgroundPriority();
                if (!canContinue(token, targetRef)) return;

                if (attempt > 0) {
                    long delay = RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)];
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!canContinue(token, targetRef)) return;
                }

                File raw = null;
                Response response = null;
                try {
                    Request.Builder builder = new Request.Builder()
                            .url(token.url)
                            .header("Accept", "image/jpeg,image/png,image/webp,image/*;q=0.8,*/*;q=0.5");

                    String cookies = cookieStore.getCookieHeader();
                    if (cookies != null && cookies.length() > 0) {
                        builder.header("Cookie", cookies);
                    }

                    Call call = client.newCall(builder.build());
                    token.call = call;
                    response = call.execute();

                    if (response == null) {
                        throw new IOException("empty response");
                    }
                    if (!response.isSuccessful() || response.body() == null) {
                        int code = response.code();
                        if (code >= 400 && code < 500 && code != 408 && code != 429) {
                            throw new PermanentHttpException("HTTP " + code);
                        }
                        throw new IOException("HTTP " + code);
                    }

                    ResponseBody body = response.body();
                    long expected = body.contentLength();
                    if (expected > MAX_DOWNLOAD_BYTES) {
                        throw new PermanentHttpException("poster too large: " + expected);
                    }

                    raw = File.createTempFile("poster-", ".download", cacheDir);
                    BufferedInputStream in = new BufferedInputStream(body.byteStream(), 16 * 1024);
                    BufferedOutputStream out =
                            new BufferedOutputStream(new FileOutputStream(raw), 16 * 1024);
                    long copied = 0L;
                    byte[] buffer = new byte[16 * 1024];
                    try {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            if (token.cancelled) {
                                throw new IOException("cancelled");
                            }
                            copied += read;
                            if (copied > MAX_DOWNLOAD_BYTES) {
                                throw new PermanentHttpException("poster exceeds download limit");
                            }
                            out.write(buffer, 0, read);
                        }
                        out.flush();
                    } finally {
                        try { in.close(); } catch (Exception ignored) {}
                        try { out.close(); } catch (Exception ignored) {}
                    }

                    if (copied < 256L) {
                        throw new IOException("poster body too small: " + copied);
                    }
                    if (!isCompleteImage(raw, body.contentType() == null
                            ? "" : body.contentType().toString(), expected)) {
                        throw new IOException("incomplete image body " + copied + "/" + expected);
                    }

                    final File downloaded = raw;
                    raw = null;
                    final long bytes = copied;
                    Log.d(TAG, "HTTP OK attempt=" + (attempt + 1)
                            + " bytes=" + bytes + " " + shortUrl(token.url));

                    decodeExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            setBackgroundPriority();
                            processDownloaded(token, targetRef, downloaded,
                                    placeholderResId, width, height, bytes);
                        }
                    });
                } catch (PermanentHttpException e) {
                    Log.w(TAG, "HTTP permanent failure " + shortUrl(token.url)
                            + " " + e.getMessage());
                    if (raw != null) raw.delete();
                    completeFailure(token, targetRef, placeholderResId);
                } catch (IOException e) {
                    if (raw != null) raw.delete();
                    if (!token.cancelled) {
                        Log.w(TAG, "HTTP retry=" + (attempt + 1) + " "
                                + shortUrl(token.url) + " " + e.getMessage());
                        retryOrFail(token, targetRef, placeholderResId,
                                width, height, attempt);
                    }
                } catch (Throwable error) {
                    if (raw != null) raw.delete();
                    Log.w(TAG, "Poster download failed " + shortUrl(token.url), error);
                    retryOrFail(token, targetRef, placeholderResId,
                            width, height, attempt);
                } finally {
                    token.call = null;
                    if (response != null) response.close();
                }
            }
        });
    }

    private void processDownloaded(LoadToken token,
                                   WeakReference<ImageView> targetRef,
                                   File downloaded,
                                   int placeholderResId,
                                   int width,
                                   int height,
                                   long networkBytes) {
        if (!canContinue(token, targetRef)) {
            downloaded.delete();
            return;
        }

        Bitmap bitmap = decodeSampled(downloaded, width, height, "NET");
        if (bitmap == null) {
            downloaded.delete();
            Log.w(TAG, "Decode failed after " + networkBytes
                    + "B " + shortUrl(token.url));
            completeFailure(token, targetRef, placeholderResId);
            return;
        }

        File finalFile = cacheFile(token.url);
        boolean stored = writeCompactCache(bitmap, finalFile);
        downloaded.delete();

        if (stored) {
            touch(finalFile);
            maybeTrimDiskCache();
        }

        completeSuccess(token, targetRef, bitmap);
    }

    private void retryOrFail(LoadToken token,
                             WeakReference<ImageView> targetRef,
                             int placeholderResId,
                             int width,
                             int height,
                             int attempt) {
        if (token.cancelled || closed) return;
        if (attempt + 1 < RETRY_DELAYS_MS.length) {
            enqueueDownload(token, targetRef, placeholderResId,
                    width, height, attempt + 1);
        } else {
            completeFailure(token, targetRef, placeholderResId);
        }
    }

    private Bitmap decodeSampled(File file,
                                 int requestedWidth,
                                 int requestedHeight,
                                 String source) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }

            int sample = 1;
            while ((bounds.outWidth / (sample * 2)) >= requestedWidth
                    && (bounds.outHeight / (sample * 2)) >= requestedHeight) {
                sample *= 2;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inDither = true;

            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap != null) {
                Log.d(TAG, source + " DECODE "
                        + bounds.outWidth + "x" + bounds.outHeight
                        + " sample=" + options.inSampleSize
                        + " => " + bitmap.getWidth() + "x" + bitmap.getHeight());
            }
            return bitmap;
        } catch (OutOfMemoryError oom) {
            Log.w(TAG, "Bitmap OOM; evicting poster memory cache", oom);
            memoryCache.evictAll();
            System.gc();
            return null;
        } catch (Throwable error) {
            Log.w(TAG, "Bitmap decode error " + file.getAbsolutePath(), error);
            return null;
        }
    }

    private boolean isCompleteImage(File file, String contentType, long expectedLength) {
        if (file == null || !file.exists()) return false;
        long length = file.length();
        if (expectedLength >= 0L && length != expectedLength) return false;
        if (length < 12L) return false;

        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            byte[] first = new byte[12];
            raf.readFully(first);

            boolean jpeg = (first[0] & 0xff) == 0xff && (first[1] & 0xff) == 0xd8;
            boolean png = (first[0] & 0xff) == 0x89
                    && first[1] == 0x50 && first[2] == 0x4e && first[3] == 0x47;
            boolean gif = first[0] == 0x47 && first[1] == 0x49 && first[2] == 0x46;
            boolean webp = first[0] == 0x52 && first[1] == 0x49
                    && first[2] == 0x46 && first[3] == 0x46
                    && first[8] == 0x57 && first[9] == 0x45
                    && first[10] == 0x42 && first[11] == 0x50;

            if (jpeg || contentType.indexOf("jpeg") >= 0 || contentType.indexOf("jpg") >= 0) {
                raf.seek(length - 2L);
                return raf.readUnsignedByte() == 0xff
                        && raf.readUnsignedByte() == 0xd9;
            }

            if (png || contentType.indexOf("png") >= 0) {
                raf.seek(length - 8L);
                return raf.readUnsignedByte() == 0x49
                        && raf.readUnsignedByte() == 0x45
                        && raf.readUnsignedByte() == 0x4e
                        && raf.readUnsignedByte() == 0x44;
            }

            if (gif || contentType.indexOf("gif") >= 0) {
                raf.seek(length - 1L);
                return raf.readUnsignedByte() == 0x3b;
            }

            if (webp || contentType.indexOf("webp") >= 0) {
                long declared = ((long) first[4] & 0xffL)
                        | (((long) first[5] & 0xffL) << 8)
                        | (((long) first[6] & 0xffL) << 16)
                        | (((long) first[7] & 0xffL) << 24);
                return declared + 8L == length;
            }

            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Image completeness check failed", error);
            return false;
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Exception ignored) {}
            }
        }
    }

    private boolean writeCompactCache(Bitmap bitmap, File finalFile) {
        File temp = new File(finalFile.getParentFile(), finalFile.getName() + ".tmp");
        BufferedOutputStream out = null;
        try {
            out = new BufferedOutputStream(new FileOutputStream(temp), 16 * 1024);
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_CACHE_QUALITY, out)) {
                return false;
            }
            out.flush();
            out.close();
            out = null;

            if (finalFile.exists() && !finalFile.delete()) {
                return false;
            }
            if (!temp.renameTo(finalFile)) {
                return false;
            }
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Failed to persist poster cache", error);
            return false;
        } finally {
            if (out != null) {
                try { out.close(); } catch (Exception ignored) {}
            }
            if (temp.exists() && !temp.equals(finalFile)) {
                temp.delete();
            }
        }
    }

    private void completeSuccess(final LoadToken token,
                                 final WeakReference<ImageView> targetRef,
                                 final Bitmap bitmap) {
        if (bitmap == null) return;
        if (token.cancelled || closed) {
            bitmap.recycle();
            return;
        }

        memoryCache.put(token.url, bitmap);
        main.post(new Runnable() {
            @Override
            public void run() {
                ImageView target = targetRef.get();
                if (target == null || !isCurrent(target, token)) {
                    return;
                }
                target.setImageBitmap(bitmap);
                clearBinding(target, token);
            }
        });
    }

    private void completeFailure(final LoadToken token,
                                 final WeakReference<ImageView> targetRef,
                                 final int placeholderResId) {
        main.post(new Runnable() {
            @Override
            public void run() {
                ImageView target = targetRef.get();
                if (target == null || !isCurrent(target, token)) {
                    return;
                }
                target.setImageResource(placeholderResId);
                clearBinding(target, token);
            }
        });
    }

    private boolean canContinue(LoadToken token, WeakReference<ImageView> targetRef) {
        if (closed || token == null || token.cancelled) return false;
        ImageView target = targetRef.get();
        return target != null && isCurrent(target, token);
    }

    private boolean isCurrent(ImageView target, LoadToken token) {
        synchronized (bindings) {
            return bindings.get(target) == token && !token.cancelled;
        }
    }

    private void clearBinding(ImageView target, LoadToken token) {
        synchronized (bindings) {
            if (bindings.get(target) == token) {
                bindings.remove(target);
            }
        }
    }

    private File cacheFile(String url) {
        return new File(cacheDir, sha1(url) + ".jpg");
    }

    private String sha1(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(text.getBytes("UTF-8"));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (int i = 0; i < bytes.length; i++) {
                int value = bytes[i] & 0xff;
                if (value < 16) result.append('0');
                result.append(Integer.toHexString(value));
            }
            return result.toString();
        } catch (Throwable ignored) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private void touch(File file) {
        if (file != null && file.exists()) {
            file.setLastModified(System.currentTimeMillis());
        }
    }

    private void cleanupTemporaryFiles() {
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            String name = files[i].getName();
            if (name.endsWith(".tmp") || name.endsWith(".download")) {
                files[i].delete();
            }
        }
    }

    private synchronized void maybeTrimDiskCache() {
        writesSinceTrim++;
        if (writesSinceTrim < 20) return;
        writesSinceTrim = 0;
        trimDiskCache();
    }

    private void trimDiskCache() {
        File[] files = cacheDir.listFiles();
        if (files == null || files.length == 0) return;

        long total = 0L;
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile() && files[i].getName().endsWith(".jpg")) {
                total += files[i].length();
            }
        }
        if (total <= DISK_CACHE_BYTES) return;

        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                long a = left.lastModified();
                long b = right.lastModified();
                if (a == b) return 0;
                return a < b ? -1 : 1;
            }
        });

        long target = (DISK_CACHE_BYTES * 9L) / 10L;
        for (int i = 0; i < files.length && total > target; i++) {
            File file = files[i];
            if (!file.isFile() || !file.getName().endsWith(".jpg")) continue;
            long size = file.length();
            if (file.delete()) {
                total -= size;
            }
        }
        Log.d(TAG, "DISK TRIM => " + total + "B");
    }

    private void setBackgroundPriority() {
        try {
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }
    }

    private String shortUrl(String url) {
        if (url == null) return "";
        if (url.length() <= 96) return url;
        return url.substring(0, 93) + "...";
    }

    private static final class LoadToken {
        final String url;
        volatile boolean cancelled;
        volatile Call call;

        LoadToken(String url) {
            this.url = url;
        }

        void cancel() {
            cancelled = true;
            Call current = call;
            if (current != null) {
                try {
                    current.cancel();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final class PermanentHttpException extends IOException {
        PermanentHttpException(String message) {
            super(message);
        }
    }
}
