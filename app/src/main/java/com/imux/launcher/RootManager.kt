package com.imux.launcher

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** Session-only root bridge. It does not install a module or alter boot state. */
object RootManager {
    fun requestRoot(): Result<String> = runCatching {
        val process = ProcessBuilder("su").redirectErrorStream(true).start()
        OutputStreamWriter(process.outputStream).use { writer ->
            writer.write("id\nexit\n")
            writer.flush()
        }
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        val code = process.waitFor()
        if (code != 0 || !output.contains("uid=0")) error("Root was denied or is unavailable")
        output.trim()
    }

    fun exec(command: String): Result<String> = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        val code = process.waitFor()
        if (code != 0) error(output.ifBlank { "Command failed: $code" })
        output.trim()
    }
}
