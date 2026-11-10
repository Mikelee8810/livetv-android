package com.livetv.app.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Keeps scheduled recordings alive on TVs that kill background apps.
 *
 * Preferred path: Shizuku (ADB-level shell, no root) adds the app to the
 * device-idle whitelist and allows it to run in the background. Fallback:
 * the standard "ignore battery optimizations" system prompt, which many
 * Google TV builds do not ship.
 */
object BatteryFix {
    private const val TAG = "BatteryFix"
    private const val REQ = 4242

    fun isExempt(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun shizukuRunning(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    /** Runs the fix and reports a user-facing result through [done] (on a background thread for Shizuku). */
    fun apply(activity: Activity, done: (String) -> Unit) {
        if (shizukuRunning()) {
            if (Shizuku.isPreV11()) { done("Your Shizuku is too old. Update Shizuku and try again."); return }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                runShizuku(activity, done)
            } else {
                Shizuku.addRequestPermissionResultListener(object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        Shizuku.removeRequestPermissionResultListener(this)
                        if (grantResult == PackageManager.PERMISSION_GRANTED) runShizuku(activity, done)
                        else done("Shizuku permission was denied, so nothing was changed.")
                    }
                })
                Shizuku.requestPermission(REQ)
            }
            return
        }
        // No Shizuku: try the standard system prompt.
        try {
            @Suppress("BatteryLife")
            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + activity.packageName)))
            done("Choose Allow on the next screen.")
        } catch (e: Exception) {
            done("This TV has no battery setting screen. Start Shizuku and try again.")
        }
    }

    private fun runShizuku(ctx: Context, done: (String) -> Unit) {
        Thread {
            val pkg = ctx.packageName
            val cmds = listOf(
                "dumpsys deviceidle whitelist +$pkg",
                "cmd appops set $pkg RUN_IN_BACKGROUND allow",
                "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow"
            )
            var ok = 0
            for (c in cmds) if (exec(c) == 0) ok++
            done(if (ok > 0) "Done. Recordings will keep running in the background." else "Shizuku could not apply the fix. Check that Shizuku is running.")
        }.start()
    }

    /**
     * Shizuku.newProcess is not public API in Shizuku 13, but it is the
     * documented-by-sample way to run one shell command without a bound
     * UserService; it is called reflectively and fails soft.
     */
    private fun exec(cmd: String): Int = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val code = p.waitFor()
        Log.i(TAG, "'$cmd' -> $code")
        code
    } catch (e: Throwable) {
        Log.w(TAG, "Shizuku exec failed: $cmd", e)
        -1
    }
}
