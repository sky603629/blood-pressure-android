package com.pressurediary.app;

final class Measurement {
    final long id;
    final int systolic;
    final int diastolic;
    final int pulse;
    final long measuredAt;
    final String note;
    final String source;
    final String timeZone;
    final int utcOffsetMinutes;

    Measurement(long id, int systolic, int diastolic, int pulse, long measuredAt, String note) {
        this(id, systolic, diastolic, pulse, measuredAt, note, "legacy",
                java.util.TimeZone.getDefault().getID(),
                java.util.TimeZone.getDefault().getOffset(measuredAt) / 60000);
    }

    Measurement(long id, int systolic, int diastolic, int pulse, long measuredAt, String note,
                String source, String timeZone, int utcOffsetMinutes) {
        this.id = id;
        this.systolic = systolic;
        this.diastolic = diastolic;
        this.pulse = pulse;
        this.measuredAt = measuredAt;
        this.note = note == null ? "" : note;
        this.source = source;
        this.timeZone = timeZone;
        this.utcOffsetMinutes = utcOffsetMinutes;
    }
}
