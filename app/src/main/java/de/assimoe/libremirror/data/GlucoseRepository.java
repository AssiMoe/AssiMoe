package de.assimoe.libremirror.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.assimoe.libremirror.LibreApiClient;
import de.assimoe.libremirror.LibreMirrorWidgetProvider;
import de.assimoe.libremirror.SecurePrefs;
import de.assimoe.libremirror.core.SyncStatus;

public final class GlucoseRepository {
    private static final String MIGRATION_KEY = "legacy_db_migration_v1_done";
    private static final long RETENTION_MS = 90L * 24L * 60L * 60L * 1000L;
    private static final long EVENT_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;

    private static volatile GlucoseRepository INSTANCE;

    private final Context appContext;
    private final AppDatabase database;
    private final ExecutorService maintenanceExecutor =
            Executors.newSingleThreadExecutor();

    private GlucoseRepository(Context context) {
        appContext = context.getApplicationContext();
        database = AppDatabase.get(appContext);
    }

    public static GlucoseRepository get(Context context) {
        GlucoseRepository current = INSTANCE;
        if (current != null) return current;

        synchronized (GlucoseRepository.class) {
            current = INSTANCE;

            if (current == null) {
                current = new GlucoseRepository(context);
                INSTANCE = current;
            }

            return current;
        }
    }

    public void migrateLegacyDataAsync() {
        maintenanceExecutor.execute(this::migrateLegacyDataIfNeeded);
    }

    public void clearAllAsync() {
        maintenanceExecutor.execute(() -> {
            database.glucoseDao().deleteAll();
            database.syncEventDao().deleteAll();
        });
    }

    public void migrateLegacyDataIfNeeded() {
        SharedPreferences prefs = SecurePrefs.prefs(appContext);

        if (prefs.getBoolean(MIGRATION_KEY, false)) return;

        List<GlucoseReadingEntity> legacy = readLegacyHistory(prefs);

        if (!legacy.isEmpty()) {
            database.glucoseDao().insertAll(legacy);
        }

        prefs.edit()
                .putBoolean(MIGRATION_KEY, true)
                .putInt("core_schema_version", 1)
                .apply();

        prune();
    }

    public void persistFetchResult(LibreApiClient.FetchResult result) {
        if (result == null || result.current == null) return;

        migrateLegacyDataIfNeeded();

        List<GlucoseReadingEntity> entities = new ArrayList<>();

        for (LibreApiClient.Reading reading : result.history) {
            GlucoseReadingEntity entity = entityFromReading(
                    reading,
                    "LIBRELINKUP"
            );
            if (entity != null) entities.add(entity);
        }

        if (entities.isEmpty()) {
            GlucoseReadingEntity entity = entityFromReading(
                    result.current,
                    "LIBRELINKUP"
            );
            if (entity != null) entities.add(entity);
        }

        if (!entities.isEmpty()) {
            database.glucoseDao().insertAll(entities);
        }

        writeCompatibilityCache(result);
        prune();
    }

    public void recordSyncEvent(
            SyncStatus status,
            String message,
            long durationMs
    ) {
        SyncEventEntity event = new SyncEventEntity();
        event.timestampMs = System.currentTimeMillis();
        event.status = status.name();
        event.message = message == null ? "" : message;
        event.durationMs = Math.max(0L, durationMs);

        database.syncEventDao().insert(event);
    }

    public GlucoseReadingEntity latest() {
        return database.glucoseDao().latest();
    }

    public List<GlucoseReadingEntity> recent(int limit) {
        List<GlucoseReadingEntity> rows =
                database.glucoseDao().recentDescending(Math.max(1, limit));

        if (rows == null) return Collections.emptyList();

        Collections.reverse(rows);
        return rows;
    }

    public List<GlucoseReadingEntity> between(long fromMs, long toMs) {
        List<GlucoseReadingEntity> rows =
                database.glucoseDao().between(fromMs, toMs);

        return rows == null ? Collections.emptyList() : rows;
    }

    private void writeCompatibilityCache(LibreApiClient.FetchResult result) {
        StringBuilder values = new StringBuilder();
        StringBuilder points = new StringBuilder();

        for (LibreApiClient.Reading reading : result.history) {
            if (values.length() > 0) values.append(';');
            values.append(String.format(Locale.US, "%.1f", reading.mgdl));

            if (points.length() > 0) points.append(';');
            points.append(reading.timestampMs)
                    .append(',')
                    .append(String.format(Locale.US, "%.1f", reading.mgdl));
        }

        SecurePrefs.prefs(appContext).edit()
                .putString("last_value", result.current.displayValue())
                .putInt("last_trend", result.current.trend)
                .putLong("last_sensor_ms", result.current.timestampMs)
                .putString("patient_name", result.patientName)
                .putString("history_values", values.toString())
                .putString("history_points", points.toString())
                .apply();

        LibreMirrorWidgetProvider.updateAll(appContext);
    }

    private List<GlucoseReadingEntity> readLegacyHistory(
            SharedPreferences prefs
    ) {
        List<GlucoseReadingEntity> result = new ArrayList<>();

        String points = prefs.getString("history_points", "");

        if (points != null && !points.trim().isEmpty()) {
            String[] rows = points.split(";");

            for (String row : rows) {
                String[] parts = row.split(",");
                if (parts.length != 2) continue;

                try {
                    GlucoseReadingEntity entity = new GlucoseReadingEntity();
                    entity.timestampMs = Long.parseLong(parts[0]);
                    entity.mgdl = Double.parseDouble(
                            parts[1].replace(',', '.')
                    );
                    entity.trend = 0;
                    entity.source = "LEGACY_V1_3";
                    entity.receivedAtMs = entity.timestampMs;
                    result.add(entity);
                } catch (Exception ignored) {
                }
            }
        }

        if (result.isEmpty()) {
            String values = prefs.getString("history_values", "");
            long lastSensorMs = prefs.getLong(
                    "last_sensor_ms",
                    System.currentTimeMillis()
            );

            if (values != null && !values.trim().isEmpty()) {
                String[] rows = values.split(";");
                long stepMs = 5L * 60L * 1000L;
                long startMs = lastSensorMs
                        - Math.max(0, rows.length - 1) * stepMs;

                for (int i = 0; i < rows.length; i++) {
                    try {
                        GlucoseReadingEntity entity =
                                new GlucoseReadingEntity();
                        entity.timestampMs = startMs + i * stepMs;
                        entity.mgdl = Double.parseDouble(
                                rows[i].replace(',', '.')
                        );
                        entity.trend = 0;
                        entity.source = "LEGACY_V1_3_ESTIMATED_TIME";
                        entity.receivedAtMs = entity.timestampMs;
                        result.add(entity);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        if (!result.isEmpty()) {
            int lastTrend = prefs.getInt("last_trend", 0);
            result.get(result.size() - 1).trend = lastTrend;
        }

        return result;
    }

    private static GlucoseReadingEntity entityFromReading(
            LibreApiClient.Reading reading,
            String source
    ) {
        if (reading == null || reading.timestampMs <= 0L) return null;

        GlucoseReadingEntity entity = new GlucoseReadingEntity();
        entity.timestampMs = reading.timestampMs;
        entity.mgdl = reading.mgdl;
        entity.trend = reading.trend;
        entity.source = source;
        entity.receivedAtMs = System.currentTimeMillis();
        return entity;
    }

    private void prune() {
        long now = System.currentTimeMillis();
        database.glucoseDao().deleteOlderThan(now - RETENTION_MS);
        database.syncEventDao().deleteOlderThan(now - EVENT_RETENTION_MS);
    }
}
