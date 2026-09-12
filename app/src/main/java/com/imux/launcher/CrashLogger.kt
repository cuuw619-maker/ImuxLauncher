package com.imux.launcher

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {
    private const val FILE_NAME = "imux_runtime.log"
    private const val MAX_CHARS = 120_000

    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        log(context, "INFO", "Application started; Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log(context, "FATAL", "Uncaught exception on ${thread.name}: ${throwable.stackTraceToString()}")
            try {
                val intent = Intent(context, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(CrashActivity.EXTRA_MESSAGE, throwable.stackTraceToString())
                }
                context.startActivity(intent)
            } catch (_: Throwable) {
                // The process is already in an unrecoverable state; keep the log on disk.
            }
            Thread.sleep(250)
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }

    fun log(context: Context, level: String, message: String) {
        runCatching {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val file = File(context.filesDir, FILE_NAME)
            file.appendText("[$timestamp] [$level] $message\n")
            if (file.length() > MAX_CHARS * 2L) {
                val text = file.readText()
                file.writeText(text.takeLast(MAX_CHARS))
            }
        }
    }

    fun read(context: Context): String = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()
            ?: "No Imux log has been recorded yet."
    }.getOrDefault("Unable to read Imux log.")

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
