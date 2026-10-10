package com.pressurediary.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A consistent local database revision, ordered by creation ID rather than measurement date. */
final class MeasurementSnapshot {
    final long version;
    final long highWatermark;
    final List<Measurement> records;

    MeasurementSnapshot(long version, long highWatermark, List<Measurement> records) {
        if (version < 1 || highWatermark < 0 || records.size() > 20000) {
            throw new IllegalArgumentException("Unsupported snapshot size or revision");
        }
        long previous = 0;
        for (Measurement item : records) {
            if (item.id <= previous || item.id > highWatermark) {
                throw new IllegalArgumentException("Snapshot records must be ordered and unique");
            }
            previous = item.id;
        }
        this.version = version;
        this.highWatermark = highWatermark;
        this.records = Collections.unmodifiableList(new ArrayList<>(records));
    }
}
