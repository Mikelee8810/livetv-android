package com.livetv.app.player

import android.content.Context
import android.net.Uri
import android.os.Build
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.livetv.app.OnDeviceStreamServer
import com.livetv.app.data.Item
import com.livetv.app.data.Prefs

@OptIn(UnstableApi::class)
class PlayerEngine(private val context: Context, private val prefs: Prefs) {

    companion object {
        /** Number of resolver routes in OnDeviceStreamServer.ROUTES. */
        const val ROUTE_COUNT = 8
    }
    private val TAG = "PlayerEngine"
    private var exoPlayer: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private val maxRetries = 5

    // Route failover: the local resolver exposes 7 DaddyLive routes (see
    // OnDeviceStreamServer.ROUTES). Retries used to replay the exact same
    // URL, so a dead route retried forever. Now each retry steps to the
    // next route, and the route that last reached READY is remembered per
    // channel and tried first next time.
    private var routeAttempt = 0
    private var droppedFramesTotal = 0

    // Decoder fallback ladder (pattern from OwnTV_Core's engine ladder):
    // step 0 = the user's decoder preference, step 1 = force software,
    // step 2 = force hardware. A decoder failure climbs one step and the
    // player is rebuilt, instead of replaying into the same broken decoder.
    private var decoderEscalation = 0
    private var decoderEscalationsTotal = 0
    // Decoder-error rebuilds since playback last reached READY. Without a
    // cap, a stream that keeps failing as DECODING_FAILED rebuilds the
    // player forever and never surfaces an error or spends the retry
    // budget. Three strikes, then the normal retry path takes over.
    private var decoderRebuildsSinceReady = 0

    // When playback last reached READY. The retry budget only resets after
    // 30 s of stable playback; resetting on every READY made a stream that
    // died every ~25 s retry forever (v4.2 stutter loop).
    private var readySinceMs = 0L

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

    // Health watchdog v2 (v4.2.6). The v1 watchdog trusted
    // VideoFrameMetadataListener as its "frames are rendering" heartbeat.
    // On the MediaTek hardware decoder that callback goes silent while the
    // picture is fine, so the watchdog seek-rescued a healthy stream every
    // few seconds and replayed the buffered seconds in a loop - the exact
    // symptom it was meant to cure. v2 reads
    // DecoderCounters.renderedOutputBufferCount, which the renderer
    // increments for every frame it actually releases for display, and its
    // recoveries never seek-rescue on MediaTek silicon (Media3 itself
    // releases rather than flushes c2.mtk decoders): first a re-prepare,
    // then a rebuild on the other decoder. Production players recover the
    // same way; none of them nudge-seek.
    private var lastObservedPosition = -1L
    private var stallCheckCount = 0
    @Volatile private var videoCounters: androidx.media3.exoplayer.DecoderCounters? = null
    private var lastRenderedCount = -1
    private var lastRenderAdvanceMs = 0L
    private var positionAtRenderAdvance = -1L
    private var freezeRecoveries = 0
    // Watchdog grace period: the first seconds of any (re)start are tune
    // transients, not freezes. On the 4.2.6 soak the watchdog fired on
    // counts stuck at 2-3 frames right after tunes and zaps - position
    // "advances" off the live window while the video renderer is still
    // waiting for its first keyframe - and one such false verdict even
    // flipped the TV onto the software decoder mid-session. No verdict
    // before the grace period, and no freeze verdict until the stream
    // has genuinely rendered video (a real freeze happens thousands of
    // frames in, not at frame 3).
    private var playbackStartMs = 0L

    private fun resetWatchdogTracking(now: Long) {
        lastObservedPosition = -1L
        stallCheckCount = 0
        lastRenderedCount = -1
        lastRenderAdvanceMs = now
        positionAtRenderAdvance = -1L
    }

    /** Recovery rung 1: re-prepare the same media. No seek, no flush. */
    private fun reprepare(p: ExoPlayer) {
        try {
            p.stop()
            p.prepare()
            p.playWhenReady = true
        } catch (e: Exception) {
            Log.w(TAG, "Re-prepare failed", e)
        }
    }

    /** Recovery rung 2: rebuild the player on the other decoder and replay. */
    private fun flipDecoderAndReload() {
        val item = currentItem ?: return
        decoderEscalation++
        decoderEscalationsTotal++
        val sv = attachedSurfaceView
        val tv = attachedTextureView
        val np = initPlayer()
        if (sv != null) np.setVideoSurfaceView(sv)
        if (tv != null) np.setVideoTextureView(tv)
        play(item, false)
    }

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            val p = exoPlayer
            val now = android.os.SystemClock.uptimeMillis()
            // Forgive past failures only after 30 s of stable playback.
            if (p != null && retryCount > 0 && readySinceMs > 0L &&
                now - readySinceMs > 30_000L &&
                p.playbackState == Player.STATE_READY
            ) {
                retryCount = 0
            }

