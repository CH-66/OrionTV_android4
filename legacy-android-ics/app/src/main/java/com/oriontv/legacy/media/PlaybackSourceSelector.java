package com.oriontv.legacy.media;

import com.oriontv.legacy.api.models.SearchResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PlaybackSourceSelector {
    private final Set<String> failedSources = new HashSet<String>();

    public void markFailed(String source) {
        if (source != null) failedSources.add(source);
    }

    public SearchResult next(List<SearchResult> sources, String currentSource, int episodeIndex) {
        if (sources == null) return null;
        SearchResult best = null;
        int bestScore = -1;
        for (int i = 0; i < sources.size(); i++) {
            SearchResult item = sources.get(i);
            if (item == null || item.source == null) continue;
            if (item.source.equals(currentSource)) continue;
            if (failedSources.contains(item.source)) continue;
            if (item.episodes == null || item.episodes.size() <= episodeIndex) continue;
            int score = score(item.resolution);
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    private int score(String resolution) {
        if (resolution == null) return 0;
        if (resolution.indexOf("1080") >= 0) return 4;
        if (resolution.indexOf("720") >= 0) return 3;
        if (resolution.indexOf("480") >= 0) return 2;
        if (resolution.indexOf("360") >= 0) return 1;
        return 0;
    }
}
