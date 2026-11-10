package com.livetv.app.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Live HLS playback through libVLC.
 *
 * Why: on the Google TV Streamer (MediaTek MT8696, 32-bit userspace)
 * Media3's hardware path (c2.mtk.avc.decoder) freezes the picture while
 * audio continues (androidx/media#2972), and the software decoder cannot
 * keep real time, so ExoPlayer drops hundreds of frames per second to
 * stay in sync. libVLC drives the same MediaCodec hardware decoder
 * through its own pipeline and plays these streams smoothly.
 */
class VlcBackend(context: Context, private val listener: Listener) {

    interface Listener {
        fun onVlcPlaying()
        fun onVlcBuffering(buffering: Boolean)
        fun onVlcVideoSize(width: Int, height: Int)
        fun onVlcError(reason: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val libVlc = LibVLC(
        context.applicationContext,
        arrayListOf(
            "--no-stats",
            "--no-sub-autodetect-file",
            // Hardware MediaCodec first, software fallback inside VLC.
            "--codec=mediacodec_ndk,mediacodec_jni,all",
            "--audio-time-stretch",
            "--http-reconnect",
        )
    )
    private val player = MediaPlayer(libVlc)
    private var view: TextureView? = null
    private var attached = false
    private var lastUrl: String? = null
    var isActive = false; private set

    init {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> listener.onVlcPlaying()
                MediaPlayer.Event.Buffering -> listener.onVlcBuffering(e.buffering < 100f)
                MediaPlayer.Event.Vout -> if (e.voutCount > 0) reportSize()
                MediaPlayer.Event.ESAdded -> reportSize()
                MediaPlayer.Event.EncounteredError -> listener.onVlcError("libVLC error")
                // A live stream never "ends"; reaching the end means the
                // source stopped answering.
                MediaPlayer.Event.EndReached -> listener.onVlcError("stream ended")
            }
        }
    }

    private fun reportSize() {
        val t = player.currentVideoTrack ?: return
        if (t.width > 0 && t.height > 0) listener.onVlcVideoSize(t.width, t.height)
    }

    fun attach(textureView: TextureView?) {
        if (view === textureView && attached) return
        detachVout()
        view = textureView
        if (textureView != null && isActive) attachVout()
    }

    private fun attachVout() {
        val v = view ?: return
        val vout = player.vlcVout
        if (vout.areViewsAttached()) vout.detachViews()
        vout.setVideoView(v)
        vout.attachViews()
        attached = true
        updateWindowSize()
        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateWindowSize() }
    }

    private fun updateWindowSize() {
        val v = view ?: return
        if (v.width > 0 && v.height > 0) {
            player.vlcVout.setWindowSize(v.width, v.height)
            player.aspectRatio = null
            player.scale = 0f
        }
    }

    private fun detachVout() {
        if (attached) {
            try { player.vlcVout.detachViews() } catch (e: Exception) { Log.w(TAG, "detach", e) }
            attached = false
        }
    }

    fun play(url: String, userAgent: String, captions: Boolean, audioDelayMs: Int = 0) {
        lastUrl = url
        isActive = true
        if (!attached) attachVout()
        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            // Live HLS from the local proxy: a few seconds of cache keeps
            // zaps fast while riding over segment jitter.
            addOption(":network-caching=3000")
            addOption(":live-caching=3000")
            addOption(":http-user-agent=$userAgent")
        }
        player.media = media
        media.release()
        player.play()
        setAudioDelay(audioDelayMs)
        setCaptions(captions)
        Log.i(TAG, "VLC play $url")
    }

    /** CEA-608 inside the TS shows up as an SPU track once demuxed. */
    fun setCaptions(on: Boolean) {
        main.postDelayed({
            if (!isActive) return@postDelayed
            val tracks = player.spuTracks ?: return@postDelayed
            val target = if (on) tracks.firstOrNull { it.id >= 0 }?.id ?: -1 else -1
            player.spuTrack = target
        }, 1500L)
    }

    /** Positive delays audio; negative brings it earlier. */
    fun setAudioDelay(ms: Int) { val ok = player.setAudioDelay(ms * 1000L); Log.i(TAG, "audio delay " + ms + " ms applied=" + ok) }

    fun restart() { lastUrl?.let { url -> player.media?.let { player.stop(); player.play() } ?: Log.w(TAG, "no media for $url") } }
    fun pause() = player.pause()
    fun resume() = player.play()
    val isPlaying get() = player.isPlaying

    fun stop() {
        if (!isActive) return
        isActive = false
        player.stop()
        detachVout()
    }

    fun release() {
        stop()
        player.setEventListener(null)
        player.release()
        libVlc.release()
    }

    fun audioTracks(): List<Pair<Int, String>> =
        player.audioTracks?.filter { it.id >= 0 }?.map { it.id to it.name } ?: emptyList()
    fun selectAudio(id: Int) { player.audioTrack = id }
    val currentAudio get() = player.audioTrack

    fun healthText(): String {
        val t = player.currentVideoTrack
        return buildString {
            append("Engine: libVLC (hardware MediaCodec)\n")
            append("State: ").append(if (player.isPlaying) "playing" else "not playing").append('\n')
            if (t != null) append("Video: ").append(t.width).append('x').append(t.height).append(' ').append(t.codec ?: "").append('\n')
        }
    }

    companion object { private const val TAG = "VlcBackend" }
}
