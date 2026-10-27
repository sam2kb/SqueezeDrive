/*
 * SqueezeDrive - Lyrion music for your car
 * Copyright (c) 2026 SqueezeDrive contributors
 *
 * Portions adapted from Squeeze Client, Copyright (c) 2024 Danny Baumann.
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
 */

package com.wittyfinch.squeezedrive

import android.content.SharedPreferences
import de.maniac103.squeezeclient.extfuncs.getOrCreateDeviceIdentifier
import de.maniac103.squeezeclient.model.PlayerId

/**
 * Derives the identity this device reports to the Lyrion server.
 *
 * The server identifies a slimproto player by the MAC address carried in its HELO packet, so the
 * local playback service sends a MAC derived from a stable, persisted device identifier. The same
 * derivation is used here so the media service addresses the very same player the local player
 * registers as (see LocalPlaybackService / SlimprotoSocket.sendHello).
 */
object PlayerIdentity {
    /**
     * The ID the server knows this device's local player under. A pseudo-MAC is derived from the
     * persisted device identifier so it is stable across restarts but contains no real hardware
     * address.
     */
    fun localPlayerId(prefs: SharedPreferences): PlayerId {
        val identifier = prefs.getOrCreateDeviceIdentifier()
        val mac = (0..5).joinToString(":") {
            "%02x".format((identifier.leastSignificantBits shr (it * 8)) and 0xff)
        }
        return PlayerId(mac)
    }
}
