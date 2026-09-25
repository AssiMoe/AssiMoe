package de.assimoe.libremirror;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class WidgetRenderer {
    static final int STYLE_MINI = 1;
    static final int STYLE_CLEAN = 2;
    static final int STYLE_COMPACT = 3;
    static final int STYLE_LARGE = 4;
    static final int STYLE_ALERT = 5;

    private WidgetRenderer() {}

    static void update(Context context, AppWidgetManager manager, int appWidgetId, int style) {
        SharedPreferences prefs = SecurePrefs.prefs(context);

        String value = prefs.getString("last_value", "");
        int trend = prefs.getInt("last_trend", 0);
        long sensorMs = prefs.getLong("last_sensor_ms", 0L);
        String error = prefs.getString("last_error", "");
        double low = parseDouble(prefs.getString("low", "70"), 70.0);
        double high = parseDouble(prefs.getString("high", "180"), 180.0);
        boolean dark = prefs.getBoolean("dark_mode", true);

        int layout = layoutForStyle(style);
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);

        String arrow = LibreApiClient.arrow(trend);
        String display = value.isEmpty() ? "—" : value;
        String age = value.isEmpty()
                ? "Noch kein Glukosewert"
                : sensorMs > 0L
                ? "vor " + ageText(System.currentTimeMillis() - sensorMs)
                : "LibreMirror";

        String status = error.isEmpty() ? age : "Letzter Wert • Verbindung prüfen";

        if (style == STYLE_MINI) {
            views.setTextViewText(R.id.widget_value, display);
            views.setTextViewText(R.id.widget_arrow, value.isEmpty() ? "" : arrow);
            views.setTextViewText(R.id.widget_unit, value.isEmpty() ? "" : "mg/dL");
            views.setTextViewText(R.id.widget_status, status);
        } else if (style == STYLE_CLEAN) {
            views.setTextViewText(R.id.widget_title, "LibreMirror");
            views.setTextViewText(R.id.widget_value, display);
            views.setTextViewText(R.id.widget_arrow, value.isEmpty() ? "" : arrow);
            views.setTextViewText(R.id.widget_unit, value.isEmpty() ? "" : "mg/dL");
            views.setTextViewText(R.id.widget_status, status);
        } else if (style == STYLE_COMPACT) {
            views.setTextViewText(R.id.widget_title, "LibreMirror");
            views.setTextViewText(R.id.widget_value, display);
            views.setTextViewText(R.id.widget_arrow, value.isEmpty() ? "" : arrow);
            views.setTextViewText(R.id.widget_unit, value.isEmpty() ? "" : "mg/dL");
            views.setTextViewText(R.id.widget_status, status);
            views.setTextViewText(
                    R.id.widget_trend,
                    value.isEmpty() ? "Kein Wert" : LibreApiClient.trendLabel(trend)
            );
        } else if (style == STYLE_LARGE) {
            views.setTextViewText(R.id.widget_title, "LibreMirror");
            views.setTextViewText(R.id.widget_value, display);
            views.setTextViewText(R.id.widget_arrow, value.isEmpty() ? "" : arrow);
            views.setTextViewText(R.id.widget_unit, value.isEmpty() ? "" : "mg/dL");
            views.setTextViewText(R.id.widget_status, status);

            List<Float> history = readHistory(prefs.getString("history_values", ""));
            Bitmap chart = renderChart(history, dark);
            views.setImageViewBitmap(R.id.widget_chart, chart);
        } else {
            double numeric = parseDouble(value, Double.NaN);
            int background;
            String label;

            if (Double.isNaN(numeric)) {
                background = R.drawable.widget_alert_neutral;
                label = "Kein Wert";
            } else if (numeric < low) {
                background = R.drawable.widget_alert_low;
                label = "NIEDRIG";
            } else if (numeric > high) {
                background = R.drawable.widget_alert_high;
                label = "HOCH";
            } else {
                background = R.drawable.widget_alert_ok;
                label = "IM BEREICH";
            }

            views.setInt(R.id.widget_root, "setBackgroundResource", background);
            views.setTextViewText(R.id.widget_alert_label, label);
            views.setTextViewText(R.id.widget_value, display);
            views.setTextViewText(R.id.widget_arrow, value.isEmpty() ? "" : arrow);
            views.setTextViewText(R.id.widget_unit, value.isEmpty() ? "" : "mg/dL");
            views.setTextViewText(R.id.widget_status, status);
        }

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pending = PendingIntent.getActivity(
                context,
                appWidgetId + style * 10000,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        views.setOnClickPendingIntent(R.id.widget_root, pending);
        manager.updateAppWidget(appWidgetId, views);
    }

    private static int layoutForStyle(int style) {
        switch (style) {
            case STYLE_MINI: return R.layout.widget_mini;
            case STYLE_CLEAN: return R.layout.widget_clean;
            case STYLE_LARGE: return R.layout.widget_large;
            case STYLE_ALERT: return R.layout.widget_alert;
            case STYLE_COMPACT:
            default: return R.layout.widget_compact;
        }
    }

    private static Bitmap renderChart(List<Float> values, boolean dark) {
        int width = 700;
        int height = 220;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        if (values.size() < 2) {
            Paint empty = new Paint(Paint.ANTI_ALIAS_FLAG);
            empty.setColor(dark ? 0xFF7890A6 : 0xFF6A7F95);
            empty.setTextSize(30f);
            empty.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Noch nicht genug Verlaufsdaten", width / 2f, height / 2f, empty);
            return bitmap;
        }

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }

        if (Math.abs(max - min) < 12f) {
            float mid = (max + min) / 2f;
            min = mid - 6f;
            max = mid + 6f;
        } else {
            min -= 8f;
            max += 8f;
        }

        float left = 8f;
        float right = width - 8f;
        float top = 12f;
        float bottom = height - 12f;

        Path line = new Path();

        for (int i = 0; i < values.size(); i++) {
            float x = left + i * (right - left) / Math.max(1f, values.size() - 1f);
            float ratio = (values.get(i) - min) / Math.max(1f, max - min);
            float y = bottom - ratio * (bottom - top);

            if (i == 0) line.moveTo(x, y);
            else {
                float prevX = left + (i - 1) * (right - left) / Math.max(1f, values.size() - 1f);
                float prevRatio = (values.get(i - 1) - min) / Math.max(1f, max - min);
                float prevY = bottom - prevRatio * (bottom - top);
                float mid = (prevX + x) / 2f;
                line.cubicTo(mid, prevY, mid, y, x, y);
            }
        }

        Path fill = new Path(line);
        fill.lineTo(right, bottom);
        fill.lineTo(left, bottom);
        fill.close();

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setShader(new LinearGradient(
                0, top, 0, bottom,
                dark ? 0x5522B6FF : 0x44149CFF,
                dark ? 0x0022B6FF : 0x00149CFF,
                Shader.TileMode.CLAMP
        ));
        canvas.drawPath(fill, fillPaint);

        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(8f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(dark ? 0xFF22D3EE : 0xFF0B8FEA);
        canvas.drawPath(line, linePaint);

        return bitmap;
    }

    private static List<Float> readHistory(String raw) {
        List<Float> values = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return values;

        String[] parts = raw.split(";");
        for (String part : parts) {
            try {
                values.add(Float.parseFloat(part.replace(',', '.')));
            } catch (Exception ignored) {
            }
        }
        return values;
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Double.parseDouble(value.replace(',', '.'));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String ageText(long ageMs) {
        long minutes = Math.max(0L, ageMs / 60_000L);
        if (minutes <= 0L) return "< 1 Min.";
        if (minutes == 1L) return "1 Min.";
        return minutes + " Min.";
    }
}
