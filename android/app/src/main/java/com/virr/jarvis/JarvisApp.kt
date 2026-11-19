package com.virr.jarvis

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Application
import android.os.Build

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            CrashLog.add("CRASH (thread ${thread.name})", e)
            previous?.uncaughtException(thread, e)
        }
        CrashLog.add("START", "Jarvis ${BuildConfigInfo.version} on Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}")
        logPastExits()
    }

    /** Catches kills no Java handler sees: out-of-memory, native crashes, being swiped away. */
    private fun logPastExits() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val prefs = getSharedPreferences("jarvis-log", MODE_PRIVATE)
            val last = prefs.getLong("lastExit", 0L)
            var newest = last
            for (i in am.getHistoricalProcessExitReasons(packageName, 0, 10)) {
                if (i.timestamp <= last) continue
                newest = maxOf(newest, i.timestamp)
                val why = when (i.reason) {
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (killed by Android for memory)"
                    ApplicationExitInfo.REASON_CRASH -> "CRASH"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                    ApplicationExitInfo.REASON_ANR -> "ANR (app not responding)"
                    ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                    ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED (force stop)"
                    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                    ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                    else -> "OTHER(${i.reason})"
                }
                CrashLog.add("PREVIOUS EXIT", "$why | ${i.description ?: ""} | process=${i.processName} | pss=${i.pss}KB rss=${i.rss}KB")
            }
            prefs.edit().putLong("lastExit", newest).apply()
        } catch (e: Exception) {
            CrashLog.add("exit-info", e)
        }
    }
}

object BuildConfigInfo { const val version = "0.7" }
