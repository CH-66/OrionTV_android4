package com.oriontv.legacy.media;

import android.content.Context;
import android.media.AudioManager;
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
    private IjkMediaPlayer mediaPlayer;
    private String pendingUrl;
    private boolean surfaceReady;
    private boolean prepared;
    private final android.os.Handler handler = new android.os.Handler();

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && prepared) {
                try {
                    listener.onProgress((int) mediaPlayer.getCurrentPosition(), (int) mediaPlayer.getDuration());
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
        Log.d(TAG, "load " + url);
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
            Log.d(TAG, "playPause playing=" + mediaPlayer.isPlaying());
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "playPause failed", e);
            return false;
        }
    }

    public boolean seekBy(int deltaMs) {
        if (mediaPlayer == null || !prepared) return false;
        try {
            long position = mediaPlayer.getCurrentPosition() + deltaMs;
            if (position < 0) position = 0;
            long duration = mediaPlayer.getDuration();
            if (duration > 0 && position > duration) position = duration;
            mediaPlayer.seekTo(position);
            Log.d(TAG, "seekBy delta=" + deltaMs + " position=" + position + " duration=" + duration);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "seekBy failed delta=" + deltaMs, e);
            return false;
        }
    }

    public boolean isPlaying() {
        if (mediaPlayer == null || !prepared) return false;
        try {
            return mediaPlayer.isPlaying();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public boolean isPrepared() {
        return mediaPlayer != null && prepared;
    }

    public int position() {
        if (mediaPlayer == null || !prepared) return 0;
        return (int) mediaPlayer.getCurrentPosition();
    }

    public int duration() {
        if (mediaPlayer == null || !prepared) return 0;
        return (int) mediaPlayer.getDuration();
    }

    public void release() {
        handler.removeCallbacks(progressRunnable);
        prepared = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (RuntimeException ignored) {
            }
            mediaPlayer.setDisplay(null);
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void prepare(String url) {
        release();
        Log.d(TAG, "prepare ijk " + url);
        mediaPlayer = new IjkMediaPlayer();
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.setVolume(1.0f, 1.0f);
        mediaPlayer.setDisplay(surfaceView.getHolder());
        mediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0);
        mediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 0);
        mediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 1);
        mediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", SDL_FCC_I420);
        mediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
        mediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
        mediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1);
        mediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        mediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        mediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 15000000);
        mediaPlayer.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(IMediaPlayer mp) {
                prepared = true;
                Log.d(TAG, "onPrepared duration=" + mp.getDuration());
                mp.start();
                listener.onPrepared((int) mp.getDuration());
                handler.post(progressRunnable);
            }
        });
        mediaPlayer.setOnCompletionListener(new IMediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(IMediaPlayer mp) {
                Log.d(TAG, "onCompletion");
                listener.onCompleted();
            }
        });
        mediaPlayer.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(IMediaPlayer mp, int what, int extra) {
                prepared = false;
                Log.e(TAG, "onError what=" + what + " extra=" + extra + " url=" + pendingUrl);
                listener.onError("IjkPlayer error " + what + "/" + extra);
                return true;
            }
        });

        try {
            mediaPlayer.setDataSource(url);
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            Log.e(TAG, "setDataSource IOException " + url, e);
            listener.onError(e.getMessage());
        } catch (RuntimeException e) {
            Log.e(TAG, "setDataSource RuntimeException " + url, e);
            listener.onError(e.getMessage());
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (pendingUrl != null) {
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
            mediaPlayer.setDisplay(null);
        }
    }
}
