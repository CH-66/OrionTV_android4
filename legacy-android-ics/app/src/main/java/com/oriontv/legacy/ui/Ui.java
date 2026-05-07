package com.oriontv.legacy.ui;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.oriontv.legacy.R;

public final class Ui {
    private Ui() {}

    public static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static LinearLayout vertical(Activity activity) {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackgroundColor(activity.getResources().getColor(R.color.orion_bg));
        view.setPadding(dp(activity, 24), dp(activity, 18), dp(activity, 24), dp(activity, 18));
        return view;
    }

    public static LinearLayout row(Activity activity) {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    public static TextView title(Activity activity, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(28);
        view.setSingleLine(true);
        view.setPadding(0, 0, 0, dp(activity, 12));
        return view;
    }

    public static TextView text(Activity activity, String text, int sp) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        return view;
    }

    public static TextView muted(Activity activity, String text, int sp) {
        TextView view = text(activity, text, sp);
        view.setTextColor(Color.rgb(185, 185, 185));
        return view;
    }

    public static Button button(Activity activity, String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setBackgroundResource(R.drawable.button_focus);
        button.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        return button;
    }

    public static EditText edit(Activity activity, String hint) {
        EditText editText = new EditText(activity);
        editText.setHint(hint);
        editText.setHintTextColor(Color.rgb(150, 150, 150));
        editText.setTextColor(Color.WHITE);
        editText.setSingleLine(true);
        editText.setTextSize(18);
        editText.setFocusable(true);
        editText.setFocusableInTouchMode(true);
        editText.setBackgroundResource(R.drawable.edit_focus);
        editText.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        return editText;
    }

    public static ProgressBar progress(Activity activity) {
        ProgressBar progressBar = new ProgressBar(activity);
        progressBar.setIndeterminate(true);
        return progressBar;
    }

    public static View spacer(Activity activity, int width, int height) {
        View view = new View(activity);
        view.setLayoutParams(new ViewGroup.LayoutParams(dp(activity, width), dp(activity, height)));
        return view;
    }

    public static void toast(Activity activity, String text) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }
}
