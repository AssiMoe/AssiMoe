package de.assimoe.libremirror;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public class AutoModeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView value;
    private TextView arrow;
    private TextView status;

    private final Runnable update = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 2000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF04090F);
        getWindow().setNavigationBarColor(0xFF04090F);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(22), dp(22), dp(22), dp(22));
        root.setBackgroundColor(0xFF04090F);

        TextView title = text("LibreMirror AUTO", 18, true, 0xFF66C7FF);
        root.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        value = text("—", 78, true, Color.WHITE);
        row.addView(value);

        arrow = text("→", 72, true, 0xFF19D38A);
        arrow.setPadding(dp(16), 0, 0, 0);
        row.addView(arrow);

        root.addView(row);

        TextView unit = text("mg/dL", 22, true, 0xFFB6C7D9);
        root.addView(unit);

        status = text("Kein Wert", 16, true, 0xFF7890A6);
        status.setPadding(0, dp(14), 0, 0);
        root.addView(status);

        TextView exit = text("Tippen zum Beenden", 13, false, 0xFF7186A1);
        exit.setPadding(0, dp(28), 0, 0);
        root.addView(exit);

        root.setOnClickListener(v -> finish());
        setContentView(root);
    }

    private void render() {
        SharedPreferences prefs = SecurePrefs.prefs(this);
        boolean privateMode = prefs.getBoolean("private_mode", false);
        boolean locked = ((android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE))
                .isDeviceLocked();

        String raw = prefs.getString("last_value", "");
        int trend = prefs.getInt("last_trend", 0);
        long sensorMs = prefs.getLong("last_sensor_ms", 0L);

        if (privateMode && locked) {
            value.setText("•••");
            arrow.setText("");
            status.setText("Privatmodus");
            return;
        }

        value.setText(raw.isEmpty() ? "—" : raw);
        arrow.setText(raw.isEmpty() ? "" : LibreApiClient.arrow(trend));

        if (raw.isEmpty()) {
            status.setText("Kein Glukosewert");
        } else if (sensorMs > 0L) {
            long minutes = Math.max(0L, (System.currentTimeMillis() - sensorMs) / 60000L);
            status.setText(LibreApiClient.trendLabel(trend) + " • vor " +
                    (minutes == 0 ? "< 1 Min." : minutes + " Min."));
        } else {
            status.setText(LibreApiClient.trendLabel(trend));
        }
    }

    private TextView text(String text, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(update);
        handler.post(update);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(update);
        super.onPause();
    }
}
