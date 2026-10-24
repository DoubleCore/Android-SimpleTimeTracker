package com.example.util.simpletimetracker.lan

import com.example.util.simpletimetracker.domain.record.model.Record
import com.example.util.simpletimetracker.domain.record.model.RunningRecord
import com.example.util.simpletimetracker.lan.LanProtocol.KIND_SESSION_END
import com.example.util.simpletimetracker.lan.LanProtocol.KIND_SESSION_START
import com.example.util.simpletimetracker.lan.LanProtocol.KIND_SETTINGS_PUT
import com.example.util.simpletimetracker.lan.LanProtocol.ORIGIN_PHONE
import com.example.util.simpletimetracker.lan.LanProtocol.STATUS_APPLIED
import com.example.util.simpletimetracker.lan.LanProtocol.STATUS_DUPLICATE
import com.example.util.simpletimetracker.lan.LanProtocol.STATUS_REJECTED
import com.example.util.simpletimetracker.lan.LanProtocol.STATUS_RETRY
import com.example.util.simpletimetracker.lan.LanProtocol.STATUS_SUPERSEDED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Persistent phone-side bookkeeping for watch sessions (separate SQLite file, not Room). */
interface LanSessionStore {
    fun inboxResult(opId: String): LanOpResult?
    fun saveInbox(result: LanOpResult)
    fun mapping(sessionId: String): LanSessionMapping?
    fun saveMapping(mapping: LanSessionMapping)
    fun sessionForRunning(typeId: Long, startedAt: Long): String?
    fun onApplied(count: Int)
}

data class LanSessionMapping(
    val sessionId: String,
    val typeId: Long,
    val startedAt: Long,
    val recordId: Long?,
    val origin: String,
    val state: String,
) {
    companion object {
        const val STATE_RUNNING = "running"
        const val STATE_ENDED = "ended"
        const val STATE_SUPERSEDED = "superseded"
    }
}

/** The only place watch facts touch the original STT repositories. */
interface LanRecordsGateway {
    suspend fun typeExists(typeId: Long): Boolean
    suspend fun running(typeId: Long): RunningRecord?
    suspend fun allRunning(): List<RunningRecord>
    suspend fun startRunning(typeId: Long, startedAt: Long)

    /** [recordId] 0 lets Room generate the id. Short records follow the user's ignore setting. */
    suspend fun stopRunning(record: RunningRecord, endedAt: Long, recordId: Long)
    suspend fun recordExists(recordId: Long): Boolean
    suspend fun addRecord(record: Record)
    suspend fun allowMultitasking(): Boolean
    suspend fun setAllowMultitasking(value: Boolean)

    /** Called before the Room write so the cloud bridge reuses the watch session UUID. */
    fun adoptCloudIdentity(sessionId: String, typeId: Long, startedAt: Long, recordId: Long?)
    suspend fun afterChanges()
}

/**
 * Applies watch operations idempotently:
 * - same op_id twice returns the stored result,
 * - same session twice never creates a second running timer or record,
 * - a timer the phone already stopped is reported as superseded (phone wins).
 */
