package com.oriontv.legacy;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.util.ArrayList;

public class PlayerActivity extends BaseActivity implements LegacyPlayerController.Listener {
    private static final String VIDEO_PROXY_BASE = "http://tvproxy.t2t.cc.cd/v1";
    private static final String VIDEO_PROXY_TOKEN = "4pCCnLfe_qROZ0cGRF1tU1CigWgHDSwNvdbhAsP4Q04";
    private static final int SEEK_STEP_MS = 10000;

    private final Gson gson = new Gson();
    private final PlaybackSourceSelector selector = new PlaybackSourceSelector();
    private final android.os.Handler handler = new android.os.Handler();

    private ArrayList<SearchResult> sources = new ArrayList<SearchResult>();
    private SearchResult currentSource;
    private int episodeIndex;
    private LegacyPlayerController controller;
    private long lastSave;

    private FrameLayout playerRoot;
    private SurfaceView surfaceView;
    private LinearLayout topBar;
    private LinearLayout bottomPanel;
    private TextView titleView;
    private TextView metaView;
    private TextView statusView;
    private TextView currentTimeView;
    private TextView durationView;
    private ProgressBar progress;
    private Button playPauseButton;
    private Button sourceButton;
    private boolean controlsVisible;

    private final Runnable hideChrome = new Runnable() {
        @Override
        public void run() {
            hideControls();
        }
    };

