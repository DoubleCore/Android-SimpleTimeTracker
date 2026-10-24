package com.example.util.simpletimetracker.lan

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * TimeTrak LAN protocol 2 (phone side). The watch owns its timer facts and replicates
 * them as idempotent operations; the phone never receives "remote control" commands.
 */
object LanProtocol {
    const val VERSION = 2
    const val MAX_OPS_PER_REQUEST = 50

    const val KIND_SESSION_START = "session_start"
    const val KIND_SESSION_END = "session_end"
    const val KIND_SETTINGS_PUT = "settings_put"

    const val ORIGIN_WATCH = "watch"
    const val ORIGIN_PHONE = "phone"

    const val STATUS_APPLIED = "applied"
    const val STATUS_DUPLICATE = "duplicate"
    const val STATUS_SUPERSEDED = "superseded"
    const val STATUS_REJECTED = "rejected"

    /** Transient failure: not stored in the inbox, the watch keeps and retries the operation. */
    const val STATUS_RETRY = "retry"

    fun parseOps(body: String): List<LanOp> {
        val array = JSONObject(body).getJSONArray("ops")
        require(array.length() <= MAX_OPS_PER_REQUEST) { "too_many_ops" }
        return (0 until array.length()).map { parseOp(array.getJSONObject(it)) }
    }

    private fun parseOp(json: JSONObject): LanOp {
        val opId = json.getString("op_id")
        require(isUuid(opId)) { "invalid_op_id" }
        val kind = json.getString("kind")
        return LanOp(
            opId = opId,
            kind = kind,
            sessionId = json.optString("session_id", "").takeIf { it.isNotEmpty() },
            activityId = json.optLong("activity_id", 0L),
            startedAt = json.optLong("started_at", 0L),
            endedAt = if (json.has("ended_at") && !json.isNull("ended_at")) json.getLong("ended_at") else null,
            origin = json.optString("origin", ORIGIN_WATCH),
            allowMultitasking = if (json.has("allow_multitasking")) json.getBoolean("allow_multitasking") else null,
        )
    }

    fun encodeResults(results: List<LanOpResult>, rev: String): JSONObject {
        val array = JSONArray()
        results.forEach { result ->
            val item = JSONObject().put("op_id", result.opId).put("status", result.status)
            result.error?.let { item.put("error", it) }
            result.startedAt?.let { item.put("started_at", it) }
            array.put(item)
        }
        return JSONObject().put("protocol", VERSION).put("results", array).put("rev", rev)
    }

    fun isUuid(value: String?): Boolean {
        if (value == null || value.length != 36) return false
        return runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
    }

    /** Same deterministic mapping as NativeCloudBridge.localId, so replays hit the same Room row. */
    fun recordIdFor(sessionId: String): Long {
        return (UUID.fromString(sessionId).mostSignificantBits and 0x0000FFFFFFFFFFFFL) + 1_000_000_000L
    }

    fun phoneSessionId(typeId: Long, startedAt: Long): String {
        return UUID.nameUUIDFromBytes("timetrak:phone-run:$typeId:$startedAt".toByteArray()).toString()
    }
}

data class LanOp(
    val opId: String,
    val kind: String,
    val sessionId: String?,
    val activityId: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val origin: String,
    val allowMultitasking: Boolean?,
)

data class LanOpResult(
    val opId: String,
    val status: String,
    val error: String? = null,
    val startedAt: Long? = null,
)
