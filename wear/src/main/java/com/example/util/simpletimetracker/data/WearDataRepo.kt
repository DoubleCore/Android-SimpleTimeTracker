package com.example.util.simpletimetracker.data

import android.content.Context
import com.example.util.simpletimetracker.BuildConfig
import com.example.util.simpletimetracker.domain.daysOfWeek.model.DayOfWeek
import com.example.util.simpletimetracker.domain.model.WearActivity
import com.example.util.simpletimetracker.domain.model.WearCategory
import com.example.util.simpletimetracker.domain.model.WearCurrentActivity
import com.example.util.simpletimetracker.domain.model.WearCurrentState
import com.example.util.simpletimetracker.domain.model.WearLastRecord
import com.example.util.simpletimetracker.domain.model.WearRecordRepeatResult
import com.example.util.simpletimetracker.domain.model.WearRecordTag
import com.example.util.simpletimetracker.domain.model.WearSetSettings
import com.example.util.simpletimetracker.domain.model.WearSettings
import com.example.util.simpletimetracker.domain.model.WearShouldShowTagSelectionResult
import com.example.util.simpletimetracker.domain.model.WearStatistics
import com.example.util.simpletimetracker.domain.model.WearTag
import com.example.util.simpletimetracker.domain.statistics.model.ChartFilterType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.doublecore.stt.cloud.CloudConfig
import org.doublecore.stt.cloud.CloudSettingsActivity
import org.doublecore.stt.cloud.CloudStore
import org.doublecore.stt.cloud.CloudSync
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native watch data source without GMS.
 * Local mode (phone Wi-Fi paired, or no cloud configured): the watch owns its timers in
 * [DeviceStore] and replicates them to the phone through [LanSyncEngine].
 * Cloud mode (only a cloud server configured): the earlier HTTPS outbox path.
 */
