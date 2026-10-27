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

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class ServerConfiguration(
    val name: String,
    val hostnameAndPort: String,
    val username: String?,
    val password: String?
) {
    /** Null instead of throwing when the stored address cannot be parsed. */
    val urlOrNull: HttpUrl?
        get() = runCatching { url }.getOrNull()

    val url: HttpUrl get() {
        // Adapted for SqueezeDrive: accept an explicit scheme (LAN servers are usually plain
        // HTTP, but HTTPS works where the server provides it) instead of always forcing http,
        // which made a "https://host" entry fail to parse. Only the authority is kept: a pasted
        // path or query is not meaningful for a Lyrion server and would corrupt the cometd and
        // artwork URLs derived from this base.
        val value = hostnameAndPort.trim()
        val scheme = if (value.startsWith("https://", ignoreCase = true)) "https" else "http"
        val authority = value.substringAfter("://", value)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val url = "$scheme://$authority".toHttpUrl()
        // Lyrion's default web/API port applies whenever the address did not name one.
        val hasExplicitPort = when {
            authority.startsWith("[") -> authority.substringAfter("]", "").startsWith(":")
            authority.isEmpty() -> false
            else -> authority.lastIndexOf(':') > 0
        }
        return if (hasExplicitPort) url else url.newBuilder().port(9000).build()
    }

    @OptIn(ExperimentalEncodingApi::class)
    val credentialsAsAuthorizationHeader
        get() = if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            null
        } else {
            val auth = Base64.encode("$username:$password".toByteArray())
            "Basic $auth"
        }
}
