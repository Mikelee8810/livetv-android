package com.livetv.app.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.datasource.DefaultHttpDataSource
import com.livetv.app.OnDeviceStreamServer
import com.livetv.app.data.Item
import com.livetv.app.data.Prefs

@OptIn(UnstableApi::class)
class PlayerEngine(private val context: Context, private val prefs: Prefs) {
    private val TAG = "PlayerEngine"
    private var exoPlayer: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private val maxRetries = 5

    private var attachedSurfaceView: SurfaceView? = null
    private var attachedTextureView: TextureView? = null

    var currentItem: Item? = null; private set
    var isPlaying = false; private set
    var isBuffering = false; private set
    var videoWidth = 0; private set
    var videoHeight = 0; private set

    var onStateChanged: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onTracksChanged: (() -> Unit)? = null

    init {
        try {
            OnDeviceStreamServer.ensureStarted()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting local stream server", e)
        }
    }

    fun initPlayer(surfaceView: SurfaceView? = null): ExoPlayer {
        release()

        val loadControl = DefaultLoadControl.Builder().apply {
            val (minBuffer, maxBuffer) = when (prefs.buffer) {
                "Small" -> Pair(25000, 25000)
                "Large" -> Pair(50000, 50000)
                "Very large" -> Pair(80000, 80000)
                else -> Pair(50000, 50000) // TiviMate standard: 50s live buffer
            }
            setBufferDurationsMs(minBuffer, maxBuffer, 1000, 2000)
            setPrioritizeTimeOverSizeThresholds(true)
            setBackBuffer(0, false)
        }.build()

        val renderersFactory = DefaultRenderersFactory(context).apply {
            val audioExtension = if (prefs.audioDecoder == "Software") 
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER 
            else 
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            setExtensionRendererMode(audioExtension)
            setEnableDecoderFallback(true)
            setAllowedVideoJoiningTimeMs(10000)
        }

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguages("en", "eng")
                    .setPreferredAudioLanguage(prefs.preferAudioLang.ifBlank { "eng" })
            )
        }

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .build()

        player.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                Log.w(TAG, "DROPPED VIDEO FRAMES: $droppedFrames frames in ${elapsedMs}ms")
            }
            override fun onVideoCodecError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                videoCodecError: Exception
            ) {
                Log.e(TAG, "VIDEO CODEC ERROR: ", videoCodecError)
            }
        })

        if (attachedTextureView != null) {
            player.setVideoTextureView(attachedTextureView)
        } else if (attachedSurfaceView != null) {
            player.setVideoSurfaceView(attachedSurfaceView)
        } else if (surfaceView != null) {
            player.setVideoSurfaceView(surfaceView)
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                isPlaying = playbackState == Player.STATE_READY && player.playWhenReady

                if (playbackState == Player.STATE_READY) {
                    retryCount = 0
                }
                onStateChanged?.invoke()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                onStateChanged?.invoke()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Playback error: ${error.errorCodeName} - ${error.message}")
                if (prefs.autoRetry && retryCount < maxRetries) {
                    retryCount++
                    Log.i(TAG, "Auto-retrying stream (attempt $retryCount of $maxRetries)...")
                    mainHandler.postDelayed({
                        currentItem?.let { play(it, false) }
                    }, 2500L)
                } else {
                    onError?.invoke(error.localizedMessage ?: "Playback error: ${error.errorCodeName}")
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                videoWidth = videoSize.width
                videoHeight = videoSize.height
                onStateChanged?.invoke()
            }

            override fun onTracksChanged(tracks: Tracks) {
                onTracksChanged?.invoke()
            }
        })

        exoPlayer = player
        return player
    }

    fun attachSurface(surfaceView: SurfaceView?) {
        if (attachedSurfaceView == surfaceView && attachedTextureView == null) return
        attachedSurfaceView = surfaceView
        attachedTextureView = null
        exoPlayer?.setVideoSurfaceView(surfaceView)
    }

    fun attachTextureView(textureView: TextureView?) {
        if (attachedTextureView == textureView && attachedSurfaceView == null) return
        attachedTextureView = textureView
        attachedSurfaceView = null
        exoPlayer?.setVideoTextureView(textureView)
    }

    fun play(item: Item, resetRetry: Boolean = true) {
        if (resetRetry) retryCount = 0
        currentItem = item
        val player = exoPlayer ?: initPlayer()

        var streamUrl = item.url
        if (streamUrl.startsWith("/")) {
            streamUrl = "http://127.0.0.1:48913$streamUrl"
        }

        val userAgent = prefs.userAgent.ifBlank { "Mozilla/5.0 (Linux; Android 14; Google TV Streamer) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" }
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)

        val uri = Uri.parse(streamUrl)
        val mediaSource = if (streamUrl.contains(".m3u8", ignoreCase = true) || streamUrl.contains("manifest", ignoreCase = true)) {
            HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(false)
                .createMediaSource(MediaItem.fromUri(uri))
        } else {
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(uri))
        }

        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
        onStateChanged?.invoke()
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume() {
        exoPlayer?.play()
    }

    fun seekRelative(seconds: Int) {
        exoPlayer?.let { p ->
            val cur = p.currentPosition
            val target = (cur + seconds * 1000L).coerceAtLeast(0L)
            p.seekTo(target)
        }
    }

    fun setAspectRatio(mode: String, surfaceContainer: android.widget.FrameLayout?, targetView: View?) {
        if (surfaceContainer == null || targetView == null || videoWidth <= 0 || videoHeight <= 0) return
        val containerW = surfaceContainer.width.toFloat()
        val containerH = surfaceContainer.height.toFloat()
        if (containerW <= 0f || containerH <= 0f) return

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val screenAspect = containerW / containerH

        val lp = targetView.layoutParams as android.widget.FrameLayout.LayoutParams
        when (mode) {
            "Fill" -> {
                lp.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                lp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            }
            "Zoom" -> {
                if (videoAspect > screenAspect) {
                    lp.height = containerH.toInt()
                    lp.width = (containerH * videoAspect).toInt()
                } else {
                    lp.width = containerW.toInt()
                    lp.height = (containerW / videoAspect).toInt()
                }
            }
            "16:9" -> {
                val targetAspect = 16f / 9f
                if (targetAspect > screenAspect) {
                    lp.width = containerW.toInt()
                    lp.height = (containerW / targetAspect).toInt()
                } else {
                    lp.height = containerH.toInt()
                    lp.width = (containerH * targetAspect).toInt()
                }
            }
            "4:3" -> {
                val targetAspect = 4f / 3f
                if (targetAspect > screenAspect) {
                    lp.width = containerW.toInt()
                    lp.height = (containerW / targetAspect).toInt()
                } else {
                    lp.height = containerH.toInt()
                    lp.width = (containerH * targetAspect).toInt()
                }
            }
            else -> { // "Fit" / Auto
                if (videoAspect > screenAspect) {
                    lp.width = containerW.toInt()
                    lp.height = (containerW / videoAspect).toInt()
                } else {
                    lp.height = containerH.toInt()
                    lp.width = (containerH * videoAspect).toInt()
                }
            }
        }
        lp.gravity = android.view.Gravity.CENTER
        targetView.layoutParams = lp
    }

    fun getAudioTracks(): List<TrackInfo> {
        val player = exoPlayer ?: return emptyList()
        val tracks = player.currentTracks
        val result = mutableListOf<TrackInfo>()
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = format.label ?: format.language ?: "Audio ${result.size + 1}"
                    result.add(TrackInfo(group, i, label, group.isTrackSelected(i)))
                }
            }
        }
        return result
    }

    fun selectAudioTrack(trackInfo: TrackInfo) {
        val player = exoPlayer ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(trackInfo.group.mediaTrackGroup, trackInfo.trackIndex))
            .build()
    }

    fun getSubtitleTracks(): List<TrackInfo> {
        val player = exoPlayer ?: return emptyList()
        val tracks = player.currentTracks
        val result = mutableListOf<TrackInfo>()
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = format.label ?: format.language ?: "Subtitle ${result.size + 1}"
                    result.add(TrackInfo(group, i, label, group.isTrackSelected(i)))
                }
            }
        }
        return result
    }

    fun selectSubtitleTrack(trackInfo: TrackInfo?) {
        val player = exoPlayer ?: return
        if (trackInfo == null) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        } else {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(trackInfo.group.mediaTrackGroup, trackInfo.trackIndex))
                .build()
        }
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
        isPlaying = false
        isBuffering = false
    }

    data class TrackInfo(
        val group: Tracks.Group,
        val trackIndex: Int,
        val label: String,
        val isSelected: Boolean
    )
}
