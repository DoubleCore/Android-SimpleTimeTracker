/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.example.util.simpletimetracker.features.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.util.simpletimetracker.features.settings.viewModel.SettingsViewModel

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
            if (type == com.example.util.simpletimetracker.features.settings.ui.SettingsItemType.CloudConnection) {
                context.startActivity(android.content.Intent(
                    context,
                    org.doublecore.stt.cloud.CloudSettingsActivity::class.java,
                ))
            } else {
                viewModel.onSettingClick(type)
            }
        },
    )
    }
}
