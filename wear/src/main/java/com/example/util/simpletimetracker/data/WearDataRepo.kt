package com.example.util.simpletimetracker.data

import android.content.Context
import com.example.util.simpletimetracker.BuildConfig
import com.example.util.simpletimetracker.domain.model.*
import com.example.util.simpletimetracker.domain.daysOfWeek.model.DayOfWeek
import com.example.util.simpletimetracker.domain.statistics.model.ChartFilterType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import org.doublecore.stt.cloud.*
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Native watch UI backed by HTTPS + durable local SQLite, with no GMS dependency. */
@Singleton
class WearDataRepo @Inject constructor(@ApplicationContext private val context: Context) {
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity=1, onBufferOverflow=BufferOverflow.DROP_OLDEST)
    val dataUpdated: SharedFlow<Unit> get() = changes
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var store: CloudStore? = null
    private var address = ""
    private val lan = LanConnection(context)
    @Volatile private var lanCache = JSONObject()
        .put("activities", org.json.JSONArray()).put("sessions", org.json.JSONArray())

    private fun refreshLan() { lanCache = lan.request("GET", "/v1/state", null) }
    private fun lanObjects(key: String): List<JSONObject> {
        val items = lanCache.getJSONArray(key)
        return (0 until items.length()).map { items.getJSONObject(it) }
    }
    private fun activityId(value: JSONObject): Long =
        if (lan.configured()) value.getLong("activity_id") else numeric(value.getString("activity_id"))
    private fun mappedId(value: JSONObject): Long =
        if (lan.configured()) value.getLong("id") else numeric(value.getString("id"))
    init {
        scope.launch {
            while (isActive) {
                try {
                    val config = CloudConfig(context)
                    if (lan.configured()) {
                        refreshLan()
                        changes.emit(Unit)
                    } else if (config.url.isNotEmpty()) {
                        CloudSync().run(database(config), config.url, config.token)
                        changes.emit(Unit)
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { changes.emit(Unit) }
                delay(2000)
            }
        }
    }
    @Synchronized private fun database(config: CloudConfig = CloudConfig(context)): CloudStore {
        check(config.url.isNotEmpty()) { "请先设置云同步连接" }
        if (store == null || address != config.url) {
            store?.close(); store = CloudStore(context, config.databaseName()); address = config.url
        }
        return requireNotNull(store)
    }
    private fun numeric(id: String) = (UUID.fromString(id).mostSignificantBits and 0x0000FFFFFFFFFFFFL) + 1_000_000_000L
    private fun activities() = (if (lan.configured()) lanObjects("activities") else database().view("activities").values.toList())
        .filter { !it.optBoolean("hidden") }
    private fun sessions() = (if (lan.configured()) lanObjects("sessions") else database().view("sessions").values.toList())
        .filter { !it.optBoolean("deleted") }
    private fun activity(id: Long) = activities().first { mappedId(it) == id }
    private fun mapped(value: JSONObject) = WearActivity(mappedId(value), value.getString("name"),
        value.optString("icon", "ic_book"), 0xff4caf50L)
    private suspend fun <T> local(action: () -> T): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            if (lan.configured()) refreshLan()
            action()
        }
    }
    private fun enqueue(kind: String, activity: String? = null, session: String? = null) {
        val config = CloudConfig(context)
        val op = JSONObject().put("id", UUID.randomUUID().toString()).put("device_id",config.device)
            .put("kind",kind).put("occurred_at",System.currentTimeMillis() / 1000 * 1000)
        if (activity != null) op.put("activity_id",activity)
        if (session != null) op.put("session_id",session)
        database(config).enqueue(op)
        WatchCloudRetryJobService.scheduleSoon(context)
        changes.tryEmit(Unit)
    }
    suspend fun loadActivities(forceReload: Boolean): Result<List<WearActivity>> = local { activities().map(::mapped) }
    suspend fun loadCurrentActivities(forceReload: Boolean): Result<WearCurrentState> = local {
        val all = sessions()
        WearCurrentState(all.filter { it.isNull("ended_at") }.map {
            WearCurrentActivity(activityId(it), it.getLong("started_at"), emptyList())
        }, all.filter { !it.isNull("ended_at") }.sortedByDescending { it.getLong("ended_at") }.take(10).map {
            WearLastRecord(activityId(it),it.getLong("started_at"),it.getLong("ended_at"),emptyList())
        }, emptyList())
    }
    suspend fun startActivity(id: Long, tags: List<WearRecordTag>, useSelectedTags: Boolean): Result<Unit> = local {
        if (lan.configured()) {
            lan.request("POST", "/v1/action", JSONObject().put("kind", "start").put("activity_id", id))
            refreshLan(); changes.tryEmit(Unit)
        } else {
            val uuid = activity(id).getString("id")
            check(sessions().none { it.getString("activity_id") == uuid && it.isNull("ended_at") }) { "此活动已在计时" }
            enqueue("start", uuid, UUID.randomUUID().toString())
        }
        Unit
    }
    suspend fun stopActivity(id: Long): Result<Unit> = local {
        if (lan.configured()) {
            val current = sessions().first { activityId(it) == id && it.isNull("ended_at") }
            lan.request("POST", "/v1/action", JSONObject().put("kind", "stop")
                .put("activity_id", id).put("expected_started_at", current.getLong("started_at")))
            refreshLan(); changes.tryEmit(Unit)
        } else {
            val current = sessions().first { activityId(it) == id && it.isNull("ended_at") }
            enqueue("stop", session=current.getString("id"))
        }
        Unit
    }
    suspend fun repeatActivity(): Result<WearRecordRepeatResult> = local {
        val last = sessions().filter { !it.isNull("ended_at") }.maxByOrNull { it.getLong("ended_at") }
        val result = when {
            last == null -> WearRecordRepeatResult.ActionResult.NoPreviousFound
            sessions().any { activityId(it) == activityId(last) && it.isNull("ended_at") } ->
                WearRecordRepeatResult.ActionResult.AlreadyTracking
            else -> {
                if (lan.configured()) {
                    lan.request("POST", "/v1/action", JSONObject().put("kind","start").put("activity_id",activityId(last)))
                    refreshLan(); changes.tryEmit(Unit)
                } else enqueue("start",last.getString("activity_id"),UUID.randomUUID().toString())
                WearRecordRepeatResult.ActionResult.Started
            }
        }
        WearRecordRepeatResult(result)
    }
    suspend fun loadStatistics(forceReload: Boolean, shift: Int, filterType: ChartFilterType): Result<List<WearStatistics>> = local {
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0);add(Calendar.DAY_OF_YEAR,shift) }
        val begin=cal.timeInMillis; cal.add(Calendar.DAY_OF_YEAR,1);val end=cal.timeInMillis
        val totals=sessions().groupBy { it.getString("activity_id") }.mapValues { (_,list) -> list.sumOf {
            (minOf(end,if (it.isNull("ended_at")) System.currentTimeMillis() else it.getLong("ended_at"))-maxOf(begin,it.getLong("started_at"))).coerceAtLeast(0)
        } }
        activities().map(::mapped).map { a -> WearStatistics(a.id,a.name,a.icon,a.color,
            totals.entries.firstOrNull { (if (lan.configured()) it.key.toLong() else numeric(it.key))==a.id }?.value ?: 0L) }.filter { it.duration>0 }
    }
    suspend fun loadTagsForActivity(activityId: Long): Result<List<WearTag>> = Result.success(emptyList())
    suspend fun loadShouldShowTagSelection(activityId: Long) = Result.success(WearShouldShowTagSelectionResult(false,emptyList(),emptyList()))
    suspend fun loadShouldShowTagValueSelection(selectedTagIds: List<Long>,clickedTagId: Long) = Result.success(false)
    suspend fun loadSettings(forceReload: Boolean) = Result.success(WearSettings(BuildConfig.WEAR_API_VERSION,true,false,emptySet(),true,false,0,DayOfWeek.MONDAY))
    suspend fun setSettings(settings: WearSetSettings): Result<Unit> = Result.failure(UnsupportedOperationException("云模式请在手机管理活动"))
    suspend fun openAppPhone(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.startActivity(android.content.Intent(context,
                if (lan.configured()) LanSettingsActivity::class.java else CloudSettingsActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            Unit
        }
    }
}