@Singleton
class WearDataRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    private val device: DeviceStore,
    private val lanSync: LanSyncEngine,
) {
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val dataUpdated: SharedFlow<Unit> get() = changes
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var foreground = false
    private var store: CloudStore? = null
    private var address = ""
    init {
        scope.launch {
            while (isActive) {
                try {
                    if (lanSync.configured()) {
                        if (lanSync.sync()) changes.emit(Unit)
                    }
                    val config = CloudConfig(context)
                    if (config.url.isNotEmpty() && !lanSync.configured()) {
                        CloudSync().run(database(config), config.url, config.token)
                        changes.emit(Unit)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    changes.emit(Unit)
                }
                // Fast refresh only while the watch UI is visible; background retries use JobScheduler.
                withTimeoutOrNull(if (foreground) FOREGROUND_POLL_MS else BACKGROUND_POLL_MS) { wake.receive() }
            }
        }
    }

    /** Called from the activity lifecycle so the phone snapshot is polled every few seconds only when visible. */
    fun setForeground(visible: Boolean) {
        foreground = visible
        if (visible) wake.trySend(Unit)
    }

    private fun localMode(): Boolean = lanSync.configured() || CloudConfig(context).url.isEmpty()

    private fun afterLocalChange() {
        changes.tryEmit(Unit)
        wake.trySend(Unit)
        if (lanSync.configured()) WatchCloudRetryJobService.scheduleSoon(context)
    }

    // region Cloud mode (unchanged behaviour)
    @Synchronized private fun database(config: CloudConfig = CloudConfig(context)): CloudStore {
        check(config.url.isNotEmpty()) { "请先设置云同步连接" }
        if (store == null || address != config.url) {
            store?.close()
            store = CloudStore(context, config.databaseName())
            address = config.url
        }
        return requireNotNull(store)
    }
    private fun numeric(id: String) = (UUID.fromString(id).mostSignificantBits and 0x0000FFFFFFFFFFFFL) + 1_000_000_000L
    private fun activities() = database().view("activities").values.filter { !it.optBoolean("hidden") }
    private fun sessions() = database().view("sessions").values.filter { !it.optBoolean("deleted") }
    private fun activity(id: Long) = activities().first { numeric(it.getString("id")) == id }
    private fun mapped(value: JSONObject) = WearActivity(
        numeric(value.getString("id")), value.getString("name"),
        value.optString("icon", "ic_book"), 0xff4caf50L,
    )
    private fun enqueue(kind: String, activity: String? = null, session: String? = null) {
        val config = CloudConfig(context)
        val op = JSONObject().put("id", UUID.randomUUID().toString()).put("device_id", config.device)
            .put("kind", kind).put("occurred_at", System.currentTimeMillis() / 1000 * 1000)
        if (activity != null) op.put("activity_id", activity)
        if (session != null) op.put("session_id", session)
        database(config).enqueue(op)
        changes.tryEmit(Unit)
    }
    // endregion

    private suspend fun <T> local(action: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(action) }

    private fun now() = System.currentTimeMillis() / 1000 * 1000

    private fun mapped(value: DeviceStore.Activity) = WearActivity(value.id, value.name, value.icon, value.color, value.categoryIds)

    suspend fun loadActivities(forceReload: Boolean): Result<List<WearActivity>> = local {
        if (localMode()) device.activities().filter { !it.hidden }.map(::mapped) else activities().map(::mapped)
    }

    suspend fun loadCategories(): Result<List<WearCategory>> = local {
        if (localMode()) device.categories().map { WearCategory(it.id, it.name, it.color) } else emptyList()
    }

    suspend fun loadCurrentActivities(forceReload: Boolean): Result<WearCurrentState> = local {
        if (localMode()) {
            WearCurrentState(
                device.openSessions().map { WearCurrentActivity(it.activityId, it.startedAt, emptyList()) },
                device.recentClosed(10).map { WearLastRecord(it.activityId, it.startedAt, it.endedAt ?: it.startedAt, emptyList()) },
                emptyList(),
            )
        } else {
            val all = sessions()
            WearCurrentState(
                all.filter { it.isNull("ended_at") }.map {
                    WearCurrentActivity(numeric(it.getString("activity_id")), it.getLong("started_at"), emptyList())
                },
                all.filter { !it.isNull("ended_at") }.sortedByDescending { it.getLong("ended_at") }.take(10).map {
                    WearLastRecord(numeric(it.getString("activity_id")), it.getLong("started_at"), it.getLong("ended_at"), emptyList())
                },
                emptyList(),
            )
        }
    }

    suspend fun startActivity(id: Long, tags: List<WearRecordTag>, useSelectedTags: Boolean): Result<Unit> = local {
        if (localMode()) {
            device.start(id, now())
            afterLocalChange()
        } else {
            val uuid = activity(id).getString("id")
            check(sessions().none { it.getString("activity_id") == uuid && it.isNull("ended_at") }) { "此活动已在计时" }
            enqueue("start", uuid, UUID.randomUUID().toString())
        }
    }

    suspend fun stopActivity(id: Long): Result<Unit> = local {
        if (localMode()) {
            device.stop(id, now())
            afterLocalChange()
        } else {
            val current = sessions().first { numeric(it.getString("activity_id")) == id && it.isNull("ended_at") }
            enqueue("stop", session = current.getString("id"))
        }
    }

    suspend fun repeatActivity(): Result<WearRecordRepeatResult> = local {
        if (localMode()) {
            val last = device.recentClosed(1).firstOrNull()
            val result = when {
                last == null -> WearRecordRepeatResult.ActionResult.NoPreviousFound
                device.openSessions().any { it.activityId == last.activityId } -> WearRecordRepeatResult.ActionResult.AlreadyTracking
                else -> {
                    device.start(last.activityId, now())
                    afterLocalChange()
                    WearRecordRepeatResult.ActionResult.Started
                }
            }
            return@local WearRecordRepeatResult(result)
        }
        val last = sessions().filter { !it.isNull("ended_at") }.maxByOrNull { it.getLong("ended_at") }
        val result = when {
            last == null -> WearRecordRepeatResult.ActionResult.NoPreviousFound
            sessions().any { it.getString("activity_id") == last.getString("activity_id") && it.isNull("ended_at") } -> WearRecordRepeatResult.ActionResult.AlreadyTracking
            else -> {
                enqueue("start", last.getString("activity_id"), UUID.randomUUID().toString())
                WearRecordRepeatResult.ActionResult.Started
            }
        }
        WearRecordRepeatResult(result)
    }

    suspend fun loadStatistics(forceReload: Boolean, shift: Int, filterType: ChartFilterType): Result<List<WearStatistics>> = local {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, shift)
        }
        val begin = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val end = cal.timeInMillis
        val current = System.currentTimeMillis()
        if (localMode()) {
            // Statistics cover what this watch recorded or saw running; full history stays on the phone.
            val totals = device.sessionsInRange(begin, end).groupBy { it.activityId }.mapValues { (_, list) ->
                list.sumOf { (minOf(end, it.endedAt ?: current) - maxOf(begin, it.startedAt)).coerceAtLeast(0) }
            }
            return@local device.activities().map { a ->
                WearStatistics(a.id, a.name, a.icon, a.color, totals[a.id] ?: 0L)
            }.filter { it.duration > 0 }
        }
        val totals = sessions().groupBy { it.getString("activity_id") }.mapValues { (_, list) ->
            list.sumOf {
                (minOf(end, if (it.isNull("ended_at")) current else it.getLong("ended_at")) - maxOf(begin, it.getLong("started_at"))).coerceAtLeast(0)
            }
        }
        activities().map(::mapped).map { a ->
            WearStatistics(
                a.id, a.name, a.icon, a.color,
                totals.entries.firstOrNull { numeric(it.key) == a.id }?.value ?: 0L,
            )
        }.filter { it.duration > 0 }
    }

    suspend fun loadTagsForActivity(activityId: Long): Result<List<WearTag>> = Result.success(emptyList())
    suspend fun loadShouldShowTagSelection(activityId: Long) = Result.success(WearShouldShowTagSelectionResult(false, emptyList(), emptyList()))
    suspend fun loadShouldShowTagValueSelection(selectedTagIds: List<Long>, clickedTagId: Long) = Result.success(false)

    suspend fun loadSettings(forceReload: Boolean): Result<WearSettings> = local {
        val allowMultitasking = if (localMode()) device.allowMultitasking() else true
        WearSettings(BuildConfig.WEAR_API_VERSION, allowMultitasking, false, emptySet(), true, false, 0, DayOfWeek.MONDAY)
    }

    suspend fun setSettings(settings: WearSetSettings): Result<Unit> = local {
        check(localMode()) { "云模式请在手机修改设置" }
        // Shared phone setting: applied locally first, then replicated like any other operation.
        device.setAllowMultitasking(settings.allowMultitasking, now())
        afterLocalChange()
    }

    suspend fun openAppPhone(): Result<Unit> = local {
        val target = if (localMode()) {
            com.example.util.simpletimetracker.presentation.LanPairingActivity::class.java
        } else {
            CloudSettingsActivity::class.java
        }
        context.startActivity(android.content.Intent(context, target).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private companion object {
        const val FOREGROUND_POLL_MS = 3_000L
        const val BACKGROUND_POLL_MS = 60_000L
    }
}
