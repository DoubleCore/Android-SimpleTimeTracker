/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.example.util.simpletimetracker.features.settings.ui

interface SettingsItemType {

    object AllowMultitasking : SettingsItemType
    object AllowMultitaskingHint : SettingsItemType
    object ShowCompactList : SettingsItemType
    object CloudConnection : SettingsItemType
    object Version : SettingsItemType
}