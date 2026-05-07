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
            String merged = mergeCookies(preferencesStore.getAuthCookies(), toCookieHeader(setCookie));
            preferencesStore.setAuthCookies(merged);
        }
    }

    public void clear() {
        preferencesStore.setAuthCookies("");
    }

    private String toCookieHeader(String setCookie) {
        String value = setCookie.trim();
        int comma = value.indexOf(',');
        if (comma >= 0 && value.indexOf(';') > comma) {
            value = value.substring(0, comma).trim();
        }
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon).trim();
        }
        return value;
    }

    private String mergeCookies(String existing, String next) {
        if (next == null || next.length() == 0) return existing == null ? "" : existing;
        if (existing == null || existing.length() == 0) return next;
        String[] parts = existing.split(";\\s*");
        String nextName = next;
        int equals = next.indexOf('=');
        if (equals > 0) {
            nextName = next.substring(0, equals);
        }
        StringBuilder builder = new StringBuilder();
        boolean replaced = false;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i] == null ? "" : parts[i].trim();
            if (part.length() == 0) continue;
            int split = part.indexOf('=');
            String name = split > 0 ? part.substring(0, split).trim() : part;
            if (name.equals(nextName)) {
                part = next;
                replaced = true;
            }
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(part);
        }
        if (!replaced) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(next);
        }
        return builder.toString();
    }
}
