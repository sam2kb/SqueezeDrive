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
 * Portions of this project are adapted from Squeeze Client,
 * Copyright (c) 2024 Danny Baumann, under the same license. See docs/UPSTREAM.md.
 */

package com.sam2kb.squeezedrive

import android.app.Application
import android.content.SharedPreferences
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import androidx.appcompat.app.AppCompatDelegate
import de.maniac103.squeezeclient.cometd.ConnectionHelper
import de.maniac103.squeezeclient.extfuncs.prefs
import de.maniac103.squeezeclient.extfuncs.serverConfig
import de.maniac103.squeezeclient.service.localplayer.LocalPlaybackService
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class SqueezeDriveApplication :
    Application(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    val json = Json {
        coerceInputValues = true
        ignoreUnknownKeys = true
    }
    val httpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val serverConfig = prefs.serverConfig
            val authHeader = serverConfig?.credentialsAsAuthorizationHeader
            val actualRequest = when {
                request.url.host == serverConfig?.urlOrNull?.host && authHeader != null ->
                    request.newBuilder().addHeader("Authorization", authHeader).build()

                else -> request
            }
            chain.proceed(actualRequest)
        }
        .followRedirects(true)
        .build()
    val connectionHelper = ConnectionHelper(this)

    // Kept from the donor: the ported context helpers (ContextExtensions.backProgressInterpolator)
    // resolve this property on the application.
    val backProgressInterpolator: Interpolator by lazy {
        AnimationUtils.loadInterpolator(this, android.R.interpolator.decelerate_quint)!!
    }

    override fun onCreate() {
        super.onCreate()

        // Don't use a lambda here, it might be garbage collected
        // (internally, listeners are stored in a WeakHashMap)
        prefs.registerOnSharedPreferenceChangeListener(this)

        // SqueezeDrive is a car app: app-owned screens are always dark.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        if (prefs.serverConfig != null) {
            connectionHelper.connect()
            LocalPlaybackService.triggerStartOrStop(this)
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        when (key) {
            "local_player_enabled" -> LocalPlaybackService.triggerStartOrStop(this)

            "server_name", "server_url", "user", "password" -> {
                connectionHelper.disconnect()
                if (prefs?.serverConfig != null) {
                    connectionHelper.connect()
                    LocalPlaybackService.triggerStartOrStop(this)
                }
            }
        }
    }
}
