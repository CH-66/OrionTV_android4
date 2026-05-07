package com.oriontv.legacy;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.gson.Gson;
import com.oriontv.legacy.api.models.AppSettings;
import com.oriontv.legacy.api.models.Channel;
import com.oriontv.legacy.api.models.SearchResult;
import com.oriontv.legacy.media.M3uParser;
import com.oriontv.legacy.ui.Ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class LiveActivity extends BaseActivity {
    private final OkHttpClient client = new OkHttpClient();
    private final ArrayList<Channel> channels = new ArrayList<Channel>();
    private ArrayAdapter<String> adapter;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadChannels();
    }

    private void buildUi() {
        LinearLayout root = Ui.vertical(this);
        root.addView(Ui.title(this, "直播"));
        status = Ui.muted(this, "正在加载频道", 16);
        root.addView(status);

        GridView grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setHorizontalSpacing(Ui.dp(this, 8));
        grid.setVerticalSpacing(Ui.dp(this, 8));
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openChannel(position);
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void loadChannels() {
        AppSettings settings = app.prefs().getSettings();
        if (settings.m3uUrl == null || settings.m3uUrl.length() == 0) {
            status.setText("请先在设置中配置直播 M3U 地址");
            return;
        }
        Request request = new Request.Builder().url(settings.m3uUrl).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, final IOException e) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText("直播源加载失败：" + e.getMessage());
                    }
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String body = response.body() == null ? "" : response.body().string();
                final List<Channel> parsed = new M3uParser().parse(body);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        channels.clear();
                        channels.addAll(parsed);
                        adapter.clear();
                        for (int i = 0; i < channels.size(); i++) {
                            Channel channel = channels.get(i);
                            adapter.add((channel.group == null ? "" : channel.group + " / ") + channel.name);
                        }
                        adapter.notifyDataSetChanged();
                        status.setText(channels.size() == 0 ? "没有解析到频道" : "频道：" + channels.size());
                    }
                });
            }
        });
    }

    private void openChannel(int position) {
        if (position < 0 || position >= channels.size()) return;
        SearchResult live = new SearchResult();
        live.id = "live";
        live.source = "m3u";
        live.source_name = "直播";
        live.title = "直播";
        live.poster = "";
        for (int i = 0; i < channels.size(); i++) {
            live.episodes.add(channels.get(i).url);
        }
        ArrayList<SearchResult> sources = new ArrayList<SearchResult>();
        sources.add(live);
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("sources_json", new Gson().toJson(sources));
        intent.putExtra("source", "m3u");
        intent.putExtra("id", "live");
        intent.putExtra("title", "直播");
        intent.putExtra("source_name", "直播");
        intent.putExtra("episode_index", position);
        startActivity(intent);
    }
}
