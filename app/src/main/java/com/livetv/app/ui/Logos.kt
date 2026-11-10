package com.livetv.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

/** Channel logo loader with an in-memory cache; views get a callback once a bitmap arrives. */
object Logos {
    private val cache = object : LruCache<String, Bitmap>(600) {}
    private val pending = HashSet<String>()
    private val waiters = HashMap<String, MutableList<() -> Unit>>()
    private val failedAt = HashMap<String, Long>()
    private const val FAIL_TTL_MS = 300_000L
    // One quick retry before the 5-minute back-off: logo hosts
    // (imgur, raw.githubusercontent) throw transient 429/5xx when
    // hundreds of logos are requested at once on first launch.
    private val retried = HashSet<String>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun get(ctx: Context, url: String, onReady: () -> Unit): Bitmap? {
        if (url.isEmpty()) return null
        // Audit Lg1: failures used to be permanent for the process
        // life; a transient CDN error blacklisted a logo forever.
        // Retry after 5 minutes.
        val failed = failedAt[url]
        if (failed != null) {
            if (System.currentTimeMillis() - failed < FAIL_TTL_MS) return null
            failedAt.remove(url)
        }
        cache.get(url)?.let { return it }
        // Audit Lg2: every requester is registered as a waiter. The
        // old pending-set dedupe silently dropped later requesters'
        // callbacks, leaving their views on the placeholder forever.
        // Audit R3: but GuideView calls get() on EVERY draw for every
        // visible row, so unbounded registration piled up hundreds of
        // lambdas per URL. Cap the waiter list - late draw callers
        // still get invalidated by the guide's regular tick/scroll.
        val wl = waiters.getOrPut(url) { mutableListOf() }
        if (wl.size < 8) wl.add(onReady)
        if (pending.add(url)) {
            Glide.with(ctx.applicationContext).asBitmap().load(url).override(180, 120).into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(r: Bitmap, t: Transition<in Bitmap>?) {
                    cache.put(url, r); pending.remove(url)
                    // Audit Lg3: waiter callbacks (which capture
                    // views) are invoked once and then dropped on
                    // every terminal event, so they cannot pile up
                    // or leak with the Glide target.
                    val cbs = waiters.remove(url).orEmpty()
                    cbs.forEach { it() }
                }
                override fun onLoadCleared(p: Drawable?) { pending.remove(url); waiters.remove(url) }
                override fun onLoadFailed(e: Drawable?) {
                    pending.remove(url)
                    if (retried.add(url)) {
                        // Keep the waiters; retry once after a short pause.
                        main.postDelayed({ get(ctx, url) {} }, 3000L)
                    } else {
                        waiters.remove(url); failedAt[url] = System.currentTimeMillis()
                    }
                }
            })
        }
        return null
    }

    /**
     * Warm the cache for the first channels so the guide shows real
     * logos on first paint instead of loading them row by row as they
     * scroll into view. Must be called on the main thread.
     */
    fun prefetch(ctx: Context, urls: List<String>, limit: Int = 300) {
        urls.asSequence().filter { it.isNotBlank() }.distinct().take(limit).forEach { get(ctx, it) {} }
    }
}
