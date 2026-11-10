package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Source backed by SuperCine's public TMDB API.
 *
 * The API returns either an absolute embed URL (mostly movies) or an opaque token
 * (mostly episodes). Keeping the token in our own URL lets this extractor resolve
 * rotating video hosts only when the user actually selects the source.
 */
class SuperCineExtractor : Extractor() {
    override val name = "SuperCine"
    override val mainUrl = BASE_URL

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun servers(videoType: Video.Type): List<Video.Server> = withContext(Dispatchers.IO) {
        runCatching {
            val apiUrl = when (videoType) {
                is Video.Type.Movie ->
                    "$API_URL/movies?what=player&tmdb=${videoType.id}&version=1.0&origin=web"
                is Video.Type.Episode ->
                    "$API_URL/tvshows?what=player&tmdb=${videoType.tvShow.id}" +
                        "&season=${videoType.season.number}&episode=${videoType.number}" +
                        "&version=1.0&origin=web"
            }

            val json = JSONObject(get(apiUrl, BASE_URL))
            if (!json.optString("status").equals("success", ignoreCase = true)) {
                return@runCatching emptyList()
            }

            val players = json.optJSONArray("players") ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until players.length()) {
                    val player = players.optJSONObject(index) ?: continue
                    val payload = player.optString("url").trim()
                    if (payload.isBlank()) continue

                    val language = languageLabel(player.optString("lang"))
                    val playerTitle = player.optString("title")
                        .replace(Regex("^Player\\s*", RegexOption.IGNORE_CASE), "")
                        .trim()
                        .ifBlank { "Opção ${index + 1}" }
                    val type = player.optString("type").trim().lowercase()
                    val source = Uri.parse("$BASE_URL/vela-resolve").buildUpon()
                        .appendQueryParameter("url", payload)
                        .appendQueryParameter("type", type)
                        .build()
                        .toString()

                    add(
                        Video.Server(
                            id = "supercine-${videoKey(videoType)}-$index-${payload.hashCode()}",
                            name = "SuperCine • $language • $playerTitle",
                            src = source,
                        )
                    )
                }
            }
        }.onFailure {
            Log.w(TAG, "Falha ao consultar SuperCine: ${it.message}")
        }.getOrElse { emptyList() }
    }

    override suspend fun extract(link: String): Video {
        val uri = Uri.parse(link)
        val payload = uri.getQueryParameter("url")?.trim().orEmpty()
        val type = uri.getQueryParameter("type")?.trim()?.lowercase().orEmpty()
        require(payload.isNotBlank()) { "Link SuperCine sem servidor" }

        val embedUrl = if (payload.startsWith("http://") || payload.startsWith("https://")) {
            payload
        } else {
            resolveToken(payload)
        }

        return when {
            embedUrl.contains("vidply.com", ignoreCase = true) ->
                VidPlyExtractor().extract(embedUrl)
            type == "streamwish" || type == "streamhg" ->
                StreamWishExtractor().extract(embedUrl)
            type == "vidhide" || type == "filelions" ->
                VidHideExtractor().extract(embedUrl)
            type == "mixdrop" || embedUrl.contains("mixdrop.", ignoreCase = true) ->
                MixDropExtractor().extract(embedUrl)
            type == "filemoon" -> {
                val origin = URL(embedUrl).let { "${it.protocol}://${it.host}" }
                FilemoonExtractor.Any(origin).extract(embedUrl)
            }
            else -> Extractor.extract(embedUrl)
        }
    }

    private fun resolveToken(token: String): String {
        val url = Uri.parse("$BASE_URL/embed-api/").buildUpon()
            .appendQueryParameter("action", "embed")
            .appendQueryParameter("url", token)
            .build()
            .toString()
        val html = getBlocking(url, "$BASE_URL/embed-api/")
        val document = Jsoup.parse(html, url)

        return document.selectFirst("iframe[src]")?.absUrl("src")?.takeIf { it.isNotBlank() }
            ?: Regex("window\\.location(?:\\.href)?\\s*=\\s*[\"']([^\"']+)")
                .find(html)?.groupValues?.get(1)
            ?: Regex("https?://[^\"'\\s<>]+")
                .findAll(html)
                .map { it.value.replace("&amp;", "&") }
                .firstOrNull { !it.startsWith(BASE_URL) }
            ?: throw Exception("Servidor final não encontrado no SuperCine")
    }

    private suspend fun get(url: String, referer: String): String = withContext(Dispatchers.IO) {
        getBlocking(url, referer)
    }

    private fun getBlocking(url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "application/json,text/html,*/*")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.7")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("SuperCine HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private fun languageLabel(value: String): String = when (value.trim().lowercase()) {
        "dublado", "dub", "pt-br", "portugues", "português" -> "Dublado PT-BR"
        "legendado", "leg", "original" -> "Legendado"
        else -> value.trim().replaceFirstChar { it.uppercase() }.ifBlank { "Áudio não informado" }
    }

    private fun videoKey(videoType: Video.Type): String = when (videoType) {
        is Video.Type.Movie -> "movie-${videoType.id}"
        is Video.Type.Episode ->
            "tv-${videoType.tvShow.id}-s${videoType.season.number}e${videoType.number}"
    }

    companion object {
        private const val TAG = "SuperCineExtractor"
        private const val BASE_URL = "https://supercine-tv.net"
        private const val API_URL = "$BASE_URL/wp-json/api"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}
