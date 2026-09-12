package com.imux.launcher

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** Session-only root bridge through the already-installed `su` provider. */
object RootManager {
    private const val TIMEOUT_SECONDS = 15L
    private val suCandidates = listOf("su", "/system/bin/su", "/system/xbin/su")

    fun requestRoot(): Result<String> = runCatching {
        var lastError = "su executable was not found"
        for (provider in suCandidates) {
            val result = runCatching { runSu(provider, "id -u") }
            if (result.isFailure) {
                lastError = result.exceptionOrNull()?.message ?: lastError
                continue
            }
            val su = result.getOrThrow()
            val uid = su.output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
            if (su.exitCode == 0 && uid == "0") return@runCatching provider
            lastError = su.output.ifBlank { "su exited with code ${su.exitCode}" }
        }
        error("Root permission was denied or unavailable: $lastError")
    }

    fun exec(command: String): Result<String> = runCatching {
        var lastError = "su executable was not found"
        for (provider in suCandidates) {
            val result = runCatching { runSu(provider, command) }
            if (result.isFailure) {
                lastError = result.exceptionOrNull()?.message ?: lastError
                continue
            }
            val su = result.getOrThrow()
            if (su.exitCode == 0) return@runCatching su.output.trim()
            lastError = su.output.ifBlank { "Command failed: ${su.exitCode}" }
        }
        error(lastError)
    }

    private data class SuResult(val exitCode: Int, val output: String)

    private fun runSu(provider: String, command: String): SuResult {
        val process = ProcessBuilder(provider, "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("su command timed out")
        }
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        return SuResult(process.exitValue(), output)
    }
}
