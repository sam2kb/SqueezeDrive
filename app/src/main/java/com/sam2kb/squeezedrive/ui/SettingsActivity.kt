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

package com.sam2kb.squeezedrive.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.sam2kb.squeezedrive.R
import de.maniac103.squeezeclient.cometd.ConnectionState
import de.maniac103.squeezeclient.extfuncs.connectionHelper
import de.maniac103.squeezeclient.extfuncs.localPlayerName
import de.maniac103.squeezeclient.extfuncs.prefs
import de.maniac103.squeezeclient.extfuncs.putLocalPlayerName
import kotlinx.coroutines.launch

/**
 * Parked settings screen for the car, launched by the AAOS settings host through the
 * APPLICATION_PREFERENCES action. It is intentionally not distraction-optimized: it is
 * only meant to be used while parked, while the AAOS media host owns the driving UI.
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }
}

class SettingsFragment : PreferenceFragmentCompat() {
    private val connectionHelper get() = requireContext().connectionHelper
    private var localPlayerNamePreference: EditTextPreference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // androidx.preference ignores android:inputType on the preference tag, so mask the
        // password dialog input here (the tag still declares the intent).
        findPreference<EditTextPreference>(KEY_PASSWORD)?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        // The ported preference helpers own the storage contract for this key (the server
        // terminates player names with a NUL byte, which must not be stored), so keep the
        // raw dialog value out of the preference store and write through putLocalPlayerName().
        val sharedPrefs = requireContext().prefs
        localPlayerNamePreference = findPreference<EditTextPreference>(KEY_LOCAL_PLAYER_NAME)?.apply {
            isPersistent = false
            text = sharedPrefs.localPlayerName ?: getString(R.string.settings_local_player_name_default)
            setOnPreferenceChangeListener { _, newValue ->
                val name = (newValue as String).trimEnd('\u0000')
                sharedPrefs.edit { putLocalPlayerName(name) }
                this.text = name
                false
            }
        }

        findPreference<Preference>(KEY_RECONNECT)?.setOnPreferenceClickListener {
            connectionHelper.disconnect()
            connectionHelper.connect()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        localPlayerNamePreference?.text =
            requireContext().prefs.localPlayerName ?: getString(R.string.settings_local_player_name_default)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val statusPreference = findPreference<Preference>(KEY_CONNECTION_STATUS) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                connectionHelper.state.collect { state ->
                    statusPreference.summary = connectionStatusText(state)
                }
            }
        }
    }

    private fun connectionStatusText(state: ConnectionState): String = when (state) {
        is ConnectionState.Disconnected ->
            getString(R.string.settings_connection_status_disconnected)

        is ConnectionState.Connecting ->
            getString(R.string.settings_connection_status_connecting)

        is ConnectionState.Connected ->
            resources.getQuantityString(
                R.plurals.settings_connection_status_connected,
                state.players.size,
                state.players.size
            )

        is ConnectionState.ConnectionFailure ->
            getString(
                R.string.settings_connection_status_failed,
                // The throwable comes from the HTTP/cometd layer and never carries the
                // credentials; still, prefer a plain reason over a stack trace.
                state.cause.message?.takeIf { it.isNotBlank() } ?: state.cause.javaClass.simpleName
            )
    }

    companion object {
        private const val KEY_CONNECTION_STATUS = "connection_status"
        private const val KEY_LOCAL_PLAYER_NAME = "local_player_name"
        private const val KEY_PASSWORD = "password"
        private const val KEY_RECONNECT = "reconnect"
    }
}
