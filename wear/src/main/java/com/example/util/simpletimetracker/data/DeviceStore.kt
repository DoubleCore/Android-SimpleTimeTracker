/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.example.util.simpletimetracker.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watch-owned durable store, independent of any phone or cloud configuration.
 * Every timer action commits the session row and its outbox operation in one
 * transaction before any network attempt; sessions are never deleted.
 */
@Singleton
class DeviceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SQLiteOpenHelper(context, File(context.noBackupFilesDir, DB_NAME).absolutePath, null, 1) {

    data class Activity(
        val id: Long,
        val name: String,
        val icon: String,
        val color: Long,
        val hidden: Boolean,
        val order: Int,
        val categoryIds: List<Long>,
    )

    data class Category(val id: Long, val name: String, val color: Long, val order: Int)

    data class Session(
        val id: String,
        val activityId: Long,
        val startedAt: Long,
        val endedAt: Long?,
        val origin: String,
    )

    data class Outbound(val seq: Long, val opId: String, val payload: JSONObject)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE activity_cache (id INTEGER PRIMARY KEY, name TEXT NOT NULL, icon TEXT NOT NULL, " +
                "color INTEGER NOT NULL, hidden INTEGER NOT NULL, ord INTEGER NOT NULL, category_ids TEXT NOT NULL)",
        )
        db.execSQL("CREATE TABLE category_cache (id INTEGER PRIMARY KEY, name TEXT NOT NULL, color INTEGER NOT NULL, ord INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE session (id TEXT PRIMARY KEY, activity_id INTEGER NOT NULL, started_at INTEGER NOT NULL, " +
                "ended_at INTEGER, origin TEXT NOT NULL, start_confirmed INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX session_open_idx ON session(ended_at, activity_id)")
        db.execSQL(
            "CREATE TABLE outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT, op_id TEXT NOT NULL UNIQUE, " +
                "session_id TEXT, payload TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending', " +
                "attempts INTEGER NOT NULL DEFAULT 0, error TEXT)",
        )
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        importLegacyLanStore(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Watch store migration required; never erase offline records")
    }

    // region Catalogue cache

    @Synchronized
    fun hasCatalogue(): Boolean = count("SELECT COUNT(*) FROM activity_cache") > 0

    @Synchronized
    fun activities(): List<Activity> {
        readableDatabase.rawQuery(
            "SELECT id, name, icon, color, hidden, ord, category_ids FROM activity_cache ORDER BY ord, id",
            null,
        ).use { c ->
            val result = mutableListOf<Activity>()
            while (c.moveToNext()) {
                val ids = JSONArray(c.getString(6))
                result += Activity(
                    id = c.getLong(0),
                    name = c.getString(1),
                    icon = c.getString(2),
                    color = c.getLong(3),
                    hidden = c.getInt(4) != 0,
                    order = c.getInt(5),
                    categoryIds = (0 until ids.length()).map(ids::getLong),
                )
            }
            return result
        }
    }

