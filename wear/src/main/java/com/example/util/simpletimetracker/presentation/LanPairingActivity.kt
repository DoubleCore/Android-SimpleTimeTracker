/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.example.util.simpletimetracker.presentation

import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.example.util.simpletimetracker.data.DeviceStore
import com.example.util.simpletimetracker.data.LanSyncEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.doublecore.stt.cloud.LanAuth
import org.doublecore.stt.cloud.LanConnection
import javax.inject.Inject

/** Watch-side pairing with the phone Wi-Fi receiver; opened from the watch settings list. */
@AndroidEntryPoint
class LanPairingActivity : ComponentActivity() {

    @Inject lateinit var lanSync: LanSyncEngine

    @Inject lateinit var store: DeviceStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad * 2)
        }
        scroll.addView(root)
        setContentView(scroll)

        fun text(value: String, size: Float = 13f) = TextView(this).apply {
            text = value
            textSize = size
        }.also(root::addView)

        text("Wi-Fi 直连手机", 16f)
        text("手表独立计时，联网后自动同步到手机，并实时显示手机上的计时。")
        val connection = LanConnection(this)
        val ip = EditText(this).apply {
            setSingleLine(true)
            hint = "手机IP，如192.168.1.20"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(connection.address())
        }.also(root::addView)
        val secret = EditText(this).apply {
            setSingleLine(true)
            hint = "手机显示的配对密钥"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(connection.token().takeIf { it.isNotEmpty() }?.let(LanAuth::formatSecret).orEmpty())
        }.also(root::addView)
        val status = text("")
        fun refreshStatus() = scope.launch {
            val summary = withContext(Dispatchers.IO) {
                val last = store.lastSyncAt()
                buildString {
                    append(if (connection.configured()) "已配对 ${connection.address()}" else "未配对")
                    append("\n待同步 ${store.pendingCount()} 条")
                    val rejected = store.rejectedCount()
                    if (rejected > 0) append(" · 手机拒收 $rejected 条")
                    if (last > 0) append("\n上次同步 ").append(android.text.format.DateFormat.getTimeFormat(this@LanPairingActivity).format(last))
                    store.lastError().takeIf { it.isNotEmpty() }?.let { append("\n").append(it) }
                }
            }
            status.text = summary
        }
        refreshStatus()

        Button(this).apply {
            text = "保存并连接"
            setOnClickListener {
                try {
                    connection.save(ip.text.toString().trim(), secret.text.toString())
                    status.text = "正在连接手机…"
                    scope.launch {
                        val error = withContext(Dispatchers.IO) {
                            runCatching { lanSync.sync() }.exceptionOrNull()?.message
                        }
                        if (error != null) status.text = "连接待恢复：$error" else refreshStatus()
                    }
                } catch (e: Exception) {
                    status.text = e.message
                }
            }
        }.also(root::addView)
        Button(this).apply {
            text = "清除配对"
            setOnClickListener {
                connection.clear()
                refreshStatus()
            }
        }.also(root::addView)
        Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        }.also(root::addView)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
