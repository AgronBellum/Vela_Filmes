package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object StremioSubtitleManager {
    private const val TAG = "StremioSubtitles"
    private val ADDON_BASE_URLS = listOf(
        "https://opensubtitlesv3-pro.dexter21767.com/eyJsYW5ncyI6WyJwb3J0dWd1ZXNlLWJyIl0sInNvdXJjZSI6ImFsbCIsImFpVHJhbnNsYXRlZCI6dHJ1ZSwiYXV0b0FkanVzdG1lbnQiOnRydWV9",
        "https://opensubtitles-v3.strem.io"
    )
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    }

    suspend fun getExternalSubtitles(isSeries: Boolean, imdbId: String, season: Int = 1, episode: Int = 1): List<StremioSubtitles.Subtitle> = withContext(Dispatchers.IO) {
        val cleanImdb = imdbId.trim().lowercase()
        val finalImdbId = if (cleanImdb.startsWith("tt")) cleanImdb else "tt$cleanImdb"
        val type = if (isSeries) "series" else "movie"
        val id = if (isSeries) "$finalImdbId:$season:$episode" else finalImdbId
        ADDON_BASE_URLS.map { baseUrl -> async { fetchFromAddon(baseUrl, type, id) } }.awaitAll().flatten()
            .distinctBy { it.url }
            .sortedWith(compareBy({ portuguesePriority(it) }, { it.lang.orEmpty().lowercase() }))
    }

    private fun fetchFromAddon(baseUrl: String, type: String, id: String): List<StremioSubtitles.Subtitle> {
        val url = "$baseUrl/subtitles/$type/$id.json"
        return runCatching {
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").header("Accept", "application/json").build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val subtitles = JSONObject(body).optJSONArray("subtitles") ?: return emptyList()
                buildList {
                    for (i in 0 until subtitles.length()) {
                        val subtitle = subtitles.getJSONObject(i)
                        val subtitleUrl = subtitle.optString("url").trim()
                        if (subtitleUrl.isBlank() || !subtitleUrl.startsWith("http")) continue
                        val language = subtitle.optString("lang", "Português")
                        val languageCode = subtitle.optString("lang_code", "por").lowercase()
                        if (!language.contains("portugu", ignoreCase = true) && languageCode !in setOf("pt", "pt-br", "pt-pt", "pb", "por")) continue
                        add(StremioSubtitles.Subtitle(
                            id = subtitle.optString("id").ifBlank { subtitleUrl },
                            url = subtitleUrl,
                            lang = language.ifBlank { subtitle.optString("name").ifBlank { "Português" } },
                            format = subtitleExtension(subtitleUrl),
                            languageTag = toLanguageTag(languageCode, language)
                        ))
                    }
                }
            }
        }.getOrElse {
            Log.w(TAG, "Erro ao buscar no addon Stremio ($baseUrl): ${it.message}")
            emptyList()
        }
    }

    fun getMimeType(url: String): String = when (subtitleExtension(url)) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "xml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    private fun toLanguageTag(languageCode: String, language: String): String = when {
        languageCode in setOf("pt-br", "pb") || language.contains("brazil", true) || language.contains("brasil", true) -> "pt-BR"
        languageCode == "pt-pt" || language.contains("portugal", true) -> "pt-PT"
        languageCode in setOf("pt", "por") || language.contains("portugu", true) -> "pt"
        else -> languageCode
    }

    private fun subtitleExtension(url: String): String {
        val cleanUrl = url.substringBefore('?').substringBefore('#').lowercase().removeSuffix(".gz")
        return when {
            cleanUrl.endsWith(".vtt") -> "vtt"
            cleanUrl.endsWith(".ass") -> "ass"
            cleanUrl.endsWith(".ssa") -> "ssa"
            cleanUrl.endsWith(".ttml") -> "ttml"
            cleanUrl.endsWith(".xml") -> "xml"
            else -> "srt"
        }
    }

    private fun portuguesePriority(subtitle: StremioSubtitles.Subtitle): Int {
        val values = listOfNotNull(subtitle.lang, subtitle.id, subtitle.url).map { it.lowercase() }
        return when {
            values.any { it.contains("ai-translated") || it.contains("translated") || it.contains("translation") || it.contains(" ia") } -> 3
            values.any { it == "pt-br" || it == "pt_br" || it == "pb" || it.contains("brazil") || it.contains("brasil") || it.contains("portuguese-br") || it.contains("portuguese br") || it.contains("português-br") || it.contains("português br") } -> 0
            values.any { it == "pt-pt" || it == "pt_pt" || it.contains("portugal") } -> 1
            else -> 2
        }
    }

    suspend fun downloadAndNormalize(context: Context, subtitleUrl: String): Uri = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(subtitleUrl)
            // Some addon providers reject OkHttp's default user agent even though
            // the subtitle listing itself is accessible.
            .header("User-Agent", "Mozilla/5.0")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val tempFile = File.createTempFile("stremio-sub-", ".${subtitleExtension(subtitleUrl)}", context.cacheDir)
            response.body?.byteStream()?.use { input ->
                val subtitleInput = if (subtitleUrl.substringBefore('?').substringBefore('#').endsWith(".gz", ignoreCase = true)) GZIPInputStream(input) else input
                subtitleInput.use { decodedInput -> FileOutputStream(tempFile).use { output -> decodedInput.copyTo(output) } }
            } ?: throw Exception("Corpo vazio ao baixar legenda")
            SubtitleEncoding.normalizeFile(tempFile).toUri()
        }
    }
}
