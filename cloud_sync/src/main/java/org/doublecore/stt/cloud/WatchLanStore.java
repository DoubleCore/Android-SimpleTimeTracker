package org.doublecore.stt.cloud;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Independent watch recording database.
 * Recording always commits locally before attempting phone LAN transfer.
 * The phone receives completed records, never remote timer commands.
 */
public final class WatchLanStore extends SQLiteOpenHelper {
    public WatchLanStore(Context context) {
        super(context, new File(context.getNoBackupFilesDir(), "stt-watch-lan-v1.db").getAbsolutePath(), null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE activity (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE session (id TEXT PRIMARY KEY, activity_id INTEGER NOT NULL, " +
                "started_at INTEGER NOT NULL, ended_at INTEGER, comment TEXT NOT NULL DEFAULT '', " +
                "synced INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX session_pending_idx ON session(synced, ended_at)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Watch recording migration required; never erase offline records");
    }

    /** Update the watch's locally cached catalogue; never delete its historical sessions. */
    public synchronized void saveActivities(JSONArray items) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                long id = item.getLong("id");
                if (id <= 0 || !item.has("name")) throw new IllegalArgumentException("Invalid activity");
                ContentValues values = new ContentValues();
                values.put("id", id);
                values.put("payload", item.toString());
                db.insertWithOnConflict("activity", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<JSONObject> activities() throws Exception {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT payload FROM activity ORDER BY id", null)) {
            while (c.moveToNext()) result.add(new JSONObject(c.getString(0)));
        }
        return result;
    }

    public synchronized List<JSONObject> sessions() throws Exception {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,activity_id,started_at,ended_at,comment,synced FROM session ORDER BY started_at DESC", null)) {
            while (c.moveToNext()) result.add(decode(c));
        }
        return result;
    }

    /** Start a watch-owned timer locally: does not require a reachable phone. */
    public synchronized JSONObject start(long typeId) throws Exception {
        if (typeId <= 0) throw new IllegalArgumentException("Invalid activity");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor a = db.rawQuery("SELECT payload FROM activity WHERE id=?", new String[]{Long.toString(typeId)})) {
                if (!a.moveToFirst() || new JSONObject(a.getString(0)).optBoolean("hidden")) {
                    throw new IllegalStateException("手表未缓存此活动，首次使用时需从手机同步活动列表");
                }
            }
            try (Cursor c = db.rawQuery("SELECT id FROM session WHERE activity_id=? AND ended_at IS NULL",
                    new String[]{Long.toString(typeId)})) {
                if (c.moveToFirst()) throw new IllegalStateException("该活动已经在手表计时");
            }
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis() / 1000 * 1000;
            ContentValues values = new ContentValues();
            values.put("id", id);
            values.put("activity_id", typeId);
            values.put("started_at", now);
            values.put("comment", "");
            db.insertOrThrow("session", null, values);
            db.setTransactionSuccessful();
            return new JSONObject().put("id", id).put("activity_id", typeId)
                    .put("started_at", now).put("ended_at", JSONObject.NULL)
                    .put("comment", "").put("synced", false);
        } finally {
            db.endTransaction();
        }
    }

    /** Stop locally first. The completed session enters the durable upload queue implicitly. */
    public synchronized JSONObject stop(long typeId) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor c = db.rawQuery(
                    "SELECT id,activity_id,started_at,ended_at,comment,synced FROM session " +
                            "WHERE activity_id=? AND ended_at IS NULL ORDER BY started_at DESC LIMIT 1",
                    new String[]{Long.toString(typeId)})) {
                if (!c.moveToFirst()) throw new IllegalStateException("没有正在进行的手表计时");
                JSONObject item = decode(c);
                long end = Math.max(item.getLong("started_at"), System.currentTimeMillis() / 1000 * 1000);
                ContentValues values = new ContentValues();
                values.put("ended_at", end);
                db.update("session", values, "id=?", new String[]{item.getString("id")});
                item.put("ended_at", end);
                db.setTransactionSuccessful();
                return item;
            }
        } finally {
            db.endTransaction();
        }
    }

    /** Stable record UUID is sent on every retry; phone ACK only after Room commit. */
    public synchronized List<JSONObject> pending(int limit) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,activity_id,started_at,ended_at,comment,synced FROM session " +
                        "WHERE ended_at IS NOT NULL AND synced=0 ORDER BY started_at LIMIT " + Math.min(Math.max(limit, 1),100),
                null)) {
            while (c.moveToNext()) result.add(decode(c));
        }
        return result;
    }

    public synchronized void acknowledge(String id) {
        ContentValues values = new ContentValues();
        values.put("synced", 1);
        getWritableDatabase().update("session", values, "id=? AND ended_at IS NOT NULL", new String[]{id});
    }
    public synchronized int pendingCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM session WHERE ended_at IS NOT NULL AND synced=0", null)) {
            c.moveToFirst(); return c.getInt(0);
        }
    }

    private static JSONObject decode(Cursor c) throws Exception {
        return new JSONObject().put("id", c.getString(0))
                .put("activity_id", c.getLong(1))
                .put("started_at", c.getLong(2))
                .put("ended_at", c.isNull(3) ? JSONObject.NULL : c.getLong(3))
                .put("comment", c.getString(4))
                .put("synced", c.getInt(5) != 0);
    }

    /**
     * Pull activity catalogue and upload completed local watch records.
     * A failure keeps every unacknowledged record safely in SQLite.
     */
    public synchronized int synchronize(LanConnection lan) throws Exception {
        JSONObject state = lan.request("GET", "/v1/state", null);
        saveActivities(state.getJSONArray("activities"));
        int accepted = 0;
        for (JSONObject record : pending(100)) {
            JSONObject op = new JSONObject().put("record_id", record.getString("id"))
                    .put("activity_id", record.getLong("activity_id"))
                    .put("started_at", record.getLong("started_at"))
                    .put("ended_at", record.getLong("ended_at"))
                    .put("comment", record.getString("comment"));
            JSONObject ack = lan.request("POST", "/v1/import", op);
            if (!ack.optBoolean("accepted") || !record.getString("id").equals(ack.optString("record_id"))) {
                throw new java.io.IOException("手机没有确认记录写入，保留本地记录");
            }
            acknowledge(record.getString("id"));
            accepted++;
        }
        return accepted;
    }
}
