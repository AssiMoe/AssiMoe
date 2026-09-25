package de.assimoe.libremirror;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

public class LibreMirrorWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(
            Context context,
            AppWidgetManager appWidgetManager,
            int[] appWidgetIds
    ) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName provider = new ComponentName(
                context,
                LibreMirrorWidgetProvider.class
        );

        int[] ids = manager.getAppWidgetIds(provider);

        for (int id : ids) {
            updateWidget(context, manager, id);
        }
    }

    private static void updateWidget(
            Context context,
            AppWidgetManager manager,
            int appWidgetId
    ) {
        SharedPreferences prefs = SecurePrefs.prefs(context);

        String value = prefs.getString("last_value", "");
        int trend = prefs.getInt("last_trend", 0);
        long sensorMs = prefs.getLong("last_sensor_ms", 0L);
        String error = prefs.getString("last_error", "");

        RemoteViews views = new RemoteViews(
                context.getPackageName(),
                R.layout.widget_libremirror
        );

        String mainValue = value.isEmpty()
                ? "—"
                : value + " " + LibreApiClient.arrow(trend);

        String subtitle;

        if (value.isEmpty()) {
            subtitle = "Noch kein Glukosewert";
        } else if (!error.isEmpty()) {
            subtitle = "Letzter Wert • Verbindung prüfen";
        } else if (sensorMs > 0L) {
            subtitle = "vor " + ageText(System.currentTimeMillis() - sensorMs);
        } else {
            subtitle = "LibreMirror";
        }

        views.setTextViewText(R.id.widget_value, mainValue);
        views.setTextViewText(R.id.widget_unit, value.isEmpty() ? "" : "mg/dL");
        views.setTextViewText(R.id.widget_status, subtitle);

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );

        PendingIntent pending = PendingIntent.getActivity(
                context,
                appWidgetId,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );

        views.setOnClickPendingIntent(R.id.widget_root, pending);
        manager.updateAppWidget(appWidgetId, views);
    }

    private static String ageText(long ageMs) {
        long minutes = Math.max(0L, ageMs / 60_000L);

        if (minutes <= 0L) return "< 1 Min.";
        if (minutes == 1L) return "1 Min.";
        return minutes + " Min.";
    }
}
