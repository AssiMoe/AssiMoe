package de.assimoe.libremirror;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.wearable.DataMap;

final class WearDataStore {
    private static final String PREFS = "libremirror_wear";

    private WearDataStore() {}

    static void save(Context context, DataMap map) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("value", map.getString("value", ""))
                .putInt("trend", map.getInt("trend", 0))
                .putLong("timestamp_ms", map.getLong("timestamp_ms", 0L))
                .putString("status", map.getString("status", ""))
                .putBoolean("private_mode", map.getBoolean("private_mode", false))
                .apply();
    }

    static Snapshot read(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Snapshot(
                p.getString("value", ""),
                p.getInt("trend", 0),
                p.getLong("timestamp_ms", 0L),
                p.getString("status", ""),
                p.getBoolean("private_mode", false)
        );
    }

    static String arrow(int trend) {
        switch (trend) {
            case 1: return "↓";
            case 2: return "↘";
            case 3: return "→";
            case 4: return "↗";
            case 5: return "↑";
            default: return "•";
        }
    }

    static final class Snapshot {
        final String value;
        final int trend;
        final long timestampMs;
        final String status;
        final boolean privateMode;

        Snapshot(String value, int trend, long timestampMs, String status, boolean privateMode) {
            this.value = value == null ? "" : value;
            this.trend = trend;
            this.timestampMs = timestampMs;
            this.status = status == null ? "" : status;
            this.privateMode = privateMode;
        }
    }
}
