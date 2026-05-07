package com.oriontv.legacy;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oriontv.legacy.api.ApiCallback;
import com.oriontv.legacy.api.models.Favorite;
import com.oriontv.legacy.api.models.PlayRecord;
import com.oriontv.legacy.ui.PosterGridAdapter;
import com.oriontv.legacy.ui.PosterItem;
import com.oriontv.legacy.ui.Ui;

import java.util.ArrayList;
import java.util.Map;

public class FavoritesActivity extends BaseActivity {
    private PosterGridAdapter adapter;
    private TextView status;
    private boolean showingRecords;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadFavorites();
    }

    private void buildUi() {
        LinearLayout root = Ui.vertical(this);
        LinearLayout row = Ui.row(this);
        Button favorites = Ui.button(this, "收藏");
        Button records = Ui.button(this, "历史");
        row.addView(favorites, new LinearLayout.LayoutParams(Ui.dp(this, 100), Ui.dp(this, 46)));
        row.addView(Ui.spacer(this, 10, 1));
        row.addView(records, new LinearLayout.LayoutParams(Ui.dp(this, 100), Ui.dp(this, 46)));
        root.addView(row);

        status = Ui.muted(this, "", 16);
        root.addView(status);

        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 160));
        grid.setHorizontalSpacing(Ui.dp(this, 12));
        grid.setVerticalSpacing(Ui.dp(this, 12));
        adapter = new PosterGridAdapter(this);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                open(adapter.getPosterItem(position));
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        favorites.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadFavorites();
            }
        });
        records.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadRecords();
            }
        });

        setContentView(root);
    }

    private void loadFavorites() {
        showingRecords = false;
        status.setText("正在加载收藏");
        if (app.prefs().useLocalStorage()) {
            showFavorites(app.local().getFavorites());
        } else {
            app.api().getFavorites(new ApiCallback<Map<String, Favorite>>() {
                @Override
                public void onSuccess(Map<String, Favorite> value) {
                    showFavorites(value);
                }

                @Override
                public void onError(Throwable error) {
                    handleError(error);
                }
            });
        }
    }

    private void showFavorites(Map<String, Favorite> map) {
        ArrayList<PosterItem> items = new ArrayList<PosterItem>();
        if (map != null) {
            for (Map.Entry<String, Favorite> entry : map.entrySet()) {
                Favorite fav = entry.getValue();
                PosterItem item = new PosterItem();
                item.id = entry.getKey();
                item.title = fav.title;
                item.poster = fav.cover == null ? "" : app.api().imageProxyUrl(fav.cover);
                item.subtitle = fav.source_name;
                item.payload = fav;
                items.add(item);
            }
        }
        adapter.setItems(items);
        status.setText(items.size() == 0 ? "暂无收藏" : "收藏：" + items.size());
    }

    private void loadRecords() {
        showingRecords = true;
        status.setText("正在加载历史");
        if (app.prefs().useLocalStorage()) {
            showRecords(app.local().getPlayRecords());
        } else {
            app.api().getPlayRecords(new ApiCallback<Map<String, PlayRecord>>() {
                @Override
                public void onSuccess(Map<String, PlayRecord> value) {
                    showRecords(value);
                }

                @Override
                public void onError(Throwable error) {
                    handleError(error);
                }
            });
        }
    }

    private void showRecords(Map<String, PlayRecord> map) {
        ArrayList<PosterItem> items = new ArrayList<PosterItem>();
        if (map != null) {
            for (Map.Entry<String, PlayRecord> entry : map.entrySet()) {
                PlayRecord record = entry.getValue();
                PosterItem item = new PosterItem();
                item.id = entry.getKey();
                item.title = record.title;
                item.poster = record.cover == null ? "" : app.api().imageProxyUrl(record.cover);
                item.subtitle = record.source_name + " 第" + record.index + "集";
                item.payload = record;
                items.add(item);
            }
        }
        adapter.setItems(items);
        status.setText(items.size() == 0 ? "暂无播放历史" : "历史：" + items.size());
    }

    private void open(PosterItem item) {
        Intent intent = new Intent(this, DetailActivity.class);
        intent.putExtra("title", item.title);
        String[] parts = item.id == null ? new String[0] : item.id.split("\\+");
        if (parts.length >= 2) {
            intent.putExtra("source", parts[0]);
            intent.putExtra("id", parts[1]);
        }
        startActivity(intent);
    }
}
