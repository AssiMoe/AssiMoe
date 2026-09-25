package de.assimoe.libremirror;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FullGlucoseChartView extends View {
    private final List<Long> times = new ArrayList<>();
    private final List<Float> values = new ArrayList<>();

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rangePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean darkMode = true;
    private float low = 70f;
    private float high = 180f;

    public FullGlucoseChartView(Context context) {
        super(context);
        init();
    }

    public FullGlucoseChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2.8f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(0.8f));

        textPaint.setTextSize(dp(11f));

        lowPaint.setStyle(Paint.Style.STROKE);
        lowPaint.setStrokeWidth(dp(1.6f));
        lowPaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{dp(6), dp(5)}, 0
        ));

        highPaint.setStyle(Paint.Style.STROKE);
        highPaint.setStrokeWidth(dp(1.6f));
        highPaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{dp(6), dp(5)}, 0
        ));

        pointPaint.setStyle(Paint.Style.FILL);
        fillPaint.setStyle(Paint.Style.FILL);

        setDarkMode(true);
    }

    public void setDarkMode(boolean darkMode) {
        this.darkMode = darkMode;

        linePaint.setColor(darkMode ? 0xFF22B6FF : 0xFF0B8FEA);
        gridPaint.setColor(darkMode ? 0x33486278 : 0x224B7296);
        textPaint.setColor(darkMode ? 0xFF8FA7BC : 0xFF68819A);
        rangePaint.setColor(darkMode ? 0x3321C881 : 0x2221A66B);
        lowPaint.setColor(0xFFFF5869);
        highPaint.setColor(0xFFFFAA1C);
        pointPaint.setColor(darkMode ? 0xFF22D3EE : 0xFF149CFF);

        invalidate();
    }

    public void setData(
            List<Long> newTimes,
            List<Float> newValues,
            float low,
            float high
    ) {
        times.clear();
        values.clear();

        if (newTimes != null) times.addAll(newTimes);
        if (newValues != null) values.addAll(newValues);

        this.low = low;
        this.high = high;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        float left = dp(46);
        float right = width - dp(10);
        float top = dp(18);
        float bottom = height - dp(36);

        float chartWidth = Math.max(1f, right - left);
        float chartHeight = Math.max(1f, bottom - top);

        float minY = 50f;
        float maxY = 350f;

        if (!values.isEmpty()) {
            float minValue = Float.MAX_VALUE;
            float maxValue = -Float.MAX_VALUE;

            for (float value : values) {
                minValue = Math.min(minValue, value);
                maxValue = Math.max(maxValue, value);
            }

            minY = Math.min(50f, (float) Math.floor((minValue - 20f) / 10f) * 10f);
            maxY = Math.max(250f, (float) Math.ceil((maxValue + 30f) / 10f) * 10f);

            minY = Math.max(0f, minY);
            maxY = Math.min(450f, maxY);
        }

        drawTargetRange(canvas, left, right, top, chartHeight, minY, maxY);
        drawGrid(canvas, left, right, top, bottom, minY, maxY);

        if (values.size() < 2) {
            Paint empty = new Paint(Paint.ANTI_ALIAS_FLAG);
            empty.setColor(darkMode ? 0xFF607A90 : 0xFF7A8DA0);
            empty.setTextSize(dp(13));
            empty.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(
                    "Noch nicht genug Verlaufsdaten",
                    left + chartWidth / 2f,
                    top + chartHeight / 2f,
                    empty
            );
            return;
        }

        long firstTime = times.isEmpty() ? 0L : times.get(0);
        long lastTime = times.isEmpty() ? 0L : times.get(times.size() - 1);
        boolean hasTimes = times.size() == values.size() && lastTime > firstTime;

        Path line = new Path();

        for (int i = 0; i < values.size(); i++) {
            float x;

            if (hasTimes) {
                float ratio = (times.get(i) - firstTime)
                        / (float) Math.max(1L, lastTime - firstTime);
                x = left + ratio * chartWidth;
            } else {
                x = left + i * chartWidth / Math.max(1f, values.size() - 1f);
            }

            float y = mapY(values.get(i), minY, maxY, top, chartHeight);

            if (i == 0) {
                line.moveTo(x, y);
            } else {
                float prevX;

                if (hasTimes) {
                    float prevRatio = (times.get(i - 1) - firstTime)
                            / (float) Math.max(1L, lastTime - firstTime);
                    prevX = left + prevRatio * chartWidth;
                } else {
                    prevX = left + (i - 1) * chartWidth
                            / Math.max(1f, values.size() - 1f);
                }

                float prevY = mapY(
                        values.get(i - 1),
                        minY,
                        maxY,
                        top,
                        chartHeight
                );

                float mid = (prevX + x) / 2f;
                line.cubicTo(mid, prevY, mid, y, x, y);
            }
        }

        Path fill = new Path(line);
        fill.lineTo(right, bottom);
        fill.lineTo(left, bottom);
        fill.close();

        fillPaint.setShader(new LinearGradient(
                0,
                top,
                0,
                bottom,
                darkMode ? 0x3022B6FF : 0x25149CFF,
                darkMode ? 0x0022B6FF : 0x00149CFF,
                Shader.TileMode.CLAMP
        ));

        canvas.drawPath(fill, fillPaint);
        canvas.drawPath(line, linePaint);

        float lastX = right;
        if (hasTimes) {
            float ratio = (times.get(times.size() - 1) - firstTime)
                    / (float) Math.max(1L, lastTime - firstTime);
            lastX = left + ratio * chartWidth;
        }

        float lastY = mapY(
                values.get(values.size() - 1),
                minY,
                maxY,
                top,
                chartHeight
        );

        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setStyle(Paint.Style.FILL);
        halo.setColor(darkMode ? 0xFF0D1B2A : 0xFFFFFFFF);
        canvas.drawCircle(lastX, lastY, dp(7), halo);
        canvas.drawCircle(lastX, lastY, dp(5), pointPaint);

        drawTimeLabels(canvas, left, right, bottom, firstTime, lastTime, hasTimes);
    }

    private void drawTargetRange(
            Canvas canvas,
            float left,
            float right,
            float top,
            float chartHeight,
            float minY,
            float maxY
    ) {
        float highY = mapY(high, minY, maxY, top, chartHeight);
        float lowY = mapY(low, minY, maxY, top, chartHeight);

        canvas.drawRect(left, highY, right, lowY, rangePaint);
        canvas.drawLine(left, lowY, right, lowY, lowPaint);
        canvas.drawLine(left, highY, right, highY, highPaint);
    }

    private void drawGrid(
            Canvas canvas,
            float left,
            float right,
            float top,
            float bottom,
            float minY,
            float maxY
    ) {
        textPaint.setTextAlign(Paint.Align.RIGHT);

        int lines = 5;

        for (int i = 0; i <= lines; i++) {
            float ratio = i / (float) lines;
            float y = top + ratio * (bottom - top);
            float value = maxY - ratio * (maxY - minY);

            canvas.drawLine(left, y, right, y, gridPaint);
            canvas.drawText(
                    String.format(Locale.getDefault(), "%.0f", value),
                    left - dp(7),
                    y + dp(4),
                    textPaint
            );
        }
    }

    private void drawTimeLabels(
            Canvas canvas,
            float left,
            float right,
            float bottom,
            long firstTime,
            long lastTime,
            boolean hasTimes
    ) {
        textPaint.setTextAlign(Paint.Align.CENTER);

        SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.getDefault());

        for (int i = 0; i < 4; i++) {
            float ratio = i / 3f;
            float x = left + ratio * (right - left);

            String label;

            if (hasTimes) {
                long time = firstTime + (long) ((lastTime - firstTime) * ratio);
                label = format.format(new Date(time));
            } else {
                label = i == 0 ? "Start" : i == 3 ? "Jetzt" : "•";
            }

            canvas.drawText(label, x, bottom + dp(24), textPaint);
        }
    }

    private float mapY(
            float value,
            float min,
            float max,
            float top,
            float chartHeight
    ) {
        float ratio = (value - min) / Math.max(1f, max - min);
        ratio = Math.max(0f, Math.min(1f, ratio));
        return top + (1f - ratio) * chartHeight;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
