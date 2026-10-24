package com.example.util.simpletimetracker.lan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.example.util.simpletimetracker.cloud.NativeCloudBridge
import dagger.hilt.android.AndroidEntryPoint
import org.doublecore.stt.cloud.LanAuth
import org.doublecore.stt.cloud.LanConnection
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** Status and control page for the phone-side watch receiver, opened from the settings list. */
@AndroidEntryPoint
class LanHostActivity : ComponentActivity() {

    @Inject lateinit var store: PhoneLanStore

    @Inject lateinit var cloudBridge: NativeCloudBridge

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var serviceStatus: TextView
    private lateinit var addressText: TextView
    private lateinit var secretText: TextView
    private lateinit var watchStatus: TextView
    private lateinit var cloudStatus: TextView
    private lateinit var toggle: Button

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)
        setContentView(scroll)

        fun label(text: String = "", size: Float = 15f): TextView {
            val view = TextView(this).apply {
                this.text = text
                textSize = size
                setPadding(0, 10, 0, 10)
            }
            root.addView(view)
            return view
        }
        fun button(title: String, action: () -> Unit): Button {
            val view = Button(this).apply {
                text = title
                isAllCaps = false
                setOnClickListener { action() }
            }
            root.addView(view)
            return view
        }

        label("TimeTrak · 手表 Wi-Fi 直连", 21f)
        label(
            "手表独立计时并保存在手表本地；手机开启接收后，手表会自动把开始/结束同步进原版历史，" +
                "手机上的计时也会实时显示在手表上。仅限可信 Wi-Fi 或手机热点使用，通信未加密但有签名校验。",
        )
        serviceStatus = label()
        toggle = button("") {
            if (LanReceiverService.isEnabled(this)) {
                LanReceiverService.stop(this)
            } else {
                requestNotificationPermissionIfNeeded()
                LanReceiverService.start(this)
            }
            handler.postDelayed({ render() }, 300)
        }
        addressText = label()
        secretText = label(size = 18f)
        button("重新生成配对密钥（手表需重新填写）") {
            LanReceiverService.resetSecret(this)
            render()
        }
        watchStatus = label()
        cloudStatus = label()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render() {
        val enabled = LanReceiverService.isEnabled(this)
        serviceStatus.text = when {
            enabled && LanReceiverService.running -> "接收服务：运行中（通知栏可停止）"
            enabled && LanReceiverService.lastError.isNotEmpty() -> "接收服务：启动失败 · ${LanReceiverService.lastError}"
            enabled -> "接收服务：启动中…"
            else -> "接收服务：已关闭"
        }
        toggle.text = if (enabled) "停止接收" else "开启接收"
        val addresses = LanReceiverService.addresses()
        addressText.text = if (addresses.isEmpty()) {
            "未发现局域网 IPv4：请连接 Wi-Fi 或打开手机热点"
        } else {
            "手表填写的手机地址：\n" + addresses.joinToString("\n") { "$it  (端口 ${LanConnection.PORT})" }
        }
        secretText.text = "配对密钥：\n" + LanAuth.formatSecret(LanReceiverService.secret(this))
        val lastApplied = store.lastAppliedAt()
        val lastContact = LanReceiverService.lastContactAt
        watchStatus.text = buildString {
            append("已到手机：累计接收手表操作 ${store.appliedCount()} 条")
            if (lastApplied > 0) append("\n最近写入：").append(format(lastApplied))
            if (lastContact > 0) append("\n最近联系：").append(format(lastContact)).append(" · ").append(LanReceiverService.lastPeer)
        }
        cloudStatus.text = "云端备份：${cloudBridge.status}"
    }

    private fun format(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time))

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
