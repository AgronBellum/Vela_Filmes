package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

class WatchPlayExtractor : Extractor() {
    override val name = "WatchPlay"
    override val mainUrl = WATCHPLAY_URL
    override val aliasUrls = listOf(
        "https://v1.watchplay.shop",
        "https://watchplayer.net"
    )

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun servers(videoType: Video.Type): List<Video.Server> = runCatching {
        val pageUrl = playerPageUrl(videoType)
        val html = get(pageUrl, pageUrl)
        val doc = Jsoup.parse(html)

        val items = doc.select(".player_select_item[data-id]")
        if (items.isEmpty()) {
            return@runCatching listOf(server(videoType))
        }

        items.mapIndexedNotNull { index, element ->
            val videoId = element.attr("data-id").trim()
            if (videoId.isBlank()) return@mapIndexedNotNull null

            val optionName = element.selectFirst(".player_select_name")?.text()?.trim()
                ?: element.text().trim().takeIf { it.isNotBlank() }
                ?: "Opção ${index + 1}"

            val serverUrl = "$pageUrl#video_id=$videoId"

            Video.Server(
                id = "watchplay-dublado-$index-$videoId",
                name = "WatchPlay Dublado - $optionName",
                src = serverUrl
            )
        }
    }.onFailure {
        Log.w(TAG, "Falha ao buscar players WatchPlay: ${it.message}")
    }.getOrElse { listOf(server(videoType)) }

    fun server(videoType: Video.Type): Video.Server {
        val url = playerPageUrl(videoType)
        return Video.Server(
            id = url,
            name = "WatchPlay Dublado",
            src = url
        )
    }

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        val pageUrl = normalizeWatchPlayUrl(link.substringBefore("#"))
        var videoId = link.substringAfter("#video_id=", "")
        val html = get(pageUrl, pageUrl)
        val directUrl = Regex("""url\s*:\s*["']([^"']+)["']""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::normalizeUrl)
            ?.takeIf { it.startsWith("http") }

        if (directUrl != null) {
            return@withContext Video(
                source = directUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to pageUrl,
                    "Origin" to WATCHPLAY_URL,
                    "Accept" to "*/*",
                ),
                type = if (directUrl.contains(".mp4", ignoreCase = true)) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8,
                extraBuffering = true,
            )
        }

        // Se o link não veio codificado com video_id, faz a raspagem da página HTML base
        if (videoId.isBlank()) {
            val doc = Jsoup.parse(html)
            videoId = doc.selectFirst(".player_select_item[data-id]")?.attr("data-id").orEmpty()
        }

        if (videoId.isBlank()) {
            throw Exception("ID do vídeo não encontrado na página WatchPlay")
        }

        // Requisição POST para obter o link final do vídeo
        val body = FormBody.Builder()
            .add("action", "getPlayer")
            .add("video_id", videoId)
            .build()

        val jsonText = post("$WATCHPLAY_URL/api", body, pageUrl)
        val json = JSONObject(jsonText)
        val data = json.optJSONObject("data") ?: throw Exception("Resposta de API inválida")

        val rawVideoUrl = data.optString("video_url")
        val videoUrl = normalizeUrl(rawVideoUrl)

        if (videoUrl.isBlank()) {
            throw Exception("URL do vídeo veio vazia")
        }


        val mimeType = when {
            videoUrl.contains(".m3u8", ignoreCase = true) || videoUrl.contains("/hls/", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            videoUrl.contains(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
            else -> MimeTypes.APPLICATION_M3U8
        }

        Video(
            source = videoUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to pageUrl,
                "Origin" to WATCHPLAY_URL,
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
            ),
            type = mimeType,
            extraBuffering = true
        )
    }

    private fun playerPageUrl(videoType: Video.Type): String = when (videoType) {
        is Video.Type.Movie -> "$WATCHPLAY_URL/movie/${videoType.id}"
        is Video.Type.Episode -> "$WATCHPLAY_URL/tvshow/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
    }

    private suspend fun get(url: String, referer: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .header("Cache-Control", "no-cache")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private suspend fun post(url: String, body: FormBody, referer: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Origin", WATCHPLAY_URL)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private fun normalizeUrl(value: String): String = value.trim().replace("\\/", "/")

    private fun normalizeWatchPlayUrl(value: String): String = value
        .replace("https://v1.watchplay.shop", WATCHPLAY_URL, ignoreCase = true)
        .replace("http://v1.watchplay.shop", WATCHPLAY_URL, ignoreCase = true)

    companion object {
        private const val TAG = "WatchPlayExtractor"
        private const val WATCHPLAY_URL = "https://v2.watchplay.shop"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
