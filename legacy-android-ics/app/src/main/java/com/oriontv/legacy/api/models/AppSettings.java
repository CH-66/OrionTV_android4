package com.oriontv.legacy.api.models;

import java.util.HashMap;
import java.util.Map;

public class AppSettings {
    public String apiBaseUrl = "";
    public String m3uUrl = "";
    public boolean remoteInputEnabled = true;
    public VideoSourceConfig videoSource = new VideoSourceConfig();

    public static class VideoSourceConfig {
        public boolean enabledAll = true;
        public Map<String, Boolean> sources = new HashMap<String, Boolean>();
    }
}
