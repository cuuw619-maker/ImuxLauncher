package com.imux.launcher

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** Session-only root bridge. It only talks to an already-installed `su` provider. */
object RootManager {
    private const val TIMEOUT_SECONDS = 15L

    fun requestRoot(): Result<String> = runCatching {
        val process = ProcessBuilder("su", "-c", "id -u")
            .redirectErrorStream(true)
            .start()

        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Root request timed out")
        }

        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }.trim()
        if (process.exitValue() != 0 || output != "0") {
            error("Root was denied or is unavailable")
        }
        output
    }

    fun exec(command: String): Result<String> = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()

        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Root command timed out")
        }

        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        if (process.exitValue() != 0) {
            error(output.ifBlank { "Command failed: ${process.exitValue()}" })
        }
        output.trim()
    }
}
