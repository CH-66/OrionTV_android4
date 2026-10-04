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
        pendingUrl = url;
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

    public void release() {
        handler.removeCallbacks(progressRunnable);
        prepared = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                mediaPlayer.setDisplay(null);
            } catch (RuntimeException ignored) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void prepare(String url) {
        release();
        Log.d(TAG, "prepare system MediaPlayer " + url);

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.setVolume(1.0f, 1.0f);
        mediaPlayer.setDisplay(surfaceView.getHolder());
        mediaPlayer.setScreenOnWhilePlaying(true);

        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
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

        mediaPlayer.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
            @Override
            public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                Log.d(TAG, "system videoSize=" + width + "x" + height);\n                listener.onVideoSizeChanged(width, height);
            }
        });

        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                Log.d(TAG, "system onCompletion");
                listener.onCompleted();
            }
        });

        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                prepared = false;
                Log.e(TAG, "system onError what=" + what + " extra=" + extra + " url=" + pendingUrl);
                listener.onError("System player error " + what + "/" + extra);
                return true;
            }
        });

        try {
            mediaPlayer.setDataSource(url);
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            Log.e(TAG, "system setDataSource IOException " + url, e);
            listener.onError(e.getMessage());
        } catch (RuntimeException e) {
            Log.e(TAG, "system setDataSource RuntimeException " + url, e);
            listener.onError(e.getMessage());
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (pendingUrl != null && mediaPlayer == null) {
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
