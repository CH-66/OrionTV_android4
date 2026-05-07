package com.oriontv.legacy;

import android.app.Application;

import com.oriontv.legacy.api.OrionApiClient;
import com.oriontv.legacy.data.LocalRepository;
import com.oriontv.legacy.data.PreferencesStore;

public class App extends Application {
    private static App instance;
    private PreferencesStore preferencesStore;
    private LocalRepository localRepository;
    private OrionApiClient apiClient;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        preferencesStore = new PreferencesStore(this);
        localRepository = new LocalRepository(preferencesStore);
        apiClient = new OrionApiClient(this, preferencesStore);
    }

    public static App get() {
        return instance;
    }

    public PreferencesStore prefs() {
        return preferencesStore;
    }

    public LocalRepository local() {
        return localRepository;
    }

    public OrionApiClient api() {
        return apiClient;
    }
}
