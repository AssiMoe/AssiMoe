package de.assimoe.libremirror;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class HistoryDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "libremirror_history.db";
    private static final int DB_VERSION = 1;
    private static final long MAX_RETENTION_MS = 90L * 24L * 60L * 60L * 1000L;

    public HistoryDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE readings (" +
                        "timestamp_ms INTEGER PRIMARY KEY," +
                        "mgdl REAL NOT NULL," +
                        "trend INTEGER NOT NULL DEFAULT 0" +
                        ")"
        );
        db.execSQL("CREATE INDEX idx_readings_time ON readings(timestamp_ms)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public synchronized void insert(long timestampMs, double mgdl, int trend) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL(
                "INSERT OR REPLACE INTO readings(timestamp_ms,mgdl,trend) VALUES(?,?,?)",
                new Object[]{timestampMs, mgdl, trend}
        );
        db.execSQL(
                "DELETE FROM readings WHERE timestamp_ms < ?",
                new Object[]{System.currentTimeMillis() - MAX_RETENTION_MS}
        );
    }

    public synchronized void insertAll(List<LibreApiClient.Reading> readings) {
        if (readings == null || readings.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (LibreApiClient.Reading reading : readings) {
                db.execSQL(
                        "INSERT OR REPLACE INTO readings(timestamp_ms,mgdl,trend) VALUES(?,?,?)",
                        new Object[]{reading.timestampMs, reading.mgdl, reading.trend}
                );
            }
            db.execSQL(
                    "DELETE FROM readings WHERE timestamp_ms < ?",
                    new Object[]{System.currentTimeMillis() - MAX_RETENTION_MS}
            );
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<Point> query(long fromMs, long toMs) {
        List<Point> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT timestamp_ms,mgdl,trend FROM readings " +
                        "WHERE timestamp_ms>=? AND timestamp_ms<? ORDER BY timestamp_ms ASC",
                new String[]{String.valueOf(fromMs), String.valueOf(toMs)}
        );
        try {
            while (c.moveToNext()) {
                out.add(new Point(c.getLong(0), c.getDouble(1), c.getInt(2)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public synchronized List<Point> latest(int limit) {
        List<Point> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT timestamp_ms,mgdl,trend FROM readings " +
                        "ORDER BY timestamp_ms DESC LIMIT ?",
                new String[]{String.valueOf(Math.max(1, limit))}
        );
        try {
            while (c.moveToNext()) {
                out.add(0, new Point(c.getLong(0), c.getDouble(1), c.getInt(2)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public synchronized void clearHistory() {
        getWritableDatabase().delete("readings", null, null);
    }

    public static final class Point {
        public final long timestampMs;
        public final double mgdl;
        public final int trend;

        public Point(long timestampMs, double mgdl, int trend) {
            this.timestampMs = timestampMs;
            this.mgdl = mgdl;
            this.trend = trend;
        }
    }
}
