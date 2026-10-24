package com.example.util.simpletimetracker.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.doublecore.stt.cloud.CloudConfig
import org.doublecore.stt.cloud.CloudStore
import org.doublecore.stt.cloud.CloudSync
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Non-GMS retry for Watch cloud mode. CloudStore contains the durable outbox;
 * a reconnect/reboot does not require pressing "立即同步".
 */
@AndroidEntryPoint
class WatchCloudRetryJobService : JobService() {
    @Inject lateinit var lanSync: LanSyncEngine

    @Inject lateinit var deviceStore: DeviceStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            var success = true
            try {
                val config = CloudConfig(applicationContext)
                if (config.url.isNotEmpty()) {
                    CloudStore(applicationContext, config.databaseName()).use { db ->
                        success = CloudSync().run(db, config.url, config.token)
                    }
                }
            } catch (_: Exception) {
                success = false
            }
            // Watch-owned LAN operations are independent of cloud-mode outbox records.
            // Always try each path separately; failure of one must not block the other.
            try {
                if (lanSync.configured()) {
                    lanSync.sync()
                    if (deviceStore.pendingCount() > 0) success = false
                }
            } catch (_: Exception) {
                success = false
            }
            if (isActive) jobFinished(params, !success)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PERIODIC_JOB_ID = 52063
        private const val QUICK_JOB_ID = 52064
        private val PERIOD_MILLIS = TimeUnit.MINUTES.toMillis(15)

        fun ensurePeriodic(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.allPendingJobs.any { it.id == PERIODIC_JOB_ID }) return
            scheduler.schedule(
                JobInfo.Builder(PERIODIC_JOB_ID, ComponentName(context, WatchCloudRetryJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setPeriodic(PERIOD_MILLIS)
                    .build(),
            )
        }

        /** Persisted outbox is sent automatically as soon as system scheduling permits. */
        fun scheduleSoon(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.schedule(
                JobInfo.Builder(QUICK_JOB_ID, ComponentName(context, WatchCloudRetryJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(0)
                    .setOverrideDeadline(TimeUnit.MINUTES.toMillis(2))
                    .setBackoffCriteria(TimeUnit.SECONDS.toMillis(30), JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build(),
            )
        }
    }
}
