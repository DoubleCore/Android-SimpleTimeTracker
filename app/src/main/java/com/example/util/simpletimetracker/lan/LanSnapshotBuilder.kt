package com.example.util.simpletimetracker.lan

import com.example.util.simpletimetracker.domain.category.interactor.CategoryInteractor
import com.example.util.simpletimetracker.domain.category.interactor.RecordTypeCategoryInteractor
import com.example.util.simpletimetracker.domain.color.mapper.AppColorMapper
import com.example.util.simpletimetracker.domain.prefs.interactor.PrefsInteractor
import com.example.util.simpletimetracker.domain.record.interactor.RunningRecordInteractor
import com.example.util.simpletimetracker.domain.recordType.interactor.RecordTypeInteractor
import org.doublecore.stt.cloud.LanAuth
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full phone state for the watch: activity catalogue in card order, categories,
 * running timers and shared settings. The revision is a content hash, so every
 * change (including categories and record edits that the Wear hooks skip) is seen.
 */
@Singleton
class LanSnapshotBuilder @Inject constructor(
    private val recordTypeInteractor: RecordTypeInteractor,
    private val categoryInteractor: CategoryInteractor,
    private val recordTypeCategoryInteractor: RecordTypeCategoryInteractor,
    private val runningRecordInteractor: RunningRecordInteractor,
    private val prefsInteractor: PrefsInteractor,
    private val appColorMapper: AppColorMapper,
    private val store: PhoneLanStore,
) {

    data class Snapshot(val rev: String, val json: JSONObject)

    suspend fun build(): Snapshot {
        val categoriesByType = recordTypeCategoryInteractor.getAll()
            .groupBy({ it.recordTypeId }, { it.categoryId })
        val activities = JSONArray()
        recordTypeInteractor.getAll().forEachIndexed { index, type ->
            activities.put(
                JSONObject()
                    .put("id", type.id)
                    .put("name", type.name)
                    .put("icon", type.icon)
                    .put("color", appColorMapper.mapToColorInt(type.color).toLong() and 0xFFFFFFFFL)
                    .put("hidden", type.hidden)
                    .put("order", index)
                    .put("category_ids", JSONArray(categoriesByType[type.id].orEmpty().sorted())),
            )
        }
        val categories = JSONArray()
        categoryInteractor.getAll().forEachIndexed { index, category ->
            categories.put(
                JSONObject()
                    .put("id", category.id)
                    .put("name", category.name)
                    .put("color", appColorMapper.mapToColorInt(category.color).toLong() and 0xFFFFFFFFL)
                    .put("order", index),
            )
        }
        val running = JSONArray()
        runningRecordInteractor.getAll().sortedBy { it.id }.forEach { record ->
            val watchSession = store.sessionForRunning(record.id, record.timeStarted)
            running.put(
                JSONObject()
                    .put("session_id", watchSession ?: LanProtocol.phoneSessionId(record.id, record.timeStarted))
                    .put("activity_id", record.id)
                    .put("started_at", record.timeStarted)
                    .put("origin", if (watchSession != null) LanProtocol.ORIGIN_WATCH else LanProtocol.ORIGIN_PHONE),
            )
        }
        val settings = JSONObject().put("allow_multitasking", prefsInteractor.getAllowMultitasking())
        val content = JSONObject()
            .put("activities", activities)
            .put("categories", categories)
            .put("running", running)
            .put("settings", settings)
        val rev = LanAuth.sha256Hex(content.toString().toByteArray()).take(16)
        return Snapshot(rev, content.put("rev", rev).put("protocol", LanProtocol.VERSION))
    }
}
