package com.oriontv.legacy;

import android.app.Application;

import com.oriontv.legacy.api.OrionApiClient;
import com.oriontv.legacy.data.LocalRepository;
import com.oriontv.legacy.data.PreferencesStore;
import com.oriontv.legacy.media.PlaybackProxyServer;

public class App extends Application {
    private static App instance;
    private PreferencesStore preferencesStore;
    private LocalRepository localRepository;
    private OrionApiClient apiClient;
    private PlaybackProxyServer playbackProxyServer;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        preferencesStore = new PreferencesStore(this);
        localRepository = new LocalRepository(preferencesStore);
        apiClient = new OrionApiClient(this, preferencesStore);
        playbackProxyServer = new PlaybackProxyServer();
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

    public PlaybackProxyServer playbackProxy() {
        return playbackProxyServer;
    }

    @Override
    public void onTerminate() {
        if (playbackProxyServer != null) {
            try {
                playbackProxyServer.close();
            } catch (Exception ignored) {
            }
        }
        super.onTerminate();
    }
}