@Singleton
class LanOperationApplier @Inject constructor(
    private val store: LanSessionStore,
    private val gateway: LanRecordsGateway,
) {
    private val mutex = Mutex()
    internal var clock: () -> Long = System::currentTimeMillis

    suspend fun apply(ops: List<LanOp>): List<LanOpResult> = mutex.withLock {
        var applied = 0
        val results = ops.map { op ->
            store.inboxResult(op.opId)?.let { return@map it }
            val result = try {
                applyOne(op)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LanOpResult(op.opId, STATUS_RETRY, "internal")
            }
            if (result.status != STATUS_RETRY) store.saveInbox(result)
            if (result.status == STATUS_APPLIED) applied++
            result
        }
        if (applied > 0) {
            store.onApplied(applied)
            gateway.afterChanges()
        }
        results
    }

    private suspend fun applyOne(op: LanOp): LanOpResult {
        return when (op.kind) {
            KIND_SESSION_START -> start(op)
            KIND_SESSION_END -> end(op)
            KIND_SETTINGS_PUT -> settings(op)
            else -> rejected(op, "unknown_kind")
        }
    }

    private suspend fun start(op: LanOp): LanOpResult {
        val sessionId = op.sessionId?.takeIf(LanProtocol::isUuid) ?: return rejected(op, "invalid_session_id")
        val now = clock()
        if (!validTime(op.startedAt, now)) return rejected(op, "invalid_time")
        if (!gateway.typeExists(op.activityId)) return rejected(op, "activity_missing")
        store.mapping(sessionId)?.let { return LanOpResult(op.opId, STATUS_DUPLICATE, startedAt = it.startedAt) }

        val current = gateway.running(op.activityId)
        if (current != null) {
            // The phone is already timing this activity: merge, the phone start time wins.
            store.saveMapping(mapping(sessionId, op, current.timeStarted, null, LanSessionMapping.STATE_RUNNING))
            return LanOpResult(op.opId, STATUS_DUPLICATE, startedAt = current.timeStarted)
        }
        if (!gateway.allowMultitasking()) {
            // Safety net when the watch acted on a stale snapshot; the watch normally ends these first.
            gateway.allRunning()
                .filter { it.id != op.activityId }
                .forEach { gateway.stopRunning(it, maxOf(it.timeStarted, op.startedAt), recordId = 0L) }
        }
        gateway.adoptCloudIdentity(sessionId, op.activityId, op.startedAt, recordId = null)
        gateway.startRunning(op.activityId, op.startedAt)
        store.saveMapping(mapping(sessionId, op, op.startedAt, null, LanSessionMapping.STATE_RUNNING))
        return LanOpResult(op.opId, STATUS_APPLIED, startedAt = op.startedAt)
    }

    private suspend fun end(op: LanOp): LanOpResult {
        val sessionId = op.sessionId?.takeIf(LanProtocol::isUuid) ?: return rejected(op, "invalid_session_id")
        val endedAt = op.endedAt ?: return rejected(op, "invalid_time")
        val now = clock()
        if (!validTime(op.startedAt, now) || !validTime(endedAt, now) ||
            endedAt < op.startedAt || endedAt - op.startedAt > MAX_DURATION_MS
        ) {
            return rejected(op, "invalid_time")
        }
        if (!gateway.typeExists(op.activityId)) return rejected(op, "activity_missing")

        val known = store.mapping(sessionId)
        if (known != null && known.state != LanSessionMapping.STATE_RUNNING) {
            return LanOpResult(op.opId, STATUS_DUPLICATE)
        }
        val recordId = LanProtocol.recordIdFor(sessionId)
        val current = gateway.running(op.activityId)
        val expectedStart = known?.startedAt ?: op.startedAt
        if (current != null && current.timeStarted == expectedStart) {
            gateway.stopRunning(current, maxOf(endedAt, current.timeStarted), recordId)
            store.saveMapping(mapping(sessionId, op, current.timeStarted, recordId, LanSessionMapping.STATE_ENDED))
            return LanOpResult(op.opId, STATUS_APPLIED)
        }
        if (known != null || op.origin == ORIGIN_PHONE) {
            // The phone stopped or replaced this timer first; its history is authoritative.
            store.saveMapping(mapping(sessionId, op, expectedStart, null, LanSessionMapping.STATE_SUPERSEDED))
            return LanOpResult(op.opId, STATUS_SUPERSEDED)
        }
        // Started and stopped while the phone was unreachable: import the complete record.
        if (!gateway.recordExists(recordId)) {
            gateway.adoptCloudIdentity(sessionId, op.activityId, op.startedAt, recordId)
            gateway.addRecord(Record(recordId, op.activityId, op.startedAt, endedAt, "", emptyList()))
        }
        store.saveMapping(mapping(sessionId, op, op.startedAt, recordId, LanSessionMapping.STATE_ENDED))
        return LanOpResult(op.opId, STATUS_APPLIED)
    }

    private suspend fun settings(op: LanOp): LanOpResult {
        val value = op.allowMultitasking ?: return rejected(op, "invalid_settings")
        if (gateway.allowMultitasking() == value) return LanOpResult(op.opId, STATUS_DUPLICATE)
        gateway.setAllowMultitasking(value)
        return LanOpResult(op.opId, STATUS_APPLIED)
    }

    private fun mapping(sessionId: String, op: LanOp, startedAt: Long, recordId: Long?, state: String) =
        LanSessionMapping(sessionId, op.activityId, startedAt, recordId, op.origin, state)

    private fun rejected(op: LanOp, error: String) = LanOpResult(op.opId, STATUS_REJECTED, error)

    private fun validTime(timestamp: Long, now: Long): Boolean {
        return timestamp >= MIN_TIMESTAMP && timestamp <= now + CLOCK_TOLERANCE_MS
    }

    companion object {
        private const val MIN_TIMESTAMP = 946_684_800_000L // 2000-01-01
        private const val CLOCK_TOLERANCE_MS = 120_000L
        private const val MAX_DURATION_MS = 366L * 24 * 3600 * 1000
    }
}
