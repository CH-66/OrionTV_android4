package com.oriontv.legacy;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oriontv.legacy.api.ApiCallback;
import com.oriontv.legacy.api.models.AppSettings;
import com.oriontv.legacy.api.models.SearchResponse;
import com.oriontv.legacy.api.models.SearchResult;
import com.oriontv.legacy.remote.LegacyHttpInputServer;
import com.oriontv.legacy.ui.PosterGridAdapter;
import com.oriontv.legacy.ui.PosterItem;
import com.oriontv.legacy.ui.Ui;

import java.util.ArrayList;
import java.util.List;

public class SearchActivity extends BaseActivity {
    private EditText keyword;
    private TextView status;
    private PosterGridAdapter adapter;
    private LegacyHttpInputServer remoteServer;
    private LinearLayout historyRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        startRemoteInputIfNeeded();
    }

    @Override
    protected void onDestroy() {
        if (remoteServer != null) remoteServer.stop();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = Ui.vertical(this);
        root.addView(Ui.title(this, "搜索"));

        LinearLayout searchRow = Ui.row(this);
        keyword = Ui.edit(this, "输入电影、剧集名称");
        Button search = Ui.button(this, "搜索");
        searchRow.addView(keyword, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        searchRow.addView(Ui.spacer(this, 12, 1));
        searchRow.addView(search, new LinearLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 52)));
        root.addView(searchRow);

        HorizontalScrollView historyScroll = new HorizontalScrollView(this);
        historyRow = Ui.row(this);
        historyScroll.addView(historyRow);
        root.addView(historyScroll);

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

        search.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch(keyword.getText().toString());
            }
        });
        keyword.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                doSearch(keyword.getText().toString());
                return true;
            }
        });

        setContentView(root);
        renderLocalHistory();
    }

    private void doSearch(final String value) {
        final String term = value == null ? "" : value.trim();
        if (term.length() == 0) return;
        requireApiUrl();
        status.setText("正在搜索：" + term);
        app.local().addSearchHistory(term);
        renderLocalHistory();
        app.api().searchVideos(term, new ApiCallback<SearchResponse>() {
            @Override
            public void onSuccess(SearchResponse value) {
                List<SearchResult> results = value == null ? null : value.results;
                ArrayList<PosterItem> items = new ArrayList<PosterItem>();
                if (results != null) {
                    for (int i = 0; i < results.size(); i++) {
                        SearchResult result = results.get(i);
                        PosterItem item = new PosterItem();
                        item.id = result.id;
                        item.source = result.source;
                        item.title = result.title;
                        item.poster = app.api().imageProxyUrl(result.poster);
                        item.subtitle = result.source_name + " " + (result.year == null ? "" : result.year);
                        item.sourceName = result.source_name;
                        item.year = result.year;
                        item.payload = result;
                        items.add(item);
                    }
                }
                adapter.setItems(items);
                status.setText(items.size() == 0 ? "没有找到相关内容" : "搜索结果：" + items.size());
            }

            @Override
            public void onError(Throwable error) {
                status.setText("搜索失败");
                handleError(error);
            }
        });
    }

    private void renderLocalHistory() {
        if (historyRow == null) return;
        historyRow.removeAllViews();
        java.util.List<String> history = app.local().getSearchHistory();
        for (int i = 0; i < history.size(); i++) {
            final String item = history.get(i);
            Button button = Ui.button(this, item);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    keyword.setText(item);
                    doSearch(item);
                }
            });
            historyRow.addView(button, new LinearLayout.LayoutParams(Ui.dp(this, 130), Ui.dp(this, 42)));
            historyRow.addView(Ui.spacer(this, 8, 1));
        }
    }

    private void open(PosterItem item) {
        Intent intent = new Intent(this, DetailActivity.class);
        intent.putExtra("title", item.title);
        intent.putExtra("source", item.source);
        intent.putExtra("id", item.id);
        startActivity(intent);
    }

    private void startRemoteInputIfNeeded() {
        AppSettings settings = app.prefs().getSettings();
        if (!settings.remoteInputEnabled) return;
        remoteServer = new LegacyHttpInputServer(this);
        remoteServer.setListener(new LegacyHttpInputServer.Listener() {
            @Override
            public void onMessage(String message) {
                keyword.setText(message);
                doSearch(message);
            }

            @Override
            public void onHandshake() {
                Ui.toast(SearchActivity.this, "远程输入已连接");
            }
        });
        try {
            String url = remoteServer.start();
            status.setText("远程输入：" + url);
        } catch (Exception e) {
            status.setText("远程输入启动失败：" + e.getMessage());
        }
    }
}
