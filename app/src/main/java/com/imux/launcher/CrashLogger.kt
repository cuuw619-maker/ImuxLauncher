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
        if (currentProcessName(context).endsWith(":crash")) return

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log(context, "FATAL", "Uncaught exception on ${thread.name}: ${throwable.stackTraceToString()}")
            runCatching {
                context.startActivity(Intent(context, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(CrashActivity.EXTRA_MESSAGE, throwable.stackTraceToString())
                })
            }
            // CrashActivity runs in a separate process, so it remains available after this process dies.
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }

    private fun currentProcessName(context: Context): String = runCatching {
        if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
        else context.packageName
    }.getOrDefault(context.packageName)

    fun log(context: Context, level: String, message: String) {
        runCatching {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val file = File(context.filesDir, FILE_NAME)
            file.appendText("[$timestamp] [$level] $message\n")
            if (file.length() > MAX_CHARS * 2L) file.writeText(file.readText().takeLast(MAX_CHARS))
        }
    }

    fun read(context: Context): String = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText() ?: "No Imux log has been recorded yet."
    }.getOrDefault("Unable to read Imux log.")

    fun clear(context: Context) { runCatching { File(context.filesDir, FILE_NAME).delete() } }
}
