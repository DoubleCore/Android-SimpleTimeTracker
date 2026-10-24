/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.example.util.simpletimetracker.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.doublecore.stt.cloud.LanConnection
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single coordinator between the watch store and the phone receiver:
 * pushes the outbox in order, then pulls the phone snapshot (skipped when the
 * revision is unchanged) and reconciles running timers. Local actions never wait for it.
 */
@Singleton
class LanSyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: DeviceStore,
) {
    private val lock = Mutex()

    fun configured(): Boolean = LanConnection(context).configured()

    /** @return true when local data visible in the UI changed. */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        val lan = LanConnection(context)
        if (!lan.configured()) return@withContext false
        if (!lock.tryLock()) return@withContext false
        try {
            var changed = push(lan)
            val known = store.rev()
            val state = lan.request("GET", if (known.isEmpty()) "/v2/state" else "/v2/state?known=$known", null)
            if (!state.optBoolean("unchanged")) {
                store.saveCatalogue(state)
                store.reconcileRunning(state.optJSONArray("running") ?: JSONArray(), System.currentTimeMillis())
                changed = true
            }
            store.recordSyncResult(System.currentTimeMillis(), null)
            changed
        } catch (e: Exception) {
            store.recordSyncResult(System.currentTimeMillis(), e.message ?: e.javaClass.simpleName)
            throw e
        } finally {
            lock.unlock()
        }
    }

    private fun push(lan: LanConnection): Boolean {
        var delivered = false
        while (true) {
            val batch = store.pending(BATCH)
            if (batch.isEmpty()) return delivered
            val ops = JSONArray()
            batch.forEach { ops.put(it.payload) }
            val response = try {
                lan.request("POST", "/v2/ops", JSONObject().put("ops", ops))
            } catch (e: Exception) {
                store.markAttempt(batch.map { it.opId }, e.message ?: "network")
                throw e
            }
            val results = response.getJSONArray("results")
            for (i in 0 until results.length()) {
                val result = results.getJSONObject(i)
                val status = result.getString("status")
                // Keep order: a transient failure stops this round, later ops wait for the retry.
                if (status == DeviceStore.STATUS_RETRY) return delivered
                store.acknowledge(
                    opId = result.getString("op_id"),
                    status = status,
                    error = result.optString("error", "").takeIf { it.isNotEmpty() },
                    confirmedStart = if (result.has("started_at")) result.getLong("started_at") else null,
                )
                delivered = true
            }
            if (results.length() < batch.size) return delivered
        }
    }

    private companion object {
        const val BATCH = 50
    }
}
