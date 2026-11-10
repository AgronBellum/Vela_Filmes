package com.streamflixreborn.streamflix.extractors

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

class ClickHostExtractor : Extractor() {

    override val name = "ClickHost"

    override val mainUrl = BASE_URL

    override val aliasUrls = listOf(
        BASE_URL,
        "https://clickhost.xyz"
    )

    // ============================================================
    // SERVERS
    // ============================================================

    suspend fun servers(
        videoType: Video.Type
    ): List<Video.Server> = withContext(Dispatchers.IO) {

        val pageUrl = playerPageUrl(videoType)

        Log.i(TAG, "========================================")
        Log.i(TAG, "CLICKHOST - BUSCANDO SERVIDORES")
        Log.i(TAG, "Página: $pageUrl")
        Log.i(TAG, "========================================")

        runCatching {

            val html = fetchPageHtml(pageUrl)

            Log.d(TAG, "HTML recebido: ${html.length} caracteres")

            val serversJson = extractServersJson(html)

            if (serversJson.isNullOrBlank()) {
                Log.w(TAG, "Array 'servers' não encontrado.")
                return@runCatching listOf(defaultServer(pageUrl))
            }

            Log.d(TAG, "servers[] = $serversJson")

            val array = JSONArray(serversJson)
            val result = mutableListOf<Video.Server>()

            for (index in 0 until array.length()) {

                val obj = array.optJSONObject(index) ?: continue

                val id = obj.optString("id").trim()
                if (id.isBlank()) continue

                val label = obj.optString("label").trim().ifBlank {
                    "Servidor ${index + 1}"
                }

                val isHls = obj.optBoolean("is_hls", false)
                val isNative = obj.optBoolean("is_native", false)

                val server = Video.Server(
                    id = id,
                    name = "ClickHost - $label",
                    src = pageUrl
                )

                result += server

                Log.i(
                    TAG,
                    "Servidor #${index + 1}: id=$id | label=$label | hls=$isHls | native=$isNative"
                )
            }

            if (result.isEmpty()) {
                Log.w(TAG, "servers[] estava vazio.")
                listOf(defaultServer(pageUrl))
            } else {
                Log.i(TAG, "Total de servidores encontrados: ${result.size}")
                result.distinctBy { it.id }
            }

        }.getOrElse { error ->
            Log.e(TAG, "Erro ao obter servidores ClickHost", error)
            listOf(defaultServer(pageUrl))
        }
    }

    private fun defaultServer(pageUrl: String): Video.Server {
        return Video.Server(
            id = "0",
            name = "ClickHost Principal",
            src = pageUrl
        )
    }

    // ============================================================
    // EXTRACTION
    // ============================================================

    override suspend fun extract(
        link: String
    ): Video {
        return extract(link, null)
    }

    override suspend fun extract(
        link: String,
        server: Video.Server?
    ): Video = withContext(Dispatchers.IO) {

        /*
         * 1. IDENTIFICAR O SERVER ID
         */
        val serverId = server?.id
            ?.trim()
            // IDs reais dos servidores ClickHost sao numericos. Outros
            // agregadores usam IDs textuais proprios e nao podem ser enviados
            // para /embed/stream/{id}/init.
            ?.takeIf { it.isNotBlank() && it != "0" && it.all(Char::isDigit) }

        val pageUrl = server?.src
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: link.trim()

        Log.i(TAG, "========================================")
        Log.i(TAG, "CLICKHOST - EXTRAÇÃO")
        Log.i(TAG, "Página: $pageUrl")
        Log.i(TAG, "Server ID recebido: $serverId")
        Log.i(TAG, "========================================")

        var resolvedServerId = serverId

        if (resolvedServerId == null) {
            Log.i(TAG, "Nenhum server selecionado. Buscando primeiro servidor da página...")
            val html = fetchPageHtml(pageUrl)
            val serversJson = extractServersJson(html)
            resolvedServerId = extractFirstServerId(serversJson)
        }

        if (resolvedServerId.isNullOrBlank()) {
            throw IOException("ClickHost: nenhum server_id encontrado.")
        }

        /*
         * 2. MONTAR E EXECUTAR POST /init (OkHttp Direct)
         */
        val initUrl = buildInitUrl(resolvedServerId)

        Log.i(TAG, "----------------------------------------")
        Log.i(TAG, "CLICKHOST INIT")
        Log.i(TAG, "Server ID : $resolvedServerId")
        Log.i(TAG, "POST      : $initUrl")
        Log.i(TAG, "----------------------------------------")

        val initRequest = Request.Builder()
            .url(initUrl)
            .post(ByteArray(0).toRequestBody(null)) // POST com payload zero de bytes
            .header("User-Agent", USER_AGENT)
            .header("Referer", pageUrl)
            .header("Origin", BASE_URL)
            .header("Accept", "*/*")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val body = httpClient.newCall(initRequest).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()

            Log.i(TAG, "INIT HTTP: ${response.code}")
            Log.i(TAG, "INIT Content-Type: ${response.header("Content-Type")}")
            Log.d(TAG, "INIT Response: $responseBody")

            if (!response.isSuccessful) {
                throw IOException("ClickHost INIT HTTP ${response.code}: $responseBody")
            }

            responseBody
        }

