package com.oriontv.legacy.media;

import com.oriontv.legacy.api.models.SearchResult;
import com.oriontv.legacy.data.PreferencesStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PlaybackSourceSelector {
    private final Set<String> failedSources = new HashSet<String>();
    private final PreferencesStore prefs;

    public PlaybackSourceSelector(PreferencesStore prefs) {
        this.prefs = prefs;
    }

    public void markFailed(String source) {
        if (source != null) failedSources.add(source);
    }

    public void reset() {
        failedSources.clear();
    }

    public SearchResult best(List<SearchResult> sources, int episodeIndex) {
        return choose(sources, null, episodeIndex, false);
    }

    public SearchResult next(List<SearchResult> sources, String currentSource, int episodeIndex) {
        return choose(sources, currentSource, episodeIndex, true);
    }

    private SearchResult choose(List<SearchResult> sources, String currentSource,
                                int episodeIndex, boolean skipCurrent) {
        if (sources == null) return null;
        SearchResult best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < sources.size(); i++) {
            SearchResult item = sources.get(i);
            if (item == null || item.source == null) continue;
            if (skipCurrent && item.source.equals(currentSource)) continue;
            if (failedSources.contains(item.source)) continue;
            if (item.episodes == null || item.episodes.size() <= episodeIndex) continue;

            // Resolution remains a preference, but a source with known ad insertions
            // is always ranked below an otherwise usable source with no ad history.
            int adPenalty = prefs == null ? 0 : prefs.getSourceAdPenalty(item.source);
            int score = score(item.resolution) * 100 - adPenalty * 1000;
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
