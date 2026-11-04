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

    // Starvation-loop detection (v4.2.8). A sick edge does not error, it
    // just delivers slightly slower than playback: the player cycles
    // READY/BUFFERING mid-window forever, the picture updating in jumps
    // while audio plays on - and every other watchdog stays blind because
    // frames DO advance in bursts and the state keeps leaving READY.
    // Four rebuffer cycles inside 90 seconds is that trap's signature;
    // the cure is a different resolver route (a different edge host),
    // escalated at most once per two minutes.
    private var lastPlayState = -1
    private val rebufferTimesMs = mutableListOf<Long>()
    private var lastStarvationEscalationMs = 0L

    // Position-advance safety net (v4.2.9). Every older watchdog had a
    // blind spot the tail-pinned trap walked through: the freeze verdict
    // needed position ADVANCING while frames stuck, the stall verdict
    // needed four consecutive READY seconds, and the desync verdict
    // needed a readable live offset and a non-buffering sample. A player
    // parked at the live window's tail - position crawling and resetting
    // as the window slides, state flapping READY/BUFFERING - satisfied
    // none of them and sat frozen for ten minutes. This net only asks:
    // did the position actually move in the last 20 seconds?
    private var netWindowStartMs = 0L
    private var netWindowStartPos = -1L
    private var lastNetRecoveryMs = 0L
    // Since when the player has been continuously in BUFFERING (0 = not
    // buffering). The 4.2.9 net missed the worst trap because it only
    // sampled position: a player parked in BUFFERING with its position
    // plunging negative never satisfied "advanced >= 0" and no other
    // branch (desync needs !buffering, stall needs READY, freeze needs
    // position advancing) would touch it. Continuous buffering time is
    // now a first-class trigger.
    private var bufferingSinceMs = 0L
    private var consecutiveNetRecoveries = 0
    // Uptime of the first STATE_READY since the last play() call. The
    // net watchdog below is gated on it: before the first frame, long
    // buffering is a slow tune, not a stuck stream, and resetting the
    // tune is what turned 20 s of startup buffering into a 44 s first
    // frame on device (2026-10-30).
    private var firstReadyMs = 0L
    private val tlWindow = androidx.media3.common.Timeline.Window()
    private var lastTuneRecoveryMs = 0L
    private var tuneRecoveryCount = 0
    private var lastDesyncSeekMs = 0L
    private val behindLiveWindowTimesMs = mutableListOf<Long>()
    private var userBehindLive = false
    private var userPaused = false
    private var routeSaved = false
    private var retryRunnable: Runnable? = null
    @Volatile private var released = false
    private var flipCountThisChannel = 0
    // Audio-with-no-video verdict state (audit V-05).
    private var noVideoSinceMs = 0L
    private var noVideoRecoveries = 0

    // Absolute media position (audit P1). currentPosition is relative
    // to the live window's start; at the live edge the window slides
    // forward at ~1x, so the relative position hovers roughly CONSTANT
    // during perfectly healthy playback. Any "did the position move"
    // verdict computed from the relative position declares healthy
    // playback stuck - the v4.3.2 net did exactly that about every
    // 60-80 s. The window anchor (positionInFirstPeriodMs) grows as
    // segments fall off the back, so anchor + relative position climbs
    // at 1x while playing and stays flat only when truly stuck.
    private fun absPos(p: ExoPlayer): Long {
        val tl = p.currentTimeline
        if (tl.isEmpty) return p.currentPosition
        return try {
            tl.getWindow(p.currentMediaItemIndex, tlWindow).positionInFirstPeriodMs + p.currentPosition
        } catch (e: Exception) {
            p.currentPosition
        }
    }

    private fun resetWatchdogTracking(now: Long) {
        lastObservedPosition = -1L
        stallCheckCount = 0
        lastRenderedCount = -1
        lastRenderAdvanceMs = now
        positionAtRenderAdvance = -1L
        // The net watchdog's clocks live here too. They used to survive
        // play() and reprepare(), so the position window opened by a
        // previous session (startPos 35.69 at the live edge) was later
        // compared against a fresh session's position (10.46) and read
        // as "stuck" on a healthy player (emulator bench, 2026-10-30).
        netWindowStartMs = 0L
        netWindowStartPos = 0L
        bufferingSinceMs = 0L
        noVideoSinceMs = 0L
    }

    /** Recovery rung 1: re-prepare the same media at the live seat. */
    private fun reprepare(p: ExoPlayer) {
        try {
            p.stop()
            // Seek before prepare (audit P3): stop()+prepare() alone
            // resumes at the old window-relative position on a brand-new
            // timeline, which can land behind the window.
            p.seekToDefaultPosition()
            p.prepare()
            p.playWhenReady = true
            // A re-prepare is a fresh tune: every clock the watchdogs
            // read restarts here, and the net re-gates on the next
            // first READY exactly as after play() (audit P2, P3, P4).
            playbackStartMs = android.os.SystemClock.uptimeMillis()
            lastPlayState = -1
            firstReadyMs = 0L
            resetWatchdogTracking(playbackStartMs)
        } catch (e: Exception) {
            // A failed re-prepare must not be the end (audit P20).
            Log.w(TAG, "Re-prepare failed; replaying instead", e)
            currentItem?.let { play(it, false) }
        }
    }

    /** Recovery rung 2: rebuild the player on the other decoder and replay. */
    private fun flipDecoderAndReload() {
        val item = currentItem ?: return
        // A flip only helps when there IS another decoder to flip to
        // (audit P9): with an explicit Hardware/Software preference, or
        // off the Streamer where the ladder collapses, a "rebuild on
        // the other decoder" rebuilds into the same broken decoder.
        val explicitPref = prefs.videoDecoder == "Hardware" || prefs.videoDecoder == "Software"
        flipCountThisChannel++
        if (explicitPref || flipCountThisChannel > 2) {
            Log.w(TAG, "Decoder flip not applicable or capped; replaying on the next route")
            routeAttempt++
            play(item, false)
            mainHandler.removeCallbacks(healthCheckRunnable)
            mainHandler.postDelayed(healthCheckRunnable, 1000L)
            return
        }
        decoderEscalation++
        decoderEscalationsTotal++
        // initPlayer() already attaches the remembered surface exactly
        // once (audit V-16): re-attaching here double-set the surface
        // and, if both view kinds were ever remembered, left the player
        // rendering into whichever view was attached last.
        initPlayer()
        play(item, false)
    }

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            val p = exoPlayer
            val now = android.os.SystemClock.uptimeMillis()
            // Forgive past failures only after 30 s of stable playback.
            // The working route is also only remembered here, after it
            // has proven itself for 30 continuous seconds: saving it on
            // every READY (the old behavior) let a route that rendered
            // a few frames and then starved become the channel's
            // permanent starting route.
            if (p != null && readySinceMs > 0L &&
                now - readySinceMs > 30_000L &&
                p.playbackState == Player.STATE_READY
            ) {
                retryCount = 0
                consecutiveNetRecoveries = 0
                decoderRebuildsSinceReady = 0
                decoderEscalation = 0
                if (!routeSaved) {
                    routeSaved = true
                    currentItem?.let { prefs.put("route_" + it.ch.id, routeAttempt % ROUTE_COUNT) }
                }
            }
            if (p != null) {
                if (p.playbackState == Player.STATE_BUFFERING) {
                    if (bufferingSinceMs == 0L) bufferingSinceMs = now
                    netWindowStartMs = 0L
                } else {
                    bufferingSinceMs = 0L
                }
            }

            if (p != null && p.playWhenReady && p.playbackState != Player.STATE_IDLE) {
                val currentPos = absPos(p)
                val buffering = p.playbackState == Player.STATE_BUFFERING || isBuffering

                if (firstReadyMs == 0L && p.playbackState == Player.STATE_READY) {
                    firstReadyMs = now
                    tuneRecoveryCount = 0
                }
                // Tune deadline (audit P2): the net below is gated on
                // first READY, so a tune whose loader hangs forever in
                // BUFFERING (the 87 s idle-loader case) needs its own
                // verdict. 45 s without a first READY: re-prepare once,
                // then change route.
                if (firstReadyMs == 0L && playbackStartMs > 0L &&
                    now - playbackStartMs > 45000L && now - lastTuneRecoveryMs > 60000L &&
                    currentItem != null
                ) {
                    tuneRecoveryCount++
                    lastTuneRecoveryMs = now
                    playbackStartMs = now
                    resetWatchdogTracking(now)
                    if (tuneRecoveryCount >= 2) {
                        tuneRecoveryCount = 0
                        Log.w(TAG, "Tune deadline missed twice; switching resolver route")
                        routeAttempt++
                        play(currentItem!!, false)
                    } else {
                        Log.w(TAG, "Tune deadline: no READY in 45 s; re-preparing")
                        reprepare(p)
                    }
                    mainHandler.postDelayed(this, 1000L)
                    return
                }

                // Starvation-loop tracking: count READY -> BUFFERING falls.
                if (lastPlayState == Player.STATE_READY && p.playbackState == Player.STATE_BUFFERING) {
                    rebufferTimesMs.add(now)
                }
                lastPlayState = p.playbackState
                rebufferTimesMs.removeAll { now - it > 90000L }
                if (rebufferTimesMs.size >= 4 &&
                    now - playbackStartMs > 30000L &&
                    now - lastStarvationEscalationMs > 120000L &&
                    currentItem != null
                ) {
                    rebufferTimesMs.clear()
                    lastStarvationEscalationMs = now
                    routeAttempt++
                    Log.w(TAG, "Starvation loop: 4+ rebuffers in 90s; switching to resolver route ${routeAttempt % ROUTE_COUNT}")
                    resetWatchdogTracking(now)
                    play(currentItem!!, false)
                    mainHandler.postDelayed(this, 1000L)
                    return
                }

                // 0. The hole-free net. Triggers on ANY of: 20 s of
                // continuous BUFFERING, or a 20 s window in which the
                // position moved less than 2 s (a plunging negative
                // position counts - the 4.2.9 version required the
                // movement to be positive and so never fired on the
                // parked-at-the-tail trap it was built for). No branch
                // of this check cares whether the state is READY or
                // BUFFERING, which is the point: the trap lives in the
                // gap between the two.
                if (firstReadyMs > 0L && now - lastNetRecoveryMs > 60000L) {
                    // 45 s, not 20: the live window itself is ~36 s, so
                    // a stall the seat can absorb legitimately buffers
                    // for tens of seconds. The net exists for the
                    // loader-stuck state (BUFFERING for 87 s while the
                    // loader sat idle), not for slow segments.
                    var stuck = bufferingSinceMs > 0L && now - bufferingSinceMs > 45000L
                    if (!buffering && p.playbackState == Player.STATE_READY) {
                        if (netWindowStartMs == 0L) {
                            netWindowStartMs = now
                            netWindowStartPos = currentPos
                        } else if (now - netWindowStartMs >= 20000L) {
                            if (currentPos - netWindowStartPos < 2000L) stuck = true
                            netWindowStartMs = now
                            netWindowStartPos = currentPos
                        }
                    }
                    if (stuck) {
                        consecutiveNetRecoveries++
                        lastNetRecoveryMs = now
                        netWindowStartMs = 0L
                        bufferingSinceMs = 0L
                        resetWatchdogTracking(now)
                        if (consecutiveNetRecoveries >= 3 && currentItem != null) {
                            // Re-syncing is not working: this route is
                            // bad. Forget it (including the remembered
                            // one) and replay on the next route.
                            Log.w(TAG, "Stuck stream; net recovery #$consecutiveNetRecoveries, escalating route and forgetting the saved one")
                            routeAttempt++
                            prefs.sp.edit().remove("route_" + currentItem!!.ch.id).apply()
                            consecutiveNetRecoveries = 0
                            play(currentItem!!, false)
                        } else {
                            Log.w(TAG, "Stuck stream; net recovery #$consecutiveNetRecoveries, re-preparing")
                            reprepare(p)
                        }
                        mainHandler.postDelayed(this, 1000L)
                        return
                    }
                }

                // 1. Timeline desync: the one seek Media3 itself sanctions
                // for live playback, and it is paired with prepare(). The
                // grace period matters here too: position reads negative
                // transiently right after a (re)prepare while the timeline
                // resets, and chasing that reading with another seek +
                // prepare just churns the recovery (seen on device,
                // 2026-10-30).
                if (!buffering && !userBehindLive && now - playbackStartMs > 12000L &&
                    now - lastDesyncSeekMs > 15000L &&
                    p.isCurrentMediaItemLive && p.currentLiveOffset != C.TIME_UNSET &&
                    p.currentLiveOffset > 25000L) {
                    Log.w(TAG, "Live offset too large (offset=${p.currentLiveOffset}); seeking to default position")
                    lastDesyncSeekMs = now
                    resetWatchdogTracking(now)
                    p.seekToDefaultPosition()
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
                    // 1c. Audio playing, NO video (audit V-05): READY and
                    // the position advances on audio while there is no
                    // video format at all, or the decoder has rendered
                    // zero frames 10 s after the first READY. Every
                    // older net was blind to this state (the freeze
                    // verdict requires >=60 rendered frames first), so
                    // black-screen-with-sound ran forever. Escalate:
                    // first a route change, then a decoder flip.
                    if (firstReadyMs > 0L && now - firstReadyMs > 10000L &&
                        (p.videoFormat == null || rendered == 0)) {
                        if (noVideoSinceMs == 0L) noVideoSinceMs = now
                        if (now - noVideoSinceMs > 8000L) {
                            noVideoRecoveries++
                            Log.w(TAG, "Audio playing with NO VIDEO (format=${p.videoFormat}, rendered=$rendered); recovery $noVideoRecoveries")
                            resetWatchdogTracking(now)
                            if (noVideoRecoveries >= 2 && currentItem != null) {
                                noVideoRecoveries = 0
                                flipDecoderAndReload()
                                return
                            }
                            if (currentItem != null) {
                                routeAttempt++
                                play(currentItem!!, false)
                            } else {
                                reprepare(p)
                            }
                            mainHandler.postDelayed(this, 1000L)
                            return
                        }
                    } else {
                        noVideoSinceMs = 0L
                    }

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
                            // initPlayer() posts its own health tick;
                            // returning without re-posting keeps exactly
                            // one health chain alive (audit P15).
                            flipDecoderAndReload()
                            return
                        }
                        reprepare(p)
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
                "Normal" -> Pair(20000, 34000)
                // "Large" and "Very large" intentionally equal
                // "Normal": the ~36 s live window caps the useful
                // buffer, so a bigger setting could never be honoured
                // for live playback anyway (audit P17).
                "Large" -> Pair(20000, 34000)
                "Very large" -> Pair(20000, 34000)
                else -> Pair(20000, 34000)
            }
            setBufferDurationsMs(
                minBuffer,
                maxBuffer,
                2500, // bufferForPlaybackMs: do not start on a sliver of data
                // Resume after a rebuffer at 2 s, not 5: while the player
                // waits for a 5 s cushion the ~36 s live window slides 5 s
                // further away, which is how a brief starve turned into a
                // permanent mid-window trap (2026-10-30). OwnTV's live
                // load control restarts at 2 s for the same reason.
                2000
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
                // A failed decoder query must not become a decoder-init
                // failure feeding the rebuild loop (audit V-15): fall
                // back to the platform's default selector answer.
                val decoders = try {
                    MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                } catch (e: Exception) {
                    Log.w(TAG, "Decoder query failed for $mimeType; using default selector", e)
                    androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                        .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                }
                if (mimeType.startsWith("video/")) {
                    // Hardware first by default: the Streamer's software
                    // AVC decoder cannot keep up with HD and drops frames
                    // in bursts of hundreds (measured on device, v4.2.2).
                    // The ladder is the documented 3 steps (audit V-12),
                    // one modulus everywhere: step 0 = user preference /
                    // hardware-first, step 1 = force software, step 2 =
                    // force hardware again after software also failed.
                    // An explicit user choice always wins.
                    val preferSoftware = when {
                        prefs.videoDecoder == "Software" -> true
                        prefs.videoDecoder == "Hardware" -> false
                        else -> decoderEscalation % 3 == 1
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
                    .setPreferredAudioLanguages(prefs.preferAudioLang.ifBlank { "eng" }, "en", "eng")
                    // Video guardrails (audit V-01): without a floor the
                    // adaptive selector can settle on a low-bitrate
                    // audio-only HLS variant and stay there - sound with
                    // a black screen. A minimum video bitrate plus the
                    // onTracksChanged override below keep selection on a
                    // rendition that actually carries video.
                    .setMinVideoBitrate(250_000)
                    .setMaxVideoSize(1920, 1080)
                    .setViewportSize(1920, 1080, true)
                    // Tunneled playback: the hardware A/V sync path on
                    // Android TV silicon (audit V-14). Media3 falls
                    // back automatically when a decoder can't tunnel.
                    .setTunnelingEnabled(true)
            )
        }

        val livePlaybackSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(0.97f)
            .setFallbackMaxPlaybackSpeed(1.05f)
            // Do not let every rebuffer ratchet the target offset further
            // back (the +500 ms default): in a ~36 s window the target
            // migrating on its own makes the trap deeper each cycle. The
            // offset is set deliberately in the LiveConfiguration instead.
            .setTargetLiveOffsetIncrementOnRebufferMs(0L)
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

        // Remember a surface handed in as a parameter (audit V-06):
        // rebuilds call initPlayer() with no argument, and a parameter
        // that was never stored left the rebuilt player surfaceless -
        // audio kept playing, the picture never came back.
        if (surfaceView != null && attachedSurfaceView == null && attachedTextureView == null) {
            attachedSurfaceView = surfaceView
        }
        if (attachedTextureView != null) {
            player.setVideoTextureView(attachedTextureView)
        } else if (attachedSurfaceView != null) {
            player.setVideoSurfaceView(attachedSurfaceView)
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                isPlaying = playbackState == Player.STATE_READY && player.playWhenReady

                if (playbackState == Player.STATE_READY) {
                    readySinceMs = android.os.SystemClock.uptimeMillis()
                    // decoderRebuildsSinceReady is NOT reset here
                    // (audit P7): a decoder that reaches READY on audio
                    // and then fails DECODING_FAILED would rebuild
                    // forever. The budget resets in the health check
                    // after 30 s of stable READY.
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
                    // Bounded (audit P14): a seek fixes a one-off; a
                    // stream that keeps falling out of the window is a
                    // route problem, so the third in a minute changes
                    // route instead of looping seeks forever.
                    val nowMs = android.os.SystemClock.uptimeMillis()
                    behindLiveWindowTimesMs.removeAll { nowMs - it > 60000L }
                    behindLiveWindowTimesMs.add(nowMs)
                    playbackStartMs = nowMs
                    resetWatchdogTracking(nowMs)
                    if (behindLiveWindowTimesMs.size >= 3 && currentItem != null) {
                        behindLiveWindowTimesMs.clear()
                        Log.w(TAG, "Behind live window 3x in a minute; switching resolver route")
                        routeAttempt++
                        play(currentItem!!, false)
                    } else {
                        Log.i(TAG, "Behind live window! Re-syncing to live edge...")
                        player.seekToDefaultPosition()
                    }
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
                    val r = Runnable { currentItem?.let { play(it, false) } }
                    retryRunnable = r
                    mainHandler.postDelayed(r, 2500L)
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
                // Audio-only guard (audit V-01/V-19): if a video group
                // exists but the selected track carries no video (an
                // audio-only variant chosen by adaptive selection),
                // force-select the first real video track instead of
                // playing sound over a black screen indefinitely.
                var videoSelected = false
                var fallbackGroup: Tracks.Group? = null
                var fallbackIndex = -1
                for (group in tracks.groups) {
                    if (group.type != C.TRACK_TYPE_VIDEO) continue
                    for (i in 0 until group.length) {
                        val fmt = group.getTrackFormat(i)
                        val realVideo = fmt.sampleMimeType?.startsWith("video") == true
                        if (group.isTrackSelected(i) && realVideo) videoSelected = true
                        if (realVideo && fallbackGroup == null) {
                            fallbackGroup = group
                            fallbackIndex = i
                        }
                    }
                }
                val fg = fallbackGroup
                if (!videoSelected && fg != null && fallbackIndex >= 0) {
                    Log.w(TAG, "No video track selected though video exists; forcing a video rendition")
                    try {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(TrackSelectionOverride(fg.mediaTrackGroup, fallbackIndex))
                            .build()
                    } catch (e: Exception) {
                        Log.w(TAG, "Video track override failed", e)
                    }
                }
                onTracksChanged?.invoke()
            }
        })

        mainHandler.removeCallbacks(healthCheckRunnable)
        mainHandler.postDelayed(healthCheckRunnable, 2000L)
        exoPlayer = player
        return player
    }

    fun attachSurface(surfaceView: SurfaceView?) {
        if (attachedSurfaceView == surfaceView && attachedTextureView == null) return
        val old = attachedSurfaceView
        attachedSurfaceView = surfaceView
        attachedTextureView = null
        // Detach through the dedicated clear call (audit V-07): the
        // setter-with-null path left the player targeting a destroyed
        // surface on some Media3 paths.
        if (surfaceView == null && old != null) {
            exoPlayer?.clearVideoSurfaceView(old)
        } else {
            exoPlayer?.setVideoSurfaceView(surfaceView)
        }
    }

    fun attachTextureView(textureView: TextureView?) {
        if (attachedTextureView == textureView && attachedSurfaceView == null) return
        attachedTextureView = textureView
        attachedSurfaceView = null
        exoPlayer?.setVideoTextureView(textureView)
    }

    fun play(item: Item, resetRetry: Boolean = true) {
        if (released) return
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        if (resetRetry) {
            retryCount = 0
            routeAttempt = prefs.int("route_" + item.ch.id, 0)
            consecutiveNetRecoveries = 0
            lastNetRecoveryMs = 0L
            lastStarvationEscalationMs = 0L
            decoderRebuildsSinceReady = 0
            decoderEscalation = 0
            flipCountThisChannel = 0
            behindLiveWindowTimesMs.clear()
        }
        currentItem = item
        userBehindLive = false
        userPaused = false
        routeSaved = false
        tuneRecoveryCount = 0
        freezeRecoveries = 0
        netWindowStartMs = 0L
        bufferingSinceMs = 0L
        rebufferTimesMs.clear()
        lastPlayState = -1
        playbackStartMs = android.os.SystemClock.uptimeMillis()
        firstReadyMs = 0L
        // A stale ready timestamp made the 30 s stability forgiveness
        // and the no-video verdict read a previous tune's age
        // (audit V-35).
        readySinceMs = 0L
        noVideoRecoveries = 0
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
            // 25 s, above the local proxy's own worst case (a fast
            // manifest attempt plus a token renewal plus one retry can
            // stack to ~24 s); a timeout below the proxy's answer time
            // makes the player abandon loads that were about to succeed.
            .setReadTimeoutMs(25000)
            .setAllowCrossProtocolRedirects(true)
        // No cache in front of live HLS. A CacheDataSource here was the
        // v4.2 stutter bug: playlist reloads were served the first cached
        // manifest forever, so playback died at the end of the initial
        // window (~25 s) and retried in a loop. Live streams must always
        // fetch the manifest fresh; segments are use-once anyway.
        val dataSourceFactory: androidx.media3.datasource.DataSource.Factory = httpFactory

        val uri = Uri.parse(streamUrl)
        val liveConfig = MediaItem.LiveConfiguration.Builder()
            // DaddyLive windows measure ~36 s on device (about 6
            // segments). The offset is measured from the live EDGE, so
            // the slack that keeps the playhead from being evicted off
            // the back of the window is window minus offset. A 28 s
            // offset left only ~6-8 s of that slack and one slow
            // segment killed playback with BEHIND_LIVE_WINDOW 12 s in
            // (2026-10-30). A ~16 s target leaves ~20 s of eviction
            // slack; a stall then costs a rebuffer, not the stream.
            .setTargetOffsetMs(16000L)
            .setMinOffsetMs(10000L)
            .setMaxOffsetMs(30000L)
            .setMinPlaybackSpeed(0.97f)
            .setMaxPlaybackSpeed(1.05f)
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
                // Fail fast on load errors. The default policy's backoff
                // can hold a single timing-out segment for ~27 s of a
                // ~36 s window before any error surfaces, which reads on
                // screen as an endless buffer. Two quick retries, then
                // the error reaches onPlayerError, where the route and
                // re-resolve machinery can actually fix it.
                // Extends the default policy (audit V-02): the old
                // anonymous policy returned null from
                // getFallbackSelectionFor, which disabled Media3's HLS
                // variant/track fallback entirely - one bad rendition
                // errored the whole playback instead of switching.
                // Inheriting the default keeps fallback working while
                // the two overrides below keep the fail-fast timing.
                .setLoadErrorHandlingPolicy(object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
                    override fun getRetryDelayMsFor(
                        loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
                    ): Long {
                        return if (loadErrorInfo.errorCount <= 3) 500L else C.TIME_UNSET
                    }
                    // 1 here made Loader.maybeThrowError throw after
                    // ~2 consecutive failures, so a local-proxy blip
                    // cost a full error plus re-tune (audit P13).
                    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 3
                })
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
        // The programme start in window coordinates: the playhead plus
        // its distance behind the live edge, minus how far the start
        // is behind now (audit P21 - the old math assumed the playhead
        // sat exactly at the edge).
        val offset = if (p.isCurrentMediaItemLive && p.currentLiveOffset != C.TIME_UNSET) p.currentLiveOffset else 0L
        val target = p.currentPosition + offset - behindMs
        if (target < 0L) return false
        userBehindLive = true
        p.seekTo(target)
        p.play()
        return true
    }

    /** Jump back to the live edge after watching behind live. */
    fun returnToLive() {
        userBehindLive = false
        exoPlayer?.seekToDefaultPosition()
        exoPlayer?.play()
    }

    fun pause() {
        userPaused = true
        exoPlayer?.pause()
    }

    /** User-initiated resume (the overlay's play action). */
    fun resume() {
        userPaused = false
        exoPlayer?.play()
    }

    // Lifecycle pause: not a user pause, so it does not set userPaused.
    fun pauseFromLifecycle() {
        exoPlayer?.pause()
    }

    // Lifecycle resume (audit P22): an onResume must not override a
    // pause the user chose, or replay a stream that already errored.
    // A long lifecycle pause leaves the playhead behind the sliding
    // window, so re-seat at the live edge instead of erroring out
    // through BEHIND_LIVE_WINDOW (audit M4).
    fun resumeFromLifecycle() {
        if (userPaused) return
        exoPlayer?.seekToDefaultPosition()
        exoPlayer?.play()
    }

    fun seekRelative(seconds: Int) {
        exoPlayer?.let { p ->
            if (seconds < 0) userBehindLive = true
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

    /** Video tracks, mirroring the audio picker (audit V-01: there was
     *  no video-track API at all, so a wrong rendition could never be
     *  corrected from the UI or diagnostics). */
    fun getVideoTracks(): List<TrackInfo> {
        val player = exoPlayer ?: return emptyList()
        val tracks = player.currentTracks
        val result = mutableListOf<TrackInfo>()
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = format.label ?: "${format.width}x${format.height}"
                    result.add(TrackInfo(group, i, label, group.isTrackSelected(i)))
                }
            }
        }
        return result
    }

    fun selectVideoTrack(trackInfo: TrackInfo) {
        val player = exoPlayer ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(trackInfo.group.mediaTrackGroup, trackInfo.trackIndex))
            .build()
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

    fun release(terminal: Boolean = false) {
        if (terminal) released = true
        mainHandler.removeCallbacksAndMessages(null)
        // Detach the dying player from its surface explicitly and, on
        // terminal release, forget the views (audit V-07). Non-terminal
        // release is initPlayer()'s rebuild path and must keep the
        // remembered surface for the new player.
        try {
            if (attachedTextureView != null) exoPlayer?.clearVideoTextureView(attachedTextureView)
            if (attachedSurfaceView != null) exoPlayer?.clearVideoSurfaceView(attachedSurfaceView)
        } catch (e: Exception) {
            Log.w(TAG, "Surface detach on release failed", e)
        }
        exoPlayer?.release()
        exoPlayer = null
        if (terminal) {
            attachedSurfaceView = null
            attachedTextureView = null
        }
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
