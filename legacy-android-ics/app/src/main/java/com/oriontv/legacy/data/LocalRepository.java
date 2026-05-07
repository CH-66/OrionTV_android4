package com.oriontv.legacy.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.oriontv.legacy.api.models.Favorite;
import com.oriontv.legacy.api.models.PlayRecord;
import com.oriontv.legacy.api.models.PlayerSettings;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LocalRepository {
    private final PreferencesStore prefs;
    private final Gson gson = new Gson();
    private final Type favoriteMapType = new TypeToken<LinkedHashMap<String, Favorite>>() {}.getType();
    private final Type playRecordMapType = new TypeToken<LinkedHashMap<String, PlayRecord>>() {}.getType();
    private final Type playerSettingsMapType = new TypeToken<LinkedHashMap<String, PlayerSettings>>() {}.getType();
    private final Type stringListType = new TypeToken<ArrayList<String>>() {}.getType();

    public LocalRepository(PreferencesStore prefs) {
        this.prefs = prefs;
    }

    public static String key(String source, String id) {
        return source + "+" + id;
    }

    public Map<String, Favorite> getFavorites() {
        try {
            Map<String, Favorite> map = gson.fromJson(prefs.getFavoritesJson(), favoriteMapType);
            return map == null ? new LinkedHashMap<String, Favorite>() : map;
        } catch (RuntimeException e) {
            return new LinkedHashMap<String, Favorite>();
        }
    }

    public void saveFavorite(String source, String id, Favorite favorite) {
        Map<String, Favorite> map = getFavorites();
        favorite.save_time = System.currentTimeMillis();
        map.put(key(source, id), favorite);
        prefs.setFavoritesJson(gson.toJson(map));
    }

    public void removeFavorite(String source, String id) {
        Map<String, Favorite> map = getFavorites();
        map.remove(key(source, id));
        prefs.setFavoritesJson(gson.toJson(map));
    }

    public boolean isFavorited(String source, String id) {
        return getFavorites().containsKey(key(source, id));
    }

    public Map<String, PlayRecord> getPlayRecords() {
        try {
            Map<String, PlayRecord> map = gson.fromJson(prefs.getPlayRecordsJson(), playRecordMapType);
            return map == null ? new LinkedHashMap<String, PlayRecord>() : map;
        } catch (RuntimeException e) {
            return new LinkedHashMap<String, PlayRecord>();
        }
    }

    public PlayRecord getPlayRecord(String source, String id) {
        return getPlayRecords().get(key(source, id));
    }

    public void savePlayRecord(String source, String id, PlayRecord record) {
        Map<String, PlayRecord> records = getPlayRecords();
        record.save_time = System.currentTimeMillis();
        records.put(key(source, id), record);
        prefs.setPlayRecordsJson(gson.toJson(records));
    }

    public void removePlayRecord(String source, String id) {
        Map<String, PlayRecord> records = getPlayRecords();
        records.remove(key(source, id));
        prefs.setPlayRecordsJson(gson.toJson(records));
    }

    public Map<String, PlayerSettings> getPlayerSettings() {
        try {
            Map<String, PlayerSettings> map = gson.fromJson(prefs.getPlayerSettingsJson(), playerSettingsMapType);
            return map == null ? new LinkedHashMap<String, PlayerSettings>() : map;
        } catch (RuntimeException e) {
            return new LinkedHashMap<String, PlayerSettings>();
        }
    }

    public PlayerSettings getPlayerSettings(String source, String id) {
        return getPlayerSettings().get(key(source, id));
    }

    public void savePlayerSettings(String source, String id, PlayerSettings settings) {
        Map<String, PlayerSettings> map = getPlayerSettings();
        map.put(key(source, id), settings);
        prefs.setPlayerSettingsJson(gson.toJson(map));
    }

    public List<String> getSearchHistory() {
        try {
            List<String> list = gson.fromJson(prefs.getSearchHistoryJson(), stringListType);
            return list == null ? new ArrayList<String>() : list;
        } catch (RuntimeException e) {
            return new ArrayList<String>();
        }
    }

    public void addSearchHistory(String keyword) {
        if (keyword == null) return;
        String trimmed = keyword.trim();
        if (trimmed.length() == 0) return;

        List<String> list = getSearchHistory();
        ArrayList<String> next = new ArrayList<String>();
        next.add(trimmed);
        for (int i = 0; i < list.size() && next.size() < 20; i++) {
            String item = list.get(i);
            if (!trimmed.equals(item)) {
                next.add(item);
            }
        }
        prefs.setSearchHistoryJson(gson.toJson(next));
    }

    public void clearSearchHistory() {
        prefs.setSearchHistoryJson("[]");
    }
}
