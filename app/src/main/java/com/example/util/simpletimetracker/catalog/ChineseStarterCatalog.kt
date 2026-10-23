package com.example.util.simpletimetracker.catalog

import android.content.Context
import com.example.util.simpletimetracker.domain.color.model.AppColor
import com.example.util.simpletimetracker.domain.recordTag.interactor.RecordTypeToTagInteractor
import com.example.util.simpletimetracker.domain.recordTag.model.RecordTag
import com.example.util.simpletimetracker.domain.recordTag.model.RecordTagValueType
import com.example.util.simpletimetracker.domain.recordTag.repo.RecordTagRepo
import com.example.util.simpletimetracker.domain.recordType.model.RecordType
import com.example.util.simpletimetracker.domain.recordType.repo.RecordTypeRepo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Idempotent Chinese catalogue using STT's NATIVE Room repositories.
 *
 * Fresh database: auto-install once. Existing account: only install on explicit
 * user request; never translate, delete, or overwrite existing activity/tag IDs.
 * Names are user data; internal IDs and API/SQLite fields remain unchanged.
 */
@Singleton
class ChineseStarterCatalog @Inject constructor(
    @ApplicationContext context: Context,
    private val types: RecordTypeRepo,
    private val tags: RecordTagRepo,
    private val typesToTags: RecordTypeToTagInteractor,
) {
    private val prefs = context.getSharedPreferences("timetrak_cn_catalog_v1", Context.MODE_PRIVATE)
    private val lock = Mutex()

    data class ActivityTemplate(
        val name: String,
        val symbol: String,
        val colorId: Int,
        val labels: List<String>,
    )
    data class Result(val activitiesCreated: Int, val tagsCreated: Int, val linksCreated: Int)

    companion object {
        // Fixed Chinese defaults. The label text is stored in STT, NOT tied to locale.
        val templates: List<ActivityTemplate> = listOf(
            ActivityTemplate("科研", "研", 6, listOf("论文阅读", "实验复现", "模型训练", "消融实验", "结果分析")),
            ActivityTemplate("编程", "码", 10, listOf("功能开发", "程序调试", "代码审查", "系统部署")),
            ActivityTemplate("学习", "学", 4, listOf("理论学习", "课程学习", "资料整理", "知识复习")),
            ActivityTemplate("项目", "项", 15, listOf("需求分析", "方案设计", "文档编写", "项目沟通")),
            ActivityTemplate("会议", "会", 2, listOf("学术会议", "团队讨论", "项目汇报")),
            ActivityTemplate("生活", "生", 9, listOf("饮食", "家务", "出行", "日常事务")),
            ActivityTemplate("休息", "休", 12, listOf("午休", "娱乐", "放松")),
        )
    }

    /**
     * Run when STT first opens; do not inject predefined categories into an
     * existing user's historical database. A partially completed seed resumes.
     */
    suspend fun initializeIfFresh(): Result = lock.withLock {
        if (prefs.getBoolean("complete", false)) return@withLock Result(0, 0, 0)
        if (!prefs.getBoolean("started", false)) {
            if (types.getAll().isNotEmpty() || tags.getAll().isNotEmpty()) {
                return@withLock Result(0, 0, 0)
            }
            check(prefs.edit().putBoolean("started", true).commit()) {
                "无法持久保存中文初始化状态"
            }
        }
        val result = insertMissing()
        check(prefs.edit().putBoolean("complete", true).commit()) {
            "无法持久保存中文标签初始化结果"
        }
        result
    }

    /** Existing users choose this explicitly; safe to click repeatedly. */
    suspend fun importWithoutOverwriting(): Result = lock.withLock {
        val result = insertMissing()
        check(prefs.edit().putBoolean("started", true).putBoolean("complete", true).commit())
        result
    }

    private suspend fun insertMissing(): Result {
        val knownTypes = types.getAll().associateBy { it.name }.toMutableMap()
        val knownTags = tags.getAll().associateBy { it.name }.toMutableMap()
        var newTypes = 0
        var newTags = 0
        var newLinks = 0

        for (template in templates) {
            val type = knownTypes[template.name] ?: run {
                val value = RecordType(
                    name = template.name,
                    icon = template.symbol, // STT recognizes non-ic_ text icons, including Chinese.
                    color = AppColor(template.colorId, ""),
                    defaultDuration = 0,
                    note = "",
                )
                val id = types.add(value)
                check(id > 0) { "新增活动失败：${template.name}" }
                value.copy(id = id).also {
                    knownTypes[template.name] = it
                    newTypes++
                }
            }
            val alreadyLinked = typesToTags.getTags(type.id).toMutableSet()
            for (label in template.labels) {
                val tag = knownTags[label] ?: run {
                    val value = RecordTag(
                        name = label,
                        icon = label.take(1),
                        color = type.color,
                        iconColorSource = type.id,
                        note = "",
                        archived = false,
                        valueType = RecordTagValueType.NONE,
                        valueSuffix = "",
                    )
                    val id = tags.add(value)
                    check(id > 0) { "新增标签失败：$label" }
                    value.copy(id = id).also {
                        knownTags[label] = it
                        newTags++
                    }
                }
                if (alreadyLinked.add(tag.id)) {
                    typesToTags.addTags(type.id, listOf(tag.id))
                    newLinks++
                }
            }
        }
        return Result(newTypes, newTags, newLinks)
    }
}
