package com.example.util.simpletimetracker.lan

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side LAN bookkeeping, kept outside the Room schema so the original
 * database never needs a migration for this feature.
 */
@Singleton
class PhoneLanStore @Inject constructor(
    @ApplicationContext context: Context,
) : SQLiteOpenHelper(
    context,
    File(context.noBackupFilesDir, DB_NAME).absolutePath,
    null,
    1,
),
    LanSessionStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE inbox (op_id TEXT PRIMARY KEY, status TEXT NOT NULL, error TEXT, started_at INTEGER, received_at INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE session_map (session_id TEXT PRIMARY KEY, type_id INTEGER NOT NULL, " +
                "started_at INTEGER NOT NULL, record_id INTEGER, origin TEXT NOT NULL, state TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX session_map_run_idx ON session_map(type_id, started_at)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("LAN store migration required; never drop received watch history")
    }

    @Synchronized
    override fun inboxResult(opId: String): LanOpResult? {
        readableDatabase.rawQuery("SELECT status, error, started_at FROM inbox WHERE op_id=?", arrayOf(opId)).use {
            if (!it.moveToFirst()) return null
            return LanOpResult(
                opId = opId,
                status = it.getString(0),
                error = if (it.isNull(1)) null else it.getString(1),
                startedAt = if (it.isNull(2)) null else it.getLong(2),
            )
        }
    }

    @Synchronized
    override fun saveInbox(result: LanOpResult) {
        val values = ContentValues().apply {
            put("op_id", result.opId)
            put("status", result.status)
            put("error", result.error)
            put("started_at", result.startedAt)
            put("received_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("inbox", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized
    override fun mapping(sessionId: String): LanSessionMapping? {
        readableDatabase.rawQuery(
            "SELECT type_id, started_at, record_id, origin, state FROM session_map WHERE session_id=?",
            arrayOf(sessionId),
        ).use {
            if (!it.moveToFirst()) return null
            return LanSessionMapping(
                sessionId = sessionId,
                typeId = it.getLong(0),
                startedAt = it.getLong(1),
                recordId = if (it.isNull(2)) null else it.getLong(2),
                origin = it.getString(3),
                state = it.getString(4),
            )
        }
    }

    @Synchronized
    override fun saveMapping(mapping: LanSessionMapping) {
        val values = ContentValues().apply {
            put("session_id", mapping.sessionId)
            put("type_id", mapping.typeId)
            put("started_at", mapping.startedAt)
            put("record_id", mapping.recordId)
            put("origin", mapping.origin)
            put("state", mapping.state)
        }
        writableDatabase.insertWithOnConflict("session_map", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    override fun sessionForRunning(typeId: Long, startedAt: Long): String? {
        readableDatabase.rawQuery(
            "SELECT session_id FROM session_map WHERE type_id=? AND started_at=? AND state='running' LIMIT 1",
            arrayOf(typeId.toString(), startedAt.toString()),
        ).use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    @Synchronized
    override fun onApplied(count: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            setMeta(db, KEY_APPLIED_COUNT, (meta(KEY_APPLIED_COUNT, "0").toLong() + count).toString())
            setMeta(db, KEY_LAST_APPLIED_AT, System.currentTimeMillis().toString())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun appliedCount(): Long = meta(KEY_APPLIED_COUNT, "0").toLong()

    @Synchronized
    fun lastAppliedAt(): Long = meta(KEY_LAST_APPLIED_AT, "0").toLong()

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

    companion object {
        private const val DB_NAME = "timetrak-phone-lan-v1.db"
        private const val KEY_APPLIED_COUNT = "applied_count"
        private const val KEY_LAST_APPLIED_AT = "last_applied_at"
    }
}