    private final Runnable hideStatus = new Runnable() {
        @Override
        public void run() {
            if (statusView != null) statusView.setVisibility(View.GONE);
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
        handler.removeCallbacksAndMessages(null);
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
                if (direct.title == null || direct.title.length() == 0) direct.title = "Direct";
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
        playerRoot = new FrameLayout(this);
        playerRoot.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        FrameLayout.LayoutParams surfaceParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        playerRoot.addView(surfaceView, surfaceParams);

        buildTopBar();
        buildStatus();
        buildBottomPanel();

        setContentView(playerRoot);
        controller = new LegacyPlayerController(this, surfaceView, this);

        controlsVisible = true;
        showControls();
        playPauseButton.requestFocus();
    }

    private void buildTopBar() {
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setPadding(dp(34), dp(24), dp(34), dp(18));
        topBar.setBackgroundColor(0x99000000);

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(25);
        titleView.setSingleLine(true);

        metaView = new TextView(this);
        metaView.setTextColor(0xffb8c0cc);
        metaView.setTextSize(15);
        metaView.setSingleLine(true);
        metaView.setPadding(0, dp(5), 0, 0);

        topBar.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topBar.addView(metaView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        playerRoot.addView(topBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
    }

    private void buildStatus() {
        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(18);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(26), dp(14), dp(26), dp(14));
        statusView.setBackgroundDrawable(roundedBackground(0xd91a1d22, 14, 0, 0));
        statusView.setVisibility(View.GONE);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        playerRoot.addView(statusView, params);
    }

    private void buildBottomPanel() {
        bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setPadding(dp(28), dp(16), dp(28), dp(20));
        bottomPanel.setBackgroundDrawable(roundedBackground(0xe6171a1f, 18, 0, 0));

        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);

        currentTimeView = timeText("00:00", Gravity.LEFT);
        durationView = timeText("--:--", Gravity.RIGHT);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress(0);

        progressRow.addView(currentTimeView, new LinearLayout.LayoutParams(dp(72), dp(30)));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(0, dp(12), 1);
        progressParams.setMargins(dp(12), 0, dp(12), 0);
        progressRow.addView(progress, progressParams);
        progressRow.addView(durationView, new LinearLayout.LayoutParams(dp(72), dp(30)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(12), 0, 0);

        Button previous = playerButton("上一集", false);
        Button rewind = playerButton("快退 10秒", false);
        playPauseButton = playerButton("播放", true);
        Button forward = playerButton("快进 10秒", false);
        Button next = playerButton("下一集", false);
        sourceButton = playerButton("切换线路", false);

        buttons.addView(previous);
        buttons.addView(rewind);
        buttons.addView(playPauseButton);
        buttons.addView(forward);
        buttons.addView(next);
        buttons.addView(sourceButton);

        bottomPanel.addView(progressRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
        bottomPanel.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(126),
                Gravity.BOTTOM);
        bottomParams.setMargins(dp(18), 0, dp(18), dp(16));
        playerRoot.addView(bottomPanel, bottomParams);

        previous.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { previousEpisode(); }
        });
        rewind.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { seekBy(-SEEK_STEP_MS, "快退 10 秒"); }
        });
        playPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { playPause(); }
        });
        forward.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { seekBy(SEEK_STEP_MS, "快进 10 秒"); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { nextEpisode(); }
        });
        sourceButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchSource(); }
        });
    }

    private Button playerButton(final String text, final boolean primary) {
        final Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setSingleLine(true);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundDrawable(buttonBackground(false, primary));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
        params.setMargins(dp(4), 0, dp(4), 0);
        button.setLayoutParams(params);

        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                button.setBackgroundDrawable(buttonBackground(hasFocus, primary));
                button.setScaleX(hasFocus ? 1.06f : 1.0f);
                button.setScaleY(hasFocus ? 1.06f : 1.0f);
                if (hasFocus) scheduleHideControls();
            }
        });
        return button;
    }

    private TextView timeText(String text, int gravity) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xffd8dde6);
        view.setTextSize(14);
        view.setGravity(gravity | Gravity.CENTER_VERTICAL);
        view.setSingleLine(true);
        return view;
    }

    private GradientDrawable roundedBackground(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private GradientDrawable buttonBackground(boolean focused, boolean primary) {
        if (focused) {
            return roundedBackground(primary ? 0xff2f80ed : 0xff343b45, 9, 2, 0xffffffff);
        }
        return roundedBackground(primary ? 0xff2468c7 : 0xcc252a31, 9, 1, 0x334f5966);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void playCurrent() {
        if (currentSource == null || currentSource.episodes == null
                || currentSource.episodes.size() <= episodeIndex) {
            showStatus("没有可播放地址", true, 3500);
            return;
        }

        updateMediaLabels();
        showStatus("正在连接播放线路…", true, 0);

        String originalUrl = currentSource.episodes.get(episodeIndex);
        controller.load(buildPlayableUrl(originalUrl));
    }

    private void updateMediaLabels() {
        if (currentSource == null) return;
        String name = currentSource.title == null || currentSource.title.length() == 0
                ? "正在播放" : currentSource.title;
        String sourceName = currentSource.source_name == null || currentSource.source_name.length() == 0
                ? "未知线路" : currentSource.source_name;
        int total = currentSource.episodes == null ? 0 : currentSource.episodes.size();

        titleView.setText(name);
        metaView.setText("第 " + (episodeIndex + 1) + (total > 0 ? " / " + total + " 集" : " 集")
                + "   ·   " + sourceName);
        sourceButton.setText(sources.size() > 1 ? "切换线路" : "当前线路");
    }

    private String buildPlayableUrl(String originalUrl) {
        if (originalUrl == null || originalUrl.length() == 0) return originalUrl;
        if (originalUrl.toLowerCase().startsWith("https://")) {
            try {
                return VIDEO_PROXY_BASE + "?u=" + URLEncoder.encode(originalUrl, "UTF-8")
                        + "&k=" + URLEncoder.encode(VIDEO_PROXY_TOKEN, "UTF-8");
            } catch (UnsupportedEncodingException ignored) {
            }
        }
        return originalUrl;
    }

    private void showControls() {
        controlsVisible = true;
        topBar.setVisibility(View.VISIBLE);
        bottomPanel.setVisibility(View.VISIBLE);
        scheduleHideControls();
    }

    private void hideControls() {
        if (controller != null && !controller.isPlaying()) return;
        controlsVisible = false;
        topBar.setVisibility(View.GONE);
        bottomPanel.setVisibility(View.GONE);
    }

    private void scheduleHideControls() {
        handler.removeCallbacks(hideChrome);
        handler.postDelayed(hideChrome, 4500);
    }

    private void showStatus(String text, boolean showChrome, long autoHideMs) {
        statusView.setText(text);
        statusView.setVisibility(View.VISIBLE);
        if (showChrome) showControls();
        handler.removeCallbacks(hideStatus);
        if (autoHideMs > 0) handler.postDelayed(hideStatus, autoHideMs);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();

        if (key == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (controlsVisible) {
                hideControls();
            } else {
                finish();
            }
            return true;
        }

        if (isMediaKey(key)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                handleMediaKey(key);
            }
            return true;
        }

        if (!controlsVisible && isRemoteNavigationKey(key)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    seekBy(-SEEK_STEP_MS, "快退 10 秒");
                } else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    seekBy(SEEK_STEP_MS, "快进 10 秒");
                } else {
                    showControls();
                    if (playPauseButton != null) playPauseButton.requestFocus();
                }
            }
            return true;
        }

        if (controlsVisible && isRemoteNavigationKey(key)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) scheduleHideControls();
            return super.dispatchKeyEvent(event);
        }

        return super.dispatchKeyEvent(event);
    }

    private boolean isRemoteNavigationKey(int key) {
        return key == KeyEvent.KEYCODE_DPAD_CENTER
                || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_DPAD_LEFT
                || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_UP
                || key == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private boolean isMediaKey(int key) {
        return key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || key == KeyEvent.KEYCODE_MEDIA_PLAY
                || key == KeyEvent.KEYCODE_MEDIA_PAUSE
                || key == KeyEvent.KEYCODE_MEDIA_REWIND
                || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || key == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || key == KeyEvent.KEYCODE_MEDIA_NEXT
                || key == KeyEvent.KEYCODE_SPACE;
    }

    private void handleMediaKey(int key) {
        if (key == KeyEvent.KEYCODE_MEDIA_REWIND) {
            seekBy(-SEEK_STEP_MS, "快退 10 秒");
        } else if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            seekBy(SEEK_STEP_MS, "快进 10 秒");
        } else if (key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            previousEpisode();
        } else if (key == KeyEvent.KEYCODE_MEDIA_NEXT) {
            nextEpisode();
        } else {
            playPause();
        }
    }

    private void playPause() {
        if (controller != null && controller.playPause()) {
            updatePlayPauseButton();
            if (controller.isPlaying()) {
                showStatus("继续播放", true, 1200);
            } else {
                showStatus("已暂停", true, 0);
            }
        } else {
            showStatus("播放器准备中…", true, 1800);
        }
    }

    private void seekBy(int deltaMs, String label) {
        if (controller != null && controller.seekBy(deltaMs)) {
            showStatus(label + "   " + formatTime(controller.position()), false, 1500);
            showControls();
        } else {
            showStatus("播放器准备中…", true, 1800);
        }
    }

    private void nextEpisode() {
        if (currentSource != null && currentSource.episodes != null
                && episodeIndex < currentSource.episodes.size() - 1) {
            saveRecord(true);
            episodeIndex++;
            playCurrent();
        } else {
            showStatus("已经是最后一集", true, 1800);
        }
    }

    private void previousEpisode() {
        if (episodeIndex > 0) {
            saveRecord(true);
            episodeIndex--;
            playCurrent();
        } else {
            showStatus("已经是第一集", true, 1800);
        }
    }

    private void switchSource() {
        if (sources == null || sources.size() <= 1 || currentSource == null) {
            showStatus("当前没有其他可用线路", true, 1800);
            return;
        }

        int currentIndex = sources.indexOf(currentSource);
        for (int offset = 1; offset <= sources.size(); offset++) {
            SearchResult candidate = sources.get((currentIndex + offset + sources.size()) % sources.size());
            if (candidate != null && candidate.episodes != null && candidate.episodes.size() > episodeIndex) {
                saveRecord(true);
                currentSource = candidate;
                showStatus("正在切换到 " + safeSourceName(candidate), true, 1400);
                playCurrent();
                return;
            }
        }
        showStatus("没有其他线路包含当前集", true, 2000);
    }

    private String safeSourceName(SearchResult item) {
        return item == null || item.source_name == null || item.source_name.length() == 0
                ? "其他线路" : item.source_name;
    }

    @Override
    public void onPrepared(int durationMs) {
        updatePlayPauseButton();
        durationView.setText(formatTime(durationMs));
        showStatus("开始播放", true, 1200);
    }

    @Override
    public void onProgress(int positionMs, int durationMs) {
        currentTimeView.setText(formatTime(positionMs));
        durationView.setText(durationMs > 0 ? formatTime(durationMs) : "--:--");
        if (durationMs > 0) {
            progress.setProgress((int) ((positionMs * 1000L) / durationMs));
        }

        if (System.currentTimeMillis() - lastSave > 10000) saveRecord(false);
    }

    @Override
    public void onVideoSizeChanged(final int width, final int height) {
        if (width <= 0 || height <= 0) return;
        playerRoot.post(new Runnable() {
            @Override
            public void run() {
                int containerWidth = playerRoot.getWidth();
                int containerHeight = playerRoot.getHeight();
                if (containerWidth <= 0 || containerHeight <= 0) return;

                float videoRatio = (float) width / (float) height;
                float containerRatio = (float) containerWidth / (float) containerHeight;
                int targetWidth;
                int targetHeight;

                if (videoRatio > containerRatio) {
                    targetWidth = containerWidth;
                    targetHeight = Math.max(1, (int) (containerWidth / videoRatio));
                } else {
                    targetHeight = containerHeight;
                    targetWidth = Math.max(1, (int) (containerHeight * videoRatio));
                }

                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        targetWidth, targetHeight, Gravity.CENTER);
                surfaceView.setLayoutParams(params);
            }
        });
    }

    @Override
    public void onCompleted() {
        saveRecord(true);
        nextEpisode();
    }

    @Override
    public void onError(String message) {
        updatePlayPauseButton();

        if (currentSource != null) selector.markFailed(currentSource.source);
        SearchResult fallback = selector.next(
                sources, currentSource == null ? null : currentSource.source, episodeIndex);
        if (fallback != null) {
            currentSource = fallback;
            showStatus("当前线路失败，正在切换到 " + safeSourceName(fallback), true, 1600);
            playCurrent();
            return;
        }

        if (LegacyHttpCompat.isTlsProblem(new RuntimeException(message))) {
            showStatus(LegacyHttpCompat.buildCompatMessage("播放"), true, 0);
        } else {
            showStatus("该视频暂时无法播放", true, 0);
        }
    }

    private void updatePlayPauseButton() {
        if (playPauseButton == null) return;
        playPauseButton.setText(controller != null && controller.isPlaying() ? "暂停" : "播放");
    }

    private String formatTime(int ms) {
        if (ms < 0) ms = 0;
        int totalSeconds = ms / 1000;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
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
            app.api().savePlayRecord(LocalRepository.key(currentSource.source, currentSource.id),
                    record, new ApiCallback<MutationResult>() {
                        @Override public void onSuccess(MutationResult value) {}
                        @Override public void onError(Throwable error) {}
                    });
        }
    }
}
