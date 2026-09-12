package com.imux.launcher

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Session-only root bridge. Uses the normal `su` entry point exposed by the
 * installed root manager (including SukiSU-Ultra). It does not access private
 * kernel APIs and does not attempt to persist root access.
 */
object RootManager {
    private const val TIMEOUT_SECONDS = 15L

    private val suCandidates = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su"
    )

    fun requestRoot(): Result<String> = runCatching {
        val provider = findExecutable() ?: error("su executable was not found")
        val result = runSu(provider, "id -u")
        if (result.exitCode != 0) {
            error(result.output.ifBlank { "su exited with code ${result.exitCode}" })
        }

        val uid = result.output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?: error("su returned no UID")

        if (uid != "0") {
            error("Root permission was denied (uid=$uid)")
        }
        provider
    }

    fun probe(): RootInfo {
        val provider = findExecutable()
            ?: return RootInfo(false, "none", "su executable not found")

        return runCatching {
            val result = runSu(provider, "id -u")
            val uid = result.output.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.isNotEmpty() }
                ?: ""
            if (result.exitCode == 0 && uid == "0") {
                RootInfo(true, provider, "uid=0")
            } else {
                RootInfo(false, provider, result.output.ifBlank { "permission denied" })
            }
        }.getOrElse { RootInfo(false, provider, it.message ?: "su failed") }
    }

    fun exec(command: String): Result<String> = runCatching {
        val provider = findExecutable() ?: error("su executable was not found")
        val result = runSu(provider, command)
        if (result.exitCode != 0) {
            error(result.output.ifBlank { "Command failed: ${result.exitCode}" })
        }
        result.output.trim()
    }

    private fun findExecutable(): String? = suCandidates.firstOrNull { candidate ->
        runCatching {
            ProcessBuilder(candidate, "-c", "exit 0")
                .redirectErrorStream(true)
                .start()
                .use { process ->
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        false
                    } else process.exitValue() == 0
                }
        }.getOrDefault(false)
    }

    private data class SuResult(val exitCode: Int, val output: String)

    private fun runSu(provider: String, command: String): SuResult {
        val process = ProcessBuilder(provider, "-c", command)
            .redirectErrorStream(true)
            .start()

        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("su command timed out")
        }

        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        return SuResult(process.exitValue(), output)
    }

    data class RootInfo(
        val granted: Boolean,
        val provider: String,
        val detail: String
    )
}
