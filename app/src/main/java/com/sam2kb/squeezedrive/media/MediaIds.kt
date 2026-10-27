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

package com.sam2kb.squeezedrive.media

import de.maniac103.squeezeclient.model.JiveAction
import de.maniac103.squeezeclient.model.JiveActions
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The media-id scheme for the SqueezeDrive browse tree.
 *
 * Ids are deterministic so that the same logical item always maps to the same id (which lets
 * `onGetItem` / `onSetMediaItems` re-resolve an item the host saw earlier):
 *
 *  - fixed node ids: `sd:root`, `sd:favourites`, `sd:random`, `sd:library`, `sd:queue`
 *  - queue rows: `sd:q:<serverPlaylistIndex>`
 *  - browse nodes: `sd:j:<base64url>` carrying a small JSON envelope of the item's Jive actions
 *
 * The envelope has at most the keys `go`, `play`, `add`, `ins` (insert / play-next), each an action
 * object `{"cmd":[...],"params":{...}}`. Only non-null actions are stored. Encoding rebuilds
 * `JiveAction(cmd, params, null, null)` on decode: `nextWindow`/context-menu flags are not needed
 * to drive browsing or playback and are intentionally dropped.
 */
@OptIn(ExperimentalEncodingApi::class)
object MediaIds {
    const val ROOT = "sd:root"
    const val FAVOURITES = "sd:favourites"
    const val RANDOM = "sd:random"
    const val LIBRARY = "sd:library"
    const val QUEUE = "sd:queue"

    // Synthetic children of sd:random. These drive procedural playback (see BrowseTree) rather than
    // a single server action, so they use their own fixed ids instead of the sd:j: envelope.
    const val RANDOM_MIX = "sd:random:mix"
    const val RANDOM_FINITE = "sd:random:finite"
    const val RANDOM_ABOUT = "sd:random:about"

    private const val QUEUE_PREFIX = "sd:q:"
    private const val BROWSE_PREFIX = "sd:j:"

    fun queueId(index: Int): String = "$QUEUE_PREFIX$index"

    /** Encodes the four playback-relevant actions of a browse item into a `sd:j:` id. */
    fun browseId(actions: JiveActions): String =
        browseId(actions.goAction, actions.playAction, actions.addAction, actions.insertAction)

    /** Encodes the four playback-relevant actions of a browse item into a `sd:j:` id. */
    fun browseId(
        go: JiveAction?,
        play: JiveAction?,
        add: JiveAction?,
        ins: JiveAction?
    ): String {
        val envelope = buildJsonObject {
            go?.let { put("go", encodeAction(it)) }
            play?.let { put("play", encodeAction(it)) }
            add?.let { put("add", encodeAction(it)) }
            ins?.let { put("ins", encodeAction(it)) }
        }
        return BROWSE_PREFIX + Base64.UrlSafe.encode(envelope.toString().encodeToByteArray())
    }

    /** Resolves any media id to a typed [Node]. Unknown ids map to [Node.Unknown]. */
    fun parse(id: String): Node = when {
        id == ROOT -> Node.Root
        id == FAVOURITES -> Node.Favourites
        id == RANDOM -> Node.Random
        id == LIBRARY -> Node.Library
        id == QUEUE -> Node.Queue
        id == RANDOM_MIX -> Node.RandomMix
        id == RANDOM_FINITE -> Node.RandomFinite
        id == RANDOM_ABOUT -> Node.RandomAbout
        id.startsWith(QUEUE_PREFIX) ->
            id.removePrefix(QUEUE_PREFIX).toIntOrNull()?.let { Node.QueueRow(it) } ?: Node.Unknown(id)

        id.startsWith(BROWSE_PREFIX) -> decodeBrowse(id) ?: Node.Unknown(id)
        else -> Node.Unknown(id)
    }

    private fun decodeBrowse(id: String): Node? = runCatching {
        val json = Base64.UrlSafe.decode(id.removePrefix(BROWSE_PREFIX)).decodeToString()
        val obj = Json.parseToJsonElement(json).jsonObject
        Node.Browse(
            go = obj["go"]?.let { decodeAction(it.jsonObject) },
            play = obj["play"]?.let { decodeAction(it.jsonObject) },
            add = obj["add"]?.let { decodeAction(it.jsonObject) },
            ins = obj["ins"]?.let { decodeAction(it.jsonObject) }
        )
    }.getOrNull()

    private fun encodeAction(action: JiveAction): JsonObject = buildJsonObject {
        put("cmd", buildJsonArray { action.cmd.forEach { add(it) } })
        put(
            "params",
            buildJsonObject {
                // Sort keys so the same action always yields the same id.
                action.params.toSortedMap().forEach { (key, value) -> put(key, value) }
            }
        )
    }

    private fun decodeAction(obj: JsonObject): JiveAction {
        val cmd = obj["cmd"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val params = obj["params"]?.jsonObject?.mapValues { (_, value) ->
            if (value is JsonNull) null else value.jsonPrimitive.content
        } ?: emptyMap()
        return JiveAction(cmd, params, null, null)
    }

    sealed interface Node {
        object Root : Node
        object Favourites : Node
        object Random : Node
        object Library : Node
        object Queue : Node
        object RandomMix : Node
        object RandomFinite : Node
        object RandomAbout : Node

        data class QueueRow(val index: Int) : Node

        data class Browse(
            val go: JiveAction?,
            val play: JiveAction?,
            val add: JiveAction?,
            val ins: JiveAction?
        ) : Node {
            fun asJiveActions(): JiveActions = JiveActions(
                goAction = go,
                doAction = null,
                moreAction = null,
                playAction = play,
                addAction = add,
                insertAction = ins,
                downloadData = null,
                choices = null,
                checkbox = null,
                radio = null,
                slider = null,
                input = null,
                onClickRefresh = null
            )
        }

        data class Unknown(val id: String) : Node
    }
}