            if (p != null && p.playWhenReady && p.playbackState != Player.STATE_IDLE) {
                val currentPos = p.currentPosition
                val buffering = p.playbackState == Player.STATE_BUFFERING || isBuffering

                // 1. Timeline desync: the one seek Media3 itself sanctions
                // for live playback, and it is paired with prepare().
                if (!buffering && (currentPos < 0L || (p.isCurrentMediaItemLive && p.currentLiveOffset > 25000L))) {
                    Log.w(TAG, "Timeline desync (pos=$currentPos, offset=${p.currentLiveOffset}); seek + prepare")
                    resetWatchdogTracking(now)
                    p.seekToDefaultPosition()
                    p.prepare()
                    mainHandler.postDelayed(this, 1000L)
                    return
                }

                // Rendered-frame tracking, straight from the decoder counters.
                val counters = videoCounters ?: p.videoDecoderCounters
                var rendered = -1
                if (counters != null) {
                    counters.ensureUpdated()
                    rendered = counters.renderedOutputBufferCount
                }
                if (rendered >= 0) {
                    when {
                        // First read after a reset: establish the baseline.
                        // This is not proof of life and must not forgive a
                        // recovery that did not work.
                        lastRenderedCount == -1 || rendered < lastRenderedCount -> {
                            lastRenderedCount = rendered
                            lastRenderAdvanceMs = now
                            positionAtRenderAdvance = currentPos
                        }
                        // Frames genuinely advancing again.
                        rendered > lastRenderedCount -> {
                            lastRenderedCount = rendered
                            lastRenderAdvanceMs = now
                            positionAtRenderAdvance = currentPos
                            freezeRecoveries = 0
                        }
                        // rendered == lastRenderedCount: the freeze clock runs.
                    }
                }

                if (!buffering && p.playbackState == Player.STATE_READY &&
                    now - playbackStartMs > 12000L) {
                    // 2. Frozen video: rendered frames stopped while the
                    // timeline (audio) keeps moving for a full 7 seconds.
                    if (p.videoFormat != null && rendered >= 0 && lastRenderedCount >= 60 &&
                        positionAtRenderAdvance >= 0L &&
                        now - lastRenderAdvanceMs > 7000L &&
                        currentPos - positionAtRenderAdvance > 1500L
                    ) {
                        freezeRecoveries++
                        Log.w(TAG, "Video frozen: rendered count stuck at $rendered for ${now - lastRenderAdvanceMs}ms while position advanced; recovery $freezeRecoveries")
                        resetWatchdogTracking(now)
                        if (freezeRecoveries >= 2) {
                            freezeRecoveries = 0
                            flipDecoderAndReload()
                        } else {
                            reprepare(p)
                        }
                        mainHandler.postDelayed(this, 1000L)
                        return
                    }

                    // 3. Total stall: the position itself is not advancing.
                    if (lastObservedPosition != -1L && Math.abs(currentPos - lastObservedPosition) < 250L) {
                        stallCheckCount++
                        if (stallCheckCount >= 4) {
                            Log.w(TAG, "Playback stalled at $currentPos; re-preparing")
                            resetWatchdogTracking(now)
                            reprepare(p)
                            mainHandler.postDelayed(this, 1000L)
                            return
                        }
                    } else {
                        stallCheckCount = 0
                    }
                    lastObservedPosition = currentPos
                } else {
                    // Buffering: keep the freeze clock honest, take no action.
                    lastRenderAdvanceMs = now
                    stallCheckCount = 0
                }
            } else {
                resetWatchdogTracking(now)
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    init {
        try {
            OnDeviceStreamServer.ensureStarted()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting local stream server", e)
        }
    }

    fun initPlayer(surfaceView: SurfaceView? = null): ExoPlayer {
        release()

        // Live HLS buffer sizing: DaddyLive playlists have ~30s windows (5 chunks x 6s).
        // Buffer must fit within the sliding window to prevent behind-live-window stalls.
        val loadControl = DefaultLoadControl.Builder().apply {
            val (minBuffer, maxBuffer) = when (prefs.buffer) {
                "Small" -> Pair(5000, 10000)
                "Normal" -> Pair(12000, 25000)
                "Large" -> Pair(15000, 25000)
                "Very large" -> Pair(20000, 30000)
                else -> Pair(12000, 25000)
            }
            setBufferDurationsMs(
                minBuffer,
                maxBuffer,
                2500, // bufferForPlaybackMs: do not start on a sliver of data
                5000  // bufferForPlaybackAfterRebufferMs: recover solidly
            )
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

            // Decoder ordering for the Google TV Streamer / MT8696: the
            // platform lists the software decoder first, which cannot keep
            // up with HD, so the list is always reordered explicitly to the
            // wanted preference (see preferSoftware below).
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val decoders = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                if (mimeType.startsWith("video/")) {
                    val isGoogleTvStreamer = Build.MODEL.contains("Google TV Streamer", ignoreCase = true) ||
                                             Build.HARDWARE.contains("mt8696", ignoreCase = true) ||
                                             Build.HARDWARE.contains("mt", ignoreCase = true)
                    // Hardware first on the Streamer: its software AVC decoder
                    // cannot keep up with HD and drops frames in bursts of
                    // hundreds (measured on device, v4.2.2). The old freeze
                    // risk is now covered by the frame watchdog and this
                    // ladder: any decoder failure or freeze escalates to
                    // software, and an explicit user choice always wins.
                    val preferSoftware = when {
                        prefs.videoDecoder == "Software" -> true
                        prefs.videoDecoder == "Hardware" -> false
                        isGoogleTvStreamer -> decoderEscalation % 2 == 1
                        else -> false
                    }
                    // Always order the list explicitly. Returning the platform
                    // order when hardware was wanted silently kept the software
                    // decoder on the Streamer (its default order lists
                    // c2.android.avc.decoder first), v4.2.3 first build.
                    val swDecoders = decoders.filter { it.softwareOnly || it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.") }
                    val hwDecoders = decoders.filter { !it.softwareOnly && !it.name.startsWith("c2.android.") && !it.name.startsWith("OMX.google.") }
                    Log.d(TAG, "MediaCodecSelector: preferSoftware=$preferSoftware for $mimeType (hw=${hwDecoders.firstOrNull()?.name}, sw=${swDecoders.firstOrNull()?.name})")
                    return@setMediaCodecSelector (if (preferSoftware) (swDecoders + hwDecoders) else (hwDecoders + swDecoders)).toMutableList()
                }
                decoders
            }
        }

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguages("en", "eng")
                    .setPreferredAudioLanguage(prefs.preferAudioLang.ifBlank { "eng" })
            )
        }

