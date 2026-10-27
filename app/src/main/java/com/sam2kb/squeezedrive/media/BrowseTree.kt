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

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sam2kb.squeezedrive.R
import de.maniac103.squeezeclient.cometd.ConnectionHelper
import de.maniac103.squeezeclient.cometd.ConnectionState
import de.maniac103.squeezeclient.cometd.request.LibrarySearchRequest
import de.maniac103.squeezeclient.model.JiveAction
import de.maniac103.squeezeclient.model.JiveHomeMenuItem
import de.maniac103.squeezeclient.model.PagingParams
import de.maniac103.squeezeclient.model.PlayerId
import de.maniac103.squeezeclient.model.SlimBrowseItemList
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Maps Lyrion (LMS) browsing onto the Media3 library tree and holds the small amount of state the
 * random-playback nodes need.
 *
 * Every node handles loading (the returned future is the async boundary), empty content, a
 * disconnected server and request failures: connection problems surface as a [LibraryResult] error
 * so the host can retry, and only genuinely empty content is reported as an empty success.
 */
@OptIn(UnstableApi::class)
class BrowseTree(
    private val context: Context,
    private val connectionHelper: ConnectionHelper,
    private val scope: CoroutineScope,
    private val playerId: PlayerId
) {
    /** Which kind of random selection the user most recently started, if any. */
    enum class RandomMode { MIX, FINITE }

    var lastRandomMode: RandomMode? = null
        private set

    // ---------------------------------------------------------------------------------------------
    // Tree
    // ---------------------------------------------------------------------------------------------

    fun rootItem(): MediaItem = folderItem(
        MediaIds.ROOT,
        context.getString(R.string.browse_root_title),
        context.getString(R.string.browse_root_subtitle)
    )

    fun getChildren(
        parentId: String,
        page: Int,
        pageSize: Int
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (!isConnected()) {
            connectionHelper.connect()
            return Futures.immediateFuture(disconnectedResult<ImmutableList<MediaItem>>())
        }
        return scope.future {
            try {
                val items = when (val node = MediaIds.parse(parentId)) {
                    MediaIds.Node.Root -> categoryItems()
                    MediaIds.Node.Random -> randomItems()
                    MediaIds.Node.Favourites ->
                        homeMenuChildren(
                            favouritesFetchAction(), page, pageSize,
                            "favourites", context.getString(R.string.browse_favourites)
                        )

                    MediaIds.Node.Library ->
                        homeMenuChildren(
                            libraryFetchAction(), page, pageSize,
                            "library", context.getString(R.string.browse_library)
                        )

                    MediaIds.Node.Queue -> queueItems(page, pageSize)
                    is MediaIds.Node.Browse ->
                        node.go?.let { browseChildren(it, page, pageSize) } ?: emptyList()

                    else -> emptyList()
                }
                LibraryResult.ofItemList(items, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "getChildren($parentId) failed", e)
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
            }
        }
    }

    fun getItem(mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        try {
            val item = knownItem(mediaId) ?: synthesizeItem(mediaId)
            LibraryResult.ofItem(item, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "getItem($mediaId) failed", e)
            LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
        }
    }

    fun getSearchResult(
        query: String,
        page: Int,
        pageSize: Int
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (!isConnected()) {
            connectionHelper.connect()
            return Futures.immediateFuture(disconnectedResult<ImmutableList<MediaItem>>())
        }
        return scope.future {
            try {
                LibraryResult.ofItemList(searchItems(query, page, pageSize), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "search($query) failed", e)
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
            }
        }
    }

    /** Builds a best-effort [MediaItem] for a media id when no full metadata is at hand. */
    fun synthesizeItem(id: String): MediaItem {
        val node = MediaIds.parse(id)
        val title = when (node) {
            MediaIds.Node.RandomMix -> context.getString(R.string.browse_random_mix)
            MediaIds.Node.RandomFinite -> context.getString(R.string.browse_random_finite)
            MediaIds.Node.RandomAbout -> context.getString(R.string.browse_random_about)
            is MediaIds.Node.Browse -> node.title ?: context.getString(R.string.browse_unknown_item)
            else -> context.getString(R.string.browse_unknown_item)
        }
        val browsable = node is MediaIds.Node.Browse && node.go != null
        val playable = when (node) {
            is MediaIds.Node.Browse -> node.play != null
            is MediaIds.Node.QueueRow -> true
            MediaIds.Node.RandomMix, MediaIds.Node.RandomFinite -> true
            else -> false
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(browsable)
            .setIsPlayable(playable)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    // ---------------------------------------------------------------------------------------------
    // Random playback
    // ---------------------------------------------------------------------------------------------

    /**
     * Starts Lyrion's ongoing Random Mix. The mix keeps adding tracks by itself, so this is not a
     * finite selection. Throws if the command is rejected (e.g. the Random Mix plugin is missing) so
     * the failure is surfaced instead of leaving a dead control.
     */
    suspend fun startRandomMix() {
        executeActionOrThrow(
            JiveAction(listOf("randomplay", "tracks"), emptyMap(), null, null),
            context.getString(R.string.browse_random_mix)
        )
        lastRandomMode = RandomMode.MIX
    }

    /**
     * Starts a finite, one-off selection of about 25 random tracks. Only bounded requests are made:
     * one small page to learn the catalogue size, then a single page at a pseudorandom offset. The
     * tracks are shuffled and played using the per-item Jive actions the server supplied.
     */
    suspend fun startFiniteRandom() {
        val action = JiveAction(
            listOf("browselibrary", "items"),
            mapOf("mode" to "tracks", "menu" to "tracks"),
            null,
            null
        )
        val probe = isolated("random-probe") {
            connectionHelper.fetchItemsForAction(playerId, action, PagingParams(0, 1), false)
        }
        val total = probe.totalCount
        // A missing/zero count with an empty probe is a genuinely empty catalogue; a missing count
        // while items came back just means the server omitted it - fall back to the first page.
        if (total <= 0 && probe.items.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.browse_error_no_tracks))
        }
        val count = if (total <= 0) FINITE_RANDOM_SIZE else FINITE_RANDOM_SIZE.coerceAtMost(total)
        val maxStart = (total - count).coerceAtLeast(0)
        val start = if (total <= 0 || maxStart == 0) 0 else Random.nextInt(maxStart + 1)
        val page = isolated("random-page") {
            connectionHelper.fetchItemsForAction(playerId, action, PagingParams(start, count), false)
        }
        // The first track needs a play action, every appended track an add action. Play-only
        // entries cannot be appended and are excluded up front instead of being silently dropped.
        val shuffled = page.items.shuffled()
        val first = shuffled.firstOrNull { it.actions?.playAction != null }
            ?: throw IllegalStateException(context.getString(R.string.browse_error_no_tracks))
        val rest = shuffled.filter { it.actions?.addAction != null && it !== first }
        // Play the first with its play action, append the rest with their add actions. Never invent
        // playlist commands - the server's per-item actions are the source of truth.
        executeActionOrThrow(
            requireNotNull(first.actions?.playAction),
            context.getString(R.string.browse_random_finite)
        )
        // A finite selection is now genuinely running - record it before the appends so the state
        // stays truthful even if a later append fails (the queue then holds a shorter selection).
        lastRandomMode = RandomMode.FINITE
        rest.forEach { item ->
            executeActionOrThrow(
                requireNotNull(item.actions?.addAction),
                context.getString(R.string.browse_random_finite)
            )
        }
    }

    /** Clears the random-selection marker; called when the queue is cleared. */
    fun clearRandomMode() {
        lastRandomMode = null
    }

    // ---------------------------------------------------------------------------------------------
    // Shared action execution / custom-command helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Executes a Jive action and surfaces an honest failure if the server reports an error. Used for
     * playback intents so a missing plugin or a rejected command is never silently ignored.
     */
    suspend fun executeActionOrThrow(action: JiveAction, label: String) {
        val result = isolated(label) { connectionHelper.executeAction(playerId, action) }
        throwIfServerError(result, label)
    }

    suspend fun toggleFavorite(url: String, title: String): Boolean {
        val isFav = isolated("favorite") { connectionHelper.isFavorite(playerId, url) }
        return if (isFav) {
            isolated("favorite") { connectionHelper.removeFavorite(playerId, url, title) }
            false
        } else {
            isolated("favorite") { connectionHelper.addFavorite(playerId, url, title) }
            true
        }
    }

    suspend fun clearQueue() {
        isolated("queue-clear") { connectionHelper.clearCurrentPlaylist(playerId) }
        clearRandomMode()
    }

    suspend fun removeQueueItem(index: Int) {
        isolated("queue-remove") { connectionHelper.removePlaylistItem(playerId, index) }
    }

    /** Current queue length as the server reports it (one minimal request). */
    suspend fun queueCount(): Int = isolated("queue-count") {
        connectionHelper.fetchPlaylist(playerId, PagingParams.CountOnly).totalCount
    }

    suspend fun advanceToQueueItem(index: Int) {
        isolated("queue-advance") { connectionHelper.advanceToPlaylistPosition(playerId, index) }
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private fun isConnected(): Boolean = connectionHelper.state.value is ConnectionState.Connected

    private fun <T : Any> disconnectedResult(): LibraryResult<T> =
        LibraryResult.ofError<T>(LibraryResult.RESULT_ERROR_INVALID_STATE)

    private suspend fun browseChildren(
        action: JiveAction,
        page: Int,
        pageSize: Int
    ): List<MediaItem> {
        // Stride follows the host's page size so consecutive host pages never overlap or skip
        // rows; the per-request count is bounded to keep single fetches sane.
        val count = pageSize.coerceAtMost(MAX_BROWSE_PAGE)
        val list = isolated("browse") {
            connectionHelper.fetchItemsForAction(
                playerId,
                action,
                PagingParams(page * pageSize, count),
                true
            )
        }
        return list.items.map { browseItemToMedia(it) }
    }

    /**
     * Resolves a node through the server's home menu. A missing home menu (never loaded) is a
     * connection-style failure; a menu that loads but lacks the section (disabled/renamed plugin)
     * is explained with a non-interactive item rather than a silently empty folder.
     */
    private suspend fun homeMenuChildren(
        fetchAction: JiveAction?,
        page: Int,
        pageSize: Int,
        missingInfoKind: String,
        missingInfoTitle: String
    ): List<MediaItem> {
        if (fetchAction == null) {
            // Could not resolve the home-menu entry. If the menu itself never loaded this is a
            // connection failure; treat it as such so the host can retry.
            if (homeMenu() == null) {
                throw IllegalStateException(context.getString(R.string.browse_error_not_connected))
            }
            return listOf(
                infoItem(
                    MediaIds.infoId(missingInfoKind),
                    missingInfoTitle,
                    context.getString(R.string.browse_section_unavailable)
                )
            )
        }
        return browseChildren(fetchAction, page, pageSize)
    }

    private suspend fun queueItems(page: Int, pageSize: Int): List<MediaItem> {
        val count = pageSize.coerceAtMost(MAX_BROWSE_PAGE)
        val playlist = isolated("queue") {
            connectionHelper.fetchPlaylist(playerId, PagingParams(page * pageSize, count))
        }
        return playlist.items.mapIndexed { index, row ->
            val metadata = MediaMetadata.Builder()
                .setTitle(row.title)
                .setSubtitle(
                    listOfNotNull(row.artist, row.album)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { null }
                )
                .setArtworkUri(row.extractIconUrl(context)?.toUri())
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
            MediaItem.Builder()
                .setMediaId(MediaIds.queueId(playlist.offset + index))
                .setMediaMetadata(metadata)
                .build()
        }
    }

    private suspend fun searchItems(query: String, page: Int, pageSize: Int): List<MediaItem> {
        val counts = isolated("search-counts") {
            connectionHelper.getLocalLibrarySearchResultCounts(query)
        }
        val categories = buildList {
            if (counts.artists > 0) add(LibrarySearchRequest.Mode.Artist())
            if (counts.albums > 0) add(LibrarySearchRequest.Mode.Albums())
            if (counts.tracks > 0) add(LibrarySearchRequest.Mode.Tracks())
            if (counts.genres > 0) add(LibrarySearchRequest.Mode.Genres())
        }
        if (categories.isEmpty()) return emptyList()
        val perCategory = pageSize.coerceAtMost(MAX_SEARCH_PER_CATEGORY)
        return categories.flatMap { mode ->
            val list = isolated("search") {
                connectionHelper.getLocalLibrarySearchResults(
                    playerId,
                    query,
                    mode,
                    PagingParams(page * perCategory, perCategory)
                )
            }
            list.items.map { browseItemToMedia(it) }
        }
    }

    private fun browseItemToMedia(item: SlimBrowseItemList.SlimBrowseItem): MediaItem {
        val actions = item.actions
        val id = MediaIds.browseId(
            go = actions?.goAction,
            play = actions?.playAction,
            add = actions?.addAction,
            ins = actions?.insertAction,
            title = item.title
        )
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setSubtitle(item.subText ?: item.extraInfo)
            .setArtworkUri(item.extractIconUrl(context)?.toUri())
            .setIsBrowsable(actions?.goAction != null)
            .setIsPlayable(actions?.playAction != null)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    private fun categoryItems(): List<MediaItem> = listOf(
        folderItem(
            MediaIds.FAVOURITES,
            context.getString(R.string.browse_favourites),
            context.getString(R.string.browse_favourites_subtitle)
        ),
        folderItem(
            MediaIds.RANDOM,
            context.getString(R.string.browse_random),
            context.getString(R.string.browse_random_subtitle)
        ),
        folderItem(
            MediaIds.LIBRARY,
            context.getString(R.string.browse_library),
            context.getString(R.string.browse_library_subtitle)
        ),
        folderItem(
            MediaIds.QUEUE,
            context.getString(R.string.browse_queue),
            context.getString(R.string.browse_queue_subtitle)
        )
    )

    private fun randomItems(): List<MediaItem> {
        val mixSubtitle = withMode(
            context.getString(R.string.browse_random_mix_subtitle),
            if (lastRandomMode == RandomMode.MIX) {
                context.getString(R.string.browse_random_mix_active)
            } else {
                null
            }
        )
        val finiteSubtitle = withMode(
            context.getString(R.string.browse_random_finite_subtitle),
            if (lastRandomMode == RandomMode.FINITE) {
                context.getString(R.string.browse_random_finite_active)
            } else {
                null
            }
        )
        return listOf(
            playableItem(MediaIds.RANDOM_MIX, context.getString(R.string.browse_random_mix), mixSubtitle),
            playableItem(
                MediaIds.RANDOM_FINITE,
                context.getString(R.string.browse_random_finite),
                finiteSubtitle
            ),
            infoItem(
                MediaIds.RANDOM_ABOUT,
                context.getString(R.string.browse_random_about),
                context.getString(R.string.browse_random_about_body)
            )
        )
    }

    private fun knownItem(mediaId: String): MediaItem? = when (mediaId) {
        MediaIds.ROOT -> rootItem()
        MediaIds.FAVOURITES -> categoryItems()[0]
        MediaIds.RANDOM -> categoryItems()[1]
        MediaIds.LIBRARY -> categoryItems()[2]
        MediaIds.QUEUE -> categoryItems()[3]
        MediaIds.RANDOM_MIX -> randomItems()[0]
        MediaIds.RANDOM_FINITE -> randomItems()[1]
        MediaIds.RANDOM_ABOUT -> randomItems()[2]
        else -> null
    }

    private suspend fun favouritesFetchAction(): JiveAction? = homeMenu()
        ?.values
        ?.firstOrNull { item ->
            item.id.equals("favorites", true) ||
                item.id.equals("favourites", true) ||
                item.goAction?.cmd?.any { it.contains("favorites", true) } == true
        }
        ?.goAction

    private suspend fun libraryFetchAction(): JiveAction? = homeMenu()
        ?.values
        ?.firstOrNull { item ->
            item.id.equals("myMusic", true) ||
                item.goAction?.cmd?.any { it.contains("browselibrary", true) } == true
        }
        ?.goAction

    private suspend fun homeMenu(): Map<String, JiveHomeMenuItem>? = withTimeoutOrNull(HOME_MENU_TIMEOUT) {
        val playerState = connectionHelper.playerState(playerId).first()
        playerState.homeMenu.first()
    }

    private fun folderItem(id: String, title: String, subtitle: String?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    private fun playableItem(id: String, title: String, subtitle: String?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    private fun infoItem(id: String, title: String, subtitle: String?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(false)
            .setIsPlayable(false)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    private fun withMode(subtitle: String, active: String?): String =
        if (active == null) subtitle else "$subtitle · $active"

    private fun throwIfServerError(result: JsonElement, label: String) {
        val message = findErrorMessage(result) ?: return
        throw IllegalStateException("$label: $message".trimEnd(':', ' '))
    }

    /**
     * Finds Lyrion's error reporting in a response payload. CLI/Jive errors show up either as an
     * object with an "error" key (scalar or nested) or as an ["error", ...] array; other payloads
     * are walked so an error nested one level down is not silently treated as success.
     */
    private fun findErrorMessage(element: JsonElement): String? = when (element) {
        is JsonObject -> {
            val error = element["error"]
            when {
                error == null || error is JsonNull ->
                    element.values.firstNotNullOfOrNull { findErrorMessage(it) }

                else -> findErrorMessage(error) ?: "error"
            }
        }

        is JsonArray ->
            if ((element.firstOrNull() as? JsonPrimitive)?.contentOrNull == "error") {
                (element.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: "error"
            } else {
                element.firstNotNullOfOrNull { findErrorMessage(it) }
            }

        is JsonPrimitive -> element.contentOrNull
        else -> null
    }

    /**
     * Runs a server call in its own coroutine so that a self-cancellation inside the protocol layer
     * (which is how ConnectionHelper reports a dropped connection) becomes a catchable failure here
     * instead of silently abandoning the caller's future. This keeps browse/playback failures honest.
     */
    private suspend fun <T> isolated(label: String, block: suspend () -> T): T {
        val deferred = scope.async { runCatching { block() } }
        return try {
            deferred.await().getOrThrow()
        } catch (e: Throwable) {
            if (!deferred.isCancelled && e is CancellationException) {
                throw e // the caller was cancelled; propagate structured cancellation
            }
            Log.w(TAG, "$label failed", e)
            throw IllegalStateException("$label failed", e)
        }
    }

    companion object {
        private const val TAG = "BrowseTree"
        private const val MAX_BROWSE_PAGE = 500
        private const val MAX_SEARCH_PER_CATEGORY = 20
        private const val FINITE_RANDOM_SIZE = 25
        private val HOME_MENU_TIMEOUT = 5.seconds
    }
}
