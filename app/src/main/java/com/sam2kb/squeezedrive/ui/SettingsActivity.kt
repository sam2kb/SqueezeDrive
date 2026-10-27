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

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.sam2kb.squeezedrive.PlayerIdentity
import com.sam2kb.squeezedrive.R
import de.maniac103.squeezeclient.cometd.ConnectionState
import de.maniac103.squeezeclient.cometd.request.LibrarySearchRequest
import de.maniac103.squeezeclient.extfuncs.connectionHelper
import de.maniac103.squeezeclient.extfuncs.localPlayerName
import de.maniac103.squeezeclient.extfuncs.prefs
import de.maniac103.squeezeclient.extfuncs.putLocalPlayerName
import de.maniac103.squeezeclient.model.PagingParams
import de.maniac103.squeezeclient.model.PlayerId
import de.maniac103.squeezeclient.model.ServerConfiguration
import de.maniac103.squeezeclient.model.SlimBrowseItemList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
    private var searchJob: Job? = null

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

        // The server address is normalized and validated before it is stored: an unparseable
        // value must never reach the ported ServerConfiguration (it used to crash request
        // handling in a loop). Accepted: host, host:port, or the same with an explicit
        // http:// / https:// prefix; bare hosts use port 9000. A trailing slash is stripped.
        findPreference<EditTextPreference>(KEY_SERVER_URL)?.apply {
            isPersistent = false
            text = sharedPrefs.getString(KEY_SERVER_URL, null)
            setOnPreferenceChangeListener { _, newValue ->
                val normalized = normalizeServerAddress(newValue as String)
                if (normalized == null) {
                    Toast.makeText(
                        requireContext(),
                        R.string.settings_server_url_invalid,
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    sharedPrefs.edit { putString(KEY_SERVER_URL, normalized) }
                    this.text = normalized
                }
                false
            }
        }

        findPreference<Preference>(KEY_RECONNECT)?.setOnPreferenceClickListener {
            connectionHelper.disconnect()
            connectionHelper.connect()
            true
        }

        findPreference<Preference>(KEY_SEARCH)?.setOnPreferenceClickListener {
            showSearchDialog()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        localPlayerNamePreference?.text =
            requireContext().prefs.localPlayerName ?: getString(R.string.settings_local_player_name_default)
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        super.onDestroyView()
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

    // -------------------------------------------------------------------------------------------
    // Server address normalization
    // -------------------------------------------------------------------------------------------

    /**
     * Normalizes a server address for storage: trims whitespace, strips a trailing slash and
     * keeps an optional http:// or https:// prefix. Returns null when the value is empty or
     * cannot be parsed as a server URL at all (checked through [ServerConfiguration.urlOrNull],
     * which reports failure instead of throwing).
     */
    private fun normalizeServerAddress(raw: String): String? {
        var value = raw.trim()
        if (value.endsWith("/")) {
            value = value.dropLast(1)
        }
        if (value.isEmpty()) {
            return null
        }
        val valid = try {
            ServerConfiguration("validation", value, null, null).urlOrNull != null
        } catch (e: Exception) {
            // urlOrNull is not supposed to throw; treat any surprise as invalid input rather
            // than letting it escape into the settings screen.
            false
        }
        return if (valid) value else null
    }

    // -------------------------------------------------------------------------------------------
    // Parked library search
    // -------------------------------------------------------------------------------------------

    private fun showSearchDialog() {
        if (searchJob?.isActive == true) {
            return
        }
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.settings_search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_search_dialog_title)
            .setView(input)
            .setNegativeButton(R.string.settings_search_cancel, null)
            .setPositiveButton(R.string.settings_search_go, null)
            .create()
        dialog.setOnShowListener {
            // Keep the dialog open on an empty query so the error is visible in context.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = input.text?.toString()?.trim().orEmpty()
                if (query.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.settings_search_empty_query,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                startSearch(query)
            }
            input.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private fun startSearch(query: String) {
        searchJob?.cancel()
        if (connectionHelper.state.value !is ConnectionState.Connected) {
            Toast.makeText(
                requireContext(),
                R.string.settings_search_not_connected,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val playerId = PlayerIdentity.localPlayerId(requireContext().prefs)
        var finished = false
        val progress = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_search_dialog_title)
            .setMessage(getString(R.string.settings_search_in_progress, query))
            .setNegativeButton(R.string.settings_search_cancel, null)
            .create()
        // Dismissing the progress dialog (its Cancel button or back) cancels the search.
        progress.setOnDismissListener {
            if (!finished) {
                searchJob?.cancel()
            }
        }
        progress.show()

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val counts = serverRequest {
                    connectionHelper.getLocalLibrarySearchResultCounts(query)
                }.getOrThrow()
                val items = mutableListOf<SlimBrowseItemList.SlimBrowseItem>()
                val categories = listOf(
                    LibrarySearchRequest.Mode.Artist() to counts.artists,
                    LibrarySearchRequest.Mode.Albums() to counts.albums,
                    LibrarySearchRequest.Mode.Tracks() to counts.tracks,
                    LibrarySearchRequest.Mode.Genres() to counts.genres
                )
                for ((mode, count) in categories) {
                    if (count > 0) {
                        val page = serverRequest {
                            connectionHelper.getLocalLibrarySearchResults(
                                playerId,
                                query,
                                mode,
                                PagingParams(0, MAX_RESULTS_PER_CATEGORY)
                            )
                        }.getOrThrow()
                        items.addAll(page.items)
                    }
                }
                finished = true
                progress.dismiss()
                if (items.isEmpty()) {
                    showNoResults(query)
                } else {
                    showResults(playerId, query, items)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finished = true
                progress.dismiss()
                showSearchError(e)
            }
        }
    }

    private fun showResults(
        playerId: PlayerId,
        query: String,
        items: List<SlimBrowseItemList.SlimBrowseItem>
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_search_results_title, query))
            .setAdapter(SearchResultAdapter(items)) { _, which ->
                playResult(playerId, items[which])
            }
            .setNegativeButton(R.string.settings_search_close, null)
            .show()
    }

    private fun playResult(playerId: PlayerId, item: SlimBrowseItemList.SlimBrowseItem) {
        val playAction = item.actions?.playAction
        if (playAction == null) {
            // Not playable (e.g. a folder): say so instead of leaving a dead tap target.
            Toast.makeText(
                requireContext(),
                R.string.settings_search_item_not_playable,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            serverRequest { connectionHelper.executeAction(playerId, playAction) }
                .onFailure { showSearchError(it) }
        }
    }

    private fun showNoResults(query: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_search_dialog_title)
            .setMessage(getString(R.string.settings_search_no_results, query))
            .setPositiveButton(R.string.settings_search_ok, null)
            .show()
    }

    private fun showSearchError(cause: Throwable) {
        // Same honesty rule as the connection status: a plain reason, never a stack trace
        // (the throwable comes from the HTTP/cometd layer and carries no credentials).
        val reason = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_search_error_title)
            .setMessage(reason)
            .setPositiveButton(R.string.settings_search_ok, null)
            .show()
    }

    /**
     * Runs one server request in an isolated scope. ConnectionHelper deliberately cancels the
     * calling coroutine when a cometd request fails; isolating the request turns that into a
     * catchable failure here, while a dismissed dialog still cancels the search itself.
     */
    private suspend fun <T> serverRequest(block: suspend () -> T): Result<T> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        return try {
            Result.success(scope.async { block() }.await())
        } catch (e: Exception) {
            if (e is CancellationException && !currentCoroutineContext().isActive) {
                throw e
            }
            Result.failure(e)
        } finally {
            scope.cancel()
        }
    }

    private inner class SearchResultAdapter(
        items: List<SlimBrowseItemList.SlimBrowseItem>
    ) : ArrayAdapter<SlimBrowseItemList.SlimBrowseItem>(
        requireContext(),
        android.R.layout.simple_list_item_2,
        items
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(context)
                    .inflate(android.R.layout.simple_list_item_2, parent, false)
            val item = getItem(position) ?: return view
            view.findViewById<TextView>(android.R.id.text1).apply {
                text = item.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SIZE_SP)
                setTextColor(ContextCompat.getColor(context, R.color.sd_text_primary))
            }
            view.findViewById<TextView>(android.R.id.text2).apply {
                val subtitle = item.subText ?: item.extraInfo ?: ""
                text = subtitle
                visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, SUBTITLE_SIZE_SP)
                setTextColor(ContextCompat.getColor(context, R.color.sd_text_secondary))
            }
            return view
        }
    }

    companion object {
        private const val KEY_CONNECTION_STATUS = "connection_status"
        private const val KEY_LOCAL_PLAYER_NAME = "local_player_name"
        private const val KEY_PASSWORD = "password"
        private const val KEY_RECONNECT = "reconnect"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SEARCH = "search_library"

        // Car-sized search result rows: title 23sp, secondary line 18sp (see themes.xml).
        private const val TITLE_SIZE_SP = 23f
        private const val SUBTITLE_SIZE_SP = 18f

        // Bounded fetch per result category; the whole library is never pulled down.
        private const val MAX_RESULTS_PER_CATEGORY = 20
    }
}
