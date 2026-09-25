package de.assimoe.libremirror;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class GlucoseChartView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Float> values = new ArrayList<>();
    private boolean darkMode = true;

    public GlucoseChartView(Context context) {
        super(context);
        init();
    }

    public GlucoseChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2.4f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(0xFF39A9FF);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(0.8f));
        gridPaint.setColor(0x1A3A7BD5);

        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setDarkMode(boolean darkMode) {
        this.darkMode = darkMode;
        linePaint.setColor(darkMode ? 0xFF22B6FF : 0xFF149CFF);
        gridPaint.setColor(darkMode ? 0x263B5A73 : 0x1A3A7BD5);
        invalidate();
    }

    public void setValues(List<Float> newValues) {
        values.clear();
        if (newValues != null) values.addAll(newValues);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        for (int i = 1; i <= 3; i++) {
            float y = h * i / 4f;
            canvas.drawLine(0, y, w, y, gridPaint);
        }

        List<Float> data = values;
        if (data.size() < 2) {
            return;
        }

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float v : data) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (Math.abs(max - min) < 12f) {
            min -= 6f;
            max += 6f;
        } else {
            float pad = (max - min) * 0.18f;
            min -= pad;
            max += pad;
        }

        float top = dp(8);
        float bottom = h - dp(4);
        float usableH = Math.max(1, bottom - top);
        float step = data.size() == 1 ? w : w / (data.size() - 1f);

        Path line = new Path();
        float firstY = mapY(data.get(0), min, max, top, usableH);
        line.moveTo(0, firstY);

        for (int i = 1; i < data.size(); i++) {
            float x = step * i;
            float y = mapY(data.get(i), min, max, top, usableH);
            float prevX = step * (i - 1);
            float prevY = mapY(data.get(i - 1), min, max, top, usableH);
            float midX = (prevX + x) / 2f;
            line.cubicTo(midX, prevY, midX, y, x, y);
        }

        Path fill = new Path(line);
        fill.lineTo(w, h);
        fill.lineTo(0, h);
        fill.close();

        fillPaint.setShader(new LinearGradient(
                0, 0, 0, h,
                darkMode ? 0x4022B6FF : 0x40149CFF,
                darkMode ? 0x0022B6FF : 0x00149CFF,
                Shader.TileMode.CLAMP
        ));

        canvas.drawPath(fill, fillPaint);
        canvas.drawPath(line, linePaint);
    }

    private float mapY(float value, float min, float max, float top, float usableH) {
        float ratio = (value - min) / Math.max(1f, max - min);
        return top + (1f - ratio) * usableH;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
