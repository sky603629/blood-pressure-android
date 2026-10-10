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
    private static final int DB_VERSION = 2;
    private static final String[] COLUMNS = {"id", "systolic", "diastolic", "pulse", "measured_at",
            "note", "source", "time_zone", "utc_offset_minutes"};

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
                "note TEXT NOT NULL DEFAULT ''," +
                "source TEXT NOT NULL DEFAULT 'legacy'," +
                "time_zone TEXT NOT NULL DEFAULT ''," +
                "utc_offset_minutes INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_measurements_measured_at ON measurements(measured_at DESC)");
        createSyncState(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE measurements ADD COLUMN source TEXT NOT NULL DEFAULT 'legacy'");
            db.execSQL("ALTER TABLE measurements ADD COLUMN time_zone TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE measurements ADD COLUMN utc_offset_minutes INTEGER NOT NULL DEFAULT 0");
            java.util.TimeZone zone = java.util.TimeZone.getDefault();
            try (Cursor cursor = db.query("measurements", new String[]{"id", "measured_at"},
                    null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    ContentValues metadata = new ContentValues();
                    metadata.put("time_zone", zone.getID());
                    metadata.put("utc_offset_minutes", zone.getOffset(cursor.getLong(1)) / 60000);
                    db.update("measurements", metadata, "id=?", new String[]{String.valueOf(cursor.getLong(0))});
                }
            }
            createSyncState(db);
        }
    }

    private static void createSyncState(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sync_state (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL)");
        db.execSQL("INSERT INTO sync_state VALUES(1,1)");
    }

    private static void changed(SQLiteDatabase db) {
        db.execSQL("UPDATE sync_state SET revision=revision+1 WHERE id=1");
    }

    private static Measurement read(Cursor cursor) {
        return new Measurement(cursor.getLong(0), cursor.getInt(1), cursor.getInt(2), cursor.getInt(3),
                cursor.getLong(4), cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getInt(8));
    }

    List<Measurement> all() {
        List<Measurement> items = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "measurements",
                COLUMNS,
                null, null, null, null, "measured_at DESC, id DESC")) {
            while (cursor.moveToNext()) {
                items.add(read(cursor));
            }
        }
        return items;
    }

    long insert(int systolic, int diastolic, int pulse, long measuredAt, String note) {
        return insert(systolic, diastolic, pulse, measuredAt, note, "manual");
    }

    long insert(int systolic, int diastolic, int pulse, long measuredAt, String note, String source) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues value = values(systolic, diastolic, pulse, measuredAt, note);
            value.put("source", source);
            long id = db.insertOrThrow("measurements", null, value);
            changed(db);
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }

    int update(long id, int systolic, int diastolic, int pulse, long measuredAt, String note) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues value = values(systolic, diastolic, pulse, measuredAt, note);
            // An edit retains the original capture source and timezone unless its time changed.
            try (Cursor cursor = db.query("measurements", new String[]{"measured_at"}, "id=?",
                    new String[]{String.valueOf(id)}, null, null, null)) {
                if (cursor.moveToFirst() && cursor.getLong(0) == measuredAt) {
                    value.remove("time_zone");
                    value.remove("utc_offset_minutes");
                }
            }
            int count = db.update("measurements", value, "id=?", new String[]{String.valueOf(id)});
            if (count > 0) changed(db);
            db.setTransactionSuccessful();
            return count;
        } finally { db.endTransaction(); }
    }

    int delete(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int count = db.delete("measurements", "id=?", new String[]{String.valueOf(id)});
            if (count > 0) changed(db);
            db.setTransactionSuccessful();
            return count;
        } finally { db.endTransaction(); }
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
                ContentValues value = values(item.systolic, item.diastolic, item.pulse, item.measuredAt, item.note);
                value.put("source", "import");
                db.insertOrThrow("measurements", null, value);
                inserted++;
            }
            if (inserted > 0) changed(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return inserted;
    }

    MeasurementSnapshot snapshot() {
        SQLiteDatabase db = getReadableDatabase();
        db.beginTransaction();
        try {
            long version, highWatermark = 0;
            try (Cursor cursor = db.rawQuery("SELECT revision FROM sync_state WHERE id=1", null)) {
                cursor.moveToFirst();
                version = cursor.getLong(0);
            }
            try (Cursor cursor = db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name='measurements'", null)) {
                if (cursor.moveToFirst()) highWatermark = cursor.getLong(0);
            }
            List<Measurement> rows = new ArrayList<>();
            try (Cursor cursor = db.query("measurements", COLUMNS, null, null, null, null, "id ASC")) {
                while (cursor.moveToNext()) rows.add(read(cursor));
            }
            MeasurementSnapshot result = new MeasurementSnapshot(version, highWatermark, rows);
            db.setTransactionSuccessful();
            return result;
        } finally { db.endTransaction(); }
    }

    long revision() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT revision FROM sync_state WHERE id=1", null)) {
            cursor.moveToFirst();
            return cursor.getLong(0);
        }
    }

    private ContentValues values(int systolic, int diastolic, int pulse, long measuredAt, String note) {
        ContentValues values = new ContentValues();
        values.put("systolic", systolic);
        values.put("diastolic", diastolic);
        values.put("pulse", pulse);
        values.put("measured_at", measuredAt);
        values.put("note", note == null ? "" : note.trim());
        java.util.TimeZone zone = java.util.TimeZone.getDefault();
        values.put("time_zone", zone.getID());
        values.put("utc_offset_minutes", zone.getOffset(measuredAt) / 60000);
        return values;
    }
}
