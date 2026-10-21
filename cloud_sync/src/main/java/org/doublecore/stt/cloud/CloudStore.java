package org.doublecore.stt.cloud;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Outbox + replicated data + cursor. Applying each page is one local transaction. */
public final class CloudStore extends SQLiteOpenHelper {
    public CloudStore(Context context, String databaseName) {
        super(context, new File(context.getNoBackupFilesDir(), databaseName).getAbsolutePath(), null, 1);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)");
        db.execSQL("CREATE TABLE activities(id TEXT PRIMARY KEY,payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE outbox(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,body TEXT NOT NULL,error TEXT NOT NULL DEFAULT '',state TEXT NOT NULL DEFAULT 'pending')");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Explicit migration required; never erase pending records");
    }
    public synchronized String meta(String key, String fallback) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT v FROM meta WHERE k=?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : fallback;
        }
    }
    private void putMeta(SQLiteDatabase db, String key, String value) {
        ContentValues values = new ContentValues(); values.put("k", key); values.put("v", value);
        db.insertWithOnConflict("meta", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized void verifyWorkspace(JSONObject response) throws JSONException {
        if (response.getInt("protocol") != 1) throw new JSONException("服务器协议不兼容");
        String id = response.getString("workspace_id");
        String existing = meta("workspace", "");
        if (!existing.isEmpty() && !existing.equals(id)) throw new JSONException("服务器数据库已更换，请先导出/处理本地数据");
        if (existing.isEmpty()) putMeta(getWritableDatabase(), "workspace", id);
    }
    public synchronized void enqueue(JSONObject op) throws JSONException {
        if (outstanding() >= 1000) throw new IllegalStateException("待处理操作超过 1000 条，请先同步");
        ContentValues values = new ContentValues(); values.put("id", op.getString("id")); values.put("body", op.toString());
        getWritableDatabase().insertOrThrow("outbox", null, values);
    }
    public synchronized List<JSONObject> pending() throws JSONException { return operations("pending"); }
    public synchronized List<JSONObject> rejected() throws JSONException { return operations("rejected"); }
    private List<JSONObject> operations(String state) throws JSONException {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT body,error FROM outbox WHERE state=? ORDER BY seq", new String[]{state})) {
            while (c.moveToNext()) {
                JSONObject item = new JSONObject(c.getString(0));
                if (state.equals("rejected")) item.put("error", c.getString(1));
                result.add(item);
            }
        }
        return result;
    }
    public synchronized int outstanding() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM outbox WHERE state!='ignored'", null)) {
            c.moveToFirst(); return c.getInt(0);
        }
    }
    public synchronized void acknowledge(String id) {
        getWritableDatabase().delete("outbox", "id=?", new String[]{id});
    }
    public synchronized void reject(String id, String error) {
        ContentValues values = new ContentValues(); values.put("state", "rejected"); values.put("error", error);
        getWritableDatabase().update("outbox", values, "id=?", new String[]{id});
    }
    public synchronized void ignore(String id) {
        ContentValues values = new ContentValues(); values.put("state", "ignored");
        getWritableDatabase().update("outbox", values, "id=? AND state='rejected'", new String[]{id});
    }
    public synchronized long cursor() { return Long.parseLong(meta("cursor", "0")); }
    public synchronized void applyPage(JSONObject page) throws JSONException {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            verifyWorkspace(page);
            long position = cursor();
            JSONArray events = page.getJSONArray("events");
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                if (event.getLong("seq") != position + 1) throw new JSONException("同步序列不连续");
                String entity = event.getString("entity");
                String table;
                if (entity.equals("activity")) table = "activities";
                else if (entity.equals("session")) table = "sessions";
                else throw new JSONException("未知同步事件");
                JSONObject data = event.getJSONObject("data");
                ContentValues values = new ContentValues(); values.put("id", data.getString("id")); values.put("payload", data.toString());
                db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                position++;
            }
            if (position != page.getLong("cursor")) throw new JSONException("同步游标不一致");
            putMeta(db, "cursor", Long.toString(position));
            putMeta(db, "synced_at", Long.toString(System.currentTimeMillis()));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    /** Optimistic display only. Pending rows remain visibly unconfirmed until server ACK. */
    public synchronized Map<String, JSONObject> view(String table) throws JSONException {
        if (!table.equals("activities") && !table.equals("sessions")) throw new IllegalArgumentException();
        Map<String, JSONObject> result = new LinkedHashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,payload FROM " + table + " ORDER BY rowid", null)) {
            while (c.moveToNext()) result.put(c.getString(0), new JSONObject(c.getString(1)));
        }
        for (JSONObject op : pending()) {
            String kind = op.getString("kind");
            if (table.equals("activities") && kind.equals("activity")) {
                result.put(op.getString("activity_id"), new JSONObject().put("id", op.getString("activity_id"))
                        .put("name", op.getString("name")).put("pending", true));
            } else if (table.equals("sessions") && kind.equals("start")) {
                result.put(op.getString("session_id"), new JSONObject().put("id", op.getString("session_id"))
                        .put("activity_id", op.getString("activity_id")).put("started_at", op.getLong("occurred_at"))
                        .put("ended_at", JSONObject.NULL).put("pending", true));
            } else if (table.equals("sessions") && kind.equals("stop")) {
                JSONObject session = result.get(op.getString("session_id"));
                if (session != null) session.put("ended_at", op.getLong("occurred_at")).put("pending", true);
            }
        }
        return result;
    }
}
