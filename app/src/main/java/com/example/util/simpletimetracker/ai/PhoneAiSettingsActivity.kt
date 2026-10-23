package com.example.util.simpletimetracker.ai

import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** User-supplied endpoint/model/key lives on the PHONE only; no Railway AI proxy. */
@AndroidEntryPoint
class PhoneAiSettingsActivity : ComponentActivity() {
    @Inject lateinit var tagger: PhoneAiTagger
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (18 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        fun info(value: String, size: Float = 15f): TextView {
            return TextView(this).apply {
                text = value; textSize = size
                setPadding(0, 8, 0, 8)
                root.addView(this)
            }
        }
        info("TimeTrak · 手机直接 AI 自动标签", 21f)
        info("手表先保存完整记录、同步给手机。手机从 STT 本地读取可用标签，直接调用你购买的 HTTPS 大模型 API。Railway 不参与分类。")
        info("仅把新记录的描述和适用标签名称发给所选 AI 服务；可能产生模型调用费用。首次启用之前的历史记录不自动批量发送。请自行确认服务商隐私政策。")
        val config = runCatching { PhoneAiConfig(this).load() }.getOrNull()
        val endpoint = EditText(this).apply {
            hint = "完整 HTTPS Chat Completions API 地址"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(config?.endpoint ?: "")
        }
        root.addView(endpoint)
        val model = EditText(this).apply {
            hint = "模型 ID（购买服务商提供）"
            setSingleLine(true)
            setText(config?.model ?: "")
        }
        root.addView(model)
        val secret = EditText(this).apply {
            hint = if (config == null) "API Key" else "API Key（留空则保留已保存的密钥）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(secret)
        info("Key 使用 Android Keystore 加密保存在此手机；不写入 APK、GitHub 或 Railway。服务器证书必须有效，不接受重定向。")
        val status = info(if (config == null) "未启用" else "已启用 · ${tagger.status}")
        root.addView(Button(this).apply {
            text = "启用手机自动分类"
            isAllCaps = false
            setOnClickListener {
                isEnabled = false
                status.text = "正在保存设置…"
                scope.launch {
                    try {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            tagger.configure(endpoint.text.toString(), model.text.toString(), secret.text.toString())
                        }
                        secret.text.clear()
                        status.text = "已开启 · 新的带描述记录将由手机自动调用 API 匹配已有标签"
                        tagger.kick()
                        com.example.util.simpletimetracker.cloud.PhoneCloudRetryJobService.scheduleSoon(this@PhoneAiSettingsActivity)
                    } catch (e: Exception) {
                        status.text = "配置失败：${e.message ?: "请检查设置"}"
                    } finally { isEnabled = true }
                }
            }
        })
        root.addView(Button(this).apply {
            text = "关闭 AI 自动分类并清除密钥"
            isAllCaps = false
            setOnClickListener {
                tagger.disable()
                secret.text.clear()
                status.text = "自动分类已关闭，原有计时和标签保持不变"
            }
        })
        root.addView(Button(this).apply {
            text = "返回计时"
            isAllCaps = false
            setOnClickListener { finish() }
        })
    }
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
