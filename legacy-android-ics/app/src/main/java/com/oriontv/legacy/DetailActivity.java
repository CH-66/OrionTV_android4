package com.oriontv.legacy;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.gson.Gson;
import com.oriontv.legacy.api.ApiCallback;
import com.oriontv.legacy.api.models.ApiSite;
import com.oriontv.legacy.api.models.AppSettings;
import com.oriontv.legacy.api.models.Favorite;
import com.oriontv.legacy.api.models.MutationResult;
import com.oriontv.legacy.api.models.SearchResponse;
import com.oriontv.legacy.api.models.SearchResult;
import com.oriontv.legacy.data.LocalRepository;
import com.oriontv.legacy.media.M3u8Inspector;
import com.oriontv.legacy.net.LegacyHttpCompat;
import com.oriontv.legacy.ui.Ui;

import java.util.ArrayList;
import java.util.List;

public class DetailActivity extends BaseActivity {
    private final Gson gson = new Gson();
    private final ArrayList<SearchResult> sources = new ArrayList<SearchResult>();
    private final Handler handler = new Handler();
    private LinearLayout sourceRow;
    private GridView episodeGrid;
    private ArrayAdapter<String> episodeAdapter;
    private TextView status;
    private TextView titleView;
    private ImageView posterView;
    private Button favoriteButton;
    private SearchResult selected;
    private String query;
    private String preferredSource;
    private String preferredId;
    private int pendingSources;
    private int tlsFailures;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        query = getIntent().getStringExtra("title");
        if (query == null) query = "";
        preferredSource = getIntent().getStringExtra("source");
        preferredId = getIntent().getStringExtra("id");
        buildUi();
        loadSources();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = Ui.vertical(this);
        scroll.addView(root);

        LinearLayout header = Ui.row(this);
        posterView = new ImageView(this);
        posterView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        header.addView(posterView, new LinearLayout.LayoutParams(Ui.dp(this, 180), Ui.dp(this, 240)));