        if (body.isBlank()) {
            throw IOException("ClickHost INIT retornou corpo vazio.")
        }

        /*
         * 3. PARSE E VALIDAÇÃO DO JSON
         */
        val json = runCatching {
            JSONObject(body)
        }.getOrElse {
            throw IOException("ClickHost INIT retornou JSON inválido: $body")
        }

        if (!json.optBoolean("ok", false)) {
            val error = firstNonBlank(
                json.optString("error"),
                json.optString("detail"),
                json.optString("message"),
                "erro desconhecido"
            )
            throw IOException("ClickHost INIT falhou: $error")
        }

        /*
         * 4. EXTRAIR E RESOLVER URL DA STREAM
         */
        val returnedUrl = firstNonBlank(
            json.optString("url"),
            json.optString("src"),
            json.optString("source"),
            json.optString("file"),
            json.optString("stream")
        )

        if (returnedUrl.isBlank()) {
            throw IOException("ClickHost INIT retornou ok=true mas não retornou URL.")
        }

        Log.i(TAG, "URL retornada pelo INIT: $returnedUrl")

        val streamUrl = resolveUrl(BASE_URL, returnedUrl)

        Log.i(TAG, "URL final resolvida: $streamUrl")

        if (!isValidClickHostStreamUrl(streamUrl)) {
            throw IOException("ClickHost INIT retornou uma URL inesperada: $streamUrl")
        }

        /*
         * 5. IDENTIFICAR FORMATO E MIME TYPE
         */
        val apiIsHls = json.optBoolean("is_hls", false)
        val apiIsNative = json.optBoolean("is_native", false)

        val isHls = apiIsHls || isHlsUrl(streamUrl)

        val mimeType = if (isHls) {
            MimeTypes.APPLICATION_M3U8
        } else {
            MimeTypes.VIDEO_MP4
        }

        Log.i(TAG, "========================================")
        Log.i(TAG, "CLICKHOST STREAM RESOLVIDO")
        Log.i(TAG, "Server ID : $resolvedServerId")
        Log.i(TAG, "INIT URL  : $initUrl")
        Log.i(TAG, "Returned  : $returnedUrl")
        Log.i(TAG, "STREAM    : $streamUrl")
        Log.i(TAG, "HLS       : $isHls")
        Log.i(TAG, "Native    : $apiIsNative")
        Log.i(TAG, "MIME      : $mimeType")
        Log.i(TAG, "========================================")

