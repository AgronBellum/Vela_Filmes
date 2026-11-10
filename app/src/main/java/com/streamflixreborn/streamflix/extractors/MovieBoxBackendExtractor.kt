package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente fino do backend local. A assinatura do MovieBox e a descoberta de idiomas ficam no
 * servidor CasaOS; o APK recebe somente opções curtas e resolve o stream no momento do clique.
 */
class MovieBoxBackendExtractor : Extractor() {
    override val name = "Servidor Vela"
    override val mainUrl = BASE_URL

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun servers(videoType: Video.Type): List<Video.Server> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
            when (videoType) {
                is Video.Type.Movie -> payload
                    .put("mediaType", "movie")
                    .put("title", videoType.title)
                    .put("year", videoType.releaseDate.take(4).toIntOrNull() ?: JSONObject.NULL)
                    .put("tmdbId", videoType.id)
                    .put("imdbId", videoType.imdbId ?: JSONObject.NULL)

                is Video.Type.Episode -> payload
                    .put("mediaType", "tv")
                    .put("title", videoType.tvShow.title)
                    .put("year", videoType.tvShow.releaseDate?.take(4)?.toIntOrNull() ?: JSONObject.NULL)
                    .put("tmdbId", videoType.tvShow.id)
                    .put("imdbId", videoType.tvShow.imdbId ?: JSONObject.NULL)
                    .put("season", videoType.season.number)
                    .put("episode", videoType.number)
            }

            val request = Request.Builder()
                .url("$BASE_URL/v1/options")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json")
                .apply { if (API_KEY.isNotBlank()) header("X-Vela-Key", API_KEY) }
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val root = JSONObject(response.body?.string().orEmpty())
                val options = root.optJSONArray("options") ?: return@use emptyList()
                buildList {
                    for (index in 0 until options.length()) {
                        val item = options.optJSONObject(index) ?: continue
                        val token = item.optString("id")
                        if (token.isBlank()) continue
                        val shortId = token.hashCode().toUInt().toString(16)
                        add(
                            Video.Server(
                                // O token completo fica somente na URL e não vai para os
                                // metadados parceláveis do player.
                                id = "vela-moviebox-$index-$shortId",
                                name = item.optString("name", "Servidor Vela"),
                                src = "$BASE_URL/v1/resolve/$token"
                            )
                        )
                    }
                }
            }
        }.onFailure {
            // A fonte local é opcional: se o servidor estiver desligado, as demais continuam.
            Log.w(TAG, "Backend local indisponível: ${it.message}")
        }.getOrElse { emptyList() }
    }

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(link)
            .get()
            .header("Accept", "application/json")
            .apply { if (API_KEY.isNotBlank()) header("X-Vela-Key", API_KEY) }
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                throw Exception(detail?.takeIf { it.isNotBlank() } ?: "Servidor Vela HTTP ${response.code}")
            }
            val root = JSONObject(body)
            val source = root.optString("source")
            if (source.isBlank()) throw Exception("O servidor local não retornou um stream")

            val headers = root.optJSONObject("headers")?.let { json ->
                buildMap {
                    json.keys().forEach { key -> put(key, json.optString(key)) }
                }
            }
            val subtitles = buildList {
                val array = root.optJSONArray("subtitles") ?: return@buildList
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val file = item.optString("file")
                    if (file.isBlank()) continue
                    add(
                        Video.Subtitle(
                            label = item.optString("label", "Legenda"),
                            file = file,
                            default = false
                        )
                    )
                }
            }
            val qualities = buildList {
                val array = root.optJSONArray("qualities") ?: return@buildList
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val qualitySource = item.optString("source")
                    if (qualitySource.isBlank()) continue
                    val qualityHeaders = item.optJSONObject("headers")?.let { json ->
                        buildMap {
                            json.keys().forEach { key -> put(key, json.optString(key)) }
                        }
                    }
                    add(
                        Video.Quality(
                            label = item.optString("label", "Auto"),
                            height = item.optInt("height", 0),
                            source = qualitySource,
                            type = item.optString("type").takeIf { it.isNotBlank() },
                            headers = qualityHeaders,
                        )
                    )
                }
            }.distinctBy { it.height to it.source }
                .sortedByDescending { it.height }

            Video(
                source = source,
                subtitles = subtitles,
                headers = headers,
                type = root.optString("type").takeIf { it.isNotBlank() } ?: inferMimeType(source),
                extraBuffering = root.optBoolean("extraBuffering", true),
                useServerSubtitleSetting = true,
                qualities = qualities,
            )
        }
    }

    private fun inferMimeType(url: String): String? = when {
        url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
        url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
        url.contains(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
        else -> null
    }

    companion object {
        private const val TAG = "MovieBoxBackend"
        private const val BASE_URL = "http://192.168.0.238:8787"
        private const val API_KEY = ""
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
