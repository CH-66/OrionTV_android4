package com.oriontv.legacy.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.oriontv.legacy.api.models.AppSettings;
import com.oriontv.legacy.api.models.ServerConfig;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

public class PreferencesStore {
    private static final String PREFS_NAME = "oriontv_legacy";
    private static final String KEY_SETTINGS = "mytv_settings";
    private static final String KEY_SERVER_CONFIG = "mytv_server_config";
    private static final String KEY_AUTH_COOKIES = "authCookies";
    private static final String KEY_FAVORITES = "mytv_favorites";
    private static final String KEY_PLAY_RECORDS = "mytv_play_records";
    private static final String KEY_PLAYER_SETTINGS = "mytv_player_settings";
    private static final String KEY_SEARCH_HISTORY = "mytv_search_history";
    private static final String KEY_LOGIN_CREDENTIALS = "mytv_login_credentials";
    private static final String KEY_SOURCE_AD_REPUTATION = "mytv_source_ad_reputation";

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public PreferencesStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public AppSettings getSettings() {
        String json = prefs.getString(KEY_SETTINGS, "");
        if (json == null || json.length() == 0) {
            return new AppSettings();
        }
        try {
            AppSettings settings = gson.fromJson(json, AppSettings.class);
            return settings == null ? new AppSettings() : settings;
        } catch (RuntimeException e) {
            return new AppSettings();
        }
    }

    public void saveSettings(AppSettings settings) {
        prefs.edit().putString(KEY_SETTINGS, gson.toJson(settings)).apply();
    }

    public ServerConfig getServerConfig() {
        String json = prefs.getString(KEY_SERVER_CONFIG, "");
        if (json == null || json.length() == 0) {
            return null;
        }
        try {
            return gson.fromJson(json, ServerConfig.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void saveServerConfig(ServerConfig config) {
        prefs.edit().putString(KEY_SERVER_CONFIG, gson.toJson(config)).apply();
    }

    public String getAuthCookies() {
        return prefs.getString(KEY_AUTH_COOKIES, "");
    }

    public void setAuthCookies(String cookies) {
        prefs.edit().putString(KEY_AUTH_COOKIES, cookies == null ? "" : cookies).apply();
    }

    public String getFavoritesJson() {
        return prefs.getString(KEY_FAVORITES, "{}");
    }

    public void setFavoritesJson(String json) {
        prefs.edit().putString(KEY_FAVORITES, json == null ? "{}" : json).apply();
    }

    public String getPlayRecordsJson() {
        return prefs.getString(KEY_PLAY_RECORDS, "{}");
    }

    public void setPlayRecordsJson(String json) {
        prefs.edit().putString(KEY_PLAY_RECORDS, json == null ? "{}" : json).apply();
    }

    public String getPlayerSettingsJson() {
        return prefs.getString(KEY_PLAYER_SETTINGS, "{}");
    }

    public void setPlayerSettingsJson(String json) {
        prefs.edit().putString(KEY_PLAYER_SETTINGS, json == null ? "{}" : json).apply();
    }

    public String getSearchHistoryJson() {
        return prefs.getString(KEY_SEARCH_HISTORY, "[]");
    }

    public void setSearchHistoryJson(String json) {
        prefs.edit().putString(KEY_SEARCH_HISTORY, json == null ? "[]" : json).apply();
    }

    public String getLoginCredentialsJson() {
        return prefs.getString(KEY_LOGIN_CREDENTIALS, "");
    }

    public void setLoginCredentialsJson(String json) {
        prefs.edit().putString(KEY_LOGIN_CREDENTIALS, json == null ? "" : json).apply();
    }

    public int getSourceAdPenalty(String source) {
        if (source == null || source.length() == 0) return 0;
        Map<String, Integer> map = getSourceAdReputation();
        Integer value = map.get(source);
        return value == null ? 0 : Math.max(0, value.intValue());
    }

    public void recordSourceAdDetection(String source, int evidenceWeight) {
        if (source == null || source.length() == 0) return;
        Map<String, Integer> map = getSourceAdReputation();
        Integer current = map.get(source);
        int next = (current == null ? 0 : current.intValue())
                + Math.max(1, Math.min(4, evidenceWeight));
        if (next > 20) next = 20;
        map.put(source, Integer.valueOf(next));
        prefs.edit().putString(KEY_SOURCE_AD_REPUTATION, gson.toJson(map)).apply();
    }

    private Map<String, Integer> getSourceAdReputation() {
        String json = prefs.getString(KEY_SOURCE_AD_REPUTATION, "{}");
        try {
            Type type = new TypeToken<LinkedHashMap<String, Integer>>() {}.getType();
            Map<String, Integer> map = gson.fromJson(json, type);
            return map == null ? new LinkedHashMap<String, Integer>() : map;
        } catch (RuntimeException ignored) {
            return new LinkedHashMap<String, Integer>();
        }
    }

    public void clearLocalData() {
        prefs.edit()
                .remove(KEY_FAVORITES)
                .remove(KEY_PLAY_RECORDS)
                .remove(KEY_PLAYER_SETTINGS)
                .remove(KEY_SEARCH_HISTORY)
                .remove(KEY_SOURCE_AD_REPUTATION)
                .apply();
    }

    public boolean useLocalStorage() {
        ServerConfig config = getServerConfig();
        return config == null || config.StorageType == null || "localstorage".equalsIgnoreCase(config.StorageType);
    }
}
