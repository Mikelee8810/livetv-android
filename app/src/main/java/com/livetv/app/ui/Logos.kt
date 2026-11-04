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
    private val cache = object : LruCache<String, Bitmap>(260) {}
    private val pending = HashSet<String>()
    private val waiters = HashMap<String, MutableList<() -> Unit>>()
    private val failedAt = HashMap<String, Long>()
    private const val FAIL_TTL_MS = 300_000L

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
                override fun onLoadFailed(e: Drawable?) { pending.remove(url); waiters.remove(url); failedAt[url] = System.currentTimeMillis() }
            })
        }
        return null
    }
}