        LinearLayout meta = Ui.vertical(this);
        meta.setPadding(Ui.dp(this, 18), 0, 0, 0);
        titleView = Ui.title(this, query == null ? "" : query);
        status = Ui.muted(this, "正在加载播放源", 16);
        favoriteButton = Ui.button(this, "收藏");
        Button play = Ui.button(this, "播放第一集");
        meta.addView(titleView);
        meta.addView(status);
        meta.addView(Ui.spacer(this, 1, 10));
        LinearLayout actions = Ui.row(this);
        actions.addView(play, new LinearLayout.LayoutParams(Ui.dp(this, 130), Ui.dp(this, 48)));
        actions.addView(Ui.spacer(this, 12, 1));
        actions.addView(favoriteButton, new LinearLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 48)));
        meta.addView(actions);
        header.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(header);

        root.addView(Ui.muted(this, "播放源", 16));
        sourceRow = Ui.row(this);
        root.addView(sourceRow);

        root.addView(Ui.muted(this, "剧集", 16));
        episodeGrid = new GridView(this);
        episodeGrid.setNumColumns(6);
        episodeGrid.setHorizontalSpacing(Ui.dp(this, 8));
        episodeGrid.setVerticalSpacing(Ui.dp(this, 8));
        episodeGrid.setFocusable(true);
        episodeGrid.setFocusableInTouchMode(false);
        episodeGrid.setChoiceMode(GridView.CHOICE_MODE_SINGLE);
        episodeGrid.setDrawSelectorOnTop(true);
        episodeAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        episodeGrid.setAdapter(episodeAdapter);
        episodeGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openPlayer(position);
            }
        });
        episodeGrid.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_UP
                        && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    int position = episodeGrid.getSelectedItemPosition();
                    if (position >= 0 && position < episodeAdapter.getCount()) {
                        openPlayer(position);
                        return true;
                    }
                }
                return false;
            }
        });
        root.addView(episodeGrid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 360)));

        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPlayer(0);
            }
        });
        favoriteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFavorite();
            }
        });

        setContentView(scroll);
    }

    private void loadSources() {
        requireApiUrl();
        app.api().getResources(new ApiCallback<List<ApiSite>>() {
            @Override
            public void onSuccess(List<ApiSite> value) {
                searchResources(filterSources(value));
            }

            @Override
            public void onError(Throwable error) {
                status.setText("获取视频源失败");
                handleError(error);
            }
        });
    }

    private List<ApiSite> filterSources(List<ApiSite> all) {
        ArrayList<ApiSite> filtered = new ArrayList<ApiSite>();
        if (all == null) return filtered;
        AppSettings settings = app.prefs().getSettings();
        for (int i = 0; i < all.size(); i++) {
            ApiSite site = all.get(i);
            boolean enabled = settings.videoSource == null || settings.videoSource.enabledAll
                    || settings.videoSource.sources == null
                    || Boolean.TRUE.equals(settings.videoSource.sources.get(site.key));
            if (enabled) filtered.add(site);
        }
        return filtered;
    }

    private void searchResources(List<ApiSite> resources) {
        if (resources.size() == 0) {
            status.setText("没有可用视频源");
            return;
        }
        tlsFailures = 0;
        pendingSources = resources.size();
        for (int i = 0; i < resources.size(); i++) {
            final ApiSite site = resources.get(i);
            app.api().searchVideo(query, site.key, new ApiCallback<SearchResponse>() {
                @Override
                public void onSuccess(SearchResponse value) {
                    if (value != null && value.results != null) {
                        for (int i = 0; i < value.results.size(); i++) {
                            SearchResult result = value.results.get(i);
                            if (query.equals(result.title)) {
                                sources.add(result);
                                if (selected == null || site.key.equals(preferredSource)) {
                                    selectSource(result);
                                }
                                inspectResolution(result);
                            }
                        }
                    }
                    oneSourceDone();
                }

                @Override
                public void onError(Throwable error) {
                    if (LegacyHttpCompat.isTlsProblem(error)) {
                        tlsFailures++;
                    }
                    oneSourceDone();
                }
            });
        }
    }

    private void oneSourceDone() {
        pendingSources--;
        status.setText(sources.size() == 0 ? "继续搜索播放源..." : "已找到 " + sources.size() + " 个播放源");
        if (pendingSources <= 0 && sources.size() == 0) {
            if (tlsFailures > 0) {
                status.setText(LegacyHttpCompat.buildCompatMessage("片源加载"));
            } else {
                status.setText("未找到播放源");
            }
        }
        renderSources();
    }

    private void inspectResolution(final SearchResult result) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (result.episodes == null || result.episodes.size() == 0) return;
                final String resolution = new M3u8Inspector().inspectResolution(result.episodes.get(0));
                if (resolution == null) return;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        result.resolution = resolution;
                        renderSources();
                    }
                });
            }
        }).start();
    }

    private void renderSources() {
        sourceRow.removeAllViews();
        for (int i = 0; i < sources.size(); i++) {
            final SearchResult source = sources.get(i);
            String label = source.source_name == null ? source.source : source.source_name;
            if (source.resolution != null) label += " " + source.resolution;
            Button button = Ui.button(this, label);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectSource(source);
                }
            });
            sourceRow.addView(button, new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 46)));
            sourceRow.addView(Ui.spacer(this, 8, 1));
        }
    }

    private void selectSource(SearchResult source) {
        selected = source;
        titleView.setText(source.title);
        if (source.poster != null && source.poster.length() > 0) {
            app.api().loadImage(app.api().imageProxyUrl(source.poster), posterView, R.drawable.poster_placeholder);
        } else {
            posterView.setImageResource(R.drawable.poster_placeholder);
        }
        episodeAdapter.clear();
        if (source.episodes != null) {
            for (int i = 0; i < source.episodes.size(); i++) {
                episodeAdapter.add("第" + (i + 1) + "集");
            }
        }
        episodeAdapter.notifyDataSetChanged();
        updateFavoriteButton();
    }

    private void updateFavoriteButton() {
        if (selected == null) return;
        boolean isFav = app.local().isFavorited(selected.source, selected.id);
        favoriteButton.setText(isFav ? "取消收藏" : "收藏");
    }

    private void toggleFavorite() {
        if (selected == null) return;
        final String key = LocalRepository.key(selected.source, selected.id);
        final boolean isFav = app.local().isFavorited(selected.source, selected.id);
        if (isFav) {
            app.local().removeFavorite(selected.source, selected.id);
            if (!app.prefs().useLocalStorage()) {
                app.api().deleteFavorite(key, new EmptyMutation());
            }
        } else {
            Favorite favorite = new Favorite();
            favorite.cover = selected.poster;
            favorite.title = selected.title;
            favorite.source_name = selected.source_name;
            favorite.total_episodes = selected.episodes == null ? 0 : selected.episodes.size();
            favorite.search_title = query;
            favorite.year = selected.year;
            app.local().saveFavorite(selected.source, selected.id, favorite);
            if (!app.prefs().useLocalStorage()) {
                app.api().addFavorite(key, favorite, new EmptyMutation());
            }
        }
        updateFavoriteButton();
    }

    private void openPlayer(int episodeIndex) {
        if (selected == null || selected.episodes == null || selected.episodes.size() == 0) {
            Ui.toast(this, "没有可播放剧集");
            return;
        }
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("sources_json", gson.toJson(sources));
        intent.putExtra("source", selected.source);
        intent.putExtra("id", selected.id);
        intent.putExtra("title", selected.title);
        intent.putExtra("poster", selected.poster);
        intent.putExtra("source_name", selected.source_name);
        intent.putExtra("year", selected.year);
        intent.putExtra("episode_index", episodeIndex);
        startActivity(intent);
    }

    private static class EmptyMutation implements ApiCallback<MutationResult> {
        @Override
        public void onSuccess(MutationResult value) {
        }

        @Override
        public void onError(Throwable error) {
        }
    }
}
