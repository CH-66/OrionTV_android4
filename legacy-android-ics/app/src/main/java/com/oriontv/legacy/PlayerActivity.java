package com.oriontv.legacy;

import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.oriontv.legacy.api.ApiCallback;
import com.oriontv.legacy.api.models.MutationResult;
import com.oriontv.legacy.api.models.PlayRecord;
import com.oriontv.legacy.api.models.SearchResult;
import com.oriontv.legacy.data.LocalRepository;
import com.oriontv.legacy.media.LegacyPlayerController;
import com.oriontv.legacy.media.PlaybackSourceSelector;
import com.oriontv.legacy.net.LegacyHttpCompat;
import com.oriontv.legacy.ui.Ui;

import java.lang.reflect.Type;
import java.util.ArrayList;

public class PlayerActivity extends BaseActivity implements LegacyPlayerController.Listener {
    private final Gson gson = new Gson();
    private final PlaybackSourceSelector selector = new PlaybackSourceSelector();
    private ArrayList<SearchResult> sources = new ArrayList<SearchResult>();
    private SearchResult currentSource;
    private int episodeIndex;
    private LegacyPlayerController controller;
    private TextView overlay;
    private TextView title;
    private ProgressBar progress;
    private long lastSave;
    private final android.os.Handler handler = new android.os.Handler();
    private final Runnable hideOverlay = new Runnable() {
        @Override
        public void run() {
            overlay.setVisibility(View.GONE);
            title.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        parseIntent();
        buildUi();
        playCurrent();
    }

    @Override
    protected void onPause() {
        saveRecord(true);
        if (controller != null) controller.release();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (controller != null) controller.release();
        super.onDestroy();
    }

    private void parseIntent() {
        String json = getIntent().getStringExtra("sources_json");
        Type type = new TypeToken<ArrayList<SearchResult>>() {}.getType();
        try {
            sources = gson.fromJson(json, type);
        } catch (RuntimeException ignored) {
            sources = new ArrayList<SearchResult>();
        }
        if (sources == null) sources = new ArrayList<SearchResult>();
        if (sources.size() == 0) {
            String playUrl = getIntent().getStringExtra("play_url");
            if (playUrl != null && playUrl.length() > 0) {
                SearchResult direct = new SearchResult();
                direct.id = "direct";
                direct.source = "direct";
                direct.source_name = "Direct";
                direct.title = getIntent().getStringExtra("title");
                if (direct.title == null || direct.title.length() == 0) {
                    direct.title = "Direct";
                }
                direct.poster = "";
                direct.episodes.add(playUrl);
                sources.add(direct);
            }
        }
        String source = getIntent().getStringExtra("source");
        for (int i = 0; i < sources.size(); i++) {
            SearchResult item = sources.get(i);
            if (item.source != null && item.source.equals(source)) {
                currentSource = item;
                break;
            }
        }
        if (currentSource == null && sources.size() > 0) currentSource = sources.get(0);
        episodeIndex = getIntent().getIntExtra("episode_index", 0);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        SurfaceView surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        title = new TextView(this);
        title.setTextColor(0xffffffff);
        title.setTextSize(22);
        title.setPadding(Ui.dp(this, 24), Ui.dp(this, 18), Ui.dp(this, 24), Ui.dp(this, 8));
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(title, titleParams);

        overlay = new TextView(this);
        overlay.setTextColor(0xffffffff);
        overlay.setTextSize(18);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 80), Gravity.BOTTOM);
        root.addView(overlay, overlayParams);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 6), Gravity.BOTTOM);
        root.addView(progress, progressParams);

        setContentView(root);
        controller = new LegacyPlayerController(this, surface, this);
        showOverlay("准备播放");
    }

    private void playCurrent() {
        if (currentSource == null || currentSource.episodes == null || currentSource.episodes.size() <= episodeIndex) {
            showOverlay("没有可播放地址");
            return;
        }
        String originalUrl = currentSource.episodes.get(episodeIndex);
        title.setText(currentSource.title + " / " + currentSource.source_name + " / 第" + (episodeIndex + 1) + "集");
        showOverlay("正在加载第" + (episodeIndex + 1) + "集");
        String playableUrl = app.playbackProxy().proxyUrl(originalUrl);
        controller.load(playableUrl);
    }

    private void showOverlay(String text) {
        overlay.setText(text);
        overlay.setVisibility(View.VISIBLE);
        title.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOverlay);
        handler.postDelayed(hideOverlay, 5000);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            controller.playPause();
            showOverlay("播放 / 暂停");
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_MEDIA_REWIND) {
            controller.seekBy(-15000);
            showOverlay("快退 15 秒");
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            controller.seekBy(15000);
            showOverlay("快进 15 秒");
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            previousEpisode();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            nextEpisode();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void nextEpisode() {
        if (currentSource != null && currentSource.episodes != null && episodeIndex < currentSource.episodes.size() - 1) {
            saveRecord(true);
            episodeIndex++;
            playCurrent();
        }
    }

    private void previousEpisode() {
        if (episodeIndex > 0) {
            saveRecord(true);
            episodeIndex--;
            playCurrent();
        }
    }

    @Override
    public void onPrepared(int durationMs) {
        showOverlay("开始播放");
    }

    @Override
    public void onProgress(int positionMs, int durationMs) {
        if (durationMs > 0) {
            progress.setProgress((int) ((positionMs * 1000L) / durationMs));
        }
        if (System.currentTimeMillis() - lastSave > 10000) {
            saveRecord(false);
        }
    }

    @Override
    public void onCompleted() {
        saveRecord(true);
        nextEpisode();
    }

    @Override
    public void onError(String message) {
        if (currentSource != null) selector.markFailed(currentSource.source);
        SearchResult fallback = selector.next(sources, currentSource == null ? null : currentSource.source, episodeIndex);
        if (fallback != null) {
            currentSource = fallback;
            showOverlay("播放失败，切换到 " + fallback.source_name);
            playCurrent();
            return;
        }
        if (LegacyHttpCompat.isTlsProblem(new RuntimeException(message))) {
            showOverlay(LegacyHttpCompat.buildCompatMessage("播放"));
        } else {
            showOverlay("播放失败：" + message);
        }
    }

    private void saveRecord(boolean immediate) {
        if (currentSource == null) return;
        long now = System.currentTimeMillis();
        if (!immediate && now - lastSave < 10000) return;
        lastSave = now;

        PlayRecord record = new PlayRecord();
        record.title = currentSource.title;
        record.cover = currentSource.poster;
        record.index = episodeIndex + 1;
        record.total_episodes = currentSource.episodes == null ? 0 : currentSource.episodes.size();
        record.play_time = controller == null ? 0 : controller.position() / 1000;
        record.total_time = controller == null ? 0 : controller.duration() / 1000;
        record.source_name = currentSource.source_name;
        record.year = currentSource.year;
        app.local().savePlayRecord(currentSource.source, currentSource.id, record);

        if (!app.prefs().useLocalStorage()) {
            app.api().savePlayRecord(LocalRepository.key(currentSource.source, currentSource.id), record, new ApiCallback<MutationResult>() {
                @Override
                public void onSuccess(MutationResult value) {
                }

                @Override
                public void onError(Throwable error) {
                }
            });
        }
    }
}
