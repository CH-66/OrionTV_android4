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
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.oriontv.legacy.api.ApiCallback;
import com.oriontv.legacy.api.models.MutationResult;
import com.oriontv.legacy.api.models.PlayRecord;
import com.oriontv.legacy.api.models.PlayerSettings;
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
    private static final int DEFAULT_SEEK_STEP_MS = 10000;
    private static final int DEFAULT_CONTROLS_HIDE_MS = 5000;

    private final Gson gson = new Gson();
    private final PlaybackSourceSelector selector = new PlaybackSourceSelector();
    private final android.os.Handler handler = new android.os.Handler();

    private ArrayList<SearchResult> sources = new ArrayList<SearchResult>();
    private SearchResult currentSource;
    private int episodeIndex;
    private LegacyPlayerController controller;
    private long lastSave;
    private PlayerSettings playerSettings;
    private int seekStepMs = DEFAULT_SEEK_STEP_MS;
    private int controlsHideMs = DEFAULT_CONTROLS_HIDE_MS;
    private String aspectMode = "fit";
    private int videoWidth;
    private int videoHeight;
    private int pendingResumePositionMs;
    private boolean resumeApplied;
    private boolean seekPreviewActive;
    private int seekPreviewBaseMs;
    private int seekPreviewPositionMs;

    private FrameLayout playerRoot;
    private SurfaceView surfaceView;
    private LinearLayout topBar;
    private LinearLayout bottomPanel;
    private TextView titleView;
    private TextView metaView;
    private TextView statusView;
    private TextView currentTimeView;
    private TextView durationView;
    private SeekBar progress;
    private FrameLayout playbackStateOverlay;
    private ProgressBar bufferingSpinner;
    private TextView playbackStateText;
    private FrameLayout nextEpisodeOverlay;
    private TextView nextEpisodeText;
    private boolean nextEpisodePromptVisible;
    private int nextEpisodeCountdown;
    private Button playPauseButton;
    private Button rewindButton;
    private Button forwardButton;
    private Button episodeButton;
    private Button sourceButton;
    private Button settingsButton;
    private FrameLayout drawerOverlay;
    private LinearLayout drawerPanel;
    private TextView drawerTitle;
    private LinearLayout drawerContent;
    private View drawerReturnFocus;
    private FrameLayout resumeOverlay;
    private TextView resumeText;
    private boolean resumePromptVisible;
    private boolean drawerVisible;
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

    private final Runnable commitSeekPreview = new Runnable() {
        @Override
        public void run() {
            if (!seekPreviewActive) return;
            int target = seekPreviewPositionMs;
            seekPreviewActive = false;
            if (controller != null && controller.seekTo(target)) {
                showStatus("已跳转至 " + formatTime(target), false, 900);
            }
        }
    };

    private final Runnable nextEpisodeTick = new Runnable() {
        @Override
        public void run() {
            if (!nextEpisodePromptVisible) return;
            if (nextEpisodeCountdown <= 0) {
                hideNextEpisodePrompt();
                nextEpisode();
                return;
            }
            if (nextEpisodeText != null) {
                nextEpisodeText.setText("即将播放第 " + (episodeIndex + 2)
                        + " 集  ·  " + nextEpisodeCountdown + " 秒后自动播放");
            }
            nextEpisodeCountdown--;
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        parseIntent();
        loadPlayerSettings();
        buildUi();
        if (!showResumePromptIfNeeded()) {
            playCurrent();
        }
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
        buildPlaybackStateOverlay();
        buildBottomPanel();
        buildDrawer();
        buildResumeOverlay();
        buildNextEpisodeOverlay();

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

    private void buildPlaybackStateOverlay() {
        playbackStateOverlay = new FrameLayout(this);
        playbackStateOverlay.setVisibility(View.GONE);
        playbackStateOverlay.setFocusable(false);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(22), dp(14), dp(22), dp(14));
        card.setBackgroundDrawable(roundedBackground(0xe61a1e24, 14, 1, 0x334e5966));

        bufferingSpinner = new ProgressBar(this);
        bufferingSpinner.setIndeterminate(true);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(30), dp(30));
        spinnerParams.setMargins(0, 0, dp(14), 0);
        card.addView(bufferingSpinner, spinnerParams);

        playbackStateText = new TextView(this);
        playbackStateText.setTextColor(Color.WHITE);
        playbackStateText.setTextSize(18);
        playbackStateText.setSingleLine(true);
        card.addView(playbackStateText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));

        playbackStateOverlay.addView(card, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        playerRoot.addView(playbackStateOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void showPlaybackState(String text, boolean loading) {
        if (playbackStateOverlay == null) return;
        playbackStateText.setText(text);
        bufferingSpinner.setVisibility(loading ? View.VISIBLE : View.GONE);
        playbackStateOverlay.setVisibility(View.VISIBLE);
        playbackStateOverlay.bringToFront();
        if (drawerVisible) drawerOverlay.bringToFront();
        if (resumePromptVisible) resumeOverlay.bringToFront();
        if (nextEpisodePromptVisible) nextEpisodeOverlay.bringToFront();
    }

    private void hidePlaybackState() {
        if (playbackStateOverlay != null) playbackStateOverlay.setVisibility(View.GONE);
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

        progress = new SeekBar(this);
        progress.setMax(1000);
        progress.setProgress(0);
        progress.setFocusable(true);
        progress.setFocusableInTouchMode(true);
        progress.setKeyProgressIncrement(20);
        progress.setPadding(dp(4), 0, dp(4), 0);
        progress.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                progress.setScaleY(hasFocus ? 1.25f : 1.0f);
                if (hasFocus) scheduleHideControls();
            }
        });
        progress.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    previewSeekBy(-seekStepMs);
                    scheduleHideControls();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    previewSeekBy(seekStepMs);
                    scheduleHideControls();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (seekPreviewActive) {
                        handler.removeCallbacks(commitSeekPreview);
                        commitSeekPreview.run();
                    }
                    return true;
                }
                return false;
            }
        });
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (!fromUser || controller == null || !controller.isPrepared()) return;
                int durationMs = controller.duration();
                if (durationMs <= 0) return;
                seekPreviewBaseMs = controller.position();
                seekPreviewPositionMs = (int) ((value * (long) durationMs) / 1000L);
                seekPreviewActive = true;
                currentTimeView.setText(formatTime(seekPreviewPositionMs));
                showStatus("定位到 " + formatTime(seekPreviewPositionMs)
                        + " / " + formatTime(durationMs), false, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (seekPreviewActive) {
                    handler.removeCallbacks(commitSeekPreview);
                    commitSeekPreview.run();
                }
            }
        });

        progressRow.addView(currentTimeView, new LinearLayout.LayoutParams(dp(72), dp(30)));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(0, dp(30), 1);
        progressParams.setMargins(dp(12), 0, dp(12), 0);
        progressRow.addView(progress, progressParams);
        progressRow.addView(durationView, new LinearLayout.LayoutParams(dp(72), dp(30)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(12), 0, 0);

        Button previous = playerButton("上一集", false);
        rewindButton = playerButton(seekButtonLabel(false), false);
        playPauseButton = playerButton("播放", true);
        forwardButton = playerButton(seekButtonLabel(true), false);
        Button next = playerButton("下一集", false);
        episodeButton = playerButton("选集", false);
        sourceButton = playerButton("线路", false);
        settingsButton = playerButton("设置", false);

        buttons.addView(previous);
        buttons.addView(rewindButton);
        buttons.addView(playPauseButton);
        buttons.addView(forwardButton);
        buttons.addView(next);
        buttons.addView(episodeButton);
        buttons.addView(sourceButton);
        buttons.addView(settingsButton);

        bottomPanel.addView(progressRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        bottomPanel.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(138),
                Gravity.BOTTOM);
        bottomParams.setMargins(dp(18), 0, dp(18), dp(16));
        playerRoot.addView(bottomPanel, bottomParams);

        previous.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { previousEpisode(); }
        });
        rewindButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { seekBy(-seekStepMs, "快退 " + (seekStepMs / 1000) + " 秒"); }
        });
        playPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { playPause(); }
        });
        forwardButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { seekBy(seekStepMs, "快进 " + (seekStepMs / 1000) + " 秒"); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { nextEpisode(); }
        });
        episodeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openEpisodeDrawer(); }
        });
        sourceButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSourceDrawer(); }
        });
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettingsDrawer(); }
        });
    }

    private void loadPlayerSettings() {
        if (currentSource == null) {
            playerSettings = new PlayerSettings();
            return;
        }
        playerSettings = app.local().getPlayerSettings(currentSource.source, currentSource.id);
        if (playerSettings == null) playerSettings = new PlayerSettings();

        if (playerSettings.seekStepSeconds != null
                && (playerSettings.seekStepSeconds == 10
                || playerSettings.seekStepSeconds == 30
                || playerSettings.seekStepSeconds == 60)) {
            seekStepMs = playerSettings.seekStepSeconds * 1000;
        }
        if (playerSettings.controlsHideSeconds != null
                && (playerSettings.controlsHideSeconds == 0
                || playerSettings.controlsHideSeconds == 3
                || playerSettings.controlsHideSeconds == 5
                || playerSettings.controlsHideSeconds == 8)) {
            controlsHideMs = playerSettings.controlsHideSeconds * 1000;
        }
        if ("fit".equals(playerSettings.aspectMode)
                || "crop".equals(playerSettings.aspectMode)
                || "stretch".equals(playerSettings.aspectMode)) {
            aspectMode = playerSettings.aspectMode;
        }
    }

    private void saveUiPlayerSettings() {
        if (currentSource == null) return;
        if (playerSettings == null) playerSettings = new PlayerSettings();
        playerSettings.seekStepSeconds = seekStepMs / 1000;
        playerSettings.controlsHideSeconds = controlsHideMs / 1000;
        playerSettings.aspectMode = aspectMode;
        app.local().savePlayerSettings(currentSource.source, currentSource.id, playerSettings);
    }

    private String seekButtonLabel(boolean forward) {
        return (forward ? "快进 " : "快退 ") + (seekStepMs / 1000) + "秒";
    }

    private void updateSeekButtonLabels() {
        if (rewindButton != null) rewindButton.setText(seekButtonLabel(false));
        if (forwardButton != null) forwardButton.setText(seekButtonLabel(true));
    }

    private void buildResumeOverlay() {
        resumeOverlay = new FrameLayout(this);
        resumeOverlay.setBackgroundColor(0xb3000000);
        resumeOverlay.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(34), dp(28), dp(34), dp(28));
        card.setBackgroundDrawable(roundedBackground(0xf21a1e24, 18, 1, 0x445f6a78));

        TextView heading = new TextView(this);
        heading.setText("继续观看？");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(25);
        heading.setGravity(Gravity.CENTER);
        card.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        resumeText = new TextView(this);
        resumeText.setTextColor(0xffc1c8d2);
        resumeText.setTextSize(16);
        resumeText.setGravity(Gravity.CENTER);
        resumeText.setPadding(0, dp(6), 0, dp(18));
        card.addView(resumeText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        final Button continueButton = playerButton("继续播放", true);
        final Button restartButton = playerButton("从头播放", false);
        actions.addView(continueButton);
        actions.addView(restartButton);
        card.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        continueButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hideResumePrompt();
                playCurrent();
            }
        });
        restartButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pendingResumePositionMs = 0;
                resumeApplied = true;
                hideResumePrompt();
                playCurrent();
            }
        });

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                dp(500), dp(220), Gravity.CENTER);
        resumeOverlay.addView(card, cardParams);
        playerRoot.addView(resumeOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private boolean showResumePromptIfNeeded() {
        if (currentSource == null) return false;
        PlayRecord record = app.local().getPlayRecord(currentSource.source, currentSource.id);
        if (record == null || record.play_time < 30 || record.index != episodeIndex + 1) return false;
        if (record.total_time > 0 && record.play_time >= record.total_time - 30) return false;

        pendingResumePositionMs = record.play_time * 1000;
        resumeApplied = false;
        resumePromptVisible = true;
        controlsVisible = false;
        topBar.setVisibility(View.GONE);
        bottomPanel.setVisibility(View.GONE);
        resumeText.setText("上次播放到 " + formatTime(pendingResumePositionMs)
                + "  ·  第 " + record.index + " 集");
        resumeOverlay.setVisibility(View.VISIBLE);
        resumeOverlay.bringToFront();

        resumeOverlay.post(new Runnable() {
            @Override public void run() {
                View card = resumeOverlay.getChildAt(0);
                if (card instanceof ViewGroup) {
                    View actions = ((ViewGroup) card).getChildAt(2);
                    if (actions instanceof ViewGroup && ((ViewGroup) actions).getChildCount() > 0) {
                        ((ViewGroup) actions).getChildAt(0).requestFocus();
                    }
                }
            }
        });
        return true;
    }

    private void hideResumePrompt() {
        resumePromptVisible = false;
        resumeOverlay.setVisibility(View.GONE);
    }

    private void buildNextEpisodeOverlay() {
        nextEpisodeOverlay = new FrameLayout(this);
        nextEpisodeOverlay.setBackgroundColor(0x88000000);
        nextEpisodeOverlay.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(30), dp(24), dp(30), dp(24));
        card.setBackgroundDrawable(roundedBackground(0xf21a1e24, 18, 1, 0x445f6a78));

        TextView heading = new TextView(this);
        heading.setText("本集播放结束");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(24);
        heading.setGravity(Gravity.CENTER);
        card.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        nextEpisodeText = new TextView(this);
        nextEpisodeText.setTextColor(0xffc1c8d2);
        nextEpisodeText.setTextSize(16);
        nextEpisodeText.setGravity(Gravity.CENTER);
        card.addView(nextEpisodeText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        final Button playNow = playerButton("立即下一集", true);
        final Button cancel = playerButton("取消", false);
        actions.addView(playNow);
        actions.addView(cancel);
        card.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        playNow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hideNextEpisodePrompt();
                nextEpisode();
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hideNextEpisodePrompt();
                showControls();
                playPauseButton.requestFocus();
                showStatus("已停止自动连播", true, 1800);
            }
        });

        nextEpisodeOverlay.addView(card, new FrameLayout.LayoutParams(
                dp(500), dp(210), Gravity.CENTER));
        playerRoot.addView(nextEpisodeOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void showNextEpisodePrompt() {
        if (currentSource == null || currentSource.episodes == null
                || episodeIndex >= currentSource.episodes.size() - 1) {
            showPlaybackState("播放完毕", false);
            showControls();
            return;
        }

        nextEpisodePromptVisible = true;
        nextEpisodeCountdown = 5;
        handler.removeCallbacks(nextEpisodeTick);
        handler.removeCallbacks(hideChrome);
        handler.removeCallbacks(hideStatus);
        hidePlaybackState();
        statusView.setVisibility(View.GONE);
        topBar.setVisibility(View.GONE);
        bottomPanel.setVisibility(View.GONE);
        controlsVisible = false;
        nextEpisodeOverlay.setVisibility(View.VISIBLE);
        nextEpisodeOverlay.bringToFront();
        nextEpisodeTick.run();

        nextEpisodeOverlay.post(new Runnable() {
            @Override public void run() {
                View card = nextEpisodeOverlay.getChildAt(0);
                if (card instanceof ViewGroup) {
                    View actions = ((ViewGroup) card).getChildAt(2);
                    if (actions instanceof ViewGroup && ((ViewGroup) actions).getChildCount() > 0) {
                        ((ViewGroup) actions).getChildAt(0).requestFocus();
                    }
                }
            }
        });
    }

    private void hideNextEpisodePrompt() {
        nextEpisodePromptVisible = false;
        handler.removeCallbacks(nextEpisodeTick);
        if (nextEpisodeOverlay != null) nextEpisodeOverlay.setVisibility(View.GONE);
    }

    private void buildDrawer() {
        drawerOverlay = new FrameLayout(this);
        drawerOverlay.setBackgroundColor(0x66000000);
        drawerOverlay.setVisibility(View.GONE);
        drawerOverlay.setFocusable(false);

        drawerPanel = new LinearLayout(this);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setPadding(dp(24), dp(24), dp(20), dp(22));
        drawerPanel.setBackgroundDrawable(roundedBackground(0xf51a1e24, 18, 1, 0x335b6572));

        drawerTitle = new TextView(this);
        drawerTitle.setTextColor(Color.WHITE);
        drawerTitle.setTextSize(23);
        drawerTitle.setSingleLine(true);
        drawerTitle.setPadding(dp(4), 0, 0, dp(14));
        drawerPanel.addView(drawerTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        TextView hint = new TextView(this);
        hint.setText("方向键选择  ·  确定进入  ·  返回关闭");
        hint.setTextColor(0xff89929e);
        hint.setTextSize(13);
        hint.setSingleLine(true);
        hint.setPadding(dp(4), 0, 0, dp(12));
        drawerPanel.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFocusable(false);
        drawerContent = new LinearLayout(this);
        drawerContent.setOrientation(LinearLayout.VERTICAL);
        drawerContent.setPadding(0, dp(2), 0, dp(12));
        scroll.addView(drawerContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        drawerPanel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                dp(430), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.RIGHT);
        panelParams.setMargins(0, dp(10), dp(10), dp(10));
        drawerOverlay.addView(drawerPanel, panelParams);

        playerRoot.addView(drawerOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void openEpisodeDrawer() {
        if (currentSource == null || currentSource.episodes == null
                || currentSource.episodes.size() == 0) {
            showStatus("当前没有可选剧集", true, 1800);
            return;
        }

        drawerReturnFocus = episodeButton;
        drawerTitle.setText("选集  ·  第 " + (episodeIndex + 1) + " 集");
        drawerContent.removeAllViews();

        final int total = currentSource.episodes.size();
        final int columns = 5;
        LinearLayout row = null;
        Button currentButton = null;

        for (int i = 0; i < total; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.LEFT);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
                rowParams.setMargins(0, 0, 0, dp(6));
                drawerContent.addView(row, rowParams);
            }

            final int index = i;
            final boolean selected = index == episodeIndex;
            Button button = drawerItemButton(String.valueOf(index + 1), selected);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(0, dp(50), 1);
            itemParams.setMargins(dp(3), dp(2), dp(3), dp(2));
            row.addView(button, itemParams);

            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (episodeIndex != index) {
                        saveRecord(true);
                        pendingResumePositionMs = 0;
                        resumeApplied = true;
                        episodeIndex = index;
                        closeDrawer(false);
                        playCurrent();
                    } else {
                        closeDrawer(true);
                    }
                }
            });
            if (selected) currentButton = button;
        }

        if (row != null) {
            int remainder = total % columns;
            if (remainder != 0) {
                for (int i = remainder; i < columns; i++) {
                    View spacer = new View(this);
                    row.addView(spacer, new LinearLayout.LayoutParams(0, dp(50), 1));
                }
            }
        }

        showDrawer(currentButton);
    }

    private void openSourceDrawer() {
        if (sources == null || sources.size() == 0) {
            showStatus("当前没有可用线路", true, 1800);
            return;
        }

        drawerReturnFocus = sourceButton;
        drawerTitle.setText("播放线路  ·  " + safeSourceName(currentSource));
        drawerContent.removeAllViews();
        Button currentButton = null;

        for (int i = 0; i < sources.size(); i++) {
            final SearchResult item = sources.get(i);
            final boolean playable = item != null && item.episodes != null
                    && item.episodes.size() > episodeIndex;
            final boolean selected = item == currentSource;

            String label = safeSourceName(item);
            if (item != null && item.resolution != null && item.resolution.length() > 0) {
                label += "   ·   " + item.resolution;
            }
            if (!playable) label += "   ·   当前集不可用";

            Button button = drawerWideButton(label, selected, playable);
            drawerContent.addView(button, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

            if (playable) {
                button.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (currentSource != item) {
                            saveRecord(true);
                            currentSource = item;
                            closeDrawer(false);
                            showStatus("正在切换到 " + safeSourceName(item), true, 900);
                            playCurrent();
                        } else {
                            closeDrawer(true);
                        }
                    }
                });
            }
            if (selected) currentButton = button;
        }

        showDrawer(currentButton);
    }

    private void openSettingsDrawer() {
        drawerReturnFocus = settingsButton;
        drawerTitle.setText("播放设置");
        drawerContent.removeAllViews();

        final Button seekButton = drawerWideButton(
                "快进步长   " + (seekStepMs / 1000) + " 秒", false, true);
        final Button hideButton = drawerWideButton(
                "控制栏隐藏   " + controlsHideLabel(), false, true);
        final Button aspectButton = drawerWideButton(
                "画面比例   " + aspectModeLabel(), false, true);

        drawerContent.addView(seekButton);
        drawerContent.addView(hideButton);
        drawerContent.addView(aspectButton);

        seekButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (seekStepMs == 10000) seekStepMs = 30000;
                else if (seekStepMs == 30000) seekStepMs = 60000;
                else seekStepMs = 10000;
                seekButton.setText("快进步长   " + (seekStepMs / 1000) + " 秒");
                updateSeekButtonLabels();
                saveUiPlayerSettings();
            }
        });

        hideButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (controlsHideMs == 3000) controlsHideMs = 5000;
                else if (controlsHideMs == 5000) controlsHideMs = 8000;
                else if (controlsHideMs == 8000) controlsHideMs = 0;
                else controlsHideMs = 3000;
                hideButton.setText("控制栏隐藏   " + controlsHideLabel());
                saveUiPlayerSettings();
            }
        });

        aspectButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if ("fit".equals(aspectMode)) aspectMode = "crop";
                else if ("crop".equals(aspectMode)) aspectMode = "stretch";
                else aspectMode = "fit";
                aspectButton.setText("画面比例   " + aspectModeLabel());
                saveUiPlayerSettings();
                applyVideoLayout();
            }
        });

        showDrawer(seekButton);
    }

    private String controlsHideLabel() {
        if (controlsHideMs <= 0) return "不自动隐藏";
        return (controlsHideMs / 1000) + " 秒";
    }

    private String aspectModeLabel() {
        if ("crop".equals(aspectMode)) return "裁切填满";
        if ("stretch".equals(aspectMode)) return "拉伸填满";
        return "适应屏幕";
    }

    private void showDrawer(final Button preferredFocus) {
        drawerVisible = true;
        handler.removeCallbacks(hideChrome);
        handler.removeCallbacks(hideStatus);
        statusView.setVisibility(View.GONE);
        topBar.setVisibility(View.GONE);
        bottomPanel.setVisibility(View.GONE);
        controlsVisible = false;
        drawerOverlay.setVisibility(View.VISIBLE);
        drawerOverlay.bringToFront();

        drawerOverlay.post(new Runnable() {
            @Override public void run() {
                if (preferredFocus != null && preferredFocus.isEnabled()) {
                    preferredFocus.requestFocus();
                } else if (drawerContent.getChildCount() > 0) {
                    View first = drawerContent.getChildAt(0);
                    if (first instanceof ViewGroup && ((ViewGroup) first).getChildCount() > 0) {
                        ((ViewGroup) first).getChildAt(0).requestFocus();
                    } else {
                        first.requestFocus();
                    }
                }
            }
        });
    }

    private void closeDrawer(boolean restoreControls) {
        drawerVisible = false;
        drawerOverlay.setVisibility(View.GONE);
        if (restoreControls) {
            showControls();
            if (drawerReturnFocus != null) drawerReturnFocus.requestFocus();
        } else {
            controlsVisible = false;
            topBar.setVisibility(View.GONE);
            bottomPanel.setVisibility(View.GONE);
        }
    }

    private Button drawerItemButton(final String text, final boolean selected) {
        final Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setSingleLine(true);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setBackgroundDrawable(drawerBackground(false, selected));
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                button.setBackgroundDrawable(drawerBackground(hasFocus, selected));
                button.setScaleX(hasFocus ? 1.05f : 1.0f);
                button.setScaleY(hasFocus ? 1.05f : 1.0f);
            }
        });
        return button;
    }

    private Button drawerWideButton(final String text, final boolean selected, boolean enabled) {
        final Button button = drawerItemButton(text, selected);
        button.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(18), 0, dp(12), 0);
        button.setEnabled(enabled);
        button.setFocusable(enabled);
        if (!enabled) {
            button.setTextColor(0xff69727d);
            button.setBackgroundDrawable(roundedBackground(0x66272c33, 9, 1, 0x223b424b));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        params.setMargins(0, 0, 0, dp(7));
        button.setLayoutParams(params);
        return button;
    }

    private GradientDrawable drawerBackground(boolean focused, boolean selected) {
        if (focused) {
            return roundedBackground(0xff2f80ed, 9, 2, 0xffffffff);
        }
        if (selected) {
            return roundedBackground(0xff253b58, 9, 1, 0xff4d8fe8);
        }
        return roundedBackground(0xcc252a31, 9, 1, 0x334f5966);
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
        hideNextEpisodePrompt();
        showPlaybackState("正在连接播放线路…", true);

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
        if (episodeButton != null) episodeButton.setText("选集");
        if (sourceButton != null) sourceButton.setText(sources.size() > 1 ? "线路" : "当前线路");
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
        if (drawerVisible) return;
        if (controller != null && !controller.isPlaying()) return;
        controlsVisible = false;
        topBar.setVisibility(View.GONE);
        bottomPanel.setVisibility(View.GONE);
    }

    private void scheduleHideControls() {
        handler.removeCallbacks(hideChrome);
        if (controlsHideMs > 0) handler.postDelayed(hideChrome, controlsHideMs);
    }

    private void showStatus(String text, boolean showChrome, long autoHideMs) {
        statusView.setText(text);
        statusView.setVisibility(View.VISIBLE);
        statusView.bringToFront();
        if (showChrome) showControls();
        handler.removeCallbacks(hideStatus);
        if (autoHideMs > 0) handler.postDelayed(hideStatus, autoHideMs);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();

        if (key == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (nextEpisodePromptVisible) {
                hideNextEpisodePrompt();
                showControls();
                if (playPauseButton != null) playPauseButton.requestFocus();
            } else if (resumePromptVisible) {
                finish();
            } else if (drawerVisible) {
                closeDrawer(true);
            } else if (controlsVisible) {
                hideControls();
            } else {
                finish();
            }
            return true;
        }

        if ((drawerVisible || resumePromptVisible || nextEpisodePromptVisible)
                && isRemoteNavigationKey(key)) {
            return super.dispatchKeyEvent(event);
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
                    previewSeekBy(-seekStepMs);
                } else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    previewSeekBy(seekStepMs);
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
            previewSeekBy(-seekStepMs);
        } else if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            previewSeekBy(seekStepMs);
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
                hidePlaybackState();
                showStatus("继续播放", true, 1200);
            } else {
                showPlaybackState("已暂停", false);
                showControls();
            }
        } else {
            showStatus("播放器准备中…", true, 1800);
        }
    }

    private void previewSeekBy(int deltaMs) {
        if (controller == null || !controller.isPrepared()) {
            showStatus("播放器准备中…", true, 1800);
            return;
        }

        if (!seekPreviewActive) {
            seekPreviewBaseMs = controller.position();
            seekPreviewPositionMs = seekPreviewBaseMs;
            seekPreviewActive = true;
        }

        seekPreviewPositionMs += deltaMs;
        int durationMs = controller.duration();
        if (seekPreviewPositionMs < 0) seekPreviewPositionMs = 0;
        if (durationMs > 0 && seekPreviewPositionMs > durationMs) {
            seekPreviewPositionMs = durationMs;
        }

        int changed = seekPreviewPositionMs - seekPreviewBaseMs;
        String direction = changed >= 0 ? "快进 " : "快退 ";
        String amount = formatTime(Math.abs(changed));
        String target = formatTime(seekPreviewPositionMs);
        String total = durationMs > 0 ? formatTime(durationMs) : "--:--";
        showStatus(direction + amount + "   " + target + " / " + total, false, 0);

        if (durationMs > 0) {
            progress.setProgress((int) ((seekPreviewPositionMs * 1000L) / durationMs));
        }
        currentTimeView.setText(target);

        handler.removeCallbacks(commitSeekPreview);
        handler.postDelayed(commitSeekPreview, 650);
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
            pendingResumePositionMs = 0;
            resumeApplied = true;
            episodeIndex++;
            playCurrent();
        } else {
            showStatus("已经是最后一集", true, 1800);
        }
    }

    private void previousEpisode() {
        if (episodeIndex > 0) {
            saveRecord(true);
            pendingResumePositionMs = 0;
            resumeApplied = true;
            episodeIndex--;
            playCurrent();
        } else {
            showStatus("已经是第一集", true, 1800);
        }
    }

    private String safeSourceName(SearchResult item) {
        return item == null || item.source_name == null || item.source_name.length() == 0
                ? "其他线路" : item.source_name;
    }

    @Override
    public void onPrepared(int durationMs) {
        hidePlaybackState();
        updatePlayPauseButton();
        durationView.setText(formatTime(durationMs));
        if (pendingResumePositionMs > 0 && !resumeApplied) {
            int target = pendingResumePositionMs;
            pendingResumePositionMs = 0;
            resumeApplied = true;
            if (controller != null && controller.seekTo(target)) {
                showStatus("已续播至 " + formatTime(target), true, 1500);
            } else {
                showStatus("开始播放", true, 1200);
            }
        } else {
            showStatus("开始播放", true, 1200);
        }
    }

    @Override
    public void onProgress(int positionMs, int durationMs) {
        if (!seekPreviewActive) {
            currentTimeView.setText(formatTime(positionMs));
            durationView.setText(durationMs > 0 ? formatTime(durationMs) : "--:--");
            if (durationMs > 0) {
                progress.setProgress((int) ((positionMs * 1000L) / durationMs));
            }
        }

        if (System.currentTimeMillis() - lastSave > 10000) saveRecord(false);
    }

    @Override
    public void onVideoSizeChanged(final int width, final int height) {
        if (width <= 0 || height <= 0) return;
        videoWidth = width;
        videoHeight = height;
        applyVideoLayout();
    }

    private void applyVideoLayout() {
        if (surfaceView == null || playerRoot == null || videoWidth <= 0 || videoHeight <= 0) return;
        playerRoot.post(new Runnable() {
            @Override public void run() {
                int containerWidth = playerRoot.getWidth();
                int containerHeight = playerRoot.getHeight();
                if (containerWidth <= 0 || containerHeight <= 0) return;

                int targetWidth = containerWidth;
                int targetHeight = containerHeight;

                if (!"stretch".equals(aspectMode)) {
                    float videoRatio = (float) videoWidth / (float) videoHeight;
                    float containerRatio = (float) containerWidth / (float) containerHeight;

                    if ("crop".equals(aspectMode)) {
                        if (videoRatio > containerRatio) {
                            targetHeight = containerHeight;
                            targetWidth = Math.max(1, (int) (containerHeight * videoRatio));
                        } else {
                            targetWidth = containerWidth;
                            targetHeight = Math.max(1, (int) (containerWidth / videoRatio));
                        }
                    } else {
                        if (videoRatio > containerRatio) {
                            targetWidth = containerWidth;
                            targetHeight = Math.max(1, (int) (containerWidth / videoRatio));
                        } else {
                            targetHeight = containerHeight;
                            targetWidth = Math.max(1, (int) (containerHeight * videoRatio));
                        }
                    }
                }

                surfaceView.setLayoutParams(new FrameLayout.LayoutParams(
                        targetWidth, targetHeight, Gravity.CENTER));
            }
        });
    }

    @Override
    public void onBuffering(boolean buffering) {
        if (buffering) {
            showPlaybackState("正在缓冲…", true);
        } else if (controller != null && controller.isPlaying()) {
            hidePlaybackState();
        }
    }

    @Override
    public void onCompleted() {
        saveRecord(true);
        updatePlayPauseButton();
        showNextEpisodePrompt();
    }

    @Override
    public void onError(String message) {
        hidePlaybackState();
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
