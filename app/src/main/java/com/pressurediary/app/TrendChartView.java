package com.pressurediary.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class TrendChartView extends View {
    private static final int INK = 0xFF636366;
    private static final int GRID = 0xFFE5E5EA;
    private static final int ACCENT = 0xFF0066CC;
    private static final int SYSTOLIC = 0xFFBF5543;
    private static final int DIASTOLIC = 0xFF326CA5;
    private static final int PULSE = 0xFF966820;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path seriesPath = new Path();
    private final ChartViewport viewport = new ChartViewport();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("M月d日", Locale.CHINA);
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("h:mm", Locale.CHINA);
    private final float density;
    private final float fontScale;
    private final int touchSlop;
    private final DashPathEffect dashedLine;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;
    private final String[] tickDates = new String[3];
    private final String[] tickTimes = new String[3];
    private final int[] tickIndices = new int[3];
    private List<Measurement> data = Collections.emptyList();
    private int selectedIndex = -1;
    private int days;
    private Long selectedDay;
    private boolean showPulse;
    private OnMeasurementSelectedListener selectionListener;
    private OnViewportChangedListener viewportListener;
    private float left, right, top, bottom, overviewTop, overviewBottom;
    private int minValue, maxValue, valueStep;
    private float downX, downY, lastX;
    private boolean dragging, scrubbing, hadScale, verticalRejected, touchingOverview;

    interface OnMeasurementSelectedListener {
        void onSelected(Measurement measurement, int index, int count);
    }

    interface OnViewportChangedListener {
        void onChanged(int first, int last, int count, boolean canZoomIn, boolean canZoomOut);
    }

    TrendChartView(Context context) { this(context, null); }

    TrendChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        fontScale = getResources().getDisplayMetrics().scaledDensity;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        dashedLine = new DashPathEffect(new float[]{5 * density, 3 * density}, 0);
        setFocusable(true);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) {
                if (!dragging && !scrubbing && !hadScale && !verticalRejected) {
                    if (touchingOverview) centerOverview(event.getX());
                    else selectAt(event.getX());
                    performClick();
                }
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                zoom(2, plotFraction(event.getX()));
                return true;
            }
            @Override public void onLongPress(MotionEvent event) {
                if (!hadScale && !dragging && !verticalRejected && !touchingOverview) {
                    scrubbing = true;
                    selectAt(event.getX());
                }
            }
        });
        scaleGestures = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                        hadScale = true;
                        return data.size() > 1;
                    }
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        zoom(detector.getScaleFactor(), plotFraction(detector.getFocusX()));
                        return true;
                    }
                });
        scaleGestures.setQuickScaleEnabled(false);
    }

    void setData(List<Measurement> measurements, int days, boolean pulse, Long start, Long end) {
        boolean sameRange = this.days == days && (selectedDay == null ? start == null : selectedDay.equals(start));
        Calendar range = Calendar.getInstance();
        range.set(Calendar.HOUR_OF_DAY, 0);
        range.set(Calendar.MINUTE, 0);
        range.set(Calendar.SECOND, 0);
        range.set(Calendar.MILLISECOND, 0);
        range.add(Calendar.DAY_OF_MONTH, 1);
        long rangeEnd = start != null && end != null ? end : range.getTimeInMillis();
        range.add(Calendar.DAY_OF_MONTH, -Math.max(1, days));
        long rangeStart = start != null && end != null ? start : days == 0 ? Long.MIN_VALUE : range.getTimeInMillis();
        List<Measurement> filtered = new ArrayList<>();
        for (Measurement item : measurements) {
            if (item.measuredAt >= rangeStart && (days == 0 && start == null || item.measuredAt < rangeEnd)) {
                filtered.add(item);
            }
        }
        Collections.sort(filtered, (first, second) -> {
            int byTime = Long.compare(first.measuredAt, second.measuredAt);
            return byTime != 0 ? byTime : Long.compare(first.id, second.id);
        });
        boolean preserveWindow = sameRange && filtered.size() == data.size();
        if (preserveWindow) {
            for (int i = 0; i < filtered.size(); i++) {
                if (filtered.get(i).id != data.get(i).id || filtered.get(i).measuredAt != data.get(i).measuredAt) {
                    preserveWindow = false;
                    break;
                }
            }
        }
        this.days = days;
        selectedDay = start;
        showPulse = pulse;
        data = filtered;
        if (!preserveWindow) {
            viewport.reset(data.size(), 12);
            selectedIndex = data.isEmpty() ? -1 : data.size() - 1;
        }
        calculateValueRange();
        refreshViewport();
        notifySelection();
    }

    void setOnMeasurementSelectedListener(OnMeasurementSelectedListener listener) {
        selectionListener = listener;
        notifySelection();
    }

    void setOnViewportChangedListener(OnViewportChangedListener listener) {
        viewportListener = listener;
        refreshViewport();
    }

    void showAll() { viewport.showAll(); refreshViewport(); }
    void zoomIn() { zoom(1.6, selectionFraction()); }
    void zoomOut() { zoom(1 / 1.6, selectionFraction()); }

    private double selectionFraction() {
        return selectedIndex < 0 ? 0.5 : Math.max(0, Math.min(1,
                (selectedIndex - viewport.start()) / viewport.span()));
    }

    void selectRelative(int offset) {
        if (data.isEmpty()) return;
        select(Math.max(0, Math.min(data.size() - 1, selectedIndex + offset)));
    }

    private void select(int index) {
        selectedIndex = index;
        viewport.ensureVisible(index);
        refreshViewport();
        notifySelection();
    }

    private void zoom(double factor, double anchor) {
        viewport.zoom(factor, anchor);
        refreshViewport();
    }

    private void refreshViewport() {
        int first = viewport.first();
        int last = viewport.last();
        if (!data.isEmpty() && (selectedIndex < first || selectedIndex > last)) {
            selectedIndex = (first + last) / 2;
            notifySelection();
        }
        for (int slot = 0; slot < 3; slot++) {
            int index = first < 0 ? -1 : first + (last - first) * slot / 2;
            tickIndices[slot] = index;
            if (index >= 0) {
                long timestamp = data.get(index).measuredAt;
                tickDates[slot] = dateFormat.format(new Date(timestamp));
                Calendar clock = Calendar.getInstance();
                clock.setTimeInMillis(timestamp);
                int hour = clock.get(Calendar.HOUR_OF_DAY);
                String period = hour < 5 ? "凌晨" : hour < 12 ? "上午" : hour < 18 ? "下午" : "晚上";
                tickTimes[slot] = period + clockFormat.format(new Date(timestamp));
            }
        }
        String rangeText = selectedDay != null ? "所选日期" : days == 0 ? "全部记录" : "最近" + days + "日";
        setContentDescription((showPulse ? "脉搏" : "高压和低压") + "趋势图，" + rangeText + "，共" + data.size() +
                "条记录，可左右滑动、双指缩放和点选；也可使用上一条、下一条按钮");
        if (viewportListener != null) {
            viewportListener.onChanged(first, last, data.size(), viewport.canZoomIn(), viewport.canZoomOut());
        }
        invalidate();
    }

    private void notifySelection() {
        if (selectionListener != null) {
            selectionListener.onSelected(selectedIndex < 0 ? null : data.get(selectedIndex), selectedIndex, data.size());
        }
    }

    private void calculateValueRange() {
        if (data.isEmpty()) { minValue = 40; maxValue = 160; valueStep = 20; return; }
        int smallest = Integer.MAX_VALUE;
        int largest = Integer.MIN_VALUE;
        for (Measurement item : data) {
            smallest = Math.min(smallest, showPulse ? item.pulse : item.diastolic);
            largest = Math.max(largest, showPulse ? item.pulse : item.systolic);
        }
        valueStep = largest - smallest > 140 ? 40 : showPulse ? 10 : 20;
        minValue = Math.max(0, smallest / valueStep * valueStep - valueStep);
        maxValue = (largest + valueStep - 1) / valueStep * valueStep + valueStep;
        if (maxValue <= minValue) maxValue = minValue + valueStep * 2;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setTextSize(12 * fontScale);
        left = Math.max(36 * density, paint.measureText(String.valueOf(maxValue)) + 10 * density);
        right = getWidth() - 8 * density;
        top = 24 * density;
        bottom = getHeight() - (58 * fontScale + 30 * density);
        overviewTop = getHeight() - 24 * density;
        overviewBottom = getHeight() - 4 * density;
        if (right <= left || bottom <= top) return;
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
        if (data.isEmpty()) {
            paint.setColor(INK);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(16 * fontScale);
            canvas.drawText("这段时间还没有测量记录", getWidth() / 2f, getHeight() / 2f, paint);
            return;
        }
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(INK);
        paint.setTextSize(11 * fontScale);
        canvas.drawText(showPulse ? "次/分" : "mmHg", 0, 12 * fontScale, paint);
        for (int value = minValue; value <= maxValue; value += valueStep) {
            float y = yFor(value, top, bottom);
            paint.setColor(GRID);
            paint.setStrokeWidth(density);
            canvas.drawLine(left, y, right, y, paint);
            paint.setColor(INK);
            paint.setTextSize(12 * fontScale);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(String.valueOf(value), left - 8 * density, y + 4 * fontScale, paint);
        }
        canvas.save();
        canvas.clipRect(left, top - 9 * density, right, bottom + 9 * density);
        if (selectedIndex >= viewport.first() && selectedIndex <= viewport.last()) {
            paint.setColor(0x18007AFF);
            float selectedX = xFor(selectedIndex);
            canvas.drawRect(selectedX - 10 * density, top, selectedX + 10 * density, bottom, paint);
        }
        drawSeries(canvas, showPulse ? 2 : 0, false);
        if (!showPulse) drawSeries(canvas, 1, false);
        canvas.restore();

        paint.setStyle(Paint.Style.FILL);
        paint.setPathEffect(null);
        paint.setColor(INK);
        paint.setTextSize(11 * fontScale);
        boolean compactTicks = paint.measureText("下午12:59") * 2.8f > right - left;
        for (int slot = 0; slot < 3; slot++) {
            int index = tickIndices[slot];
            if (index < 0 || compactTicks && slot == 1 || slot > 0 && index == tickIndices[slot - 1]) continue;
            float x = left + (right - left) * slot / 2f;
            paint.setTextAlign(slot == 0 ? Paint.Align.LEFT : slot == 2 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(tickDates[slot], x, bottom + 20 * fontScale, paint);
            canvas.drawText(tickTimes[slot], x, bottom + 36 * fontScale, paint);
        }
        paint.setColor(0xFFF2F2F7);
        canvas.drawRoundRect(left, overviewTop, right, overviewBottom, 5 * density, 5 * density, paint);
        drawSeries(canvas, showPulse ? 2 : 0, true);
        if (!showPulse) drawSeries(canvas, 1, true);
        float overviewLeft = left + (right - left) * (float) ((viewport.start() + 0.5) / data.size());
        float overviewRight = left + (right - left) * (float) ((viewport.end() + 0.5) / data.size());
        paint.setColor(0x22007AFF);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(overviewLeft, overviewTop, overviewRight, overviewBottom, 4 * density, 4 * density, paint);
        paint.setColor(ACCENT);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        canvas.drawRoundRect(overviewLeft, overviewTop, overviewRight, overviewBottom, 4 * density, 4 * density, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawSeries(Canvas canvas, int field, boolean overview) {
        int first = overview ? 0 : Math.max(0, (int) Math.floor(viewport.start()) - 1);
        int last = overview ? data.size() - 1 : Math.min(data.size() - 1, (int) Math.ceil(viewport.end()) + 1);
        int color = field == 0 ? SYSTOLIC : field == 1 ? DIASTOLIC : PULSE;
        seriesPath.reset();
        for (int i = first; i <= last; i++) {
            float x = overview ? overviewX(i) : xFor(i);
            float y = yFor(valueFor(data.get(i), field), overview ? overviewTop + 2 * density : top,
                    overview ? overviewBottom - 2 * density : bottom);
            if (i == first) seriesPath.moveTo(x, y); else seriesPath.lineTo(x, y);
        }
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth((overview ? 1 : 2.5f) * density);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setPathEffect(!overview && field == 1 ? dashedLine : null);
        canvas.drawPath(seriesPath, paint);
        paint.setPathEffect(null);
        if (overview) return;
        for (int i = Math.max(0, viewport.first()); i <= viewport.last(); i++) {
            float x = xFor(i);
            float y = yFor(valueFor(data.get(i), field), top, bottom);
            if (i == selectedIndex) {
                paint.setColor(0xFFFFFFFF);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(x, y, 8 * density, paint);
                paint.setColor(color);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2 * density);
                canvas.drawCircle(x, y, 8 * density, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            float radius = i == selectedIndex ? 4 : viewport.span() <= 24 ? 3 : 1.5f;
            if (field == 1) canvas.drawRect(x - radius * density, y - radius * density,
                    x + radius * density, y + radius * density, paint);
            else canvas.drawCircle(x, y, radius * density, paint);
        }
    }

    private int valueFor(Measurement item, int field) {
        return field == 0 ? item.systolic : field == 1 ? item.diastolic : item.pulse;
    }

    private float xFor(int index) { return left + (right - left) * (float) ((index - viewport.start()) / viewport.span()); }
    private float overviewX(int index) { return left + (right - left) * (index + 0.5f) / data.size(); }
    private float yFor(int value, float plotTop, float plotBottom) {
        return plotBottom - (plotBottom - plotTop) * (value - minValue) / (maxValue - minValue);
    }
    private double plotFraction(float x) { return right <= left ? 0.5 : Math.max(0, Math.min(1, (x - left) / (right - left))); }

    private void centerOverview(float x) {
        if (data.isEmpty()) return;
        viewport.centerOn(plotFraction(x) * data.size() - 0.5);
        refreshViewport();
    }

    private void selectAt(float x) {
        if (data.isEmpty() || right <= left) return;
        int index = (int) Math.round(viewport.start() + plotFraction(x) * viewport.span());
        index = Math.max(viewport.first(), Math.min(viewport.last(), index));
        if (index >= 0) select(index);
    }

    private void releaseParent() {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (data.isEmpty()) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = lastX = event.getX();
            downY = event.getY();
            dragging = scrubbing = hadScale = verticalRejected = false;
            touchingOverview = downY >= overviewTop - 6 * density;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            hadScale = true;
        }
        scaleGestures.onTouchEvent(event);
        gestures.onTouchEvent(event);
        if (action == MotionEvent.ACTION_MOVE && !scaleGestures.isInProgress() && !hadScale) {
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            if (!dragging && !scrubbing && !touchingOverview && Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                verticalRejected = true;
                releaseParent();
                return false;
            }
            if (touchingOverview) { dragging = true; centerOverview(event.getX()); }
            else if (scrubbing) selectAt(event.getX());
            else {
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) dragging = true;
                if (dragging && right > left) {
                    viewport.pan(-(event.getX() - lastX) / (right - left) * viewport.span());
                    refreshViewport();
                }
            }
            lastX = event.getX();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) releaseParent();
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
