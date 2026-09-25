package de.assimoe.libremirror.core;

import android.content.SharedPreferences;

import de.assimoe.libremirror.LibreApiClient;

public final class AdaptiveSyncPolicy {
    private static final long ONE_MINUTE_MS = 60_000L;

    private AdaptiveSyncPolicy() {}

    public static Decision afterSuccess(
            SharedPreferences prefs,
            LibreApiClient.Reading reading,
            SyncStatus status
    ) {
        long baseMs = baseIntervalMs(prefs);

        if (!prefs.getBoolean("adaptive_sync_enabled", true)) {
            return new Decision(baseMs, "Festes Intervall");
        }

        if (status == SyncStatus.SENSOR_STALE) {
            return new Decision(
                    Math.min(baseMs, 2L * ONE_MINUTE_MS),
                    "Sensorwert veraltet"
            );
        }

        boolean foreground = prefs.getBoolean("ui_foreground", false);
        long foregroundSeenMs = prefs.getLong("ui_foreground_seen_ms", 0L);

        if (foreground
                && System.currentTimeMillis() - foregroundSeenMs < 90_000L) {
            return new Decision(
                    ONE_MINUTE_MS,
                    "App geöffnet"
            );
        }

        if (reading != null) {
            double low = parseDouble(
                    prefs.getString("low", "70"),
                    70.0
            );

            double high = parseDouble(
                    prefs.getString("high", "180"),
                    180.0
            );

            if (reading.mgdl < low || reading.mgdl > high) {
                return new Decision(
                        ONE_MINUTE_MS,
                        "Grenzwertstatus"
                );
            }

            if (reading.trend == 1 || reading.trend == 5) {
                return new Decision(
                        ONE_MINUTE_MS,
                        "Starker Trend"
                );
            }
        }

        return new Decision(baseMs, "Basisintervall");
    }

    public static long baseIntervalMs(SharedPreferences prefs) {
        int minutes = prefs.getInt("sync_interval_min", 1);

        if (minutes < 1) minutes = 1;
        if (minutes > 30) minutes = 30;

        return minutes * ONE_MINUTE_MS;
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(
                    value == null ? "" : value.replace(',', '.')
            );
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static final class Decision {
        public final long delayMs;
        public final String reason;

        public Decision(long delayMs, String reason) {
            this.delayMs = Math.max(ONE_MINUTE_MS, delayMs);
            this.reason = reason == null ? "" : reason;
        }
    }
}
