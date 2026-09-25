package de.assimoe.libremirror;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class JugglucoReceiver extends BroadcastReceiver {
    private static final String ACTION = "glucodata.Minute";
    private static final String MGDL = "glucodata.Minute.mgdl";
    private static final String RATE = "glucodata.Minute.Rate";
    private static final String SERIAL = "glucodata.Minute.SerialNumber";
    private static final String TIME = "glucodata.Minute.Time";

    private static final String CH_GLUCOSE = "libremirror_glucose";
    private static final String CH_ALERTS = "libremirror_alerts";
    private static final int ID_GLUCOSE = 1002;
    private static final int ID_ALERT = 1003;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;

        Bundle extras = intent.getExtras();
        if (extras == null) return;

        int mgdl = extras.getInt(MGDL, 0);
        float rate = extras.getFloat(RATE, Float.NaN);
        String serial = extras.getString(SERIAL, "");
        long sensorTime = extras.getLong(TIME, System.currentTimeMillis());

        if (mgdl <= 0) return;

        SharedPreferences prefs = SecurePrefs.prefs(context);
        long now = System.currentTimeMillis();
        int trend = trendFromRate(rate);
        String sensorTimeText = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date(sensorTime));

        prefs.edit()
                .putLong("juggluco_last_seen_ms", now)
                .putInt("juggluco_last_mgdl", mgdl)
                .putFloat("juggluco_last_rate", rate)
                .putString("juggluco_last_serial", serial)
                .putLong("juggluco_last_sensor_ms", sensorTime)
                .apply();

        if (!"JUGGLUCO".equals(prefs.getString("source", "JUGGLUCO"))) return;

        prefs.edit()
                .putString("last_value", String.valueOf(mgdl))
                .putString("last_unit", "mg/dL")
                .putInt("last_trend", trend)
                .putString("last_sensor_time", sensorTimeText)
                .putLong("last_fetch_ms", now)
                .putString("last_error", "")
                .putString("last_source", "JUGGLUCO")
                .putString("sensor_serial", serial)
                .apply();

        appendHistory(prefs, mgdl);
        createChannels(context);
        showGlucoseNotification(context, mgdl, trend, sensorTimeText);
        checkAlert(context, prefs, mgdl, trend);

        if (prefs.getBoolean("enabled", false)) {
            try {
                Intent service = new Intent(context, LibreService.class);
                service.setAction(LibreService.ACTION_JUGGLUCO_READING);
                service.putExtra("mgdl", mgdl);
                service.putExtra("trend", trend);
                service.putExtra("rate", rate);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(service);
                } else {
                    context.startService(service);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void appendHistory(SharedPreferences prefs, int mgdl) {
        String raw = prefs.getString("history_values", "");
        String next = raw.isEmpty() ? String.valueOf(mgdl) : raw + ";" + mgdl;
        String[] parts = next.split(";");

        if (parts.length > 48) {
            StringBuilder trimmed = new StringBuilder();
            for (int i = parts.length - 48; i < parts.length; i++) {
                if (trimmed.length() > 0) trimmed.append(';');
                trimmed.append(parts[i]);
            }
            next = trimmed.toString();
        }

        prefs.edit().putString("history_values", next).apply();
    }

    private static void createChannels(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel glucose = new NotificationChannel(
                CH_GLUCOSE,
                "Aktueller Glukosewert",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        glucose.setSound(null, null);
        glucose.enableVibration(false);
        glucose.setDescription("Aktueller Libre-Wert für Handy und Smartwatch.");
        nm.createNotificationChannel(glucose);

        NotificationChannel alerts = new NotificationChannel(
                CH_ALERTS,
                "Glukosewarnungen",
                NotificationManager.IMPORTANCE_HIGH
        );
        alerts.enableVibration(true);
        nm.createNotificationChannel(alerts);
    }

    private static void showGlucoseNotification(Context context, int mgdl, int trend, String time) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        Notification notification = new Notification.Builder(context, CH_GLUCOSE)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(mgdl + " mg/dL  " + LibreApiClient.arrow(trend))
                .setContentText("Libre 3 direkt • Sensor " + time)
                .setContentIntent(openAppIntent(context))
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();

        nm.notify(ID_GLUCOSE, notification);
    }

    private static void checkAlert(Context context, SharedPreferences prefs, int mgdl, int trend) {
        double low = parseDouble(prefs.getString("low", "70"), 70);
        double high = parseDouble(prefs.getString("high", "180"), 180);

        String state = mgdl < low ? "LOW" : (mgdl > high ? "HIGH" : "OK");
        String previous = prefs.getString("direct_alert_state", "");

        if ("OK".equals(state)) {
            prefs.edit().putString("direct_alert_state", "OK").apply();
            return;
        }
        if (state.equals(previous)) return;

        prefs.edit().putString("direct_alert_state", state).apply();

        String title = "LOW".equals(state) ? "Glukose niedrig" : "Glukose hoch";

        Notification notification = new Notification.Builder(context, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(mgdl + " mg/dL " + LibreApiClient.arrow(trend) + " • Libre 3 direkt")
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();

        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(ID_ALERT, notification);
    }

    private static PendingIntent openAppIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, 0, intent, flags);
    }

    private static int trendFromRate(float rate) {
        if (Float.isNaN(rate)) return 0;
        if (rate <= -2.0f) return 1;
        if (rate <= -1.0f) return 2;
        if (rate <= 1.0f) return 3;
        if (rate <= 2.0f) return 4;
        return 5;
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.replace(',', '.'));
        } catch (Exception e) {
            return fallback;
        }
    }
}
