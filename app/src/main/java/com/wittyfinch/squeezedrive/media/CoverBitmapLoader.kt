/*
 * Copyright (c) 2026 SqueezeDrive contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.wittyfinch.squeezedrive.media

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException

/** Preserve real album art, using bundled artwork when an image is missing or cannot be read. */
@OptIn(UnstableApi::class)
class CoverBitmapLoader(
    private val delegate: BitmapLoader,
    private val placeholderUri: Uri
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = delegate.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        withFallback(delegate.decodeBitmap(data))

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        if (uri == placeholderUri) delegate.loadBitmap(uri)
        else withFallback(delegate.loadBitmap(uri))

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap> =
        metadata.artworkData?.let { decodeBitmap(it) }
            ?: loadBitmap(metadata.artworkUri ?: placeholderUri)

    private fun withFallback(request: ListenableFuture<Bitmap>): ListenableFuture<Bitmap> =
        Futures.catchingAsync(
            request,
            IOException::class.java,
            { _: IOException -> delegate.loadBitmap(placeholderUri) },
            MoreExecutors.directExecutor()
        )
}
