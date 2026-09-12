package com.imux.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** Small, non-persistent root bridge. It never installs a module or modifies boot state. */
object RootManager {
    suspend fun requestRoot(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("su").redirectErrorStream(true).start()
            OutputStreamWriter(process.outputStream).use { writer ->
                writer.write("id\nexit\n")
                writer.flush()
            }
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val code = process.waitFor()
            if (code != 0 || !output.contains("uid=0")) {
                error("Root was denied or is unavailable")
            }
            output.trim()
        }
    }

    suspend fun exec(command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val code = process.waitFor()
            if (code != 0) error(output.ifBlank { "Command failed: $code" })
            output.trim()
        }
    }
}
