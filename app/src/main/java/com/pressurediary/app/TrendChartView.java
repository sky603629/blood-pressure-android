package com.pressurediary.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class TrendChartView extends View {
    private static final int INK = 0xFF69766F;
    private static final int GRID = 0xFFE7ECE9;
    private static final int SYSTOLIC = 0xFFD66B51;
    private static final int DIASTOLIC = 0xFF4886B8;
    private static final int PULSE = 0xFFB1811E;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("M月d日", Locale.CHINA);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private List<Measurement> visibleData = Collections.emptyList();
    private Measurement selectedMeasurement;
    private OnMeasurementSelectedListener selectionListener;
    private int days = 7;
    private boolean showPulse;
    private long rangeStart;
    private long rangeEnd;
    private boolean singleDay;
    private float plotLeft;
    private float plotRight;
    private float plotTop;
    private float plotBottom;
    private int plotMinValue;
    private int plotMaxValue;

    interface OnMeasurementSelectedListener {
        void onSelected(Measurement measurement, int index, int count);
    }

    TrendChartView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    TrendChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setData(List<Measurement> data, int days, boolean showPulse) {
        setData(data, days, showPulse, null, null);
    }

    void setData(List<Measurement> data, int days, boolean showPulse, Long selectedStart, Long selectedEnd) {
        this.days = days;
        this.showPulse = showPulse;
        singleDay = selectedStart != null && selectedEnd != null;
        rangeStart = singleDay ? selectedStart : System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
        rangeEnd = singleDay ? selectedEnd : System.currentTimeMillis();
        List<Measurement> filtered = new ArrayList<>();
        for (int i = data.size() - 1; i >= 0; i--) {
            Measurement item = data.get(i);
            if (item.measuredAt >= rangeStart && item.measuredAt < rangeEnd) filtered.add(item);
        }
        visibleData = filtered;
        selectedMeasurement = visibleData.isEmpty() ? null : visibleData.get(visibleData.size() - 1);
        String rangeDescription = singleDay ? "所选日期" : "最近" + days + "天";
        setContentDescription((showPulse ? "脉搏" : "收缩压和舒张压") + "趋势图，" + rangeDescription +
                "，共" + visibleData.size() + "条测量记录");
        invalidate();
        notifySelection();
    }

    void setOnMeasurementSelectedListener(OnMeasurementSelectedListener listener) {
        selectionListener = listener;
        notifySelection();
    }

    private void notifySelection() {
        if (selectionListener == null) return;
        int index = selectedMeasurement == null ? -1 : visibleData.indexOf(selectedMeasurement);
        selectionListener.onSelected(selectedMeasurement, index, visibleData.size());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float fontScale = getResources().getDisplayMetrics().scaledDensity;
        float left = 42 * density;
        float right = getWidth() - 10 * density;
        float top = 14 * density;
        float bottom = getHeight() - 31 * density;
        if (right <= left || bottom <= top) return;

        if (visibleData.isEmpty()) {
            paint.setColor(INK);
            paint.setTextSize(14 * fontScale);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(singleDay ? "这一天暂无测量记录" : "暂无这段时间的记录",
                    getWidth() / 2f, getHeight() / 2f, paint);
            return;
        }

        int minValue = Integer.MAX_VALUE;
        int maxValue = Integer.MIN_VALUE;
        for (Measurement item : visibleData) {
            minValue = Math.min(minValue, showPulse ? item.pulse : item.diastolic);
            maxValue = Math.max(maxValue, showPulse ? item.pulse : item.systolic);
        }
        int step = showPulse ? 10 : 20;
        int min = Math.max(showPulse ? 30 : 40, (minValue / step) * step - step);
        int max = ((maxValue + step - 1) / step) * step + step;
        if (max <= min) max = min + step * 2;
        plotLeft = left;
        plotRight = right;
        plotTop = top;
        plotBottom = bottom;
        plotMinValue = min;
        plotMaxValue = max;

        paint.setStrokeWidth(1 * density);
        paint.setColor(GRID);
        paint.setStyle(Paint.Style.STROKE);
        paint.setTextSize(13 * fontScale);
        paint.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 3; i++) {
            float y = top + (bottom - top) * i / 3f;
            canvas.drawLine(left, y, right, y, paint);
            int label = max - (max - min) * i / 3;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(INK);
            canvas.drawText(String.valueOf(label), left - 8 * density, y + 4 * density, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(GRID);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(INK);
        paint.setTextAlign(Paint.Align.CENTER);
        int intervalCount = singleDay ? 4 : 3;
        for (int i = 0; i <= intervalCount; i++) {
            long tick = rangeStart + (rangeEnd - rangeStart) * i / intervalCount;
            float x = left + (right - left) * i / (float) intervalCount;
            String tickLabel = singleDay
                    ? (i == intervalCount ? "24:00" : timeFormat.format(new Date(tick)))
                    : dayFormat.format(new Date(tick));
            canvas.drawText(tickLabel, x, getHeight() - 8 * density, paint);
        }

        if (selectedMeasurement != null) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0x66818D86);
            paint.setStrokeWidth(1 * density);
            float selectedX = xFor(selectedMeasurement, visibleData, left, right);
            canvas.drawLine(selectedX, top, selectedX, bottom, paint);
        }

        if (showPulse) {
            drawSeries(canvas, visibleData, min, max, density, PULSE, 2);
        } else {
            drawSeries(canvas, visibleData, min, max, density, SYSTOLIC, 0);
            drawSeries(canvas, visibleData, min, max, density, DIASTOLIC, 1);
        }
    }

    private void drawSeries(Canvas canvas, List<Measurement> items, int min, int max,
                            float density, int color, int field) {
        Path path = new Path();
        List<float[]> points = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Measurement item = items.get(i);
            int value = field == 0 ? item.systolic : field == 1 ? item.diastolic : item.pulse;
            float x = xFor(item, items, plotLeft, plotRight);
            float y = yFor(value, min, max);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            points.add(new float[]{x, y});
        }
        paint.setColor(color);
        paint.setStrokeWidth(2.5f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        if (points.size() > 1) canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < points.size(); i++) {
            float[] point = points.get(i);
            if (selectedMeasurement != null && items.get(i).id == selectedMeasurement.id) {
                paint.setColor(color);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2 * density);
                canvas.drawCircle(point[0], point[1], 8 * density, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            canvas.drawCircle(point[0], point[1], 4 * density, paint);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(point[0], point[1], 1.8f * density, paint);
            paint.setColor(color);
        }
    }

    private float xFor(Measurement item, List<Measurement> items, float left, float right) {
        if (rangeEnd <= rangeStart) return (left + right) / 2f;
        float fraction = Math.max(0, Math.min(1,
                (item.measuredAt - rangeStart) / (float) (rangeEnd - rangeStart)));
        return left + (right - left) * fraction;
    }

    private float yFor(int value, int min, int max) {
        return plotBottom - (plotBottom - plotTop) * (value - min) / (float) (max - min);
    }

    private Measurement nearestMeasurement(float x, float y) {
        if (visibleData.isEmpty() || plotRight <= plotLeft || plotMaxValue <= plotMinValue) return null;
        float bestDistance = Float.MAX_VALUE;
        Measurement nearest = null;
        for (Measurement item : visibleData) {
            float pointX = xFor(item, visibleData, plotLeft, plotRight);
            int[] values = showPulse
                    ? new int[]{item.pulse}
                    : new int[]{item.systolic, item.diastolic};
            for (int value : values) {
                float dx = pointX - x;
                float dy = yFor(value, plotMinValue, plotMaxValue) - y;
                float distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearest = item;
                }
            }
        }
        float hitRadius = 40 * getResources().getDisplayMetrics().density;
        return bestDistance <= hitRadius * hitRadius ? nearest : null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (event.getAction() == MotionEvent.ACTION_UP) {
            Measurement nearest = nearestMeasurement(event.getX(), event.getY());
            if (nearest != null && nearest != selectedMeasurement) {
                selectedMeasurement = nearest;
                invalidate();
                notifySelection();
            }
            performClick();
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
