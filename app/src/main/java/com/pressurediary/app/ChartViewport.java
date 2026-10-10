package com.pressurediary.app;

/** A window over chronologically ordered records, with half a record of padding at each end. */
final class ChartViewport {
    private int count;
    private double start = -0.5;
    private double span = 1;

    void reset(int recordCount, int visibleCount) {
        count = Math.max(0, recordCount);
        span = count == 0 ? 1 : Math.min(count, Math.max(minSpan(), visibleCount));
        start = count == 0 ? -0.5 : count - 0.5 - span;
    }

    int count() { return count; }
    double start() { return start; }
    double span() { return span; }
    double end() { return start + span; }
    int first() { return count == 0 ? -1 : Math.max(0, (int) Math.ceil(start)); }
    int last() { return count == 0 ? -1 : Math.min(count - 1, (int) Math.floor(end())); }
    boolean canZoomIn() { return count > 1 && span > minSpan() + 0.001; }
    boolean canZoomOut() { return count > 1 && span < count - 0.001; }

    void pan(double records) {
        if (Double.isNaN(records) || Double.isInfinite(records)) return;
        start += records;
        clamp();
    }

    void zoom(double factor, double anchorFraction) {
        if (count == 0 || factor <= 0 || Double.isNaN(factor) || Double.isInfinite(factor)) return;
        double anchor = Math.max(0, Math.min(1, anchorFraction));
        double fixedRecord = start + span * anchor;
        span = Math.max(minSpan(), Math.min(count, span / factor));
        start = fixedRecord - span * anchor;
        clamp();
    }

    void centerOn(double record) {
        start = record - span / 2;
        clamp();
    }

    void ensureVisible(int record) {
        if (record < 0 || record >= count) return;
        if (record < start + 0.35) start = record - 0.5;
        else if (record > end() - 0.35) start = record + 0.5 - span;
        clamp();
    }

    void showAll() {
        span = Math.max(1, count);
        start = -0.5;
    }

    private double minSpan() { return Math.min(count, 3); }

    private void clamp() {
        start = Math.max(-0.5, Math.min(Math.max(-0.5, count - 0.5 - span), start));
    }
}