        val livePlaybackSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(0.98f)
            .setFallbackMaxPlaybackSpeed(1.02f)
            .build()

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(livePlaybackSpeedControl)
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
                droppedFramesTotal += droppedFrames
                Log.w(TAG, "DROPPED VIDEO FRAMES: $droppedFrames frames in ${elapsedMs}ms")
            }
            override fun onVideoCodecError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                videoCodecError: Exception
            ) {
                Log.e(TAG, "VIDEO CODEC ERROR: ", videoCodecError)
            }
            override fun onVideoEnabled(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderCounters: androidx.media3.exoplayer.DecoderCounters
            ) {
                // The watchdog's heartbeat: this counters object is updated
                // live by the renderer for every frame actually displayed.
                // (Media3 1.8 hands counters out here, not on decoder init.)
                videoCounters = decoderCounters
                playbackStartMs = android.os.SystemClock.uptimeMillis()
                resetWatchdogTracking(playbackStartMs)
                Log.i(TAG, "Video decoder counters captured")
            }
        })

        // No VideoFrameMetadataListener: on the MediaTek hardware decoder
        // it goes silent during healthy playback, which is what made the
        // old watchdog attack good streams. The watchdog reads
        // DecoderCounters (captured below) instead.

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
                    readySinceMs = android.os.SystemClock.uptimeMillis()
                    decoderRebuildsSinceReady = 0
                    // Remember the route that worked for this channel.
                    currentItem?.let { prefs.put("route_" + it.ch.id, routeAttempt % ROUTE_COUNT) }
                }
                onStateChanged?.invoke()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                onStateChanged?.invoke()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Playback error: ${error.errorCodeName} (${error.errorCode}) - ${error.message}")
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    Log.i(TAG, "Behind live window! Re-syncing to live edge...")
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                if ((error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) &&
                    decoderRebuildsSinceReady < 3) {
                    // Climb the decoder ladder and rebuild the player with the
                    // next decoder strategy instead of replaying into the same
                    // broken decoder (OwnTV_Core-style engine fallback).
                    decoderEscalation++
                    decoderEscalationsTotal++
                    decoderRebuildsSinceReady++
                    Log.w(TAG, "Decoder failure! Ladder step ${decoderEscalation % 3}, rebuilding player...")
                    val item = currentItem
                    if (item != null) {
                        initPlayer()
                        play(item, false)
                    }
                    return
                }
                if (prefs.autoRetry && retryCount < maxRetries) {
                    retryCount++
                    // A dead segment or token is not a dead route: only move
                    // to the next route on every second consecutive failure.
                    if (retryCount % 2 == 0) routeAttempt++
                    Log.i(TAG, "Auto-retrying stream (attempt $retryCount of $maxRetries, route ${routeAttempt % ROUTE_COUNT})...")
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

        mainHandler.postDelayed(healthCheckRunnable, 2000L)
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
        if (resetRetry) {
            retryCount = 0
            routeAttempt = prefs.int("route_" + item.ch.id, 0)
        }
        currentItem = item
        freezeRecoveries = 0
        playbackStartMs = android.os.SystemClock.uptimeMillis()
        resetWatchdogTracking(playbackStartMs)
        val player = exoPlayer ?: initPlayer()

        var streamUrl = item.url
        if (streamUrl.startsWith("/")) {
            streamUrl = "http://127.0.0.1:48913$streamUrl"
        }
        // Ask the local resolver to start from the current route attempt.
        // Only local proxy manifest URLs take a player route; direct
        // external playlist URLs are left untouched.
        if (streamUrl.contains("127.0.0.1:48913") && streamUrl.contains("manifest") && !streamUrl.contains("player=")) {
            streamUrl += (if (streamUrl.contains("?")) "&" else "?") + "player=" + (routeAttempt % ROUTE_COUNT)
        }

        val userAgent = prefs.userAgent.ifBlank { "Mozilla/5.0 (Linux; Android 14; Google TV Streamer) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)
        // No cache in front of live HLS. A CacheDataSource here was the
        // v4.2 stutter bug: playlist reloads were served the first cached
        // manifest forever, so playback died at the end of the initial
        // window (~25 s) and retried in a loop. Live streams must always
        // fetch the manifest fresh; segments are use-once anyway.
        val dataSourceFactory: androidx.media3.datasource.DataSource.Factory = httpFactory

        val uri = Uri.parse(streamUrl)
        val liveConfig = MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(10000L) // 10 seconds behind live: more cushion, fewer stalls
            .setMinOffsetMs(4000L)
            .setMaxOffsetMs(22000L)
            .setMinPlaybackSpeed(0.98f)
            .setMaxPlaybackSpeed(1.02f)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setLiveConfiguration(liveConfig)
            .build()

        val mediaSource = if (streamUrl.contains(".m3u8", ignoreCase = true) || streamUrl.contains("manifest", ignoreCase = true)) {
            val hlsExtractorFactory = DefaultHlsExtractorFactory(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
                true
            )
            HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(false)
                .setExtractorFactory(hlsExtractorFactory)
                .createMediaSource(mediaItem)
        } else {
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }

        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
        onStateChanged?.invoke()
    }

    /** Plain-language playback diagnostics for Settings > Playback > Stream health. */
    fun streamHealth(): String {
        val p = exoPlayer ?: return "Player has not started yet."
        val fmt = p.videoFormat
        val bufferedSec = ((p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)) / 1000L
        return buildString {
            append("Channel: ").append(currentItem?.name ?: "none").append('\n')
            append("State: ").append(
                when {
                    isBuffering -> "buffering"
                    isPlaying -> "playing"
                    else -> "paused or idle"
                }
            ).append('\n')
            append("Resolver route: ").append(routeAttempt % ROUTE_COUNT).append(" of ").append(ROUTE_COUNT - 1).append('\n')
            if (fmt != null) {
                append("Video: ").append(fmt.width).append('x').append(fmt.height)
                    .append(" ").append(fmt.sampleMimeType ?: "")
                    .append(" @ ").append(fmt.bitrate / 1000).append(" kbps").append('\n')
                append("Codec: ").append(fmt.codecs ?: "unknown").append('\n')
            } else {
                append("Video: no track yet").append('\n')
            }
            append("Buffered ahead: ").append(bufferedSec).append("s").append('\n')
            append("Dropped frames this session: ").append(droppedFramesTotal).append('\n')
            append("Decoder ladder step: ").append(decoderEscalation)
                .append(" (escalations this session: ").append(decoderEscalationsTotal).append(")\n")
            append("Retries this channel: ").append(retryCount)
        }
    }

    /**
     * Catch-up (Ondo-style): seek back to a programme's start. Works only
     * while that moment is still inside the live stream's DVR window;
     * returns false when it has already slid out of the window.
     */
    fun seekToProgramStart(programStartMs: Long): Boolean {
        val p = exoPlayer ?: return false
        val behindMs = System.currentTimeMillis() - programStartMs
        if (behindMs < 0L) return false
        val target = p.currentPosition - behindMs
        if (target < 0L) return false
        p.seekTo(target)
        p.play()
        return true
    }

    /** Jump back to the live edge after watching behind live. */
    fun returnToLive() {
        exoPlayer?.seekToDefaultPosition()
        exoPlayer?.play()
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
        videoCounters = null
        resetWatchdogTracking(0L)
    }

    data class TrackInfo(
        val group: Tracks.Group,
        val trackIndex: Int,
        val label: String,
        val isSelected: Boolean
    )
}
