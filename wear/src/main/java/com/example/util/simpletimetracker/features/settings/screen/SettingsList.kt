/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.example.util.simpletimetracker.features.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material.Text
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.util.simpletimetracker.R
import com.example.util.simpletimetracker.features.settings.ui.SettingsCheckbox
import com.example.util.simpletimetracker.features.settings.ui.SettingsHint
import com.example.util.simpletimetracker.features.settings.ui.SettingsItem
import com.example.util.simpletimetracker.features.settings.ui.SettingsItemType
import com.example.util.simpletimetracker.features.settings.ui.SettingsVersion
import com.example.util.simpletimetracker.presentation.layout.ScaffoldedScrollingColumn
import com.example.util.simpletimetracker.presentation.ui.ErrorState
import com.example.util.simpletimetracker.presentation.ui.RenderLoading
import com.example.util.simpletimetracker.presentation.ui.renderError

sealed interface SettingsListState {

    data object Loading : SettingsListState

    data class Error(
        val error: ErrorState,
    ) : SettingsListState

    data class Content(
        val items: List<SettingsItem>,
    ) : SettingsListState
}

@Composable
fun SettingsList(
    state: SettingsListState,
    onRefresh: () -> Unit = {},
    onSettingClick: (SettingsItemType) -> Unit = {},
) {
    ScaffoldedScrollingColumn(
        spacedBy = 0.dp,
    ) {
        when (state) {
            is SettingsListState.Loading -> item {
                RenderLoading()
            }
            is SettingsListState.Error -> {
                renderError(
                    state = state.error,
                    spacedBy = 10.dp,
                    onRefresh = onRefresh,
                )
            }
            is SettingsListState.Content -> {
                renderContent(
                    state = state,
                    onSettingClick = onSettingClick,
                )
            }
        }
    }
}

private fun ScalingLazyListScope.renderContent(
    state: SettingsListState.Content,
    onSettingClick: (SettingsItemType) -> Unit,
) {
    item { Spacer(Modifier) }
    for (item in state.items) {
        item {
            val onClick = remember(item) {
                { onSettingClick(item.type) }
            }
            when (item) {
                is SettingsItem.Action -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onClick),
                    ) {
                        Text(
                            modifier = Modifier
                                .padding(vertical = 3.dp)
                                .padding(horizontal = 4.dp)
                                .fillMaxWidth(),
                            text = item.text,
                        )
                    }
                }
                is SettingsItem.CheckBox -> {
                    SettingsCheckbox(
                        state = item,
                        onClick = onClick,
                    )
                }
                is SettingsItem.Hint -> {
                    SettingsHint(item)
                }
                is SettingsItem.Version -> {
                    SettingsVersion(item)
                }
            }
        }
    }
}

@Preview(device = WearDevices.LARGE_ROUND)
@Composable
private fun Loading() {
    SettingsList(
        state = SettingsListState.Loading,
    )
}

@Preview(device = WearDevices.LARGE_ROUND)
@Composable
private fun Error() {
    SettingsList(
        state = SettingsListState.Error(
            ErrorState(R.string.wear_loading_error),
        ),
    )
}

@Preview(device = WearDevices.LARGE_ROUND)
@Composable
private fun Content() {
    val items = listOf(
        SettingsItem.CheckBox(
            type = SettingsItemType.ShowCompactList,
            text = "Setting",
            checked = true,
        ),
        SettingsItem.Hint(
            type = SettingsItemType.AllowMultitaskingHint,
            hint = "Hint",
        ),
        SettingsItem.Version(
            type = SettingsItemType.Version,
            text = "Version 1.43",
        ),
    )
    SettingsList(
        state = SettingsListState.Content(
            items = items,
        ),
    )
}

@Preview(
    device = WearDevices.LARGE_ROUND,
    showSystemUi = true,
    fontScale = 1.5f,
)
@Composable
private fun ContentLong() {
    val items = listOf(
        SettingsItem.CheckBox(
            type = SettingsItemType.ShowCompactList,
            text = "Setting Setting Setting Setting Setting",
            checked = true,
        ),
        SettingsItem.Hint(
            type = SettingsItemType.AllowMultitaskingHint,
            hint = "Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint Hint ",
        ),
        SettingsItem.Version(
            type = SettingsItemType.Version,
            text = "Version 1.43",
        ),
    )
    SettingsList(
        state = SettingsListState.Content(
            items = items,
        ),
    )
}