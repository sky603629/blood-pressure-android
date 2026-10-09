package com.pressurediary.app;

final class Measurement {
    final long id;
    final int systolic;
    final int diastolic;
    final int pulse;
    final long measuredAt;
    final String note;

    Measurement(long id, int systolic, int diastolic, int pulse, long measuredAt, String note) {
        this.id = id;
        this.systolic = systolic;
        this.diastolic = diastolic;
        this.pulse = pulse;
        this.measuredAt = measuredAt;
        this.note = note == null ? "" : note;
    }
}
