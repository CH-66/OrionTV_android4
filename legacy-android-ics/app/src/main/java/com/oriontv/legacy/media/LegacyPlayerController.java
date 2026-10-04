package com.oriontv.legacy.media;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;

public class LegacyPlayerController implements SurfaceHolder.Callback {
    private static final String TAG = "LegacyPlayer";

    private static final long WATCHDOG_INTERVAL_MS = 1000L;
    private static final long BLOCKING_COMMAND_TIMEOUT_MS = 8000L;
    private static final long PREPARE_TIMEOUT_MS = 30000L;
    private static final long PLAYBACK_HEARTBEAT_TIMEOUT_MS = 12000L;

    public interface Listener {
        void onPrepared(int durationMs);
        void onProgress(int positionMs, int durationMs);
        void onVideoSizeChanged(int width, int height);
        void onBuffering(boolean buffering);
        void onCompleted();
        void onError(String message);
    }

    private final Object lock = new Object();
    private final SurfaceView surfaceView;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PlayerWorker activeWorker;
    private SurfaceHolder currentHolder;
    private String pendingUrl;

    private boolean surfaceReady;
    private boolean prepared;
    private boolean playing;
    private boolean buffering;
    private boolean lifecyclePaused;
    private boolean resumeAfterLifecyclePause;
    private boolean playWhenReady = true;
    private boolean destroyed;

    private int cachedPositionMs;
    private int cachedDurationMs;
    private int generation;

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            PlayerWorker worker;
            boolean preparedSnapshot;
            boolean playingSnapshot;
            boolean bufferingSnapshot;
            synchronized (lock) {
                if (destroyed) return;
                worker = activeWorker;
                preparedSnapshot = prepared;
                playingSnapshot = playing;
                bufferingSnapshot = buffering;
            }

            if (worker != null) {
                long now = SystemClock.elapsedRealtime();
                String phase = worker.phase;
                long phaseAge = now - worker.phaseSinceMs;
                long heartbeatAge = now - worker.heartbeatMs;

                if (isBlockingPhase(phase) && phaseAge > BLOCKING_COMMAND_TIMEOUT_MS) {
                    failWorker(worker, "系统播放器无响应（" + phase + "）");
                } else if ("PREPARING".equals(phase) && phaseAge > PREPARE_TIMEOUT_MS) {
                    failWorker(worker, "系统播放器准备超时");
                } else if (preparedSnapshot && playingSnapshot && !bufferingSnapshot
                        && heartbeatAge > PLAYBACK_HEARTBEAT_TIMEOUT_MS) {
                    failWorker(worker, "系统播放器播放线程无响应");
                }
            }

