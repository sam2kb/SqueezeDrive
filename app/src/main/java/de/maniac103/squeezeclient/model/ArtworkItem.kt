/*
 * This file is part of Squeeze Client, an Android client for the LMS music server.
 * Copyright (c) 2024 Danny Baumann
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

package de.maniac103.squeezeclient.model

import android.content.Context
import android.os.Parcelable
import com.wittyfinch.squeezedrive.media.MediaArtwork
import de.maniac103.squeezeclient.extfuncs.prefs
import de.maniac103.squeezeclient.extfuncs.serverConfig

interface ArtworkItem {
    val icon: String?
    val iconId: String?

    fun extractIconUrl(context: Context): String? {
        val artId = iconId?.takeIf { it.isNotBlank() }
        val image = when {
            artId?.toLongOrNull(16) != null -> "/music/$artId/cover"
            artId != null -> artId
            else -> icon?.takeIf { it.isNotBlank() }
        }
        val placeholder = MediaArtwork.placeholderUri(context).toString()
        if (image == null) return placeholder
        val baseUrl = context.prefs.serverConfig?.urlOrNull
        return if (baseUrl != null) {
            baseUrl.resolve(image)?.toString() ?: placeholder
        } else {
            image.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
                ?: placeholder
        }
    }
}

interface ParcelableArtworkItem :
    ArtworkItem,
    Parcelable
