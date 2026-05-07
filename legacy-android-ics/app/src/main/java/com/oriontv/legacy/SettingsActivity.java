package com.oriontv.legacy;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.oriontv.legacy.api.ApiCallback;
import com.oriontv.legacy.api.models.ApiSite;
import com.oriontv.legacy.api.models.AppSettings;
import com.oriontv.legacy.api.models.ServerConfig;
import com.oriontv.legacy.ui.Ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SettingsActivity extends BaseActivity {
    private EditText apiUrl;
    private EditText m3uUrl;
    private CheckBox remoteInput;
    private CheckBox enabledAllSources;
    private LinearLayout sourceList;
    private final Map<String, CheckBox> sourceChecks = new LinkedHashMap<String, CheckBox>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = Ui.vertical(this);
        scroll.addView(root);

        root.addView(Ui.title(this, "设置"));

        apiUrl = Ui.edit(this, "服务器地址，例如 192.168.1.2:3000");
        m3uUrl = Ui.edit(this, "直播 M3U 地址");
        remoteInput = new CheckBox(this);
        remoteInput.setText("启用远程网页输入");
        remoteInput.setTextColor(0xffffffff);
        remoteInput.setFocusable(true);
        enabledAllSources = new CheckBox(this);
        enabledAllSources.setText("启用全部视频源");
        enabledAllSources.setTextColor(0xffffffff);
        enabledAllSources.setFocusable(true);

        AppSettings settings = app.prefs().getSettings();
        apiUrl.setText(settings.apiBaseUrl);
        m3uUrl.setText(settings.m3uUrl);
        remoteInput.setChecked(settings.remoteInputEnabled);
        enabledAllSources.setChecked(settings.videoSource == null || settings.videoSource.enabledAll);

        root.addView(Ui.muted(this, "服务器地址", 14));
        root.addView(apiUrl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));
        root.addView(Ui.spacer(this, 1, 12));
        root.addView(Ui.muted(this, "直播 M3U", 14));
        root.addView(m3uUrl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));
        root.addView(remoteInput);
        root.addView(enabledAllSources);

        LinearLayout row = Ui.row(this);
        Button save = Ui.button(this, "保存并测试");
        Button login = Ui.button(this, "登录");
        Button clear = Ui.button(this, "清理本地数据");
        Button loadSources = Ui.button(this, "加载视频源");
        row.addView(save, new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 48)));
        row.addView(Ui.spacer(this, 12, 1));
        row.addView(login, new LinearLayout.LayoutParams(Ui.dp(this, 100), Ui.dp(this, 48)));
        row.addView(Ui.spacer(this, 12, 1));
        row.addView(clear, new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 48)));
        root.addView(Ui.spacer(this, 1, 16));
        root.addView(row);
        root.addView(Ui.spacer(this, 1, 12));
        root.addView(loadSources, new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 48)));
        sourceList = new LinearLayout(this);
        sourceList.setOrientation(LinearLayout.VERTICAL);
        root.addView(sourceList);

        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
            }
        });
        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new android.content.Intent(SettingsActivity.this, LoginActivity.class));
            }
        });
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.prefs().clearLocalData();
                Ui.toast(SettingsActivity.this, "本地数据已清理");
            }
        });
        loadSources.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettingsOnly();
                loadVideoSources();
            }
        });

        setContentView(scroll);
    }

    private void saveSettings() {
        saveSettingsOnly();
        app.api().getServerConfig(new ApiCallback<ServerConfig>() {
            @Override
            public void onSuccess(ServerConfig value) {
                Ui.toast(SettingsActivity.this, "连接成功：" + (value.SiteName == null ? "" : value.SiteName));
            }

            @Override
            public void onError(Throwable error) {
                handleError(error);
            }
        });
    }

    private void saveSettingsOnly() {
        AppSettings settings = app.prefs().getSettings();
        settings.apiBaseUrl = app.api().normalizeBaseUrl(apiUrl.getText().toString());
        settings.m3uUrl = m3uUrl.getText().toString().trim();
        settings.remoteInputEnabled = remoteInput.isChecked();
        if (settings.videoSource == null) settings.videoSource = new AppSettings.VideoSourceConfig();
        if (settings.videoSource.sources == null) settings.videoSource.sources = new LinkedHashMap<String, Boolean>();
        settings.videoSource.enabledAll = enabledAllSources.isChecked();
        for (Map.Entry<String, CheckBox> entry : sourceChecks.entrySet()) {
            settings.videoSource.sources.put(entry.getKey(), entry.getValue().isChecked());
        }
        app.prefs().saveSettings(settings);
    }

    private void loadVideoSources() {
        sourceList.removeAllViews();
        sourceChecks.clear();
        app.api().getResources(new ApiCallback<List<ApiSite>>() {
            @Override
            public void onSuccess(List<ApiSite> value) {
                AppSettings settings = app.prefs().getSettings();
                if (value == null || value.size() == 0) {
                    sourceList.addView(Ui.muted(SettingsActivity.this, "没有视频源", 14));
                    return;
                }
                for (int i = 0; i < value.size(); i++) {
                    ApiSite site = value.get(i);
                    CheckBox box = new CheckBox(SettingsActivity.this);
                    box.setText(site.name == null ? site.key : site.name);
                    box.setTextColor(0xffffffff);
                    box.setFocusable(true);
                    boolean checked = settings.videoSource == null || settings.videoSource.sources == null
                            || !settings.videoSource.sources.containsKey(site.key)
                            || Boolean.TRUE.equals(settings.videoSource.sources.get(site.key));
                    box.setChecked(checked);
                    sourceChecks.put(site.key, box);
                    sourceList.addView(box);
                }
            }

            @Override
            public void onError(Throwable error) {
                handleError(error);
            }
        });
    }
}
