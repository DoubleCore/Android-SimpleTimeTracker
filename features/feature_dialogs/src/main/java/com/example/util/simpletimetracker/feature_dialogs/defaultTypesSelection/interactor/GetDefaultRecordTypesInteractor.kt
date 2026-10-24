package com.example.util.simpletimetracker.feature_dialogs.defaultTypesSelection.interactor

import com.example.util.simpletimetracker.domain.color.model.AppColor
import com.example.util.simpletimetracker.domain.recordType.model.RecordType
import javax.inject.Inject

class GetDefaultRecordTypesInteractor @Inject constructor() {

    fun execute(): List<RecordType> {
        return defaultTypes
            .mapIndexed { index, type ->
                RecordType(
                    // Add ids for recycler, will be removed later before adding to DB.
                    id = index.toLong(),
                    name = type.name,
                    icon = type.icon,
                    color = AppColor(colorId = type.colorId, colorInt = ""),
                    defaultDuration = 0L,
                    note = "",
                )
            }
    }

    private val defaultTypes: List<DefaultRecordType> by lazy {
        listOf(
            // Only the preset DATA changes; existing card styles, icon resources and layout do not.
            DefaultRecordType(name = "深度学习", icon = "ic_import_contacts_24px", colorId = 1),
            DefaultRecordType(name = "科研工作", icon = "ic_business_center_24px", colorId = 1),
            DefaultRecordType(name = "编程开发", icon = "ic_desktop_windows_24px", colorId = 1),
            DefaultRecordType(name = "课程学习", icon = "ic_assignment_24px", colorId = 3),
            DefaultRecordType(name = "阅读", icon = "ic_import_contacts_24px", colorId = 3),
            DefaultRecordType(name = "写作整理", icon = "ic_assignment_24px", colorId = 3),
            DefaultRecordType(name = "会议交流", icon = "ic_business_center_24px", colorId = 5),
            DefaultRecordType(name = "日常事务", icon = "ic_extension_24px", colorId = 5),
            DefaultRecordType(name = "运动", icon = "ic_fitness_center_24px", colorId = 7),
            DefaultRecordType(name = "休息", icon = "ic_lightbulb_outline_24px", colorId = 7),
            DefaultRecordType(name = "生活", icon = "ic_restaurant_menu_24px", colorId = 7),
            DefaultRecordType(name = "睡眠", icon = "ic_airline_seat_individual_suite_24px", colorId = 10),
        )
    }

    private data class DefaultRecordType(
        val name: String,
        val icon: String,
        val colorId: Int,
    )
}