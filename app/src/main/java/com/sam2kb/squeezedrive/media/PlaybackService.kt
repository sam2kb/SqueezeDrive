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
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ServiceLifecycleDispatcher
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sam2kb.squeezedrive.PlayerIdentity
import com.sam2kb.squeezedrive.R
import de.maniac103.squeezeclient.cometd.ConnectionState
import de.maniac103.squeezeclient.extfuncs.connectionHelper
import de.maniac103.squeezeclient.extfuncs.httpClient
import de.maniac103.squeezeclient.extfuncs.prefs
import de.maniac103.squeezeclient.service.NotificationIds
import de.maniac103.squeezeclient.service.mediasession.SqueezeboxMediaPlayer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * Car-only media service for SqueezeDrive.
 *
 * Playback is proxied to Lyrion: [SqueezeboxMediaPlayer] reflects the server's real queue and state,
 * so this service never mutates the player's playlist. Browsing is served by [BrowseTree]; playback
 * intents and custom commands resolve media ids via [MediaIds] and execute the matching Lyrion
 * action.
 */
@OptIn(UnstableApi::class, ExperimentalTime::class)
class PlaybackService :
    MediaLibraryService(),
    LifecycleOwner,
    MediaLibraryService.MediaLibrarySession.Callback {
    private val dispatcher = ServiceLifecycleDispatcher(this)
    override val lifecycle: Lifecycle get() = dispatcher.lifecycle

    private lateinit var player: SqueezeboxMediaPlayer
    private lateinit var mediaSession: MediaLibraryService.MediaLibrarySession
    private lateinit var browseTree: BrowseTree
    private var lastDisconnectionTime = Clock.System.now()
    private var delayedShutdownJob: Job? = null

    override fun onCreate() {
        dispatcher.onServicePreSuperOnCreate()
        super.onCreate()

        val playerId = PlayerIdentity.localPlayerId(prefs)
        player = SqueezeboxMediaPlayer(applicationContext, connectionHelper, lifecycle)
        player.currentPlayer = playerId
        browseTree = BrowseTree(applicationContext, connectionHelper, lifecycleScope, playerId)

        val channelInfo = NotificationIds.CHANNEL_MEDIA_CONTROL
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NotificationIds.MEDIA_CONTTROL_SERVICE)
                .setChannelId(channelInfo.id)
                .setChannelName(channelInfo.nameResId)
                .build()
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                connectionHelper.state.collectLatest { status ->
                    when (status) {
                        is ConnectionState.Disconnected -> handleDisconnection()
                        is ConnectionState.Connecting -> {}
                        is ConnectionState.Connected -> handleConnection(status)
                        is ConnectionState.ConnectionFailure -> handleDisconnection()
                    }
                }
            }
        }

        // No session activity intent: this is a car-only app with no launcher activity.
        val dataSourceFactory = DefaultDataSource.Factory(
            this,
            OkHttpDataSource.Factory(httpClient)
        )
        val bitmapLoader = DataSourceBitmapLoader.Builder(this)
            .setDataSourceFactory(dataSourceFactory)
            .build()
        mediaSession = MediaLibraryService.MediaLibrarySession.Builder(this, player, this)
            .setBitmapLoader(CacheBitmapLoader(bitmapLoader))
            .build()
        addSession(mediaSession)
    }

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (player.playbackState != Player.STATE_READY && !player.playWhenReady) {
            stopSelf()
        }
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        dispatcher.onServicePreSuperOnDestroy()
        player.release()
        mediaSession.release()
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    // -------------------------------------------------------------------------------------------
    // Library browsing
    // -------------------------------------------------------------------------------------------

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(browseTree.rootItem(), params))

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ) = browseTree.getChildren(parentId, page, pageSize)

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ) = browseTree.getItem(mediaId)

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ) = browseTree.getSearchResult(query, page, pageSize)

    // -------------------------------------------------------------------------------------------
    // Playback intents (set = play now, add = append). Insert / play-next is exposed as a custom
    // command because Media3 has no dedicated "insert" callback.
    // -------------------------------------------------------------------------------------------

    override fun onSetMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = lifecycleScope.future {
        val resolved = mediaItems.mapIndexed { index, item ->
            when (val node = MediaIds.parse(item.mediaId)) {
                is MediaIds.Node.QueueRow -> browseTree.advanceToQueueItem(node.index)
                MediaIds.Node.RandomMix -> browseTree.startRandomMix()
                MediaIds.Node.RandomFinite -> browseTree.startFiniteRandom()
                is MediaIds.Node.Browse -> {
                    val action = if (index == 0) (node.play ?: node.add) else (node.add ?: node.play)
                    action?.let {
                        browseTree.executeActionOrThrow(it, getString(R.string.browse_action_play))
                    }
                }
                else -> {}
            }
            browseTree.synthesizeItem(item.mediaId)
        }
        MediaSession.MediaItemsWithStartPosition(resolved, 0, C.TIME_UNSET)
    }

    override fun onAddMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> = lifecycleScope.future {
        mediaItems.map { item ->
            when (val node = MediaIds.parse(item.mediaId)) {
                is MediaIds.Node.Browse -> node.add?.let {
                    browseTree.executeActionOrThrow(it, getString(R.string.browse_action_add))
                }
                else -> {}
            }
            browseTree.synthesizeItem(item.mediaId)
        }
    }

    // -------------------------------------------------------------------------------------------
    // Connection / custom commands
    // -------------------------------------------------------------------------------------------

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            .add(SessionCommand(CMD_FAV_TOGGLE, Bundle.EMPTY))
            .add(SessionCommand(CMD_QUEUE_CLEAR, Bundle.EMPTY))
            .add(SessionCommand(CMD_QUEUE_REMOVE, Bundle.EMPTY))
            .add(SessionCommand(CMD_ITEM_PLAY_NEXT, Bundle.EMPTY))
            .add(SessionCommand(CMD_PLAYER_POWER, Bundle.EMPTY))
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(sessionCommands)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> = lifecycleScope.future {
        try {
            when (customCommand.customAction) {
                CMD_FAV_TOGGLE -> {
                    val url = args.getString(ARG_URL)
                    if (url != null) {
                        val nowFavorite = browseTree.toggleFavorite(
                            url,
                            args.getString(ARG_TITLE).orEmpty()
                        )
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            bundleOf(ARG_FAV to nowFavorite)
                        )
                    } else {
                        commandError(SessionError.ERROR_BAD_VALUE, R.string.browse_error_fav)
                    }
                }

                CMD_QUEUE_CLEAR -> {
                    browseTree.clearQueue()
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CMD_QUEUE_REMOVE -> {
                    val index = args.getInt(ARG_INDEX, -1)
                    if (index >= 0) {
                        browseTree.removeQueueItem(index)
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    } else {
                        commandError(SessionError.ERROR_BAD_VALUE, R.string.browse_error_queue)
                    }
                }

                CMD_ITEM_PLAY_NEXT -> {
                    val mediaId = args.getString(ARG_MEDIA_ID)
                    val node = mediaId?.let { MediaIds.parse(it) }
                    val insert = (node as? MediaIds.Node.Browse)?.ins
                    when {
                        insert != null -> {
                            browseTree.executeActionOrThrow(
                                insert,
                                getString(R.string.browse_action_play_next)
                            )
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        }
                        mediaId == null ->
                            commandError(SessionError.ERROR_BAD_VALUE, R.string.browse_error_item)

                        else ->
                            commandError(
                                SessionError.ERROR_NOT_SUPPORTED,
                                R.string.browse_error_no_insert
                            )
                    }
                }

                CMD_PLAYER_POWER -> {
                    player.currentPlayer?.let { connectionHelper.togglePower(it) }
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                else -> SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "custom command ${customCommand.customAction} failed", e)
            commandError(SessionError.ERROR_INVALID_STATE, R.string.browse_error_generic)
        }
    }

    private fun commandError(code: Int, messageRes: Int): SessionResult =
        SessionResult(SessionError(code, getString(messageRes)))

    private fun handleConnection(status: ConnectionState.Connected) {
        // Update connection status first, because currentPlayer checked below is updated on
        // status changes.
        player.isConnectedToServer = true
        player.currentPlayer?.let { playerId ->
            if (status.players.any { it.id == playerId }) {
                delayedShutdownJob?.cancel()
                delayedShutdownJob = null
            } else if (delayedShutdownJob == null) {
                // The player disappeared from the server's player list. That happens briefly when a
                // player reconnects, so keep the media session for a while before giving it up.
                delayedShutdownJob = lifecycleScope.launch {
                    delay(30.seconds)
                    stopSelf()
                }
            }
        }
    }

    private fun handleDisconnection() {
        if (player.isConnectedToServer) {
            lastDisconnectionTime = Clock.System.now()
            player.isConnectedToServer = false
        }
        // Try reconnecting for a while to handle short interruptions; stop the service (which
        // removes the media notification) if we cannot reconnect for a longer amount of time.
        val retryTime = Clock.System.now() - lastDisconnectionTime
        if (retryTime < 15.minutes) {
            connectionHelper.connect()
        } else {
            stopSelf()
        }
    }

    companion object {
        private const val TAG = "PlaybackService"

        // Custom session commands (advertised in onConnect).
        const val CMD_FAV_TOGGLE = "sd.fav.toggle"
        const val CMD_QUEUE_CLEAR = "sd.queue.clear"
        const val CMD_QUEUE_REMOVE = "sd.queue.remove"
        const val CMD_ITEM_PLAY_NEXT = "sd.item.playNext"
        const val CMD_PLAYER_POWER = "sd.player.power"

        // Custom command argument keys.
        const val ARG_URL = "url"
        const val ARG_TITLE = "title"
        const val ARG_INDEX = "index"
        const val ARG_MEDIA_ID = "mediaId"
        const val ARG_FAV = "favorite"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        }
    }
}
