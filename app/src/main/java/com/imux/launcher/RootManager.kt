package com.imux.launcher

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Session-only root bridge. Uses SukiSU-Ultra's normal `su` entry point when
 * it is installed, without accessing private kernel interfaces.
 */
object RootManager {
    private const val TIMEOUT_SECONDS = 15L

    private val suCandidates = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su"
    )

    data class RootInfo(
        val granted: Boolean,
        val provider: String,
        val detail: String
    )

    fun requestRoot(): Result<String> = runCatching {
        val provider = findSuProvider() ?: error("No su executable was found")
        val process = ProcessBuilder(provider, "-c", "id -u")
            .redirectErrorStream(true)
            .start()

        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Root request timed out")
        }

        val output = readOutput(process).trim()
        if (process.exitValue() != 0 || output != "0") {
            error("Root was denied or unavailable: ${output.ifBlank { "su exit ${process.exitValue()}" }}")
        }
        provider
    }

    fun probe(): RootInfo {
        val provider = findSuProvider()
            ?: return RootInfo(false, "none", "su executable not found")

        return runCatching {
            val process = ProcessBuilder(provider, "-c", "id -u")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return RootInfo(false, provider, "su timed out")
            }
            val output = readOutput(process).trim()
            if (process.exitValue() == 0 && output == "0") {
                RootInfo(true, provider, "uid=0")
            } else {
                RootInfo(false, provider, output.ifBlank { "permission denied" })
            }
        }.getOrElse { RootInfo(false, provider, it.message ?: "su failed") }
    }

    fun exec(command: String): Result<String> = runCatching {
        val provider = findSuProvider() ?: error("No su executable was found")
        val process = ProcessBuilder(provider, "-c", command)
            .redirectErrorStream(true)
            .start()

        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Root command timed out")
        }

        val output = readOutput(process)
        if (process.exitValue() != 0) {
            error(output.ifBlank { "Command failed: ${process.exitValue()}" })
        }
        output.trim()
    }

    private fun findSuProvider(): String? = suCandidates.firstOrNull { candidate ->
        runCatching {
            val process = ProcessBuilder(candidate, "-c", "exit 0")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)
    }

    private fun readOutput(process: Process): String =
        BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
}
