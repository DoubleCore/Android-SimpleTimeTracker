package com.example.util.simpletimetracker.cloud

import android.content.Context
import com.example.util.simpletimetracker.domain.color.model.AppColor
import com.example.util.simpletimetracker.domain.record.interactor.RecordInteractor
import com.example.util.simpletimetracker.domain.record.interactor.RunningRecordInteractor
import com.example.util.simpletimetracker.domain.record.interactor.RecordsUpdateInteractor
import com.example.util.simpletimetracker.domain.record.model.Record
import com.example.util.simpletimetracker.domain.record.model.RunningRecord
import com.example.util.simpletimetracker.domain.recordType.model.RecordType
import com.example.util.simpletimetracker.domain.recordType.repo.RecordTypeRepo
import com.example.util.simpletimetracker.domain.recordTag.repo.RecordTagRepo
import com.example.util.simpletimetracker.domain.recordTag.model.RecordTag
import com.example.util.simpletimetracker.domain.recordTag.model.RecordTagValueType
import com.example.util.simpletimetracker.feature_dialogs.defaultTypesSelection.interactor.GetDefaultRecordTypesInteractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.doublecore.stt.cloud.*
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Bridges the real STT repositories to the durable cloud outbox. Never replaces the Room DB. */
@Singleton
class NativeCloudBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val types: RecordTypeRepo,
    private val tags: RecordTagRepo,
    private val defaultTypes: GetDefaultRecordTypesInteractor,
    private val records: RecordInteractor,
    private val running: RunningRecordInteractor,
    private val updates: RecordsUpdateInteractor,
    private val runningUpdates: com.example.util.simpletimetracker.domain.record.interactor.UpdateRunningRecordsInteractor,
    private val externalViews: com.example.util.simpletimetracker.domain.notifications.interactor.UpdateExternalViewsInteractor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var job: Job? = null
    private var store: CloudStore? = null
    private var address = ""
    private var fixedChineseInitialized = false
    @Volatile var status: String = "云同步未连接"
        private set

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                try { sync(); } catch (e: CancellationException) { throw e }
                catch (e: Exception) { status = "同步待重试：${e.message}" }
                delay(2000)
            }
        }
    }

    private suspend fun sync() = mutex.withLock {
        // Local preset setup must work offline, before checking cloud credentials.
        // The one-time marker also prevents recreating activities the user later removes.
        if (!fixedChineseInitialized) {
            initializeChineseCatalog()
            fixedChineseInitialized = true
        }
        val config = CloudConfig(context)
        if (config.url.isEmpty()) return@withLock
        if (address != config.url) {
            store?.close(); store = CloudStore(context, config.databaseName()); address = config.url
        }
        val db = requireNotNull(store)
        val seen = JSONObject(db.meta("native_seen", "{}"))
        // Replay an interrupted import before scanning Room, so it cannot become a spurious upload.
        val journal = JSONObject(db.meta("native_import", "{}"))
        for (key in journal.keys().asSequence().toList()) {
            val value = journal.getJSONObject(key)
            apply(db, key, value)
            seen.put(key, value)
        }
        db.setMeta("native_seen", seen.toString()); db.setMeta("native_import", "{}")
        val local = snapshot(db, config.device)
        val allKeys = (local.keys().asSequence().toList() + seen.keys().asSequence().toList()).distinct()
        for (key in allKeys.sortedBy { if (it.startsWith("a:")) 0 else 1 }) {
            val previous = seen.optJSONObject(key)
            val value = local.optJSONObject(key) ?: previous?.let {
                JSONObject(it.toString()).put(if (key.startsWith("a:")) "hidden" else "deleted", true)
            } ?: continue
            if (same(previous, value)) continue
            if (db.outstanding() >= 900) break // Room remains the durable source for the next batch.
            val op = JSONObject().put("id", UUID.randomUUID().toString()).put("device_id", config.device)
                .put("occurred_at", System.currentTimeMillis()).put("data", value)
                .put("kind", if (key.startsWith("a:")) "put_activity" else if (value.optBoolean("deleted")) "delete_session" else "put_session")
            val nextSeen = JSONObject(seen.toString()).put(key, value)
            db.enqueueWithMeta(op, "native_seen", nextSeen.toString())
            seen.put(key, value)
        }
        val complete = CloudSync().run(db, config.url, config.token)
        val pendingIds = db.pending().mapNotNull { it.optJSONObject("data")?.optString("id") }.toSet()
        val latestLocal = snapshot(db, config.device)
        var changed = false
        for ((table, prefix) in listOf("activities" to "a:", "sessions" to "s:")) {
            for ((id, raw) in db.view(table)) {
                val key = prefix + id
                val value = normalize(prefix, raw)
                if (id in pendingIds || same(seen.optJSONObject(key), value)) continue
                // A user edit made during the HTTP request wins locally and uploads next pass.
                if (!same(local.optJSONObject(key), latestLocal.optJSONObject(key))) continue
                db.setMeta("native_import", JSONObject().put(key, value).toString())
                apply(db, key, value)
                seen.put(key, value)
                db.setMeta("native_seen", seen.toString()); db.setMeta("native_import", "{}")
                changed = true
            }
        }
        if (changed) {
            updates.send(); runningUpdates.sendFullUpdate()
            externalViews.onAppStart()
        }
        status = (if (complete) "云端已连接" else "补传中／等待服务器升级") + " · 待同步 ${db.pending().size} · 需处理 ${db.rejected().size}"
    }


    /**
     * One-time data-only upgrade: do not change any layout or record IDs.
     * Fresh installs get Chinese preset activities/tags. Existing standard English
     * examples are renamed only when their original icon and color still match.
     * User-made activities and completed records are never deleted.
     */
    private suspend fun initializeChineseCatalog() {
        val prefs = context.getSharedPreferences("timetrak_fixed_chinese", Context.MODE_PRIVATE)
        if (prefs.getBoolean("initialized_v1", false)) return

        val existing = types.getAll()
        var changed = false
        if (existing.isEmpty()) {
            for (preset in defaultTypes.execute()) {
                types.add(preset.copy(id = 0L))
                changed = true
            }
        } else {
            // Exact original built-in sample names AND icons: avoid rewriting custom activities.
            val renamed = mapOf(
                "Games" to ("游戏" to "ic_headset_24px"),
                "Tv" to ("电视" to "ic_desktop_windows_24px"),
                "Youtube" to ("视频" to "ic_ondemand_video_24px"),
                "Exercise" to ("运动" to "ic_fitness_center_24px"),
                "Meditate" to ("冥想" to "ic_lightbulb_outline_24px"),
                "Read" to ("阅读" to "ic_import_contacts_24px"),
                "Chores" to ("家务" to "ic_assignment_24px"),
                "Cleaning" to ("清洁" to "ic_delete_24px"),
                "Outdoors" to ("户外" to "ic_directions_walk_24px"),
                "Indoors" to ("室内" to "ic_extension_24px"),
                "Shopping" to ("购物" to "ic_shopping_cart_24px"),
                "Cooking" to ("烹饪" to "ic_restaurant_menu_24px"),
                "Breakfast" to ("早餐" to "ic_free_breakfast_24px"),
                "Lunch" to ("午餐" to "ic_restaurant_24px"),
                "Dinner" to ("晚餐" to "ic_local_bar_24px"),
                "Sleep" to ("睡眠" to "ic_airline_seat_individual_suite_24px"),
                "Commute" to ("通勤" to "ic_airport_shuttle_24px"),
                "Work" to ("工作" to "ic_business_center_24px"),
            )
            val existingNames = existing.map { it.name }.toMutableSet()
            for (type in existing) {
                val (translated, originalIcon) = renamed[type.name] ?: continue
                if (type.icon != originalIcon || translated in existingNames) continue
                types.rename(type.id, translated)
                existingNames.remove(type.name)
                existingNames.add(translated)
                changed = true
            }
        }

        if (tags.getAll().isEmpty()) {
            for (name in listOf("计划", "专注", "资料整理", "深度工作",
                "协作沟通", "复盘", "中断", "临时事务")) {
                tags.add(RecordTag(
                    name = name,
                    icon = "ic_assignment_24px",
                    color = AppColor(colorId = 3, colorInt = ""),
                    iconColorSource = 0L,
                    note = "",
                    valueType = RecordTagValueType.NONE,
                    valueSuffix = "",
                ))
                changed = true
            }
        }
        check(prefs.edit().putBoolean("initialized_v1", true).commit()) {
            "保存中文活动初始化状态失败"
        }
        if (changed) {
            updates.send()
            runningUpdates.sendFullUpdate()
            externalViews.onAppStart()
        }
    }

    private fun uuid(device: String, key: String) = UUID.nameUUIDFromBytes("$device:$key".toByteArray()).toString()
    private fun localId(id: String): Long = (UUID.fromString(id).mostSignificantBits and 0x0000FFFFFFFFFFFFL) + 1_000_000_000L
    private fun typeUuid(db: CloudStore, device: String, id: Long): String {
        val key = "native_type:$id"
        val value = db.meta(key, uuid(device, key))
        db.setMeta(key, value); db.setMeta("native_type_id:$value", id.toString())
        return value
    }
    private fun sessionUuid(db: CloudStore, device: String, type: Long, start: Long, recordId: Long?): String {
        val runKey = "native_run:$type:$start"
        val recordKey = "native_record:$recordId"
        val value = if (recordId != null) db.meta(recordKey, db.meta(runKey, uuid(device, runKey)))
            else db.meta(runKey, uuid(device, runKey))
        db.setMeta(runKey, value)
        if (recordId != null) { db.setMeta(recordKey, value); db.setMeta("native_record_id:$value", recordId.toString()) }
        return value
    }
    private suspend fun snapshot(db: CloudStore, device: String): JSONObject {
        val result = JSONObject()
        for (type in types.getAll()) {
            val id = typeUuid(db, device, type.id)
            result.put("a:$id", JSONObject().put("id", id).put("name", type.name)
                .put("icon", type.icon).put("color_id", type.color.colorId).put("color_int", type.color.colorInt)
                .put("default_duration", type.defaultDuration).put("note", type.note).put("hidden", type.hidden))
        }
        // Scan completed records first; a stop creates the record before removing its running row.
        for (record in records.getAll()) {
            val id = sessionUuid(db, device, record.typeId, record.timeStarted, record.id)
            result.put("s:$id", session(id, typeUuid(db, device, record.typeId), record.timeStarted, record.timeEnded, record.comment))
        }
        for (record in running.getAll()) {
            val id = sessionUuid(db, device, record.id, record.timeStarted, null)
            if (!result.has("s:$id")) result.put("s:$id", session(id, typeUuid(db, device, record.id), record.timeStarted, null, record.comment))
        }
        return result
    }
    private fun session(id: String, type: String, start: Long, end: Long?, comment: String) = JSONObject()
        .put("id", id).put("activity_id", type).put("started_at", start).put("ended_at", end ?: JSONObject.NULL)
        .put("comment", comment).put("deleted", false)
    private fun normalize(prefix: String, value: JSONObject): JSONObject = if (prefix == "a:") JSONObject()
        .put("id", value.getString("id")).put("name", value.getString("name"))
        .put("icon", value.optString("icon", "ic_book")).put("color_id", value.optInt("color_id", 0))
        .put("color_int", value.optString("color_int", "")).put("default_duration", value.optLong("default_duration", 0))
        .put("note", value.optString("note", "")).put("hidden", value.optBoolean("hidden", false))
    else session(value.getString("id"), value.getString("activity_id"), value.getLong("started_at") / 1000 * 1000,
        if (value.isNull("ended_at")) null else value.getLong("ended_at") / 1000 * 1000, value.optString("comment", ""))
        .put("deleted", value.optBoolean("deleted", false))

    private suspend fun apply(db: CloudStore, key: String, value: JSONObject) {
        val id = value.getString("id")
        if (key.startsWith("a:")) {
            val local = db.meta("native_type_id:$id", localId(id).toString()).toLong()
            db.setMeta("native_type_id:$id", local.toString()); db.setMeta("native_type:$local", id)
            val incoming = RecordType(local, value.getString("name"), value.getString("icon"),
                AppColor(value.getInt("color_id"), value.getString("color_int")), value.getLong("default_duration"),
                value.getString("note"), value.getBoolean("hidden"))
            // Preserve the original Room row and its historical record references.
            if (types.get(local) == null) types.add(incoming) else types.updateMetadata(incoming)
        } else {
            val type = db.meta("native_type_id:${value.getString("activity_id")}", "").toLongOrNull() ?: return
            val start = value.getLong("started_at")
            db.setMeta("native_run:$type:$start", id)
            val recordId = db.meta("native_record_id:$id", localId(id).toString()).toLong()
            db.setMeta("native_record_id:$id", recordId.toString()); db.setMeta("native_record:$recordId", id)
            val current = running.get(type)
            val sameRunning = current != null && db.meta("native_run:$type:${current.timeStarted}", "") == id
            if (value.optBoolean("deleted")) {
                records.remove(recordId)
                if (sameRunning) running.remove(type)
            } else if (value.isNull("ended_at")) {
                // Never overwrite a different local running session that is awaiting reconciliation.
                if (current == null || sameRunning) running.add(RunningRecord(type, start, value.getString("comment"), current?.tags.orEmpty()))
            } else {
                records.add(Record(recordId, type, start, value.getLong("ended_at"), value.getString("comment"), (records.get(recordId)?.tags ?: current?.takeIf { sameRunning }?.tags).orEmpty()))
                if (sameRunning) running.remove(type)
            }
        }
    }
    private fun same(a: JSONObject?, b: JSONObject?): Boolean {
        if (a == null || b == null) return a == b
        return a.length() == b.length() && a.keys().asSequence().all { a.opt(it).toString() == b.opt(it).toString() }
    }
}
