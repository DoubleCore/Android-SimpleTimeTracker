/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.example.util.simpletimetracker.features.settings.screen

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.util.simpletimetracker.features.settings.ui.SettingsItemType
import com.example.util.simpletimetracker.features.settings.viewModel.SettingsViewModel
import com.example.util.simpletimetracker.presentation.LanPairingActivity

@Composable
fun SettingsScreen() {
    val viewModel = hiltViewModel<SettingsViewModel>()
    viewModel.init()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    SettingsList(
        state = state,
        onRefresh = viewModel::onRefresh,
        onSettingClick = { type ->
            when (type) {
                SettingsItemType.CloudConnection -> context.startActivity(
                    Intent(context, org.doublecore.stt.cloud.CloudSettingsActivity::class.java),
                )
                SettingsItemType.LanConnection -> context.startActivity(
                    Intent(context, LanPairingActivity::class.java),
                )
                else -> viewModel.onSettingClick(type)
            }
        },
    )
}