        /*
         * 6. RETORNAR OBJETO PARA O MEDIA3/EXOPLAYER
         */
        return@withContext Video(
            source = streamUrl,
            subtitles = emptyList(),
            headers = buildMediaHeaders(
                pageUrl = pageUrl,
                streamUrl = streamUrl
            ),
            type = mimeType,
            extraBuffering = true
        )
    }

    // ============================================================
    // HTTP FETCH (SUBSTITUINDO RETROFIT)
    // ============================================================

    private fun fetchPageHtml(pageUrl: String): String {
        val request = Request.Builder()
            .url(pageUrl)
            .headers(buildPageHeaders(pageUrl).toHeaders())
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Falha HTTP ${response.code} ao buscar página: $pageUrl")
            }
            val html = response.body?.string().orEmpty()
            // Faz o parse para garantir consistência básica do DOM se necessário
            Jsoup.parse(html, pageUrl).outerHtml()
        }
    }

    // ============================================================
    // INIT URL & PLAYER PAGE
    // ============================================================

    private fun buildInitUrl(serverId: String): String {
        return BASE_URL.trimEnd('/') + "/embed/stream/" + serverId.trim() + "/init"
    }

    private fun playerPageUrl(videoType: Video.Type): String {
        return when (videoType) {
            is Video.Type.Movie -> {
                val contentId = videoType.id.trim().takeIf { it.isNotBlank() }
                    ?: videoType.imdbId.orEmpty().trim()

                BASE_URL.trimEnd('/') + "/embed/filme/" + contentId + "?ad_played=1&player=v2"
            }

            is Video.Type.Episode -> {
                val show = videoType.tvShow

                val contentId = show.id.trim().takeIf { it.isNotBlank() && !it.startsWith("tt", ignoreCase = true) }
                    ?: show.imdbId?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("tt", ignoreCase = true) }
                    ?: show.id.trim().takeIf { it.isNotBlank() }
                    ?: show.imdbId.orEmpty().trim()

                BASE_URL.trimEnd('/') +
                    "/embed/serie/" +
                    contentId +
                    "/" +
                    videoType.season.number +
                    "/" +
                    videoType.number +
                    "?ad_played=1&player=v2"
            }
        }
    }

    // ============================================================
    // HEADERS
    // ============================================================

    private fun buildPageHeaders(refererUrl: String): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to refererUrl,
            "Origin" to BASE_URL,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
        )
    }

    private fun buildMediaHeaders(
        pageUrl: String,
        streamUrl: String
    ): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to pageUrl,
            "Origin" to BASE_URL,
            "Accept" to "video/mp4,video/*,*/*;q=0.8"
        )
    }

    // ============================================================
    // SERVERS JSON PARSER
    // ============================================================

    private fun extractServersJson(html: String): String? {
        val patterns = listOf(
            """const\s+servers\s*=\s*(\[[\s\S]*?\])\s*;""".toRegex(),
            """let\s+servers\s*=\s*(\[[\s\S]*?\])\s*;""".toRegex(),
            """var\s+servers\s*=\s*(\[[\s\S]*?\])\s*;""".toRegex(),
            """servers\s*=\s*(\[[\s\S]*?\])\s*;""".toRegex()
        )

        for (pattern in patterns) {
            val match = pattern.find(html) ?: continue
            val json = match.groupValues.getOrNull(1)?.trim()

            if (!json.isNullOrBlank()) {
                if (runCatching { JSONArray(json) }.isSuccess) {
                    return json
                }
            }
        }

        return extractBalancedServersArray(html)
    }

    private fun extractBalancedServersArray(html: String): String? {
        val markerRegex = Regex("""(?:const|let|var)?\s*servers\s*=\s*\[""", RegexOption.IGNORE_CASE)
        val match = markerRegex.find(html) ?: return null

        val start = html.indexOf('[', match.range.first)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until html.length) {
            val char = html[index]

            if (escaped) {
                escaped = false
                continue
            }

            if (char == '\\' && inString) {
                escaped = true
                continue
            }

            if (char == '"' && !escaped) {
                inString = !inString
                continue
            }

            if (inString) continue

            when (char) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        val json = html.substring(start, index + 1).trim()
                        return runCatching {
                            JSONArray(json)
                            json
                        }.getOrNull()
                    }
                }
            }
        }

        return null
    }

    private fun extractFirstServerId(serversJson: String?): String? {
        if (serversJson.isNullOrBlank()) return null

        return runCatching {
            val array = JSONArray(serversJson)
            for (index in 0 until array.length()) {
                val id = array.optJSONObject(index)?.optString("id")?.trim().orEmpty()
                if (id.isNotBlank()) return@runCatching id
            }
            null
        }.getOrNull()
    }

    // ============================================================
    // URL RESOLUTION & VALIDATION
    // ============================================================

    private fun resolveUrl(baseUrl: String, url: String): String {
        val value = url.trim().replace("\\/", "/")

        if (value.isBlank()) return ""

        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
            return value
        }

        if (value.startsWith("//")) {
            return "https:$value"
        }

        val base = baseUrl.toHttpUrlOrNull() ?: return value

        return base.resolve(value)?.toString() ?: value
    }

    private fun isValidClickHostStreamUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.encodedPath.contains("/embed/stream/", ignoreCase = true)
    }

    private fun isHlsUrl(url: String): Boolean {
        return Regex("""\.m3u8(?:$|[?#])""", RegexOption.IGNORE_CASE).containsMatchIn(url)
    }

    private fun firstNonBlank(vararg values: String): String {
        return values.firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }?.trim().orEmpty()
    }

    // ============================================================
    // HTTP CLIENT & CONSTANTS
    // ============================================================

    private companion object {

        private const val TAG = "ClickHostExtractor"

        private const val BASE_URL = "https://embed-api.clickhost.xyz"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Safari/537.36"

        private val httpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
