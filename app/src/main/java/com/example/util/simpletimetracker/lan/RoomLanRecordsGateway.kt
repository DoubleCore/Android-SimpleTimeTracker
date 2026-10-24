package com.example.util.simpletimetracker.lan

import com.example.util.simpletimetracker.cloud.NativeCloudBridge
import com.example.util.simpletimetracker.domain.notifications.interactor.UpdateExternalViewsInteractor
import com.example.util.simpletimetracker.domain.prefs.interactor.PrefsInteractor
import com.example.util.simpletimetracker.domain.record.interactor.RecordInteractor
import com.example.util.simpletimetracker.domain.record.interactor.RecordsUpdateInteractor
import com.example.util.simpletimetracker.domain.record.interactor.RemoveRunningRecordMediator
import com.example.util.simpletimetracker.domain.record.interactor.RunningRecordInteractor
import com.example.util.simpletimetracker.domain.record.interactor.UpdateRunningRecordsInteractor
import com.example.util.simpletimetracker.domain.record.model.Record
import com.example.util.simpletimetracker.domain.record.model.RecordBase
import com.example.util.simpletimetracker.domain.record.model.RunningRecord
import com.example.util.simpletimetracker.domain.recordType.interactor.RecordTypeInteractor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes watch facts through the original STT interactors so the timer screen,
 * notifications, widgets and statistics update exactly as for a phone-side action.
 * Phone-only automation (complex rules, pomodoro auto start) is intentionally not
 * triggered: the watch already decided what happened and when.
 */
@Singleton
class RoomLanRecordsGateway @Inject constructor(
    private val recordTypeInteractor: RecordTypeInteractor,
    private val runningRecordInteractor: RunningRecordInteractor,
    private val recordInteractor: RecordInteractor,
    private val removeRunningRecordMediator: RemoveRunningRecordMediator,
    private val updateExternalViewsInteractor: UpdateExternalViewsInteractor,
    private val recordsUpdateInteractor: RecordsUpdateInteractor,
    private val updateRunningRecordsInteractor: UpdateRunningRecordsInteractor,
    private val prefsInteractor: PrefsInteractor,
    private val cloudBridge: NativeCloudBridge,
) : LanRecordsGateway {

    override suspend fun typeExists(typeId: Long): Boolean {
        return typeId > 0 && recordTypeInteractor.get(typeId) != null
    }

    override suspend fun running(typeId: Long): RunningRecord? = runningRecordInteractor.get(typeId)

    override suspend fun allRunning(): List<RunningRecord> = runningRecordInteractor.getAll()

    override suspend fun startRunning(typeId: Long, startedAt: Long) {
        runningRecordInteractor.add(RunningRecord(typeId, startedAt, "", emptyList()))
        updateExternalViewsInteractor.onRunningRecordAdd(
            typeId = typeId,
            tagIds = emptyList(),
            updateNotificationSwitch = true,
        )
    }

    override suspend fun stopRunning(record: RunningRecord, endedAt: Long, recordId: Long) {
        val ignoreShorterThan = prefsInteractor.getIgnoreShortRecordsDuration()
        val duration = TimeUnit.MILLISECONDS.toSeconds(endedAt - record.timeStarted)
        if (ignoreShorterThan == 0L || duration > ignoreShorterThan) {
            if (recordId == 0L || !recordExists(recordId)) {
                recordInteractor.add(
                    Record(
                        id = recordId,
                        typeId = record.id,
                        timeStarted = record.timeStarted,
                        timeEnded = endedAt,
                        comment = record.comment,
                        tags = record.tags,
                    ),
                )
            }
        }
        // Removes the running row and refreshes notifications, widgets and goals.
        removeRunningRecordMediator.remove(typeId = record.id)
        updateExternalViewsInteractor.onRecordAddOrChange(
            typeIds = listOf(record.id),
            tagIds = record.tags.map(RecordBase.Tag::tagId),
            updateNotificationSwitch = true,
        )
    }

    override suspend fun recordExists(recordId: Long): Boolean = recordInteractor.get(recordId) != null

    override suspend fun addRecord(record: Record) {
        recordInteractor.add(record)
        updateExternalViewsInteractor.onRecordAddOrChange(
            typeIds = listOf(record.typeId),
            tagIds = emptyList(),
            updateNotificationSwitch = true,
        )
    }

    override suspend fun allowMultitasking(): Boolean = prefsInteractor.getAllowMultitasking()

    override suspend fun setAllowMultitasking(value: Boolean) {
        prefsInteractor.setAllowMultitasking(value)
        updateExternalViewsInteractor.onAllowMultitaskingChange()
    }

    override fun adoptCloudIdentity(sessionId: String, typeId: Long, startedAt: Long, recordId: Long?) {
        cloudBridge.adoptSessionUuid(sessionId, typeId, startedAt, recordId)
    }

    override suspend fun afterChanges() {
        recordsUpdateInteractor.send()
        updateRunningRecordsInteractor.sendFullUpdate()
        cloudBridge.notifyLocalChange()
    }
}
