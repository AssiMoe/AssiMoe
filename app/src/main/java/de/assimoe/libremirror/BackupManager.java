package de.assimoe.libremirror;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class BackupManager {
    private BackupManager() {}

    public static void exportBackup(Context context, Uri uri) throws Exception {
        SharedPreferences prefs = SecurePrefs.prefs(context);
        JSONObject root = new JSONObject();
        root.put("format", "LibreMirrorBackup");
        root.put("version", 1);
        root.put("exported_at", System.currentTimeMillis());

        JSONObject settings = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("_iv") || key.endsWith("_data") || key.startsWith("session_")) {
                continue;
            }

            Object value = entry.getValue();
            if (value instanceof Boolean
                    || value instanceof Integer
                    || value instanceof Long
                    || value instanceof Float
                    || value instanceof String) {
                settings.put(key, value);
            }
        }
        root.put("settings", settings);

        JSONArray history = new JSONArray();
        HistoryDatabase db = new HistoryDatabase(context);
        long from = System.currentTimeMillis() - 90L * 24L * 60L * 60L * 1000L;
        List<HistoryDatabase.Point> points = db.query(from, Long.MAX_VALUE);
        for (HistoryDatabase.Point point : points) {
            JSONObject item = new JSONObject();
            item.put("timestamp_ms", point.timestampMs);
            item.put("mgdl", point.mgdl);
            item.put("trend", point.trend);
            history.put(item);
        }
        root.put("history", history);

        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new Exception("Backup-Datei konnte nicht geöffnet werden.");
            out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void importBackup(Context context, Uri uri) throws Exception {
        StringBuilder text = new StringBuilder();
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("Backup-Datei konnte nicht geöffnet werden.");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) text.append(line);
            }
        }

        JSONObject root = new JSONObject(text.toString());
        if (!"LibreMirrorBackup".equals(root.optString("format", ""))) {
            throw new Exception("Keine gültige LibreMirror-Backup-Datei.");
        }

        SharedPreferences.Editor editor = SecurePrefs.prefs(context).edit();
        JSONObject settings = root.optJSONObject("settings");
        if (settings != null) {
            JSONArray names = settings.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.optString(i, "");
                    if (key.isEmpty()) continue;

                    Object value = settings.opt(key);
                    if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                    else if (value instanceof Integer) editor.putInt(key, (Integer) value);
                    else if (value instanceof Long) editor.putLong(key, (Long) value);
                    else if (value instanceof Double) {
                        double d = (Double) value;
                        if (Math.rint(d) == d && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                            editor.putInt(key, (int) d);
                        } else {
                            editor.putString(key, String.valueOf(d));
                        }
                    } else if (value instanceof String) editor.putString(key, (String) value);
                }
            }
        }
        editor.apply();

        JSONArray history = root.optJSONArray("history");
        if (history != null) {
            HistoryDatabase db = new HistoryDatabase(context);
            for (int i = 0; i < history.length(); i++) {
                JSONObject item = history.optJSONObject(i);
                if (item == null) continue;
                long ts = item.optLong("timestamp_ms", 0L);
                double mgdl = item.optDouble("mgdl", Double.NaN);
                int trend = item.optInt("trend", 0);
                if (ts > 0L && !Double.isNaN(mgdl)) db.insert(ts, mgdl, trend);
            }
        }
    }
}
