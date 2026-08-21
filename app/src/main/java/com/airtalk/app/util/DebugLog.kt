package com.airtalk.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private val buffer = mutableListOf<String>()
    private const val MAX = 800
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var prev: Thread.UncaughtExceptionHandler? = null

    fun init(ctx: Context) {
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            file = File(dir, "airtalk_debug.log")
            file?.appendText("=== session start ${Date()} ===\n")
        } catch (_: Exception) {
        }
        prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            append("CRASH", "thread=${t.name} ${e::class.java.name}: ${e.message}\n${e.stackTraceToString()}")
            prev?.uncaughtException(t, e)
        }
    }

    @Synchronized
    fun append(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        buffer.add(line)
        while (buffer.size > MAX) buffer.removeAt(0)
        try { file?.appendText(line + "\n") } catch (_: Exception) {}
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        try { file?.writeText("") } catch (_: Exception) {}
    }

    @Synchronized
    fun dump(): String = buffer.joinToString("\n")
}
