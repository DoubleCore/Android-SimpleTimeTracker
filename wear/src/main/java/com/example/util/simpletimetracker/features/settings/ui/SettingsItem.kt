/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.example.util.simpletimetracker.features.settings.ui

sealed interface SettingsItem {
    val type: SettingsItemType

    data class CheckBox(
        override val type: SettingsItemType,
        val text: String,
        val checked: Boolean,
    ) : SettingsItem

    data class Action(
        override val type: SettingsItemType,
        val text: String,
    ) : SettingsItem

    data class Hint(
        override val type: SettingsItemType,
        val hint: String,
    ) : SettingsItem

    data class Version(
        override val type: SettingsItemType,
        val text: String,
    ) : SettingsItem
}