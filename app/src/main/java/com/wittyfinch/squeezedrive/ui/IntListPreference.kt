/*
 * This file is part of SqueezeDrive, an Android Automotive OS client for the Lyrion music server.
 * Copyright (c) 2026 SqueezeDrive contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.wittyfinch.squeezedrive.ui

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference

/**
 * A [ListPreference] that persists the selected entry value as an [Int] instead of a [String].
 *
 * The ported preference helpers (`de.maniac103.squeezeclient.extfuncs.PreferenceExtensions`)
 * read `fade_in_duration` and `volume_step_size` with `SharedPreferences.getInt()`, so the
 * values stored under those keys must be integers. The entry values in XML stay plain
 * numeric strings; only the persistence type differs from [ListPreference].
 */
class IntListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.dialogPreferenceStyle,
    defStyleRes: Int = 0
) : ListPreference(context, attrs, defStyleAttr, defStyleRes) {

    override fun getPersistedString(defaultReturnValue: String?): String =
        getPersistedInt(defaultReturnValue?.toIntOrNull() ?: 0).toString()

    override fun persistString(value: String?): Boolean =
        value?.toIntOrNull()?.let { persistInt(it) } ?: false
}
