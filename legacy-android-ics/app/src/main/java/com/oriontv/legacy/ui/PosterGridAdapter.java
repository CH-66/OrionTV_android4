package com.oriontv.legacy.ui;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oriontv.legacy.R;

import java.util.ArrayList;
import java.util.List;

public class PosterGridAdapter extends BaseAdapter {
    private final Activity activity;
    private final List<PosterItem> items = new ArrayList<PosterItem>();

    public PosterGridAdapter(Activity activity) {
        this.activity = activity;
    }

    public void setItems(List<PosterItem> next) {
        items.clear();
        if (next != null) {
            items.addAll(next);
        }
        notifyDataSetChanged();
    }

    public PosterItem getPosterItem(int position) {
        return items.get(position);
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (convertView == null) {
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setBackgroundResource(R.drawable.focus_panel);
            root.setPadding(Ui.dp(activity, 6), Ui.dp(activity, 6), Ui.dp(activity, 6), Ui.dp(activity, 6));
            root.setLayoutParams(new AbsListView.LayoutParams(Ui.dp(activity, 150), Ui.dp(activity, 245)));

            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageResource(R.drawable.poster_placeholder);
            root.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 190)));

            TextView title = new TextView(activity);
            title.setTextColor(Color.WHITE);
            title.setTextSize(14);
            title.setSingleLine(true);
            root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 28)));

            TextView subtitle = new TextView(activity);
            subtitle.setTextColor(Color.rgb(180, 180, 180));
            subtitle.setTextSize(12);
            subtitle.setSingleLine(true);
            root.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 20)));

            holder = new Holder();
            holder.image = image;
            holder.title = title;
            holder.subtitle = subtitle;
            root.setTag(holder);
            convertView = root;
        } else {
            holder = (Holder) convertView.getTag();
        }

        PosterItem item = items.get(position);
        holder.title.setText(item.title == null ? "" : item.title);
        holder.subtitle.setText(item.subtitle == null ? "" : item.subtitle);
        holder.image.setImageResource(R.drawable.poster_placeholder);
        boolean activated = false;
        if (parent instanceof AbsListView) {
            AbsListView listView = (AbsListView) parent;
            activated = listView.isItemChecked(position) || position == listView.getSelectedItemPosition();
        }
        convertView.setActivated(activated);
        convertView.setSelected(activated);
        if (item.poster != null && item.poster.length() > 0) {
            activity.getApplicationContext();
            com.oriontv.legacy.App.get().api().loadImage(item.poster, holder.image, R.drawable.poster_placeholder);
        }
        return convertView;
    }

    private static class Holder {
        ImageView image;
        TextView title;
        TextView subtitle;
    }
}
