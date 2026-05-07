package com.oriontv.legacy.api;

import com.oriontv.legacy.data.PreferencesStore;

public class CookieStore {
    private final PreferencesStore preferencesStore;

    public CookieStore(PreferencesStore preferencesStore) {
        this.preferencesStore = preferencesStore;
    }

    public String getCookieHeader() {
        return preferencesStore.getAuthCookies();
    }

    public void saveFromHeader(String setCookie) {
        if (setCookie != null && setCookie.length() > 0) {
            preferencesStore.setAuthCookies(toCookieHeader(setCookie));
        }
    }

    public void clear() {
        preferencesStore.setAuthCookies("");
    }

    private String toCookieHeader(String setCookie) {
        String value = setCookie.trim();
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon).trim();
        }
        return value;
    }
}
