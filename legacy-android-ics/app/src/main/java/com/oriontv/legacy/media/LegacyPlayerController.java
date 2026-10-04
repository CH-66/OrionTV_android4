package com.oriontv.legacy.media;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;

public class LegacyPlayerController implements SurfaceHolder.Callback {
    private static final String TAG = "LegacyPlayer";

    public interface Listener {
        void onPrepared(int durationMs);
        void onProgress(int positionMs, int durationMs);
        void onVideoSizeChanged(int width, int height);
        void onBuffering(boolean buffering);
        void onCompleted();
        void onError(String message);
    }

    private final Context context;
    private final SurfaceView surfaceView;
    private final Listener listener;
    private MediaPlayer mediaPlayer;
    private String pendingUrl;
    private boolean surfaceReady;
    private boolean prepared;
    private boolean lifecyclePaused;
    private boolean resumeAfterLifecyclePause;
    private boolean destroyed;
    private int generation;
    private final android.os.Handler handler = new android.os.Handler();

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && prepared) {
                try {
                    listener.onProgress(mediaPlayer.getCurrentPosition(), mediaPlayer.getDuration());
                } catch (RuntimeException ignored) {
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    public LegacyPlayerController(Context context, SurfaceView surfaceView, Listener listener) {
        this.context = context.getApplicationContext();
        this.surfaceView = surfaceView;
        this.listener = listener;
        this.surfaceView.getHolder().addCallback(this);
    }

    public void load(String url) {
        if (destroyed) {
            Log.w(TAG, "Ignoring load after destroy url=" + url);
            return;
        }
        pendingUrl = url;
        lifecyclePaused = false;
        resumeAfterLifecyclePause = false;

        // Invalidate the previous player immediately. If the Surface is temporarily absent,
        // keeping the old MediaPlayer alive would let surfaceCreated() reattach the old video.
        releaseMediaPlayer(false);

        Log.d(TAG, "load system " + url);
        if (surfaceReady) {
            prepare(url);
        }
    }

    public boolean playPause() {
        if (mediaPlayer == null || !prepared) return false;
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            } else {
                mediaPlayer.start();
            }
            Log.d(TAG, "playPause system playing=" + mediaPlayer.isPlaying());
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "playPause failed", e);
            return false;
        }
    }

    public boolean seekBy(int deltaMs) {
        if (mediaPlayer == null || !prepared) return false;
        try {
            int position = mediaPlayer.getCurrentPosition() + deltaMs;
            if (position < 0) position = 0;
            int duration = mediaPlayer.getDuration();
            if (duration > 0 && position > duration) position = duration;
            mediaPlayer.seekTo(position);
            Log.d(TAG, "seekBy delta=" + deltaMs + " position=" + position + " duration=" + duration);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "seekBy failed delta=" + deltaMs, e);
            return false;
        }
    }

    public boolean seekTo(int positionMs) {
        if (mediaPlayer == null || !prepared) return false;
        try {
            int duration = mediaPlayer.getDuration();
            int target = positionMs;
            if (target < 0) target = 0;
            if (duration > 0 && target > duration) target = duration;
            mediaPlayer.seekTo(target);
            Log.d(TAG, "seekTo position=" + target + " duration=" + duration);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "seekTo failed position=" + positionMs, e);
            return false;
        }
    }

    public boolean isPlaying() {
        if (mediaPlayer == null || !prepared) return false;
        try {
            return mediaPlayer.isPlaying();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public boolean isPrepared() {
        return mediaPlayer != null && prepared;
    }

    public int position() {
        if (mediaPlayer == null || !prepared) return 0;
        try {
            return mediaPlayer.getCurrentPosition();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    public int duration() {
        if (mediaPlayer == null || !prepared) return 0;
        try {
            return mediaPlayer.getDuration();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    public void pauseForLifecycle() {
        if (destroyed) return;
        lifecyclePaused = true;

        if (mediaPlayer != null && prepared) {
            try {
                resumeAfterLifecyclePause = mediaPlayer.isPlaying();
                if (resumeAfterLifecyclePause) {
                    mediaPlayer.pause();
                }
            } catch (RuntimeException ignored) {
                resumeAfterLifecyclePause = false;
            }
        } else {
            // A pending prepare is expected to auto-start once the Activity is active again.
            resumeAfterLifecyclePause = mediaPlayer != null || pendingUrl != null;
        }
    }

    public void resumeFromLifecycle() {
        if (destroyed || !lifecyclePaused) return;
        lifecyclePaused = false;

        if (mediaPlayer != null && prepared) {
            if (resumeAfterLifecyclePause) {
                try {
                    mediaPlayer.start();
                } catch (RuntimeException e) {
                    Log.e(TAG, "resumeFromLifecycle failed", e);
                }
            }
            resumeAfterLifecyclePause = false;
            return;
        }

        if (mediaPlayer == null && pendingUrl != null && surfaceReady) {
            prepare(pendingUrl);
        }
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        lifecyclePaused = false;
        resumeAfterLifecyclePause = false;
        releaseMediaPlayer(true);
        try {
            surfaceView.getHolder().removeCallback(this);
        } catch (RuntimeException ignored) {
        }
    }

    public void release() {
        destroy();
    }

    private void releaseMediaPlayer(boolean clearPendingUrl) {
        // Invalidate every listener captured by the previous MediaPlayer before releasing it.
        generation++;
        handler.removeCallbacks(progressRunnable);
        prepared = false;

        MediaPlayer old = mediaPlayer;
        mediaPlayer = null;
        if (old != null) {
            try {
                old.setOnPreparedListener(null);
                old.setOnVideoSizeChangedListener(null);
                old.setOnInfoListener(null);
                old.setOnCompletionListener(null);
                old.setOnErrorListener(null);
            } catch (RuntimeException ignored) {
            }
            try {
                old.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                old.setDisplay(null);
            } catch (RuntimeException ignored) {
            }
            try {
                old.release();
            } catch (RuntimeException ignored) {
            }
        }

        if (clearPendingUrl) {
            pendingUrl = null;
        }
    }

    private boolean isCurrent(MediaPlayer player, int token) {
        return !destroyed && mediaPlayer == player && generation == token;
    }

    private void prepare(String url) {
        if (destroyed || url == null || url.length() == 0) return;

        releaseMediaPlayer(false);
        final int token = ++generation;
        Log.d(TAG, "prepare system generation=" + token + " " + url);

        final MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;
        player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        player.setVolume(1.0f, 1.0f);
        player.setDisplay(surfaceView.getHolder());
        player.setScreenOnWhilePlaying(true);

        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (!isCurrent(mp, token)) {
                    Log.d(TAG, "ignore stale onPrepared generation=" + token);
                    return;
                }
                prepared = true;
                Log.d(TAG, "system onPrepared generation=" + token
                        + " duration=" + mp.getDuration()
                        + " video=" + mp.getVideoWidth() + "x" + mp.getVideoHeight());
                if (!lifecyclePaused) {
                    mp.start();
                } else {
                    resumeAfterLifecyclePause = true;
                }
                listener.onPrepared(mp.getDuration());
                handler.removeCallbacks(progressRunnable);
                handler.post(progressRunnable);
            }
        });

        player.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
            @Override
            public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                if (!isCurrent(mp, token)) return;
                Log.d(TAG, "system videoSize generation=" + token + " "
                        + width + "x" + height);
                listener.onVideoSizeChanged(width, height);
            }
        });

        player.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (!isCurrent(mp, token)) return true;
                if (what == 701) {
                    Log.d(TAG, "system buffering start generation=" + token);
                    listener.onBuffering(true);
                } else if (what == 702) {
                    Log.d(TAG, "system buffering end generation=" + token);
                    listener.onBuffering(false);
                }
                return false;
            }
        });

        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                if (!isCurrent(mp, token)) return;
                Log.d(TAG, "system onCompletion generation=" + token);
                listener.onCompleted();
            }
        });

        player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (!isCurrent(mp, token)) {
                    Log.d(TAG, "ignore stale onError generation=" + token
                            + " what=" + what + " extra=" + extra);
                    return true;
                }
                prepared = false;
                Log.e(TAG, "system onError generation=" + token
                        + " what=" + what + " extra=" + extra + " url=" + pendingUrl);
                listener.onError("System player error " + what + "/" + extra);
                return true;
            }
        });

        try {
            player.setDataSource(url);
            player.prepareAsync();
        } catch (IOException e) {
            if (isCurrent(player, token)) {
                Log.e(TAG, "system setDataSource IOException " + url, e);
                listener.onError(e.getMessage());
            }
        } catch (RuntimeException e) {
            if (isCurrent(player, token)) {
                Log.e(TAG, "system setDataSource RuntimeException " + url, e);
                listener.onError(e.getMessage());
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (destroyed) return;

        if (mediaPlayer != null) {
            try {
                mediaPlayer.setDisplay(holder);
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to reattach Surface", e);
            }
        } else if (pendingUrl != null) {
            prepare(pendingUrl);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.setDisplay(null);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
