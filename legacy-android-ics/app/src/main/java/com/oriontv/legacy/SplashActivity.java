package com.oriontv.legacy;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SplashActivity extends Activity {
    private static final long SPLASH_DURATION_MS = 1200L;

    private final Handler handler = new Handler();
    private final Runnable openHome = new Runnable() {
        @Override
        public void run() {
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(intent);
            overridePendingTransition(0, 0);
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0xff06110b, 0xff0b2518, 0xff06110b });
        root.setBackgroundDrawable(background);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.senying_app_icon);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setContentDescription(getString(R.string.app_name));
        brand.addView(icon, new LinearLayout.LayoutParams(dp(96), dp(96)));

        View gap = new View(this);
        brand.addView(gap, new LinearLayout.LayoutParams(1, dp(22)));

        TextView title = new TextView(this);
        title.setText("森映TV");
        title.setTextColor(Color.rgb(242, 250, 245));
        title.setTextSize(44);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setIncludeFontPadding(false);
        brand.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        View titleGap = new View(this);
        brand.addView(titleGap, new LinearLayout.LayoutParams(1, dp(12)));

        TextView tagline = new TextView(this);
        tagline.setText("家庭影院，轻松直达");
        tagline.setTextColor(Color.rgb(158, 185, 168));
        tagline.setTextSize(18);
        tagline.setGravity(Gravity.CENTER);
        tagline.setIncludeFontPadding(false);
        brand.addView(tagline, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams brandParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        // Move the visual center slightly upward for a more balanced TV composition.
        brandParams.bottomMargin = dp(18);
        root.addView(brand, brandParams);

        setContentView(root);
        handler.postDelayed(openHome, SPLASH_DURATION_MS);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(openHome);
        super.onDestroy();
    }
}
