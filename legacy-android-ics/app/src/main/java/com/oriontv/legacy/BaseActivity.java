package com.oriontv.legacy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.oriontv.legacy.api.ApiException;
import com.oriontv.legacy.api.models.AppSettings;
import com.oriontv.legacy.ui.Ui;

public abstract class BaseActivity extends Activity {
    protected App app;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = App.get();
    }

    protected boolean hasApiUrl() {
        AppSettings settings = app.prefs().getSettings();
        return settings.apiBaseUrl != null && settings.apiBaseUrl.length() > 0;
    }

    protected void requireApiUrl() {
        if (!hasApiUrl()) {
            Ui.toast(this, "请先配置服务器地址");
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    protected void handleError(Throwable error) {
        if (error instanceof ApiException && ((ApiException) error).getStatusCode() == 401) {
            startActivity(new Intent(this, LoginActivity.class));
            return;
        }
        Ui.toast(this, error == null || error.getMessage() == null ? "操作失败" : error.getMessage());
    }
}
