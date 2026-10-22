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
    private val failed = HashSet<String>()

    fun get(ctx: Context, url: String, onReady: () -> Unit): Bitmap? {
        if (url.isEmpty() || url in failed) return null
        cache.get(url)?.let { return it }
        if (pending.add(url)) {
            Glide.with(ctx.applicationContext).asBitmap().load(url).override(180, 120).into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(r: Bitmap, t: Transition<in Bitmap>?) { cache.put(url, r); pending.remove(url); onReady() }
                override fun onLoadCleared(p: Drawable?) { pending.remove(url) }
                override fun onLoadFailed(e: Drawable?) { pending.remove(url); failed.add(url) }
            })
        }
        return null
    }
}
