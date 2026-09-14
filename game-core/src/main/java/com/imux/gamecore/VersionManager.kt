package com.imux.gamecore

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Lightweight release model shared by the launcher and future game services. */
data class GameVersion(
    val id: String,
    val name: String,
    val isLatest: Boolean = false,
    val releaseUrl: String? = null
)

/**
 * Fetches launcher/game versions from GitHub Releases.
 * Archive repositories can be supplied later without changing the UI contract.
 */
class VersionManager(
    private val owner: String = "cuuw619-maker",
    private val currentRepository: String = "ImuxLauncher",
    private val archiveRepositories: List<String> = emptyList()
) {
    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun fetchLatest(): GameVersion {
        val url = "https://api.github.com/repos/$owner/$currentRepository/releases/latest"
        val body: String = client.get(url).body()
        val json = Json.parseToJsonElement(body).jsonObject
        val tag = json["tag_name"]?.jsonPrimitive?.content
            ?: error("GitHub release has no tag_name")
        val name = json["name"]?.jsonPrimitive?.contentOrNull ?: tag
        val htmlUrl = json["html_url"]?.jsonPrimitive?.contentOrNull
        return GameVersion(tag, name, isLatest = true, releaseUrl = htmlUrl)
    }

    /** Returns archived repositories configured by the caller. */
    suspend fun fetchArchives(): List<GameVersion> {
        // Intentionally left as a small extension point: each archive repo can expose
        // its own /releases/latest endpoint while retaining one UI-facing model.
        return archiveRepositories.map { repository ->
            GameVersion(id = repository, name = repository)
        }
    }

    fun close() = client.close()
}
