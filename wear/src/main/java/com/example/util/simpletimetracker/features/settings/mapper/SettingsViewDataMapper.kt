/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.example.util.simpletimetracker.features.settings.mapper

import com.example.util.simpletimetracker.BuildConfig
import com.example.util.simpletimetracker.R
import com.example.util.simpletimetracker.core.ErrorStateMapper
import com.example.util.simpletimetracker.data.WearResourceRepo
import com.example.util.simpletimetracker.domain.model.WearSettings
import com.example.util.simpletimetracker.features.settings.screen.SettingsListState
import com.example.util.simpletimetracker.features.settings.ui.SettingsItem
import com.example.util.simpletimetracker.features.settings.ui.SettingsItemType
import javax.inject.Inject

class SettingsViewDataMapper @Inject constructor(
    private val resourceRepo: WearResourceRepo,
    private val errorStateMapper: ErrorStateMapper,
) {

    fun mapErrorState(): SettingsListState.Error {
        return SettingsListState.Error(errorStateMapper.map())
    }

    fun mapContentState(
        showCompactList: Boolean,
        groupByCategory: Boolean,
        wearSettings: WearSettings,
    ): SettingsListState.Content {
        val items = mutableListOf<SettingsItem>()

        items += SettingsItem.CheckBox(
            type = SettingsItemType.AllowMultitasking,
            text = resourceRepo.getString(R.string.settings_allow_multitasking),
            checked = wearSettings.allowMultitasking,
        )
        items += SettingsItem.Hint(
            type = SettingsItemType.AllowMultitaskingHint,
            hint = resourceRepo.getString(R.string.settings_allow_multitasking_hint),
        )
        items += SettingsItem.CheckBox(
            type = SettingsItemType.ShowCompactList,
            text = resourceRepo.getString(R.string.wear_settings_title_show_compact_list),
            checked = showCompactList,
        )
        items += SettingsItem.CheckBox(
            type = SettingsItemType.GroupByCategory,
            text = "按分类分组显示",
            checked = groupByCategory,
        )
        items += SettingsItem.Action(
            type = SettingsItemType.LanConnection,
            text = "Wi-Fi 直连手机",
        )
        items += SettingsItem.Action(
            type = SettingsItemType.CloudConnection,
            text = "云同步连接设置",
        )
        items += SettingsItem.Version(
            type = SettingsItemType.Version,
            text = getAppVersion(),
        )

        return SettingsListState.Content(
            items = items,
        )
    }

    private fun getAppVersion(): String {
        val versionText = resourceRepo.getString(R.string.settings_version)
        val appVersion = "$versionText ${BuildConfig.VERSION_NAME}"
        return if (BuildConfig.DEBUG) {
            "$appVersion ${BuildConfig.BUILD_TYPE}"
        } else {
            appVersion
        }
    }
}