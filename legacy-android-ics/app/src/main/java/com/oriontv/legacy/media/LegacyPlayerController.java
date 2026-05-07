package com.oriontv.legacy.media;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;

public class LegacyPlayerController implements SurfaceHolder.Callback {
    public interface Listener {
        void onPrepared(int durationMs);
        void onProgress(int positionMs, int durationMs);
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
        this.context = context;
        this.surfaceView = surfaceView;
        this.listener = listener;
        this.surfaceView.getHolder().addCallback(this);
    }

    public void load(String url) {
        pendingUrl = url;
        if (surfaceReady) {
            prepare(url);
        }
    }

    public void playPause() {
        if (mediaPlayer == null || !prepared) return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        } else {
            mediaPlayer.start();
        }
    }

    public void seekBy(int deltaMs) {
        if (mediaPlayer == null || !prepared) return;
        int position = mediaPlayer.getCurrentPosition() + deltaMs;
        if (position < 0) position = 0;
        int duration = mediaPlayer.getDuration();
        if (duration > 0 && position > duration) position = duration;
        mediaPlayer.seekTo(position);
    }

    public int position() {
        if (mediaPlayer == null || !prepared) return 0;
        return mediaPlayer.getCurrentPosition();
    }

    public int duration() {
        if (mediaPlayer == null || !prepared) return 0;
        return mediaPlayer.getDuration();
    }

    public void release() {
        handler.removeCallbacks(progressRunnable);
        prepared = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (RuntimeException ignored) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void prepare(String url) {
        release();
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.setDisplay(surfaceView.getHolder());
        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                prepared = true;
                mp.start();
                listener.onPrepared(mp.getDuration());
                handler.post(progressRunnable);
            }
        });
        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                listener.onCompleted();
            }
        });
        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                prepared = false;
                listener.onError("MediaPlayer error " + what + "/" + extra);
                return true;
            }
        });

        try {
            mediaPlayer.setDataSource(context, Uri.parse(url));
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            listener.onError(e.getMessage());
        } catch (RuntimeException e) {
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
    }
}
