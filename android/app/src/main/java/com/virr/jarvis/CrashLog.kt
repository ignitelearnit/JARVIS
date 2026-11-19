package com.virr.jarvis

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Append-only log. Never overwritten; trimmed from the top only if it grows past ~2 MB. */
object CrashLog {
    private const val NAME = "jarvis-log.txt"
    private const val MAX = 2_000_000L
    private var dir: File? = null

    fun init(ctx: Context) {
        dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    }

    fun file(): File? = dir?.let { File(it, NAME) }

    @Synchronized
    fun add(tag: String, msg: String) {
        try {
            val f = file() ?: return
            if (f.exists() && f.length() > MAX) {
                val keep = f.readText().takeLast((MAX / 2).toInt())
                f.writeText("... (older entries trimmed) ...\n$keep")
            }
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())
            f.appendText("[$ts] $tag: $msg\n")
        } catch (_: Exception) {}
    }

    fun add(tag: String, t: Throwable) = add(tag, android.util.Log.getStackTraceString(t))
}
