package com.example.util.simpletimetracker.lan

import com.example.util.simpletimetracker.domain.record.model.Record
import com.example.util.simpletimetracker.domain.record.model.RunningRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class LanOperationApplierTest {

    private val now = 1_800_000_000_000L
    private lateinit var store: FakeStore
    private lateinit var gateway: FakeGateway
    private lateinit var applier: LanOperationApplier

    @Before
    fun setUp() {
        store = FakeStore()
        gateway = FakeGateway()
        applier = LanOperationApplier(store, gateway).also { it.clock = { now } }
    }

    @Test
    fun startThenEndCreatesOneRecordWithWatchTimes() = runTest {
        val session = uuid()
        val start = start(session, typeId = 1, at = now - 60_000)
        val end = end(session, typeId = 1, startedAt = now - 60_000, endedAt = now - 1_000)

        val results = applier.apply(listOf(start, end))

        assertEquals(listOf(LanProtocol.STATUS_APPLIED, LanProtocol.STATUS_APPLIED), results.map { it.status })
        assertTrue(gateway.running.isEmpty())
        val record = gateway.records.values.single()
        assertEquals(LanProtocol.recordIdFor(session), record.id)
        assertEquals(now - 60_000, record.timeStarted)
        assertEquals(now - 1_000, record.timeEnded)
        assertEquals(1, gateway.afterChangesCalls)
    }

    @Test
    fun replayingSameOperationsIsIdempotent() = runTest {
        val session = uuid()
        val ops = listOf(start(session, 1, now - 60_000), end(session, 1, now - 60_000, now - 1_000))
        applier.apply(ops)
        val calls = gateway.afterChangesCalls

        repeat(20) {
            val replay = applier.apply(ops)
            assertEquals(LanProtocol.STATUS_APPLIED, replay.first().status) // stored inbox result
        }

        assertEquals(1, gateway.records.size)
        assertTrue(gateway.running.isEmpty())
        assertEquals(calls, gateway.afterChangesCalls)
    }

    @Test
    fun endWithNewOpIdForSameSessionDoesNotDuplicate() = runTest {
        val session = uuid()
        applier.apply(listOf(start(session, 1, now - 60_000), end(session, 1, now - 60_000, now - 1_000)))

        val again = applier.apply(listOf(end(session, 1, now - 60_000, now - 500)))

        assertEquals(LanProtocol.STATUS_DUPLICATE, again.single().status)
        assertEquals(now - 1_000, gateway.records.values.single().timeEnded)
    }

    @Test
    fun startMergesIntoAlreadyRunningPhoneTimer() = runTest {
        gateway.running[1] = RunningRecord(1, now - 120_000, "", emptyList())
        val session = uuid()

        val result = applier.apply(listOf(start(session, 1, now - 60_000))).single()

        assertEquals(LanProtocol.STATUS_DUPLICATE, result.status)
        assertEquals(now - 120_000, result.startedAt)
        assertEquals(now - 120_000, gateway.running[1]!!.timeStarted)

        // Ending the merged session stops the phone timer with the phone start time.
        val end = applier.apply(listOf(end(session, 1, now - 120_000, now - 1_000))).single()
        assertEquals(LanProtocol.STATUS_APPLIED, end.status)
        assertEquals(now - 120_000, gateway.records.values.single().timeStarted)
    }

    @Test
    fun endAfterPhoneStoppedTimerIsSuperseded() = runTest {
        val session = uuid()
        applier.apply(listOf(start(session, 1, now - 60_000)))
        gateway.running.remove(1) // stopped on the phone

        val result = applier.apply(listOf(end(session, 1, now - 60_000, now - 1_000))).single()

        assertEquals(LanProtocol.STATUS_SUPERSEDED, result.status)
        assertTrue(gateway.records.isEmpty())
    }

    @Test
    fun watchStoppingPhoneOriginTimerStopsIt() = runTest {
        gateway.running[2] = RunningRecord(2, now - 30_000, "note", emptyList())
        val session = LanProtocol.phoneSessionId(2, now - 30_000)

        val result = applier.apply(listOf(end(session, 2, now - 30_000, now - 1_000, origin = LanProtocol.ORIGIN_PHONE))).single()

        assertEquals(LanProtocol.STATUS_APPLIED, result.status)
        assertTrue(gateway.running.isEmpty())
        assertEquals("note", gateway.records.values.single().comment)
    }

    @Test
    fun phoneOriginTimerAlreadyStoppedIsSuperseded() = runTest {
        val session = LanProtocol.phoneSessionId(2, now - 30_000)

        val result = applier.apply(listOf(end(session, 2, now - 30_000, now - 1_000, origin = LanProtocol.ORIGIN_PHONE))).single()

        assertEquals(LanProtocol.STATUS_SUPERSEDED, result.status)
        assertTrue(gateway.records.isEmpty())
    }

    @Test
    fun offlineCompleteSessionWithoutStartIsImported() = runTest {
        val session = uuid()

        val result = applier.apply(listOf(end(session, 1, now - 600_000, now - 300_000))).single()

        assertEquals(LanProtocol.STATUS_APPLIED, result.status)
        assertEquals(LanProtocol.recordIdFor(session), gateway.records.values.single().id)
        assertEquals(session, gateway.adopted.single().first)
    }

    @Test
    fun startDisallowedMultitaskingStopsOtherTimers() = runTest {
        gateway.multitasking = false
        gateway.running[3] = RunningRecord(3, now - 90_000, "", emptyList())

        applier.apply(listOf(start(uuid(), 1, now - 10_000)))

        assertNull(gateway.running[3])
        assertEquals(now - 10_000, gateway.records.values.single().timeEnded)
        assertTrue(gateway.running.containsKey(1))
    }

    @Test
    fun invalidOperationsAreRejected() = runTest {
        val results = applier.apply(
            listOf(
                start(uuid(), typeId = 99, at = now - 1_000), // unknown activity
                start(uuid(), typeId = 1, at = now + 3_600_000), // future
                end(uuid(), typeId = 1, startedAt = now - 1_000, endedAt = now - 2_000), // ends before start
                LanOp(uuid(), "pause", uuid(), 1, now, null, LanProtocol.ORIGIN_WATCH, null),
            ),
        )

        assertTrue(results.all { it.status == LanProtocol.STATUS_REJECTED })
        assertTrue(gateway.running.isEmpty())
        assertTrue(gateway.records.isEmpty())
        assertEquals(0, gateway.afterChangesCalls)
    }

    @Test
    fun gatewayFailureIsRetriedNotStored() = runTest {
        gateway.failNext = true
        val op = start(uuid(), 1, now - 1_000)

        assertEquals(LanProtocol.STATUS_RETRY, applier.apply(listOf(op)).single().status)
        assertNull(store.inboxResult(op.opId))
        assertEquals(LanProtocol.STATUS_APPLIED, applier.apply(listOf(op)).single().status)
    }

    @Test
    fun settingsPutUpdatesSharedMultitasking() = runTest {
        val op = LanOp(uuid(), LanProtocol.KIND_SETTINGS_PUT, null, 0, 0, null, LanProtocol.ORIGIN_WATCH, false)

        assertEquals(LanProtocol.STATUS_APPLIED, applier.apply(listOf(op)).single().status)
        assertEquals(false, gateway.multitasking)
    }

    @Test
    fun protocolParsesAndEncodes() {
        val opId = uuid()
        val session = uuid()
        val ops = LanProtocol.parseOps(
            """{"ops":[{"op_id":"$opId","kind":"session_end","session_id":"$session","activity_id":5,"started_at":10,"ended_at":20}]}""",
        )
        assertEquals(LanOp(opId, "session_end", session, 5, 10, 20, LanProtocol.ORIGIN_WATCH, null), ops.single())

        val json = LanProtocol.encodeResults(listOf(LanOpResult(opId, "applied", startedAt = 10)), "abc")
        assertEquals(2, json.getInt("protocol"))
        assertEquals("abc", json.getString("rev"))
        assertEquals(10L, json.getJSONArray("results").getJSONObject(0).getLong("started_at"))
    }

    private fun uuid() = UUID.randomUUID().toString()

    private fun start(session: String, typeId: Long, at: Long) =
        LanOp(uuid(), LanProtocol.KIND_SESSION_START, session, typeId, at, null, LanProtocol.ORIGIN_WATCH, null)

    private fun end(
        session: String,
        typeId: Long,
        startedAt: Long,
        endedAt: Long,
        origin: String = LanProtocol.ORIGIN_WATCH,
    ) = LanOp(uuid(), LanProtocol.KIND_SESSION_END, session, typeId, startedAt, endedAt, origin, null)

    private class FakeStore : LanSessionStore {
        val inbox = mutableMapOf<String, LanOpResult>()
        val mappings = mutableMapOf<String, LanSessionMapping>()
        var applied = 0

        override fun inboxResult(opId: String) = inbox[opId]
        override fun saveInbox(result: LanOpResult) {
            inbox.putIfAbsent(result.opId, result)
        }
        override fun mapping(sessionId: String) = mappings[sessionId]
        override fun saveMapping(mapping: LanSessionMapping) {
            mappings[mapping.sessionId] = mapping
        }
        override fun sessionForRunning(typeId: Long, startedAt: Long) = mappings.values.firstOrNull {
            it.typeId == typeId && it.startedAt == startedAt && it.state == LanSessionMapping.STATE_RUNNING
        }?.sessionId
        override fun onApplied(count: Int) {
            applied += count
        }
    }

    private class FakeGateway : LanRecordsGateway {
        val types = setOf(1L, 2L, 3L)
        val running = mutableMapOf<Long, RunningRecord>()
        val records = mutableMapOf<Long, Record>()
        val adopted = mutableListOf<Pair<String, Long?>>()
        var multitasking = true
        var afterChangesCalls = 0
        var failNext = false
        private var nextId = 1L

        override suspend fun typeExists(typeId: Long) = typeId in types
        override suspend fun running(typeId: Long) = running[typeId]
        override suspend fun allRunning() = running.values.toList()
        override suspend fun startRunning(typeId: Long, startedAt: Long) {
            if (failNext) {
                failNext = false
                throw IllegalStateException("db busy")
            }
            running[typeId] = RunningRecord(typeId, startedAt, "", emptyList())
        }
        override suspend fun stopRunning(record: RunningRecord, endedAt: Long, recordId: Long) {
            val id = if (recordId == 0L) nextId++ else recordId
            records[id] = Record(id, record.id, record.timeStarted, endedAt, record.comment, record.tags)
            running.remove(record.id)
        }
        override suspend fun recordExists(recordId: Long) = records.containsKey(recordId)
        override suspend fun addRecord(record: Record) {
            records[record.id] = record
        }
        override suspend fun allowMultitasking() = multitasking
        override suspend fun setAllowMultitasking(value: Boolean) {
            multitasking = value
        }
        override fun adoptCloudIdentity(sessionId: String, typeId: Long, startedAt: Long, recordId: Long?) {
            adopted += sessionId to recordId
        }
        override suspend fun afterChanges() {
            afterChangesCalls++
        }
    }
}
