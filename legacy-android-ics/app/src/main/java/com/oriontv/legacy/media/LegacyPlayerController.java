package com.oriontv.legacy.media;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

public class LegacyPlayerController implements SurfaceHolder.Callback {
    private static final String TAG = "LegacyPlayer";
    private static final int SDL_FCC_I420 = 0x30323449;

    public interface Listener {
        void onPrepared(int durationMs);
        void onProgress(int positionMs, int durationMs);
        void onCompleted();
        void onError(String message);
    }

    private final Context context;
    private final SurfaceView surfaceView;
    private final Listener listener;

    private MediaPlayer systemPlayer;
    private IjkMediaPlayer ijkPlayer;
    private boolean usingSystemPlayer;
    private boolean fallbackTried;
    private String pendingUrl;
    private boolean surfaceReady;
    private boolean prepared;
    private final android.os.Handler handler = new android.os.Handler();

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (prepared) {
                try {
                    listener.onProgress(position(), duration());
                } catch (RuntimeException ignored) {
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    static {
        IjkMediaPlayer.loadLibrariesOnce(null);
        IjkMediaPlayer.native_profileBegin("libijkplayer.so");
    }

    public LegacyPlayerController(Context context, SurfaceView surfaceView, Listener listener) {
        this.context = context.getApplicationContext();
        this.surfaceView = surfaceView;
        this.listener = listener;
        this.surfaceView.getHolder().addCallback(this);
    }

    public void load(String url) {
        pendingUrl = url;
        fallbackTried = false;
        Log.d(TAG, "load " + url);
        if (surfaceReady) {
            prepareSystem(url);
        }
    }

    public boolean playPause() {
        if (!prepared) return false;
        try {
            if (usingSystemPlayer && systemPlayer != null) {
                if (systemPlayer.isPlaying()) systemPlayer.pause(); else systemPlayer.start();
                Log.d(TAG, "playPause system playing=" + systemPlayer.isPlaying());
                return true;
            }
            if (!usingSystemPlayer && ijkPlayer != null) {
                if (ijkPlayer.isPlaying()) ijkPlayer.pause(); else ijkPlayer.start();
                Log.d(TAG, "playPause ijk playing=" + ijkPlayer.isPlaying());
                return true;
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "playPause failed", e);
        }
        return false;
    }

    public boolean seekBy(int deltaMs) {
        if (!prepared) return false;
        try {
            long position = position() + deltaMs;
            if (position < 0) position = 0;
            long duration = duration();
            if (duration > 0 && position > duration) position = duration;
            if (usingSystemPlayer && systemPlayer != null) {
                systemPlayer.seekTo((int) position);
            } else if (ijkPlayer != null) {
                ijkPlayer.seekTo(position);
            } else {
                return false;
            }
            Log.d(TAG, "seekBy delta=" + deltaMs + " position=" + position + " duration=" + duration);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "seekBy failed delta=" + deltaMs, e);
            return false;
        }
    }

    public boolean isPlaying() {
        if (!prepared) return false;
        try {
            if (usingSystemPlayer && systemPlayer != null) return systemPlayer.isPlaying();
            if (!usingSystemPlayer && ijkPlayer != null) return ijkPlayer.isPlaying();
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    public boolean isPrepared() {
        return prepared;
    }

    public int position() {
        if (!prepared) return 0;
        try {
            if (usingSystemPlayer && systemPlayer != null) return systemPlayer.getCurrentPosition();
            if (!usingSystemPlayer && ijkPlayer != null) return (int) ijkPlayer.getCurrentPosition();
        } catch (RuntimeException ignored) {
        }
        return 0;
    }

    public int duration() {
        if (!prepared) return 0;
        try {
            if (usingSystemPlayer && systemPlayer != null) return systemPlayer.getDuration();
            if (!usingSystemPlayer && ijkPlayer != null) return (int) ijkPlayer.getDuration();
        } catch (RuntimeException ignored) {
        }
        return 0;
    }

    public void release() {
        handler.removeCallbacks(progressRunnable);
        releaseCurrentPlayer();
    }

    private void releaseCurrentPlayer() {
        prepared = false;
        if (systemPlayer != null) {
            try {
                systemPlayer.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                systemPlayer.setDisplay(null);
            } catch (RuntimeException ignored) {
            }
            systemPlayer.release();
            systemPlayer = null;
        }
        if (ijkPlayer != null) {
            try {
                ijkPlayer.stop();
            } catch (RuntimeException ignored) {
            }
            ijkPlayer.setDisplay(null);
            ijkPlayer.release();
            ijkPlayer = null;
        }
    }

    private void prepareSystem(final String url) {
        releaseCurrentPlayer();
        usingSystemPlayer = true;
        Log.d(TAG, "prepare system MediaPlayer " + url);

        systemPlayer = new MediaPlayer();
        systemPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        systemPlayer.setVolume(1.0f, 1.0f);
        systemPlayer.setDisplay(surfaceView.getHolder());
        systemPlayer.setScreenOnWhilePlaying(true);

        systemPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                prepared = true;
                Log.d(TAG, "system onPrepared duration=" + mp.getDuration()
                        + " video=" + mp.getVideoWidth() + "x" + mp.getVideoHeight());
                mp.start();
                listener.onPrepared(mp.getDuration());
                handler.removeCallbacks(progressRunnable);
                handler.post(progressRunnable);
            }
        });

        systemPlayer.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
            @Override
            public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                Log.d(TAG, "system videoSize=" + width + "x" + height);
            }
        });

        systemPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                Log.d(TAG, "system onCompletion");
                listener.onCompleted();
            }
        });

        systemPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                prepared = false;
                Log.e(TAG, "system onError what=" + what + " extra=" + extra + " url=" + pendingUrl);
                if (!fallbackTried && pendingUrl != null) {
                    fallbackTried = true;
                    Log.w(TAG, "system player failed; falling back to IjkPlayer");
                    prepareIjk(pendingUrl);
                    return true;
                }
                listener.onError("System player error " + what + "/" + extra);
                return true;
            }
        });

        try {
            systemPlayer.setDataSource(url);
            systemPlayer.prepareAsync();
        } catch (IOException e) {
            Log.e(TAG, "system setDataSource IOException " + url, e);
            fallbackToIjk(url, e.getMessage());
        } catch (RuntimeException e) {
            Log.e(TAG, "system setDataSource RuntimeException " + url, e);
            fallbackToIjk(url, e.getMessage());
        }
    }

    private void fallbackToIjk(String url, String reason) {
        if (!fallbackTried) {
            fallbackTried = true;
            Log.w(TAG, "system player setup failed; falling back to IjkPlayer: " + reason);
            prepareIjk(url);
        } else {
            listener.onError(reason == null ? "System player failed" : reason);
        }
    }

    private void prepareIjk(String url) {
        releaseCurrentPlayer();
        usingSystemPlayer = false;
        Log.d(TAG, "prepare ijk fallback " + url);

        ijkPlayer = new IjkMediaPlayer();
        ijkPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        ijkPlayer.setVolume(1.0f, 1.0f);
        ijkPlayer.setDisplay(surfaceView.getHolder());
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 0);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 1);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", SDL_FCC_I420);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 15000000);

        ijkPlayer.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(IMediaPlayer mp) {
                prepared = true;
                Log.d(TAG, "ijk fallback onPrepared duration=" + mp.getDuration());
                mp.start();
                listener.onPrepared((int) mp.getDuration());
                handler.removeCallbacks(progressRunnable);
                handler.post(progressRunnable);
            }
        });

        ijkPlayer.setOnCompletionListener(new IMediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(IMediaPlayer mp) {
                Log.d(TAG, "ijk fallback onCompletion");
                listener.onCompleted();
            }
        });

        ijkPlayer.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(IMediaPlayer mp, int what, int extra) {
                prepared = false;
                Log.e(TAG, "ijk fallback onError what=" + what + " extra=" + extra + " url=" + pendingUrl);
                listener.onError("IjkPlayer error " + what + "/" + extra);
                return true;
            }
        });

        try {
            ijkPlayer.setDataSource(url);
            ijkPlayer.prepareAsync();
        } catch (IOException e) {
            Log.e(TAG, "ijk setDataSource IOException " + url, e);
            listener.onError(e.getMessage());
        } catch (RuntimeException e) {
            Log.e(TAG, "ijk setDataSource RuntimeException " + url, e);
            listener.onError(e.getMessage());
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (pendingUrl != null && systemPlayer == null && ijkPlayer == null) {
            prepareSystem(pendingUrl);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        if (systemPlayer != null) {
            try {
                systemPlayer.setDisplay(null);
            } catch (RuntimeException ignored) {
            }
        }
        if (ijkPlayer != null) {
            ijkPlayer.setDisplay(null);
        }
    }
}
