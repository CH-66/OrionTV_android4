package com.oriontv.legacy;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oriontv.legacy.api.ApiCallback;
import com.oriontv.legacy.api.models.DoubanItem;
import com.oriontv.legacy.api.models.DoubanResponse;
import com.oriontv.legacy.api.models.PlayRecord;
import com.oriontv.legacy.ui.PosterGridAdapter;
import com.oriontv.legacy.ui.PosterItem;
import com.oriontv.legacy.ui.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends BaseActivity {
    private PosterGridAdapter adapter;
    private TextView status;
    private Category selected;
    private int pageStart;
    private View selectedPosterView;
    private final ArrayList<Button> categoryButtons = new ArrayList<Button>();

    private final Category[] categories = new Category[] {
            new Category("最近播放", "record", ""),
            new Category("热门剧集", "tv", "热门"),
            new Category("国产剧", "tv", "国产剧"),
            new Category("美剧", "tv", "美剧"),
            new Category("电影热门", "movie", "热门"),
            new Category("电影最新", "movie", "最新"),
            new Category("综艺", "tv", "综艺"),
            new Category("豆瓣 Top250", "movie", "top250")
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        selected = categories[0];
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasApiUrl()) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else {
            loadSelected();
        }
    }

    private void buildUi() {
        LinearLayout root = Ui.vertical(this);

        LinearLayout header = Ui.row(this);
        TextView brand = Ui.brand(this, "OrionTV");
        header.addView(brand, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));

        LinearLayout nav = Ui.row(this);
        Button search = Ui.navButton(this, "搜索");
        Button live = Ui.navButton(this, "直播");
        Button favorites = Ui.navButton(this, "收藏");
        Button settings = Ui.navButton(this, "设置");
        nav.addView(search, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 38)));
        nav.addView(Ui.spacer(this, 8, 1));
        nav.addView(live, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 38)));
        nav.addView(Ui.spacer(this, 8, 1));
        nav.addView(favorites, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 38)));
        nav.addView(Ui.spacer(this, 8, 1));
        nav.addView(settings, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 38)));
        header.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 42)));

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)));
        root.addView(Ui.spacer(this, 1, 10));

        HorizontalScrollView categoryScroll = new HorizontalScrollView(this);
        categoryScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout categoryRow = Ui.row(this);
        categoryButtons.clear();
        for (int i = 0; i < categories.length; i++) {
            final int categoryIndex = i;
            Button button = Ui.tabButton(this, categories[i].title);
            categoryButtons.add(button);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selected = categories[categoryIndex];
                    pageStart = 0;
                    updateCategoryTabs();
                    loadSelected();
                }
            });
            categoryRow.addView(button, new LinearLayout.LayoutParams(
                    Ui.dp(this, 122), Ui.dp(this, 38)));
            categoryRow.addView(Ui.spacer(this, 7, 1));
        }
        categoryScroll.addView(categoryRow);
        root.addView(categoryScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 42)));
        updateCategoryTabs();

        status = Ui.sectionTitle(this, "最近播放");
        root.addView(status);

        final GridView grid = new GridView(this);
        grid.setNumColumns(6);
        grid.setColumnWidth(Ui.dp(this, 164));
        grid.setHorizontalSpacing(Ui.dp(this, 14));
        grid.setVerticalSpacing(Ui.dp(this, 14));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(android.R.color.transparent);
        grid.setCacheColorHint(android.graphics.Color.TRANSPARENT);
        grid.setVerticalScrollBarEnabled(false);
        grid.setScrollingCacheEnabled(false);
        grid.setFocusable(true);
        grid.setFocusableInTouchMode(false);
        grid.setChoiceMode(GridView.CHOICE_MODE_SINGLE);
        grid.setDrawSelectorOnTop(true);
        adapter = new PosterGridAdapter(this);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                open(adapter.getPosterItem(position));
            }
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                grid.setItemChecked(position, true);

                // Only update the two cards whose focus state actually changed.
                // Repainting every visible child made old Android TVs visibly flash.
                if (selectedPosterView != null && selectedPosterView != view) {
                    selectedPosterView.setActivated(false);
                    selectedPosterView.setSelected(false);
                }
                if (view != null) {
                    view.setActivated(true);
                    view.setSelected(true);
                    selectedPosterView = view;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                if (selectedPosterView != null) {
                    selectedPosterView.setActivated(false);
                    selectedPosterView.setSelected(false);
                    selectedPosterView = null;
                }
            }
        });
        grid.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_UP
                        && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    int position = grid.getSelectedItemPosition();
                    if (position >= 0 && position < adapter.getCount()) {
                        open(adapter.getPosterItem(position));
                        return true;
                    }
                }
                return false;
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        search.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SearchActivity.class));
            }
        });
        live.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LiveActivity.class));
            }
        });
        favorites.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, FavoritesActivity.class));
            }
        });
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        setContentView(root);
    }

    private void updateCategoryTabs() {
        for (int i = 0; i < categoryButtons.size() && i < categories.length; i++) {
            categoryButtons.get(i).setSelected(categories[i] == selected);
        }
    }

    private void loadSelected() {
        if (!hasApiUrl()) return;
        status.setText("正在加载 · " + selected.title);
        if ("record".equals(selected.type)) {
            loadRecords();
        } else {
            app.api().getDoubanData(selected.type, selected.tag, 20, pageStart, new ApiCallback<DoubanResponse>() {
                @Override
                public void onSuccess(DoubanResponse value) {
                    ArrayList<PosterItem> items = new ArrayList<PosterItem>();
                    List<DoubanItem> list = value == null ? null : value.list;
                    if (list != null) {
                        for (int i = 0; i < list.size(); i++) {
                            DoubanItem item = list.get(i);
                            PosterItem poster = new PosterItem();
                            poster.id = item.title;
                            poster.title = item.title;
                            poster.poster = app.api().imageProxyUrl(item.poster);
                            poster.subtitle = item.rate;
                            poster.payload = item;
                            items.add(poster);
                        }
                    }
                    adapter.setItems(items);
                    status.setText(items.size() == 0 ? "暂无内容" : selected.title + " · " + items.size());
                }

                @Override
                public void onError(Throwable error) {
                    status.setText("加载失败");
                    handleError(error);
                }
            });
        }
    }

    private void loadRecords() {
        final Map<String, PlayRecord> localRecords = app.local().getPlayRecords();

        // Local-first: returning from PlayerActivity must update the home screen immediately,
        // even if the remote record service is slow or temporarily unavailable.
        showRecords(localRecords);

        if (app.prefs().useLocalStorage()) {
            return;
        }

        app.api().getPlayRecords(new ApiCallback<Map<String, PlayRecord>>() {
            @Override
            public void onSuccess(Map<String, PlayRecord> remoteRecords) {
                Map<String, PlayRecord> merged = mergePlayRecords(localRecords, remoteRecords);
                app.local().replacePlayRecords(merged);
                showRecords(merged);
            }

            @Override
            public void onError(Throwable error) {
                android.util.Log.w("OrionMain", "Play record refresh failed; keeping local history", error);
                // Keep the already-rendered local records. Do not replace a useful local
                // history screen with an error just because cloud sync failed.
            }
        });
    }

    private Map<String, PlayRecord> mergePlayRecords(Map<String, PlayRecord> localRecords,
                                                      Map<String, PlayRecord> remoteRecords) {
        LinkedHashMap<String, PlayRecord> merged = new LinkedHashMap<String, PlayRecord>();

        if (remoteRecords != null) {
            for (Map.Entry<String, PlayRecord> entry : remoteRecords.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }

        if (localRecords != null) {
            for (Map.Entry<String, PlayRecord> entry : localRecords.entrySet()) {
                String key = entry.getKey();
                PlayRecord local = entry.getValue();
                if (key == null || local == null) continue;

                PlayRecord remote = merged.get(key);
                if (remote == null || local.save_time >= remote.save_time) {
                    merged.put(key, local);
                }
            }
        }

        return merged;
    }

    private void showRecords(Map<String, PlayRecord> records) {
        ArrayList<PosterItem> items = new ArrayList<PosterItem>();
        ArrayList<Map.Entry<String, PlayRecord>> entries =
                new ArrayList<Map.Entry<String, PlayRecord>>();

        if (records != null) {
            entries.addAll(records.entrySet());
        }

        Collections.sort(entries, new Comparator<Map.Entry<String, PlayRecord>>() {
            @Override
            public int compare(Map.Entry<String, PlayRecord> left,
                               Map.Entry<String, PlayRecord> right) {
                PlayRecord a = left == null ? null : left.getValue();
                PlayRecord b = right == null ? null : right.getValue();
                long aTime = a == null ? 0L : a.save_time;
                long bTime = b == null ? 0L : b.save_time;
                if (aTime == bTime) return 0;
                return aTime > bTime ? -1 : 1;
            }
        });

        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, PlayRecord> entry = entries.get(i);
            if (entry == null || entry.getValue() == null) continue;

            PlayRecord record = entry.getValue();
            PosterItem poster = new PosterItem();
            poster.id = entry.getKey();
            poster.title = record.title;
            poster.poster = record.cover == null ? "" : app.api().imageProxyUrl(record.cover);
            String sourceName = record.source_name == null ? "" : record.source_name;
            poster.subtitle = sourceName + " 第" + record.index + "集";
            poster.payload = record;
            items.add(poster);
        }

        adapter.setItems(items);
        status.setText(items.size() == 0 ? "暂无播放记录" : "最近播放 · " + items.size());
    }

    private void open(PosterItem item) {
        Intent intent = new Intent(this, DetailActivity.class);
        intent.putExtra("title", item.title);
        if (item.source != null) {
            intent.putExtra("source", item.source);
        }
        if (item.id != null) {
            String[] parts = item.id.split("\\+");
            if (parts.length >= 2) {
                intent.putExtra("source", parts[0]);
                intent.putExtra("id", parts[1]);
            } else {
                intent.putExtra("id", item.id);
            }
        }
        startActivity(intent);
    }

    private static class Category {
        String title;
        String type;
        String tag;

        Category(String title, String type, String tag) {
            this.title = title;
            this.type = type;
            this.tag = tag;
        }
    }
}