    @Synchronized
    fun categories(): List<Category> {
        readableDatabase.rawQuery("SELECT id, name, color, ord FROM category_cache ORDER BY ord, id", null).use { c ->
            val result = mutableListOf<Category>()
            while (c.moveToNext()) result += Category(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3))
            return result
        }
    }

    /**
     * Replaces the catalogue with the phone snapshot. Activities removed on the phone
     * disappear from the list, but sessions referencing them stay in the store.
     */
    @Synchronized
    fun saveCatalogue(snapshot: JSONObject) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("activity_cache", null, null)
            val activities = snapshot.getJSONArray("activities")
            for (i in 0 until activities.length()) {
                val item = activities.getJSONObject(i)
                db.insertOrThrow(
                    "activity_cache",
                    null,
                    ContentValues().apply {
                        put("id", item.getLong("id"))
                        put("name", item.getString("name"))
                        put("icon", item.optString("icon", ""))
                        put("color", item.optLong("color", DEFAULT_COLOR))
                        put("hidden", if (item.optBoolean("hidden")) 1 else 0)
                        put("ord", item.optInt("order", i))
                        put("category_ids", (item.optJSONArray("category_ids") ?: JSONArray()).toString())
                    },
                )
            }
            db.delete("category_cache", null, null)
            val categories = snapshot.optJSONArray("categories") ?: JSONArray()
            for (i in 0 until categories.length()) {
                val item = categories.getJSONObject(i)
                db.insertOrThrow(
                    "category_cache",
                    null,
                    ContentValues().apply {
                        put("id", item.getLong("id"))
                        put("name", item.getString("name"))
                        put("color", item.optLong("color", DEFAULT_COLOR))
                        put("ord", item.optInt("order", i))
                    },
                )
            }
            snapshot.optJSONObject("settings")?.let {
                setMeta(db, KEY_ALLOW_MULTITASKING, it.optBoolean("allow_multitasking", true).toString())
            }
            setMeta(db, KEY_REV, snapshot.optString("rev", ""))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // endregion

    // region Sessions

    @Synchronized
    fun openSessions(): List<Session> = sessions("WHERE ended_at IS NULL ORDER BY started_at", null)

    @Synchronized
    fun recentClosed(limit: Int): List<Session> =
        sessions("WHERE ended_at IS NOT NULL ORDER BY ended_at DESC LIMIT $limit", null)

    @Synchronized
    fun sessionsInRange(from: Long, to: Long): List<Session> = sessions(
        "WHERE started_at < ? AND (ended_at IS NULL OR ended_at > ?)",
        arrayOf(to.toString(), from.toString()),
    )

    /** Starts locally; ends other open sessions first when the phone disallows multitasking. */
    @Synchronized
    fun start(activityId: Long, now: Long): Session {
        val db = writableDatabase
        db.beginTransaction()
        try {
            require(activityExists(db, activityId)) { "手表未缓存此活动，请先连接手机同步活动列表" }
            check(openSession(db, activityId) == null) { "该活动已经在计时" }
            if (!allowMultitasking()) {
                sessions(db, "WHERE ended_at IS NULL", null).forEach { endInternal(db, it, now) }
            }
            val session = Session(UUID.randomUUID().toString(), activityId, now, null, ORIGIN_WATCH)
            db.insertOrThrow(
                "session",
                null,
                ContentValues().apply {
                    put("id", session.id)
                    put("activity_id", activityId)
                    put("started_at", now)
                    put("origin", ORIGIN_WATCH)
                },
            )
            enqueue(db, session.id, opStart(session))
            db.setTransactionSuccessful()
            return session
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun stop(activityId: Long, now: Long): Session {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val open = openSession(db, activityId) ?: throw IllegalStateException("没有正在进行的计时")
            val ended = endInternal(db, open, now)
            db.setTransactionSuccessful()
            return ended
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Reconciles the local view with the phone snapshot:
     * - phone timers unknown locally are added as phone-origin sessions,
     * - local sessions whose start the phone confirmed earlier but no longer reports
     *   were stopped on the phone and are closed without generating operations.
     */
    @Synchronized
    fun reconcileRunning(running: JSONArray, now: Long): Boolean {
        val db = writableDatabase
        var changed = false
        db.beginTransaction()
        try {
            val remote = (0 until running.length()).map(running::getJSONObject)
            val remoteIds = remote.map { it.getString("session_id") }.toSet()
            val pendingSessions = pendingSessionIds(db)
            for (item in remote) {
                val id = item.getString("session_id")
                val activityId = item.getLong("activity_id")
                val startedAt = item.getLong("started_at")
                val existing = session(db, id)
                when {
                    existing == null -> {
                        // A pending local end of this activity means the user already stopped it here.
                        if (openSession(db, activityId) != null) continue
                        db.insertOrThrow(
                            "session",
                            null,
                            ContentValues().apply {
                                put("id", id)
                                put("activity_id", activityId)
                                put("started_at", startedAt)
                                put("origin", item.optString("origin", ORIGIN_PHONE))
                                put("start_confirmed", 1)
                            },
                        )
                        changed = true
                    }
                    existing.endedAt == null -> {
                        db.update(
                            "session",
                            ContentValues().apply {
                                put("start_confirmed", 1)
                                put("started_at", startedAt)
                            },
                            "id=?",
                            arrayOf(id),
                        )
                        if (existing.startedAt != startedAt) changed = true
                    }
                }
            }
            for (open in sessions(db, "WHERE ended_at IS NULL AND start_confirmed=1", null)) {
                if (open.id in remoteIds || open.id in pendingSessions) continue
                db.update(
                    "session",
                    ContentValues().apply { put("ended_at", maxOf(open.startedAt, now)) },
                    "id=?",
                    arrayOf(open.id),
                )
                changed = true
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    // endregion

    // region Outbox

    @Synchronized
    fun pending(limit: Int): List<Outbound> {
        readableDatabase.rawQuery(
            "SELECT seq, op_id, payload FROM outbox WHERE state='pending' ORDER BY seq LIMIT $limit",
            null,
        ).use { c ->
            val result = mutableListOf<Outbound>()
            while (c.moveToNext()) result += Outbound(c.getLong(0), c.getString(1), JSONObject(c.getString(2)))
            return result
        }
    }

    @Synchronized
    fun pendingCount(): Int = count("SELECT COUNT(*) FROM outbox WHERE state='pending'")

    @Synchronized
    fun rejectedCount(): Int = count("SELECT COUNT(*) FROM outbox WHERE state='rejected'")

    /** Marks a delivered operation; a start acknowledged by the phone may adopt the phone start time. */
    @Synchronized
    fun acknowledge(opId: String, status: String, error: String?, confirmedStart: Long?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val state = if (status == STATUS_REJECTED) "rejected" else "done"
            db.update(
                "outbox",
                ContentValues().apply {
                    put("state", state)
                    put("error", error)
                },
                "op_id=?",
                arrayOf(opId),
            )
            val sessionId = outboxSession(db, opId)
            if (sessionId != null && status != STATUS_REJECTED) {
                val values = ContentValues().apply {
                    put("start_confirmed", 1)
                    if (confirmedStart != null) put("started_at", confirmedStart)
                }
                db.update("session", values, "id=? AND ended_at IS NULL", arrayOf(sessionId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun markAttempt(opIds: List<String>, error: String) {
        val db = writableDatabase
        opIds.forEach {
            db.execSQL("UPDATE outbox SET attempts = attempts + 1, error = ? WHERE op_id = ?", arrayOf(error, it))
        }
    }

    // endregion

    // region Meta

    @Synchronized
    fun rev(): String = meta(KEY_REV, "")

    @Synchronized
    fun allowMultitasking(): Boolean = meta(KEY_ALLOW_MULTITASKING, "true").toBoolean()

    @Synchronized
    fun setAllowMultitasking(value: Boolean, now: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            setMeta(db, KEY_ALLOW_MULTITASKING, value.toString())
            val op = JSONObject()
                .put("op_id", UUID.randomUUID().toString())
                .put("kind", KIND_SETTINGS_PUT)
                .put("allow_multitasking", value)
                .put("occurred_at", now)
            enqueue(db, null, op)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun lastSyncAt(): Long = meta(KEY_LAST_SYNC, "0").toLong()

    @Synchronized
    fun lastError(): String = meta(KEY_LAST_ERROR, "")

    @Synchronized
    fun recordSyncResult(now: Long, error: String?) {
        val db = writableDatabase
        if (error == null) setMeta(db, KEY_LAST_SYNC, now.toString())
        setMeta(db, KEY_LAST_ERROR, error.orEmpty())
    }

    // endregion

    private fun endInternal(db: SQLiteDatabase, session: Session, now: Long): Session {
        val end = maxOf(session.startedAt, now)
        db.update("session", ContentValues().apply { put("ended_at", end) }, "id=?", arrayOf(session.id))
        val ended = session.copy(endedAt = end)
        enqueue(db, session.id, opEnd(ended))
        return ended
    }

    private fun opStart(session: Session) = JSONObject()
        .put("op_id", UUID.randomUUID().toString())
        .put("kind", KIND_SESSION_START)
        .put("session_id", session.id)
        .put("activity_id", session.activityId)
        .put("started_at", session.startedAt)
        .put("origin", session.origin)

    private fun opEnd(session: Session) = JSONObject()
        .put("op_id", UUID.randomUUID().toString())
        .put("kind", KIND_SESSION_END)
        .put("session_id", session.id)
        .put("activity_id", session.activityId)
        .put("started_at", session.startedAt)
        .put("ended_at", session.endedAt)
        .put("origin", session.origin)

    private fun enqueue(db: SQLiteDatabase, sessionId: String?, op: JSONObject) {
        db.insertOrThrow(
            "outbox",
            null,
            ContentValues().apply {
                put("op_id", op.getString("op_id"))
                put("session_id", sessionId)
                put("payload", op.toString())
            },
        )
    }

    private fun outboxSession(db: SQLiteDatabase, opId: String): String? {
        db.rawQuery("SELECT session_id, payload FROM outbox WHERE op_id=?", arrayOf(opId)).use {
            if (!it.moveToFirst() || it.isNull(0)) return null
            return if (JSONObject(it.getString(1)).optString("kind") == KIND_SESSION_START) it.getString(0) else null
        }
    }

    private fun pendingSessionIds(db: SQLiteDatabase): Set<String> {
        db.rawQuery("SELECT DISTINCT session_id FROM outbox WHERE state='pending' AND session_id IS NOT NULL", null).use {
            val result = mutableSetOf<String>()
            while (it.moveToNext()) result += it.getString(0)
            return result
        }
    }

    private fun activityExists(db: SQLiteDatabase, id: Long): Boolean {
        db.rawQuery("SELECT hidden FROM activity_cache WHERE id=?", arrayOf(id.toString())).use {
            return it.moveToFirst() && it.getInt(0) == 0
        }
    }

    private fun openSession(db: SQLiteDatabase, activityId: Long): Session? =
        sessions(db, "WHERE ended_at IS NULL AND activity_id=? ORDER BY started_at DESC LIMIT 1", arrayOf(activityId.toString()))
            .firstOrNull()

    private fun session(db: SQLiteDatabase, id: String): Session? =
        sessions(db, "WHERE id=?", arrayOf(id)).firstOrNull()

    private fun sessions(where: String, args: Array<String>?): List<Session> = sessions(readableDatabase, where, args)

    private fun sessions(db: SQLiteDatabase, where: String, args: Array<String>?): List<Session> {
        db.rawQuery("SELECT id, activity_id, started_at, ended_at, origin FROM session $where", args).use { c ->
            val result = mutableListOf<Session>()
            while (c.moveToNext()) result += decode(c)
            return result
        }
    }

    private fun decode(c: Cursor) = Session(
        id = c.getString(0),
        activityId = c.getLong(1),
        startedAt = c.getLong(2),
        endedAt = if (c.isNull(3)) null else c.getLong(3),
        origin = c.getString(4),
    )

    private fun count(sql: String): Int = readableDatabase.rawQuery(sql, null).use {
        it.moveToFirst()
        it.getInt(0)
    }

    private fun meta(key: String, fallback: String): String {
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(key)).use {
            return if (it.moveToFirst()) it.getString(0) else fallback
        }
    }

    private fun setMeta(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict(
            "meta",
            null,
            ContentValues().apply {
                put("k", key)
                put("v", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * One-time import of the earlier watch5-local-first database (stt-watch-lan-v1.db):
     * cached activities and every not-yet-uploaded session become protocol 2 operations.
     */
    private fun importLegacyLanStore(db: SQLiteDatabase) {
        val legacy = File(context.noBackupFilesDir, LEGACY_DB_NAME)
        if (!legacy.exists()) return
        try {
            SQLiteDatabase.openDatabase(legacy.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { old ->
                old.rawQuery("SELECT payload FROM activity", null).use { c ->
                    var order = 0
                    while (c.moveToNext()) {
                        val item = JSONObject(c.getString(0))
                        db.insertWithOnConflict(
                            "activity_cache",
                            null,
                            ContentValues().apply {
                                put("id", item.getLong("id"))
                                put("name", item.getString("name"))
                                put("icon", item.optString("icon", ""))
                                put("color", DEFAULT_COLOR)
                                put("hidden", if (item.optBoolean("hidden")) 1 else 0)
                                put("ord", order++)
                                put("category_ids", "[]")
                            },
                            SQLiteDatabase.CONFLICT_IGNORE,
                        )
                    }
                }
                old.rawQuery("SELECT id, activity_id, started_at, ended_at FROM session WHERE synced=0", null).use { c ->
                    while (c.moveToNext()) {
                        val session = Session(
                            id = c.getString(0),
                            activityId = c.getLong(1),
                            startedAt = c.getLong(2),
                            endedAt = if (c.isNull(3)) null else c.getLong(3),
                            origin = ORIGIN_WATCH,
                        )
                        db.insertWithOnConflict(
                            "session",
                            null,
                            ContentValues().apply {
                                put("id", session.id)
                                put("activity_id", session.activityId)
                                put("started_at", session.startedAt)
                                put("ended_at", session.endedAt)
                                put("origin", ORIGIN_WATCH)
                            },
                            SQLiteDatabase.CONFLICT_IGNORE,
                        )
                        enqueue(db, session.id, opStart(session))
                        if (session.endedAt != null) enqueue(db, session.id, opEnd(session))
                    }
                }
            }
        } catch (_: Exception) {
            // The legacy file stays untouched; its records can still be recovered manually.
        }
    }

    companion object {
        private const val DB_NAME = "timetrak-device-v1.db"
        private const val LEGACY_DB_NAME = "stt-watch-lan-v1.db"
        private const val KEY_REV = "rev"
        private const val KEY_ALLOW_MULTITASKING = "allow_multitasking"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_LAST_ERROR = "last_error"
        const val DEFAULT_COLOR = 0xFF4CAF50L

        const val ORIGIN_WATCH = "watch"
        const val ORIGIN_PHONE = "phone"
        const val KIND_SESSION_START = "session_start"
        const val KIND_SESSION_END = "session_end"
        const val KIND_SETTINGS_PUT = "settings_put"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_RETRY = "retry"
    }
}
