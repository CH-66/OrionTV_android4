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
import java.util.List;
import java.util.Map;

public class MainActivity extends BaseActivity {
    private PosterGridAdapter adapter;
    private TextView status;
    private Category selected;
    private int pageStart;

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
        LinearLayout nav = Ui.row(this);
        Button search = Ui.button(this, "搜索");
        Button live = Ui.button(this, "直播");
        Button favorites = Ui.button(this, "收藏");
        Button settings = Ui.button(this, "设置");
        nav.addView(search, new LinearLayout.LayoutParams(Ui.dp(this, 90), Ui.dp(this, 46)));
        nav.addView(Ui.spacer(this, 10, 1));
        nav.addView(live, new LinearLayout.LayoutParams(Ui.dp(this, 90), Ui.dp(this, 46)));
        nav.addView(Ui.spacer(this, 10, 1));
        nav.addView(favorites, new LinearLayout.LayoutParams(Ui.dp(this, 90), Ui.dp(this, 46)));
        nav.addView(Ui.spacer(this, 10, 1));
        nav.addView(settings, new LinearLayout.LayoutParams(Ui.dp(this, 90), Ui.dp(this, 46)));

        root.addView(nav);
        root.addView(Ui.spacer(this, 1, 12));

        HorizontalScrollView categoryScroll = new HorizontalScrollView(this);
        LinearLayout categoryRow = Ui.row(this);
        for (int i = 0; i < categories.length; i++) {
            final Category category = categories[i];
            Button button = Ui.button(this, category.title);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selected = category;
                    pageStart = 0;
                    loadSelected();
                }
            });
            categoryRow.addView(button, new LinearLayout.LayoutParams(Ui.dp(this, 130), Ui.dp(this, 44)));
            categoryRow.addView(Ui.spacer(this, 8, 1));
        }
        categoryScroll.addView(categoryRow);
        root.addView(categoryScroll);

        status = Ui.muted(this, "加载中", 16);
        root.addView(status);

        final GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 160));
        grid.setHorizontalSpacing(Ui.dp(this, 12));
        grid.setVerticalSpacing(Ui.dp(this, 12));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
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
                if (view != null) {
                    view.setActivated(true);
                    view.setSelected(true);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
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

    private void loadSelected() {
        if (!hasApiUrl()) return;
        status.setText("正在加载：" + selected.title);
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
                    status.setText(items.size() == 0 ? "暂无内容" : selected.title);
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
        if (!app.prefs().useLocalStorage()) {
            app.api().getPlayRecords(new ApiCallback<Map<String, PlayRecord>>() {
                @Override
                public void onSuccess(Map<String, PlayRecord> value) {
                    showRecords(value);
                }

                @Override
                public void onError(Throwable error) {
                    handleError(error);
                    showRecords(app.local().getPlayRecords());
                }
            });
            return;
        }
        showRecords(app.local().getPlayRecords());
    }

    private void showRecords(Map<String, PlayRecord> records) {
        ArrayList<PosterItem> items = new ArrayList<PosterItem>();
        if (records != null) {
            for (Map.Entry<String, PlayRecord> entry : records.entrySet()) {
                PlayRecord record = entry.getValue();
                PosterItem poster = new PosterItem();
                poster.id = entry.getKey();
                poster.title = record.title;
                poster.poster = record.cover == null ? "" : app.api().imageProxyUrl(record.cover);
                poster.subtitle = record.source_name + " 第" + record.index + "集";
                poster.payload = record;
                items.add(poster);
            }
        }
        adapter.setItems(items);
        status.setText(items.size() == 0 ? "暂无播放记录" : "最近播放");
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
