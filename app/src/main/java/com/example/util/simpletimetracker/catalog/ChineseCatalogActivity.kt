package com.example.util.simpletimetracker.catalog

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
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
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Optional import for EXISTING installations only. Fresh empty STT is seeded
 * automatically on first open without showing this page.
 */
@AndroidEntryPoint
class ChineseCatalogActivity : ComponentActivity() {
    @Inject lateinit var catalogue: ChineseStarterCatalog
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val scroller = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val gap = (20 * resources.displayMetrics.density).toInt()
            setPadding(gap, gap, gap, gap)
        }
        scroller.addView(body)
        setContentView(scroller)
        fun text(message: String, size: Float = 15f): TextView {
            val view = TextView(this).apply {
                this.text = message
                textSize = size
                setPadding(0, 8, 0, 8)
            }
            body.addView(view)
            return view
        }

        text("TimeTrak · 中文活动与标签", 21f)
        text("首次安装且原数据库为空时会自动导入。旧用户可在此手动补充；绝不删除、重命名或覆盖已有活动、标签、历史记录。")
        text("固定预置：7 个活动、27 个普通中文标签。手机从原有数据库读取标签，人工智能仅从当前活动对应的标签里自动选择。")
        for (item in ChineseStarterCatalog.templates) {
            text("${item.name}：${item.labels.joinToString("、")}")
        }
        val status = text("预置数据可反复导入，已存在的同名项目会复用，不会重复创建。")
        body.addView(Button(this).apply {
            this.text = "导入中文预置（保留已有数据）"
            isAllCaps = false
            setOnClickListener {
                isEnabled = false
                status.text = "正在安全写入原版 STT 数据库…"
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            catalogue.importWithoutOverwriting()
                        }
                        status.text = "已完成：新建活动 ${result.activitiesCreated} 项，新建标签 ${result.tagsCreated} 项，补充关联 ${result.linksCreated} 项。已有记录不变。"
                    } catch (failure: Exception) {
                        status.text = "导入中断：${failure.message ?: "未知错误"}。已有数据不会清空，可再次点击继续。"
                    } finally {
                        isEnabled = true
                    }
                }
            }
        })
        body.addView(Button(this).apply {
            this.text = "返回计时"
            isAllCaps = false
            setOnClickListener { finish() }
        })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
