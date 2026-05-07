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
            preferencesStore.setAuthCookies(setCookie);
        }
    }

    public void clear() {
        preferencesStore.setAuthCookies("");
    }
}
