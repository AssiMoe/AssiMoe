package de.assimoe.libremirror;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

public class WearMainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView value;
    private TextView arrow;
    private TextView age;
    private TextView status;

    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 5000L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackgroundColor(0xFF07111D);

        TextView title = text("LibreMirror", 14, true, 0xFF5CC8FF);
        root.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        value = text("—", 46, true, Color.WHITE);
        row.addView(value);

        arrow = text("", 38, true, 0xFF19D38A);
        arrow.setPadding(dp(8), 0, 0, 0);
        row.addView(arrow);

        root.addView(row);

        TextView unit = text("mg/dL", 13, true, 0xFFB6C7D9);
        root.addView(unit);

        age = text("Warte auf Handy …", 11, false, 0xFF8FA7BC);
        age.setPadding(0, dp(6), 0, 0);
        root.addView(age);

        status = text("", 10, false, 0xFF7890A6);
        status.setPadding(0, dp(3), 0, 0);
        root.addView(status);

        setContentView(root);
        fetchCurrentDataItem();
    }

    private void fetchCurrentDataItem() {
        Wearable.getDataClient(this)
                .getDataItems()
                .addOnSuccessListener(buffer -> {
                    try {
                        for (DataItem item : buffer) {
                            if ("/libremirror/live".equals(item.getUri().getPath())) {
                                WearDataStore.save(
                                        this,
                                        DataMapItem.fromDataItem(item).getDataMap()
                                );
                            }
                        }
                    } finally {
                        buffer.release();
                    }
                    render();
                });
    }

    private void render() {
        WearDataStore.Snapshot s = WearDataStore.read(this);

        if (s.privateMode) {
            value.setText("•••");
            arrow.setText("");
            age.setText("Privatmodus");
            status.setText("");
            return;
        }

        value.setText(s.value.isEmpty() ? "—" : s.value);
        arrow.setText(s.value.isEmpty() ? "" : WearDataStore.arrow(s.trend));

        if (s.timestampMs > 0L) {
            long minutes = Math.max(0L, (System.currentTimeMillis() - s.timestampMs) / 60000L);
            age.setText(minutes == 0 ? "vor < 1 Min." : "vor " + minutes + " Min.");
        } else {
            age.setText("Warte auf Handy …");
        }

        status.setText(s.status);
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
        handler.removeCallbacks(updater);
        handler.post(updater);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(updater);
        super.onPause();
    }
}
