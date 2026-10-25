package com.ipphone.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny, dependency-free diagnostics: counts process / activity starts, remembers the last
 * crash and lets the Diagnostics screen show *why* the app restarted (crash, low memory, ...).
 */
object AppDiagnostics {

    private const val PREFS = "app_diagnostics"
    private const val CRASH_FILE = "last_crash.txt"

    /** Set the first time this object is touched (IPPhoneApp.onCreate does it). */
    val processStartMs: Long = System.currentTimeMillis()

    @Volatile var activityCreateCount: Int = 0
        private set

    fun onProcessStart(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt("process_starts", p.getInt("process_starts", 0) + 1).apply()
    }

    fun onActivityCreated() {
        activityCreateCount++
    }

    fun processStarts(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("process_starts", 0)

    fun crashCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("crash_count", 0)

    fun recordCrash(context: Context, thread: Thread, error: Throwable) {
        val rt = Runtime.getRuntime()
        val text = buildString {
            append("Time: ").append(format(System.currentTimeMillis())).append('\n')
            append("Thread: ").append(thread.name).append('\n')
            append("Memory: used ").append((rt.totalMemory() - rt.freeMemory()) / 1_048_576)
                .append(" MB / max ").append(rt.maxMemory() / 1_048_576).append(" MB\n")
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n\n")
            append(android.util.Log.getStackTraceString(error))
        }
        File(context.filesDir, CRASH_FILE).writeText(text)
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt("crash_count", p.getInt("crash_count", 0) + 1).commit()
    }

    fun lastCrash(context: Context): String? =
        try {
            File(context.filesDir, CRASH_FILE).takeIf { it.exists() }?.readText()
        } catch (e: Exception) {
            null
        }

    fun clearCrash(context: Context) {
        try { File(context.filesDir, CRASH_FILE).delete() } catch (_: Exception) {}
    }

    /** Why earlier processes of this app died (Android 11+). */
    fun recentExitReasons(context: Context, max: Int = 6): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(context.packageName, 0, max).map { info ->
                "${format(info.timestamp)} — ${reasonName(info.reason)}" +
                    (info.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun reasonName(reason: Int): String =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) "unknown"
        else when (reason) {
            ApplicationExitInfo.REASON_CRASH            -> "crash (Java exception)"
            ApplicationExitInfo.REASON_CRASH_NATIVE     -> "native crash"
            ApplicationExitInfo.REASON_ANR              -> "not responding (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY       -> "killed: low memory"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed: excessive resource usage"
            ApplicationExitInfo.REASON_USER_REQUESTED   -> "stopped by user"
            ApplicationExitInfo.REASON_USER_STOPPED     -> "force-stopped by user"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "restarted: permission changed"
            ApplicationExitInfo.REASON_SIGNALED         -> "killed by signal"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED  -> "dependency died"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialisation failure"
            ApplicationExitInfo.REASON_EXIT_SELF        -> "exited itself"
            else                                        -> "other ($reason)"
        }

    private fun format(ms: Long): String =
        SimpleDateFormat("MMM dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
