package de.assimoe.libremirror.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.assimoe.libremirror.SecurePrefs;

public final class SyncMetricsStore {
    private static final String PREFIX = "metrics_";

    private SyncMetricsStore() {}

    public static void recordSyncAttempt(Context context) {
        SharedPreferences prefs = prepared(context);
        prefs.edit()
                .putInt(PREFIX + "syncs", prefs.getInt(PREFIX + "syncs", 0) + 1)
                .apply();
    }

    public static void recordApiRequest(Context context) {
        SharedPreferences prefs = prepared(context);
        prefs.edit()
                .putInt(PREFIX + "api_requests", prefs.getInt(PREFIX + "api_requests", 0) + 1)
                .apply();
    }

    public static void recordWakeup(Context context) {
        SharedPreferences prefs = prepared(context);
        prefs.edit()
                .putInt(PREFIX + "wakeups", prefs.getInt(PREFIX + "wakeups", 0) + 1)
                .apply();
    }

    public static void recordSuccess(Context context, long durationMs) {
        SharedPreferences prefs = prepared(context);
        prefs.edit()
                .putInt(PREFIX + "successes", prefs.getInt(PREFIX + "successes", 0) + 1)
                .putLong(PREFIX + "last_duration_ms", Math.max(0L, durationMs))
                .putLong(
                        PREFIX + "total_duration_ms",
                        prefs.getLong(PREFIX + "total_duration_ms", 0L)
                                + Math.max(0L, durationMs)
                )
                .apply();
    }

    public static void recordFailure(
            Context context,
            long durationMs,
            boolean retry
    ) {
        SharedPreferences prefs = prepared(context);
        SharedPreferences.Editor editor = prefs.edit()
                .putInt(PREFIX + "failures", prefs.getInt(PREFIX + "failures", 0) + 1)
                .putLong(PREFIX + "last_duration_ms", Math.max(0L, durationMs))
                .putLong(
                        PREFIX + "total_duration_ms",
                        prefs.getLong(PREFIX + "total_duration_ms", 0L)
                                + Math.max(0L, durationMs)
                );

        if (retry) {
            editor.putInt(
                    PREFIX + "retries",
                    prefs.getInt(PREFIX + "retries", 0) + 1
            );
        }

        editor.apply();
    }

    public static void setSchedule(
            Context context,
            long nextSyncAtMs,
            String reason,
            long intervalMs
    ) {
        prepared(context).edit()
                .putLong(PREFIX + "next_sync_at_ms", nextSyncAtMs)
                .putString(PREFIX + "adaptive_reason", reason == null ? "" : reason)
                .putLong(PREFIX + "effective_interval_ms", Math.max(0L, intervalMs))
                .apply();
    }

    public static Snapshot snapshot(Context context) {
        SharedPreferences prefs = prepared(context);

        PowerManager powerManager =
                (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        boolean ignored = powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());

        return new Snapshot(
                prefs.getInt(PREFIX + "syncs", 0),
                prefs.getInt(PREFIX + "api_requests", 0),
                prefs.getInt(PREFIX + "wakeups", 0),
                prefs.getInt(PREFIX + "successes", 0),
                prefs.getInt(PREFIX + "failures", 0),
                prefs.getInt(PREFIX + "retries", 0),
                prefs.getLong(PREFIX + "last_duration_ms", 0L),
                prefs.getLong(PREFIX + "total_duration_ms", 0L),
                prefs.getLong(PREFIX + "next_sync_at_ms", 0L),
                prefs.getLong(PREFIX + "effective_interval_ms", 0L),
                prefs.getString(PREFIX + "adaptive_reason", ""),
                ignored
        );
    }

    private static SharedPreferences prepared(Context context) {
        SharedPreferences prefs = SecurePrefs.prefs(context);
        String today = dayKey();
        String stored = prefs.getString(PREFIX + "day", "");

        if (!today.equals(stored)) {
            prefs.edit()
                    .putString(PREFIX + "day", today)
                    .putInt(PREFIX + "syncs", 0)
                    .putInt(PREFIX + "api_requests", 0)
                    .putInt(PREFIX + "wakeups", 0)
                    .putInt(PREFIX + "successes", 0)
                    .putInt(PREFIX + "failures", 0)
                    .putInt(PREFIX + "retries", 0)
                    .putLong(PREFIX + "last_duration_ms", 0L)
                    .putLong(PREFIX + "total_duration_ms", 0L)
                    .putLong(PREFIX + "next_sync_at_ms", 0L)
                    .putLong(PREFIX + "effective_interval_ms", 0L)
                    .putString(PREFIX + "adaptive_reason", "")
                    .apply();
        }

        return prefs;
    }

    private static String dayKey() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    public static final class Snapshot {
        public final int syncs;
        public final int apiRequests;
        public final int wakeups;
        public final int successes;
        public final int failures;
        public final int retries;
        public final long lastDurationMs;
        public final long totalDurationMs;
        public final long nextSyncAtMs;
        public final long effectiveIntervalMs;
        public final String adaptiveReason;
        public final boolean batteryOptimizationIgnored;

        Snapshot(
                int syncs,
                int apiRequests,
                int wakeups,
                int successes,
                int failures,
                int retries,
                long lastDurationMs,
                long totalDurationMs,
                long nextSyncAtMs,
                long effectiveIntervalMs,
                String adaptiveReason,
                boolean batteryOptimizationIgnored
        ) {
            this.syncs = syncs;
            this.apiRequests = apiRequests;
            this.wakeups = wakeups;
            this.successes = successes;
            this.failures = failures;
            this.retries = retries;
            this.lastDurationMs = lastDurationMs;
            this.totalDurationMs = totalDurationMs;
            this.nextSyncAtMs = nextSyncAtMs;
            this.effectiveIntervalMs = effectiveIntervalMs;
            this.adaptiveReason = adaptiveReason;
            this.batteryOptimizationIgnored = batteryOptimizationIgnored;
        }
    }
}
