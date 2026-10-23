package com.example.util.simpletimetracker.cloud

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Recovery path for the phone Room -> durable outbox -> HTTPS bridge.
 * It complements the existing 2s in-process loop; it does not run a persistent daemon.
 */
@AndroidEntryPoint
class PhoneCloudRetryJobService : JobService() {
    @Inject lateinit var bridge: NativeCloudBridge
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            val success = bridge.syncScheduled()
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
        private const val PERIODIC_JOB_ID = 52061
        private const val QUICK_JOB_ID = 52062
        private val PERIOD_MILLIS = TimeUnit.MINUTES.toMillis(15)

        fun ensurePeriodic(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.allPendingJobs.any { it.id == PERIODIC_JOB_ID }) return
            val task = JobInfo.Builder(
                PERIODIC_JOB_ID, ComponentName(context, PhoneCloudRetryJobService::class.java)
            ).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(PERIOD_MILLIS)
                .build()
            scheduler.schedule(task)
        }

        /** Extra safety net for phone-side changes. In-process sync is still faster. */
        fun scheduleSoon(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val task = JobInfo.Builder(
                QUICK_JOB_ID, ComponentName(context, PhoneCloudRetryJobService::class.java)
            ).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(0)
                .setOverrideDeadline(TimeUnit.MINUTES.toMillis(2))
                .setBackoffCriteria(TimeUnit.SECONDS.toMillis(30), JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            scheduler.schedule(task)
        }
    }
}
