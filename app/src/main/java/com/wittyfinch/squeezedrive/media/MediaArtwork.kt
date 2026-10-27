/*
 * Copyright (c) 2026 SqueezeDrive contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.wittyfinch.squeezedrive.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.annotation.DrawableRes
import com.wittyfinch.squeezedrive.R

/** Resource URIs let the media host load artwork without a network connection or credentials. */
object MediaArtwork {
    fun resourceUri(context: Context, @DrawableRes resourceId: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath(resourceId.toString())
        .build()

    fun placeholderUri(context: Context): Uri = resourceUri(context, R.drawable.artwork_placeholder)

    fun forNode(context: Context, mediaId: String): Uri {
        val resourceId = when (MediaIds.parse(mediaId)) {
            MediaIds.Node.Favourites -> R.drawable.artwork_favourites
            MediaIds.Node.Random, MediaIds.Node.RandomMix, MediaIds.Node.RandomFinite,
            MediaIds.Node.RandomAbout -> R.drawable.artwork_random
            MediaIds.Node.Library -> R.drawable.artwork_library
            MediaIds.Node.Queue -> R.drawable.artwork_queue
            else -> R.drawable.artwork_placeholder
        }
        return resourceUri(context, resourceId)
    }
}
