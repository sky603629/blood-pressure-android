package com.pressurediary.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class MeasurementDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "pressure_diary.db";
    private static final int DB_VERSION = 1;

    MeasurementDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE measurements (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "systolic INTEGER NOT NULL," +
                "diastolic INTEGER NOT NULL," +
                "pulse INTEGER NOT NULL," +
                "measured_at INTEGER NOT NULL," +
                "note TEXT NOT NULL DEFAULT '' )");
        db.execSQL("CREATE INDEX idx_measurements_measured_at ON measurements(measured_at DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Additive schema upgrades belong here when the local format changes.
    }

    List<Measurement> all() {
        List<Measurement> items = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "measurements",
                new String[]{"id", "systolic", "diastolic", "pulse", "measured_at", "note"},
                null, null, null, null, "measured_at DESC, id DESC")) {
            while (cursor.moveToNext()) {
                items.add(new Measurement(
                        cursor.getLong(0), cursor.getInt(1), cursor.getInt(2),
                        cursor.getInt(3), cursor.getLong(4), cursor.getString(5)));
            }
        }
        return items;
    }

    long insert(int systolic, int diastolic, int pulse, long measuredAt, String note) {
        return getWritableDatabase().insertOrThrow("measurements", null,
                values(systolic, diastolic, pulse, measuredAt, note));
    }

    int update(long id, int systolic, int diastolic, int pulse, long measuredAt, String note) {
        return getWritableDatabase().update("measurements",
                values(systolic, diastolic, pulse, measuredAt, note), "id=?",
                new String[]{String.valueOf(id)});
    }

    int delete(long id) {
        return getWritableDatabase().delete("measurements", "id=?",
                new String[]{String.valueOf(id)});
    }

    int importRows(List<Measurement> imported) {
        SQLiteDatabase db = getWritableDatabase();
        Set<String> knownRows = new HashSet<>();
        int inserted = 0;
        db.beginTransaction();
        try {
            try (Cursor cursor = db.query("measurements",
                    new String[]{"systolic", "diastolic", "pulse", "measured_at", "note"},
                    null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    knownRows.add(MeasurementFileTransfer.key(new Measurement(0,
                            cursor.getInt(0), cursor.getInt(1), cursor.getInt(2),
                            cursor.getLong(3), cursor.getString(4))));
                }
            }
            for (Measurement item : imported) {
                if (!knownRows.add(MeasurementFileTransfer.key(item))) continue;
                db.insertOrThrow("measurements", null,
                        values(item.systolic, item.diastolic, item.pulse, item.measuredAt, item.note));
                inserted++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return inserted;
    }

    private ContentValues values(int systolic, int diastolic, int pulse, long measuredAt, String note) {
        ContentValues values = new ContentValues();
        values.put("systolic", systolic);
        values.put("diastolic", diastolic);
        values.put("pulse", pulse);
        values.put("measured_at", measuredAt);
        values.put("note", note == null ? "" : note.trim());
        return values;
    }
}
