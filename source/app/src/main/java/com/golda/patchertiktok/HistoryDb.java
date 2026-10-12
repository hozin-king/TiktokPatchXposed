package com.golda.patchertiktok;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite store for watch history and captured live stream URLs.
 * Replaces the old single-blob SharedPreferences storage so large lists
 * stay fast; readers use LIMIT/OFFSET pagination.
 */
final class HistoryDb extends SQLiteOpenHelper {
    private static final String DB = "tiktokpatchxposed.db";
    private static final int VERSION = 1;
    private static final int VIDEO_CAP = 500;
    private static final int LIVE_CAP = 200;

    private static final String OLD_FILE = "tiktokpatchxposed_history";
    private static final String OLD_KEY = "entries";

    private static volatile HistoryDb instance;

    private HistoryDb(Context context) {
        super(context.getApplicationContext(), DB, null, VERSION);
    }

    static HistoryDb get(Context context) {
        HistoryDb local = instance;
        if (local == null) {
            synchronized (HistoryDb.class) {
                local = instance;
                if (local == null) instance = local = new HistoryDb(context);
            }
        }
        return local;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS history(" +
                "aid TEXT PRIMARY KEY, author TEXT, descr TEXT, time INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_time ON history(time DESC)");
        db.execSQL("CREATE TABLE IF NOT EXISTS live_urls(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, rtmp TEXT, flv TEXT, time INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_live_time ON live_urls(time DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
    }

    // ---- watch history ----

    static void recordVideo(Context context, String aid, String author, String desc) {
        try {
            SQLiteDatabase db = get(context).getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("aid", aid);
            values.put("author", author);
            values.put("descr", desc);
            values.put("time", System.currentTimeMillis());
            db.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            db.execSQL("DELETE FROM history WHERE aid NOT IN " +
                    "(SELECT aid FROM history ORDER BY time DESC LIMIT " + VIDEO_CAP + ")");
        } catch (Throwable ignored) { }
    }

    static List<WatchHistory.Entry> videoPage(Context context, int offset, int limit) {
        List<WatchHistory.Entry> out = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = get(context).getReadableDatabase().query("history",
                    new String[]{"aid", "author", "descr", "time"},
                    null, null, null, null, "time DESC", offset + "," + limit);
            while (cursor.moveToNext()) {
                out.add(new WatchHistory.Entry(cursor.getString(0), cursor.getString(1),
                        cursor.getString(2), cursor.getLong(3)));
            }
        } catch (Throwable ignored) { }
        finally { if (cursor != null) cursor.close(); }
        return out;
    }

    static int videoCount(Context context) {
        Cursor cursor = null;
        try {
            cursor = get(context).getReadableDatabase()
                    .rawQuery("SELECT COUNT(*) FROM history", null);
            if (cursor.moveToFirst()) return cursor.getInt(0);
        } catch (Throwable ignored) { }
        finally { if (cursor != null) cursor.close(); }
        return 0;
    }

    static void clearVideos(Context context) {
        try {
            get(context).getWritableDatabase().delete("history", null, null);
        } catch (Throwable ignored) { }
    }

    /** One-time migration from the old SharedPreferences blob. Idempotent. */
    static void migrateIfNeeded(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(OLD_FILE, Context.MODE_PRIVATE);
            String raw = prefs.getString(OLD_KEY, null);
            if (raw == null) return;
            JSONArray array = new JSONArray(raw);
            SQLiteDatabase db = get(context).getWritableDatabase();
            db.beginTransaction();
            try {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject json = array.optJSONObject(i);
                    if (json == null) continue;
                    ContentValues values = new ContentValues();
                    values.put("aid", json.optString("aid"));
                    values.put("author", json.optString("author"));
                    values.put("descr", json.optString("desc"));
                    values.put("time", json.optLong("time"));
                    db.insertWithOnConflict("history", null, values,
                            SQLiteDatabase.CONFLICT_IGNORE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            prefs.edit().remove(OLD_KEY).apply();
            RuntimeLog.log("watch history migrated to sqlite: " + array.length());
        } catch (Throwable ignored) { }
    }

    // ---- live stream urls ----

    /** Stores a capture unless it is identical to the most recent one. */
    static void recordLive(Context context, String rtmp, Map<String, String> flvUrls) {
        try {
            JSONObject flv = new JSONObject();
            if (flvUrls != null) {
                for (Map.Entry<String, String> e : flvUrls.entrySet()) flv.put(e.getKey(), e.getValue());
            }
            String flvJson = flv.toString();
            HistoryDb helper = get(context);
            SQLiteDatabase db = helper.getWritableDatabase();
            // Dedupe against the latest row.
            Cursor cursor = null;
            try {
                cursor = db.query("live_urls", new String[]{"rtmp", "flv"},
                        null, null, null, null, "time DESC", "1");
                if (cursor.moveToFirst()) {
                    String lastRtmp = cursor.getString(0);
                    String lastFlv = cursor.getString(1);
                    boolean sameRtmp = rtmp == null ? lastRtmp == null : rtmp.equals(lastRtmp);
                    if (sameRtmp && flvJson.equals(lastFlv == null ? "{}" : lastFlv)) return;
                }
            } finally {
                if (cursor != null) cursor.close();
            }
            ContentValues values = new ContentValues();
            values.put("rtmp", rtmp);
            values.put("flv", flvJson);
            values.put("time", System.currentTimeMillis());
            db.insert("live_urls", null, values);
            db.execSQL("DELETE FROM live_urls WHERE id NOT IN " +
                    "(SELECT id FROM live_urls ORDER BY time DESC LIMIT " + LIVE_CAP + ")");
        } catch (Throwable ignored) { }
    }

    static List<LiveStreamUrl.Captured> livePage(Context context, int offset, int limit) {
        List<LiveStreamUrl.Captured> out = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = get(context).getReadableDatabase().query("live_urls",
                    new String[]{"rtmp", "flv", "time"},
                    null, null, null, null, "time DESC", offset + "," + limit);
            while (cursor.moveToNext()) {
                Map<String, String> flv = new LinkedHashMap<>();
                try {
                    JSONObject json = new JSONObject(cursor.getString(1));
                    java.util.Iterator<String> keys = json.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        flv.put(key, json.optString(key));
                    }
                } catch (Throwable ignored) { }
                out.add(new LiveStreamUrl.Captured(cursor.getString(0), flv, cursor.getLong(2)));
            }
        } catch (Throwable ignored) { }
        finally { if (cursor != null) cursor.close(); }
        return out;
    }

    static int liveCount(Context context) {
        Cursor cursor = null;
        try {
            cursor = get(context).getReadableDatabase()
                    .rawQuery("SELECT COUNT(*) FROM live_urls", null);
            if (cursor.moveToFirst()) return cursor.getInt(0);
        } catch (Throwable ignored) { }
        finally { if (cursor != null) cursor.close(); }
        return 0;
    }
}
