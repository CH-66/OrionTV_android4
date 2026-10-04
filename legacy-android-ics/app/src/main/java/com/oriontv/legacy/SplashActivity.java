package com.oriontv.legacy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

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
        root.setBackgroundColor(0xff06110b);

        ImageView splash = new ImageView(this);
        splash.setImageResource(R.drawable.senying_splash);
        splash.setScaleType(ImageView.ScaleType.CENTER_CROP);
        splash.setContentDescription(getString(R.string.app_name));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        root.addView(splash, params);

        setContentView(root);
        handler.postDelayed(openHome, SPLASH_DURATION_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(openHome);
        super.onDestroy();
    }
}