            synchronized (lock) {
                if (!destroyed) {
                    mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
                }
            }
        }
    };

    public LegacyPlayerController(Context context, SurfaceView surfaceView, Listener listener) {
        this.surfaceView = surfaceView;
        this.listener = listener;
        this.surfaceView.getHolder().addCallback(this);
        this.mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
    }

    public void load(String url) {
        PlayerWorker oldWorker;
        int token;
        boolean canStart;

        synchronized (lock) {
            if (destroyed) {
                Log.w(TAG, "Ignoring load after destroy url=" + url);
                return;
            }

            generation++;
            token = generation;
            pendingUrl = url;
            lifecyclePaused = false;
            resumeAfterLifecyclePause = false;
            playWhenReady = true;
            prepared = false;
            playing = false;
            buffering = false;
            cachedPositionMs = 0;
            cachedDurationMs = 0;

            oldWorker = activeWorker;
            activeWorker = null;
            canStart = surfaceReady && currentHolder != null;
        }

        retireWorker(oldWorker, "new load");
        Log.d(TAG, "load system generation=" + token + " " + url);

        if (canStart) {
            startWorker(url, token);
        }
    }

    public boolean playPause() {
        final PlayerWorker worker;
        final boolean shouldPlay;
        final boolean desiredPlayWhenReady;

        synchronized (lock) {
            if (destroyed || activeWorker == null || !prepared) return false;

            playWhenReady = !playWhenReady;
            desiredPlayWhenReady = playWhenReady;
            shouldPlay = playWhenReady && !lifecyclePaused && surfaceReady;
            playing = shouldPlay;
            resumeAfterLifecyclePause = playWhenReady && !shouldPlay;
            worker = activeWorker;
        }

        postPlayerCommand(worker, shouldPlay ? "START" : "PAUSE", new PlayerCommand() {
            @Override
            public void run(MediaPlayer player) {
                if (shouldPlay) {
                    player.start();
                } else {
                    player.pause();
                }
            }
        });
        Log.d(TAG, "playPause queued generation=" + worker.token
                + " playWhenReady=" + desiredPlayWhenReady + " playing=" + shouldPlay);
        return true;
    }

    public boolean seekBy(int deltaMs) {
        int target;
        synchronized (lock) {
            if (destroyed || activeWorker == null || !prepared) return false;
            target = cachedPositionMs + deltaMs;
            if (target < 0) target = 0;
            if (cachedDurationMs > 0 && target > cachedDurationMs) {
                target = cachedDurationMs;
            }
        }
        return seekTo(target);
    }

    public boolean seekTo(int positionMs) {
        final PlayerWorker worker;
        final int target;
        synchronized (lock) {
            if (destroyed || activeWorker == null || !prepared) return false;

            int bounded = positionMs;
            if (bounded < 0) bounded = 0;
            if (cachedDurationMs > 0 && bounded > cachedDurationMs) {
                bounded = cachedDurationMs;
            }
            target = bounded;
            cachedPositionMs = target;
            worker = activeWorker;
        }

        postPlayerCommand(worker, "SEEK", new PlayerCommand() {
            @Override
            public void run(MediaPlayer player) {
                player.seekTo(target);
            }
        });
        Log.d(TAG, "seekTo queued generation=" + worker.token + " position=" + target);
        return true;
    }

    public boolean isPlaying() {
        synchronized (lock) {
            return !destroyed && prepared && playing;
        }
    }

    public boolean isPrepared() {
        synchronized (lock) {
            return !destroyed && prepared && activeWorker != null;
        }
    }

    public int position() {
        synchronized (lock) {
            return cachedPositionMs;
        }
    }

    public int duration() {
        synchronized (lock) {
            return cachedDurationMs;
        }
    }

    public void pauseForLifecycle() {
        final PlayerWorker worker;
        synchronized (lock) {
            if (destroyed) return;

            lifecyclePaused = true;
            resumeAfterLifecyclePause = playWhenReady;
            playing = false;
            worker = prepared ? activeWorker : null;
        }

        if (worker != null) {
            postPlayerCommand(worker, "PAUSE", new PlayerCommand() {
                @Override
                public void run(MediaPlayer player) {
                    player.pause();
                }
            });
        }
    }

    public void resumeFromLifecycle() {
        PlayerWorker worker;
        String urlToStart = null;
        int tokenToStart = 0;
        boolean shouldResume = false;

        synchronized (lock) {
            if (destroyed || !lifecyclePaused) return;

            lifecyclePaused = false;
            worker = activeWorker;

            if (worker != null && prepared) {
                shouldResume = playWhenReady && resumeAfterLifecyclePause && surfaceReady;
                if (shouldResume) {
                    playing = true;
                    resumeAfterLifecyclePause = false;
                }
            } else if (worker == null && pendingUrl != null
                    && surfaceReady && currentHolder != null) {
                urlToStart = pendingUrl;
                tokenToStart = generation;
            }
        }

        if (worker != null && shouldResume) {
            postPlayerCommand(worker, "START", new PlayerCommand() {
                @Override
                public void run(MediaPlayer player) {
                    player.start();
                }
            });
        } else if (urlToStart != null) {
            startWorker(urlToStart, tokenToStart);
        }
    }

    public void destroy() {
        PlayerWorker worker;
        synchronized (lock) {
            if (destroyed) return;

            destroyed = true;
            generation++;
            lifecyclePaused = false;
            resumeAfterLifecyclePause = false;
            playWhenReady = false;
            prepared = false;
            playing = false;
            buffering = false;
            pendingUrl = null;
            currentHolder = null;
            cachedPositionMs = 0;
            cachedDurationMs = 0;

            worker = activeWorker;
            activeWorker = null;
        }

        mainHandler.removeCallbacks(watchdogRunnable);
        retireWorker(worker, "destroy");

        try {
            surfaceView.getHolder().removeCallback(this);
        } catch (RuntimeException ignored) {
        }
    }

    public void release() {
        destroy();
    }

    private void startWorker(final String url, final int token) {
        final SurfaceHolder holder;
        final PlayerWorker worker = new PlayerWorker(token);

        synchronized (lock) {
            if (destroyed || generation != token || activeWorker != null
                    || !surfaceReady || currentHolder == null
                    || pendingUrl == null || !pendingUrl.equals(url)) {
                retireWorker(worker, "stale before start");
                return;
            }
            activeWorker = worker;
            holder = currentHolder;
        }

        Log.d(TAG, "worker start generation=" + token + " url=" + url);
        worker.handler.post(new Runnable() {
            @Override
            public void run() {
                prepareOnWorker(worker, url, holder);
            }
        });
    }

    private void prepareOnWorker(final PlayerWorker worker, String url, SurfaceHolder holder) {
        if (!isCurrentWorker(worker)) return;

        worker.setPhase("CREATE");
        final MediaPlayer player = new MediaPlayer();
        worker.player = player;

        try {
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setVolume(1.0f, 1.0f);
            player.setDisplay(holder);
            player.setScreenOnWhilePlaying(true);
            installListeners(worker, player);

            worker.setPhase("SET_DATA_SOURCE");
            Log.d(TAG, "setDataSource enter generation=" + worker.token + " " + url);
            player.setDataSource(url);
            Log.d(TAG, "setDataSource exit generation=" + worker.token);

            if (!isCurrentWorker(worker)) {
                releasePlayerOnWorker(worker);
                worker.thread.quit();
                return;
            }

            worker.setPhase("PREPARE_ASYNC");
            player.prepareAsync();
            worker.setPhase("PREPARING");
            Log.d(TAG, "prepareAsync queued generation=" + worker.token);
        } catch (IOException e) {
            Log.e(TAG, "system setDataSource IOException generation=" + worker.token + " " + url, e);
            failWorker(worker, safeMessage(e, "System player data source error"));
        } catch (RuntimeException e) {
            Log.e(TAG, "system prepare RuntimeException generation=" + worker.token + " " + url, e);
            failWorker(worker, safeMessage(e, "System player prepare error"));
        }
    }

    private void installListeners(final PlayerWorker worker, final MediaPlayer player) {
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (!isCurrentWorker(worker, mp)) {
                    Log.d(TAG, "ignore stale onPrepared generation=" + worker.token);
                    return;
                }

                worker.setPhase("POSITION");
                int duration = safeDuration(mp);
                int position = safePosition(mp);
                int width = safeVideoWidth(mp);
                int height = safeVideoHeight(mp);

                boolean shouldStart;
                synchronized (lock) {
                    if (!isCurrentWorkerLocked(worker, mp)) return;

                    prepared = true;
                    cachedDurationMs = duration;
                    cachedPositionMs = position;
                    buffering = false;
                    shouldStart = playWhenReady && !lifecyclePaused && surfaceReady;
                    playing = shouldStart;
                    resumeAfterLifecyclePause = playWhenReady && !shouldStart;
                }

                if (shouldStart) {
                    try {
                        worker.setPhase("START");
                        mp.start();
                    } catch (RuntimeException e) {
                        Log.e(TAG, "start after prepare failed generation=" + worker.token, e);
                        failWorker(worker, safeMessage(e, "System player start error"));
                        return;
                    }
                }

                worker.heartbeat();
                worker.setPhase(shouldStart ? "PLAYING" : "READY");
                Log.d(TAG, "system onPrepared generation=" + worker.token
                        + " duration=" + duration + " video=" + width + "x" + height);

                postToMainIfCurrent(worker, new Runnable() {
                    @Override
                    public void run() {
                        listener.onPrepared(duration());
                    }
                });
                startProgressTicker(worker);
            }
        });

        player.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
            @Override
            public void onVideoSizeChanged(MediaPlayer mp, final int width, final int height) {
                if (!isCurrentWorker(worker, mp)) return;

                worker.heartbeat();
                Log.d(TAG, "system videoSize generation=" + worker.token + " "
                        + width + "x" + height);
                postToMainIfCurrent(worker, new Runnable() {
                    @Override
                    public void run() {
                        listener.onVideoSizeChanged(width, height);
                    }
                });
            }
        });

        player.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (!isCurrentWorker(worker, mp)) return true;

                worker.heartbeat();
                Log.d(TAG, "system onInfo generation=" + worker.token
                        + " what=" + what + " extra=" + extra);

                if (what == 701 || what == 702) {
                    final boolean isBuffering = what == 701;
                    synchronized (lock) {
                        if (!isCurrentWorkerLocked(worker, mp)) return true;
                        buffering = isBuffering;
                    }
                    postToMainIfCurrent(worker, new Runnable() {
                        @Override
                        public void run() {
                            listener.onBuffering(isBuffering);
                        }
                    });
                }
                return false;
            }
        });

        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                if (!isCurrentWorker(worker, mp)) return;

                synchronized (lock) {
                    if (!isCurrentWorkerLocked(worker, mp)) return;
                    playWhenReady = false;
                    playing = false;
                    buffering = false;
                    cachedPositionMs = cachedDurationMs;
                }
                worker.heartbeat();
                worker.setPhase("COMPLETED");
                Log.d(TAG, "system onCompletion generation=" + worker.token);

                postToMainIfCurrent(worker, new Runnable() {
                    @Override
                    public void run() {
                        listener.onCompleted();
                    }
                });
            }
        });

        player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (!isCurrentWorker(worker, mp)) {
                    Log.d(TAG, "ignore stale onError generation=" + worker.token
                            + " what=" + what + " extra=" + extra);
                    return true;
                }

                Log.e(TAG, "system onError generation=" + worker.token
                        + " what=" + what + " extra=" + extra + " url=" + pendingUrl);
                failWorker(worker, "System player error " + what + "/" + extra);
                return true;
            }
        });
    }

    private void startProgressTicker(final PlayerWorker worker) {
        worker.handler.post(new Runnable() {
            @Override
            public void run() {
                final MediaPlayer player = worker.player;
                if (!isCurrentWorker(worker, player)) return;

                int position;
                int duration;
                try {
                    worker.setPhase("POSITION");
                    position = player.getCurrentPosition();
                    duration = player.getDuration();
                } catch (RuntimeException e) {
                    Log.e(TAG, "progress poll failed generation=" + worker.token, e);
                    failWorker(worker, safeMessage(e, "System player progress error"));
                    return;
                }

                synchronized (lock) {
                    if (!isCurrentWorkerLocked(worker, player)) return;
                    cachedPositionMs = position;
                    if (duration > 0) cachedDurationMs = duration;
                }

                worker.heartbeat();
                worker.setPhase(isPlaying() ? "PLAYING" : "READY");

                final int callbackPosition = position;
                final int callbackDuration = duration;
                postToMainIfCurrent(worker, new Runnable() {
                    @Override
                    public void run() {
                        listener.onProgress(callbackPosition, callbackDuration);
                    }
                });

                if (isCurrentWorker(worker)) {
                    worker.handler.postDelayed(this, 1000L);
                }
            }
        });
    }

    private void postPlayerCommand(final PlayerWorker worker, final String phase,
                                   final PlayerCommand command) {
        if (worker == null) return;

        worker.handler.post(new Runnable() {
            @Override
            public void run() {
                MediaPlayer player = worker.player;
                if (!isCurrentWorker(worker, player)) return;

                try {
                    worker.setPhase(phase);
                    command.run(player);
                    worker.heartbeat();
                    worker.setPhase(isPlaying() ? "PLAYING" : "READY");
                } catch (RuntimeException e) {
                    Log.e(TAG, phase + " failed generation=" + worker.token, e);
                    failWorker(worker, safeMessage(e, "System player " + phase + " error"));
                }
            }
        });
    }

    private void failWorker(final PlayerWorker worker, final String message) {
        synchronized (lock) {
            if (destroyed || worker == null || activeWorker != worker
                    || generation != worker.token) {
                return;
            }

            Log.e(TAG, "worker failed generation=" + worker.token
                    + " phase=" + worker.phase + " message=" + message);
            generation++;
            activeWorker = null;
            prepared = false;
            playing = false;
            buffering = false;
            playWhenReady = false;
            resumeAfterLifecyclePause = false;
        }

        retireWorker(worker, "failure");

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (destroyed) return;
                }
                listener.onError(message);
            }
        });
    }

    private void retireWorker(final PlayerWorker worker, String reason) {
        if (worker == null) return;

        Log.d(TAG, "worker retire generation=" + worker.token
                + " phase=" + worker.phase + " reason=" + reason);
        worker.handler.removeCallbacksAndMessages(null);
        worker.handler.post(new Runnable() {
            @Override
            public void run() {
                releasePlayerOnWorker(worker);
                worker.setPhase("RELEASED");
                worker.thread.quit();
            }
        });
    }

    private void releasePlayerOnWorker(PlayerWorker worker) {
        MediaPlayer player = worker.player;
        worker.player = null;
        if (player == null) return;

        worker.setPhase("RELEASING");
        try {
            player.setOnPreparedListener(null);
            player.setOnVideoSizeChangedListener(null);
            player.setOnInfoListener(null);
            player.setOnCompletionListener(null);
            player.setOnErrorListener(null);
        } catch (RuntimeException ignored) {
        }

        // Avoid stop(): this TV's vendor HLS stack can block inside
        // CmpbHttpLiveStreaming/IMtkPb_Ctrl_Stop. release() is isolated on the
        // retiring worker so vendor-native stalls can never block PlayerActivity.
        try {
            player.setDisplay(null);
        } catch (RuntimeException ignored) {
        }
        try {
            player.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "release failed generation=" + worker.token, e);
        }
    }

    private boolean isCurrentWorker(PlayerWorker worker) {
        synchronized (lock) {
            return !destroyed && activeWorker == worker && generation == worker.token;
        }
    }

    private boolean isCurrentWorker(PlayerWorker worker, MediaPlayer player) {
        synchronized (lock) {
            return isCurrentWorkerLocked(worker, player);
        }
    }

    private boolean isCurrentWorkerLocked(PlayerWorker worker, MediaPlayer player) {
        return !destroyed && worker != null && activeWorker == worker
                && generation == worker.token && worker.player == player;
    }

    private void postToMainIfCurrent(final PlayerWorker worker, final Runnable callback) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentWorker(worker)) return;
                callback.run();
            }
        });
    }

    private boolean isBlockingPhase(String phase) {
        return "CREATE".equals(phase)
                || "SET_DATA_SOURCE".equals(phase)
                || "PREPARE_ASYNC".equals(phase)
                || "START".equals(phase)
                || "PAUSE".equals(phase)
                || "SEEK".equals(phase)
                || "SURFACE".equals(phase)
                || "POSITION".equals(phase);
    }

    private int safeDuration(MediaPlayer player) {
        try {
            return player.getDuration();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private int safePosition(MediaPlayer player) {
        try {
            return player.getCurrentPosition();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private int safeVideoWidth(MediaPlayer player) {
        try {
            return player.getVideoWidth();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private int safeVideoHeight(MediaPlayer player) {
        try {
            return player.getVideoHeight();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private String safeMessage(Throwable error, String fallback) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.length() == 0 ? fallback : message;
    }

    @Override
    public void surfaceCreated(final SurfaceHolder holder) {
        PlayerWorker worker;
        String urlToStart = null;
        int tokenToStart = 0;
        boolean shouldResume = false;

        synchronized (lock) {
            surfaceReady = true;
            currentHolder = holder;
            if (destroyed) return;

            worker = activeWorker;
            if (worker == null && pendingUrl != null) {
                urlToStart = pendingUrl;
                tokenToStart = generation;
            } else if (worker != null && prepared) {
                shouldResume = playWhenReady && !lifecyclePaused && resumeAfterLifecyclePause;
                if (shouldResume) {
                    playing = true;
                    resumeAfterLifecyclePause = false;
                }
            }
        }

        if (worker != null) {
            final boolean resume = shouldResume;
            postPlayerCommand(worker, "SURFACE", new PlayerCommand() {
                @Override
                public void run(MediaPlayer player) {
                    player.setDisplay(holder);
                    if (resume) player.start();
                }
            });
        } else if (urlToStart != null) {
            startWorker(urlToStart, tokenToStart);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        final PlayerWorker worker;
        final boolean shouldPause;

        synchronized (lock) {
            surfaceReady = false;
            currentHolder = null;

            worker = activeWorker;
            shouldPause = worker != null && prepared && playWhenReady;
            if (shouldPause) {
                playing = false;
                resumeAfterLifecyclePause = true;
            }
        }

        if (worker != null) {
            postPlayerCommand(worker, "SURFACE", new PlayerCommand() {
                @Override
                public void run(MediaPlayer player) {
                    if (shouldPause) player.pause();
                    player.setDisplay(null);
                }
            });
        }
    }

    private interface PlayerCommand {
        void run(MediaPlayer player);
    }

    private static final class PlayerWorker {
        final int token;
        final HandlerThread thread;
        final Handler handler;

        volatile MediaPlayer player;
        volatile String phase = "IDLE";
        volatile long phaseSinceMs = SystemClock.elapsedRealtime();
        volatile long heartbeatMs = phaseSinceMs;

        PlayerWorker(int token) {
            this.token = token;
            thread = new HandlerThread("LegacyPlayer-" + token);
            thread.start();
            handler = new Handler(thread.getLooper());
        }

        void setPhase(String newPhase) {
            phase = newPhase;
            phaseSinceMs = SystemClock.elapsedRealtime();
        }

        void heartbeat() {
            heartbeatMs = SystemClock.elapsedRealtime();
        }
    }
}
