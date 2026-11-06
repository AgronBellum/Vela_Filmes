package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class EmbedPlayApiExtractor : Extractor() {

    override val name = "EmbedPlayAPI"
    override val mainUrl = BASE_URL

    override val aliasUrls = listOf(
        "https://embedplayapi.top",
        "https://www.embedplay.one",
        "https://embedplay.one"
    )

    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val okHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // ============================================================
    // 1. MAPEAMENTO DE SERVIDORES
    // ============================================================

    suspend fun servers(videoType: Video.Type): List<Video.Server> =
        withContext(Dispatchers.IO) {

            val pageUrl = playerPageUrl(videoType)
            val serverList = mutableListOf<Video.Server>()

            runCatching {

                val html = getHtml(pageUrl)
                val doc = Jsoup.parse(html)

                val movieId = doc
                    .select("[data-movie-id]")
                    .attr("data-movie-id")
                    .takeIf { it.isNotBlank() }

                val baseServerId = doc
                    .select(
                        ".server-group[data-id]," +
                                ".player_select_item[data-id]," +
                                "a.server[data-id]"
                    )
                    .firstOrNull()
                    ?.attr("data-id")
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank() && it != "_default"
                    }

                var realPageUrl = pageUrl

                // ------------------------------------------------
                // Tenta resolver a página real através do AJAX
                // ------------------------------------------------

                if (movieId != null && baseServerId != null) {

                    val httpUrl = "$BASE_URL/ajax/get_stream_link"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("id", baseServerId)
                        .addQueryParameter("movie", movieId)
                        .addQueryParameter("is_init", "false")
                        .addQueryParameter("captcha", "")
                        .addQueryParameter("ref", "")
                        .build()

                    val request = Request.Builder()
                        .url(httpUrl)
                        .get()
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", pageUrl)
                        .header("Origin", BASE_URL)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Accept", "application/json,text/plain,*/*")
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->

                        val jsonStr = response.body
                            ?.string()
                            .orEmpty()
                            .trim()

                        if (response.isSuccessful && jsonStr.startsWith("{")) {

                            runCatching {

                                val json = JSONObject(jsonStr)

                                if (json.optBoolean("success")) {

                                    val link = json
                                        .optJSONObject("data")
                                        ?.optString("link")
                                        .orEmpty()
                                        .trim()
                                        .replace("\\/", "/")

                                    if (link.isNotBlank()) {
                                        realPageUrl = normalizeUrl(
                                            link,
                                            pageUrl
                                        )
                                    }
                                }

                            }.onFailure {
                                Log.w(
                                    TAG,
                                    "Erro interpretando get_stream_link",
                                    it
                                )
                            }
                        }
                    }
                }

                // ------------------------------------------------
                // Procura os servidores na página real
                // ------------------------------------------------

                val realHtml = getHtml(realPageUrl)
                val realDoc = Jsoup.parse(realHtml)

                val serverElements = realDoc
                    .select(
                        ".player_select_item[data-id]," +
                                "a.server[data-id]," +
                                "div[data-id]"
                    )
                    .filterNot {
                        it.hasClass("server-group") ||
                                it.attr("data-id") == "_default"
                    }

                serverElements.forEachIndexed { index, element ->

                    val serverId = element
                        .attr("data-id")
                        .trim()

                    if (serverId.isBlank()) {
                        return@forEachIndexed
                    }

                    val elementText = element.text().trim()

                    val parentText = element
                        .parents()
                        .select(
                            ".server-group," +
                                    ".active-server-group," +
                                    ".nav," +
                                    ".dropdown," +
                                    ".player_select"
                        )
                        .text()
                        .lowercase()

                    val combinedText =
                        "$elementText $parentText".lowercase()

                    val audioType = when {
                        "leg" in combinedText ||
                                "legendado" in combinedText ||
                                "sub" in combinedText -> {
                            "Legendado"
                        }

                        else -> {
                            "Dublado"
                        }
                    }

                    val cleanName = elementText
                        .replace(
                            Regex("(?i)use nossa api.*"),
                            ""
                        )
                        .replace(
                            Regex("\\s+"),
                            " "
                        )
                        .trim()
                        .ifBlank {
                            "Opção ${index + 1}"
                        }

                    val optionUrl =
                        "$realPageUrl#server_id=$serverId"

                    serverList += Video.Server(
                        id = optionUrl,
                        name = "EmbedPlay $audioType - $cleanName",
                        src = optionUrl
                    )
                }

            }.onFailure {
                Log.w(
                    TAG,
                    "Falha ao mapear servidores do EmbedPlayAPI",
                    it
                )
            }

            if (serverList.isNotEmpty()) {
                serverList.distinctBy { it.id }
            } else {
                listOf(defaultServer(videoType))
            }
        }

    private fun defaultServer(
        videoType: Video.Type
    ): Video.Server {

        val embedUrl = playerPageUrl(videoType)

        return Video.Server(
            id = embedUrl,
            name = "EmbedPlay Dublado",
            src = embedUrl
        )
    }

    // ============================================================
    // 2. FLUXO PRINCIPAL
    // ============================================================

    override suspend fun extract(link: String): Video {
        return extract(link, null)
    }

    override suspend fun extract(
        link: String,
        server: Video.Server?
    ): Video {

        val rawUrl = server?.id ?: link

        val pageUrl = rawUrl
            .substringBefore("#server_id=")
            .trim()

        val targetServerId = rawUrl
            .substringAfter(
                "#server_id=",
                ""
            )
            .trim()
            .takeIf {
                it.isNotBlank() && it != pageUrl
            }

        Log.d(
            TAG,
            "Página base: $pageUrl"
        )

        Log.d(
            TAG,
            "Server ID: $targetServerId"
        )

        // --------------------------------------------------------
        // PASSO 1
        // Resolve o player final
        // --------------------------------------------------------

        val targetPlayerUrl = runCatching {

            if (targetServerId != null) {

                val rawPlayerUrl =
                    fetchPlayerUrlFromApi(
                        targetServerId,
                        pageUrl
                    )

                val normalized =
                    normalizeUrl(
                        rawPlayerUrl,
                        pageUrl
                    )

                bypassAdWrapper(normalized)

            } else {

                extractFirstPlayerUrlFromHtml(
                    pageUrl
                ) ?: pageUrl
            }

        }.getOrElse { error ->

            Log.w(
                TAG,
                "Falha ao resolver player via API",
                error
            )

            pageUrl
        }

        Log.i(
            TAG,
            "Player final: $targetPlayerUrl"
        )

        // --------------------------------------------------------
        // PASSO 2
        // WebView captura o stream
        // --------------------------------------------------------

        val streamUrl = extractWithWebView(
            targetPlayerUrl
        )

        Log.i(
            TAG,
            "Stream encontrada: $streamUrl"
        )

        val mimeType =
            resolveMimeType(streamUrl)

        val headers =
            buildHeaders(
                streamUrl,
                targetPlayerUrl
            )

        return Video(
            source = streamUrl,
            subtitles = emptyList(),
            headers = headers,
            type = mimeType,
            extraBuffering = true
        )
    }

    // ============================================================
    // 3. API
    // ============================================================

    private suspend fun fetchPlayerUrlFromApi(
        serverId: String,
        refererUrl: String
    ): String = withContext(Dispatchers.IO) {

        val formBody = FormBody.Builder()
            .add("action", "getPlayer")
            .add("video_id", serverId)
            .build()

        val request = Request.Builder()
            .url("https://www.embedplay.one/api")
            .post(formBody)
            .header("User-Agent", USER_AGENT)
            .header("Referer", refererUrl)
            .header(
                "Origin",
                refererUrl.toHttpUrl().newBuilder()
                    .build()
                    .toString()
                    .trimEnd('/')
            )
            .header(
                "X-Requested-With",
                "XMLHttpRequest"
            )
            .header(
                "Accept",
                "application/json,text/plain,*/*"
            )
            .build()

        okHttpClient.newCall(request).execute().use { response ->

            val jsonStr = response.body
                ?.string()
                .orEmpty()
                .trim()

            if (!response.isSuccessful) {
                throw Exception(
                    "API HTTP ${response.code}"
                )
            }

            if (!jsonStr.startsWith("{")) {
                throw Exception(
                    "Resposta da API não é JSON"
                )
            }

            val json = JSONObject(jsonStr)

            val dataObj =
                json.optJSONObject("data")

            val videoUrl = dataObj
                ?.optString("video_url")
                .orEmpty()
                .trim()
                .replace("\\/", "/")

            if (videoUrl.isBlank()) {
                throw Exception(
                    "video_url não encontrado para serverId=$serverId"
                )
            }

            normalizeUrl(
                videoUrl,
                refererUrl
            )
        }
    }

    private suspend fun extractFirstPlayerUrlFromHtml(
        pageUrl: String
    ): String? = withContext(Dispatchers.IO) {

        runCatching {

            val html = getHtml(pageUrl)

            val doc = Jsoup.parse(html)

            val firstServerId = doc
                .select(
                    "[data-id]"
                )
                .map {
                    it.attr("data-id").trim()
                }
                .firstOrNull {
                    it.isNotBlank() &&
                            it != "_default"
                }

            if (firstServerId != null) {

                val rawPlayer =
                    fetchPlayerUrlFromApi(
                        firstServerId,
                        pageUrl
                    )

                return@runCatching bypassAdWrapper(
                    normalizeUrl(
                        rawPlayer,
                        pageUrl
                    )
                )
            }

            null

        }.getOrNull()
    }

    private suspend fun getHtml(
        url: String
    ): String = withContext(Dispatchers.IO) {

        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                USER_AGENT
            )
            .header(
                "Referer",
                BASE_URL
            )
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            .build()

        okHttpClient.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                throw Exception(
                    "HTTP ${response.code}"
                )
            }

            response.body
                ?.string()
                .orEmpty()
        }
    }

    // ============================================================
    // 4. NORMALIZAÇÃO / BYPASS
    // ============================================================

    private fun normalizeUrl(
        url: String,
        baseUrl: String
    ): String {

        val clean = url
            .trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")

        if (clean.isBlank()) {
            return ""
        }

        return runCatching {

            if (
                clean.startsWith("http://") ||
                clean.startsWith("https://")
            ) {
                clean
            } else {
                baseUrl.toHttpUrl()
                    .resolve(clean)
                    ?.toString()
                    ?: clean
            }

        }.getOrDefault(clean)
    }

    private fun bypassAdWrapper(
        url: String
    ): String {

        val cleanUrl = url.trim()

        if (
            cleanUrl.contains(
                "embedplayabyss",
                ignoreCase = true
            ) ||
            cleanUrl.contains(
                "player.html?v=",
                ignoreCase = true
            )
        ) {

            val slug = cleanUrl
                .substringAfter("v=", "")
                .substringBefore("&")
                .trim()

            if (slug.isNotBlank()) {
                return "https://abysscdn.com/?v=$slug"
            }
        }

        return cleanUrl
    }

    // ============================================================
    // 5. WEBVIEW SNIFFER
    // ============================================================

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractWithWebView(
        playerUrl: String
    ): String = mutex.withLock {

        withTimeoutOrNull(TIMEOUT_MS) {

            suspendCancellableCoroutine { continuation ->

                var webView: WebView? = null

                var completed = false
                var destroyed = false

                fun cleanup() {

                    if (destroyed) {
                        return
                    }

                    destroyed = true

                    mainHandler.post {

                        try {
                            webView?.stopLoading()
                            webView?.loadUrl("about:blank")
                            webView?.removeAllViews()
                            webView?.destroy()
                        } catch (e: Exception) {
                            Log.d(
                                TAG,
                                "Erro destruindo WebView",
                                e
                            )
                        }

                        webView = null
                    }
                }

                fun safeResume(
                    result: Result<String>
                ) {

                    if (completed) {
                        return
                    }

                    completed = true

                    if (!continuation.isActive) {
                        cleanup()
                        return
                    }

                    result.fold(
                        onSuccess = {
                            continuation.resume(it)
                        },
                        onFailure = {
                            continuation.resumeWithException(it)
                        }
                    )

                    cleanup()
                }

                fun capture(
                    rawUrl: String?
                ) {

                    val candidate = rawUrl
                        .orEmpty()
                        .trim()
                        .replace("\\/", "/")
                        .replace(
                            "\\u0026",
                            "&"
                        )

                    if (candidate.isBlank()) {
                        return
                    }

                    if (isMainStream(candidate)) {

                        Log.i(
                            TAG,
                            "STREAM CANDIDATA: $candidate"
                        )

                        // HLS tem prioridade.
                        if (
                            candidate.contains(
                                ".m3u8",
                                ignoreCase = true
                            )
                        ) {
                            safeResume(
                                Result.success(
                                    candidate
                                )
                            )
                        } else {

                            // MP4/MPD também são aceitos,
                            // mas com pequeno atraso para
                            // permitir que um HLS apareça.
                            mainHandler.postDelayed({

                                if (!completed) {
                                    safeResume(
                                        Result.success(
                                            candidate
                                        )
                                    )
                                }

                            }, MP4_FALLBACK_DELAY_MS)
                        }
                    }
                }

                mainHandler.post {

                    try {

                        webView =
                            WebView(
                                StreamFlixApp
                                    .instance
                                    .applicationContext
                            ).apply {

                                setBackgroundColor(
                                    Color.TRANSPARENT
                                )

                                settings.apply {

                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true

                                    javaScriptCanOpenWindowsAutomatically =
                                        false

                                    setSupportMultipleWindows(
                                        false
                                    )

                                    userAgentString =
                                        USER_AGENT

                                    mixedContentMode =
                                        WebSettings
                                            .MIXED_CONTENT_ALWAYS_ALLOW

                                    mediaPlaybackRequiresUserGesture =
                                        false

                                    cacheMode =
                                        WebSettings
                                            .LOAD_NO_CACHE

                                    allowFileAccess = false
                                    allowContentAccess = false
                                }

                                webChromeClient =
                                    object :
                                        WebChromeClient() {

                                        override fun
                                                onConsoleMessage(
                                            message:
                                                ConsoleMessage?
                                        ): Boolean {

                                            val text =
                                                message
                                                    ?.message()
                                                    .orEmpty()

                                            if (
                                                text.startsWith(
                                                    CONSOLE_PREFIX
                                                )
                                            ) {

                                                capture(
                                                    text.substringAfter(
                                                        CONSOLE_PREFIX
                                                    )
                                                )
                                            }

                                            return true
                                        }
                                    }

                                webViewClient =
                                    object :
                                        WebViewClient() {

                                        override fun
                                                onReceivedSslError(
                                            view: WebView?,
                                            handler:
                                                SslErrorHandler?,
                                            error: SslError?
                                        ) {

                                            Log.w(
                                                TAG,
                                                "SSL error: ${error?.primaryError}"
                                            )

                                            handler?.proceed()
                                        }

                                        override fun
                                                shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request:
                                                WebResourceRequest?
                                        ): Boolean {

                                            val url =
                                                request
                                                    ?.url
                                                    ?.toString()
                                                    .orEmpty()

                                            capture(url)

                                            return isBlockedAdUrl(
                                                url
                                            )
                                        }

                                        override fun
                                                onPageStarted(
                                            view: WebView?,
                                            url: String?,
                                            favicon: Bitmap?
                                        ) {

                                            super
                                                .onPageStarted(
                                                    view,
                                                    url,
                                                    favicon
                                                )

                                            view?.evaluateJavascript(
                                                ANTI_POPUP_JS,
                                                null
                                            )

                                            view?.evaluateJavascript(
                                                SNIFFER_JS,
                                                null
                                            )
                                        }

                                        override fun
                                                onLoadResource(
                                            view: WebView?,
                                            url: String?
                                        ) {

                                            capture(url)

                                            super
                                                .onLoadResource(
                                                    view,
                                                    url
                                                )
                                        }

                                        override fun
                                                shouldInterceptRequest(
                                            view: WebView?,
                                            request:
                                                WebResourceRequest?
                                        ): WebResourceResponse? {

                                            val reqUrl =
                                                request
                                                    ?.url
                                                    ?.toString()
                                                    .orEmpty()

                                            capture(reqUrl)

                                            if (
                                                reqUrl.contains(
                                                    "disable-devtool",
                                                    ignoreCase = true
                                                )
                                            ) {

                                                return WebResourceResponse(
                                                    "text/javascript",
                                                    "UTF-8",
                                                    ByteArrayInputStream(
                                                        ByteArray(0)
                                                    )
                                                )
                                            }

                                            return super
                                                .shouldInterceptRequest(
                                                    view,
                                                    request
                                                )
                                        }

                                        override fun
                                                onPageFinished(
                                            view: WebView?,
                                            url: String?
                                        ) {

                                            super
                                                .onPageFinished(
                                                    view,
                                                    url
                                                )

                                            view?.evaluateJavascript(
                                                ANTI_POPUP_JS,
                                                null
                                            )

                                            view?.evaluateJavascript(
                                                SNIFFER_JS,
                                                null
                                            )

                                            view?.evaluateJavascript(
                                                PLAY_JS,
                                                null
                                            )
                                        }
                                    }

                                val referer =
                                    resolvePlayerReferer(
                                        playerUrl
                                    )

                                Log.d(
                                    TAG,
                                    "WebView Referer: $referer"
                                )

                                loadUrl(
                                    playerUrl,
                                    mapOf(
                                        "Referer" to referer,
                                        "User-Agent" to USER_AGENT
                                    )
                                )
                            }

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "Falha inicializando WebView",
                            e
                        )

                        safeResume(
                            Result.failure(e)
                        )
                    }
                }

                mainHandler.postDelayed({

                    if (!completed) {

                        safeResume(
                            Result.failure(
                                Exception(
                                    "Timeout EmbedPlayAPI: stream não encontrada"
                                )
                            )
                        )
                    }

                }, TIMEOUT_MS)

                continuation.invokeOnCancellation {
                    cleanup()
                }
            }

        } ?: throw Exception(
            "Timeout EmbedPlayAPI: operação excedeu ${TIMEOUT_MS}ms"
        )
    }

    // ============================================================
    // 6. REGRAS DE STREAM
    // ============================================================

    private fun isMainStream(
        url: String
    ): Boolean {

        if (url.isBlank()) {
            return false
        }

        val cleanUrl = url
            .replace("\\/", "/")
            .trim()

        val lower =
            cleanUrl.lowercase()

        // --------------------------------------------
        // Bloqueios
        // --------------------------------------------

        val blockedPatterns = listOf(
            "ajax/played",
            "cdn-cgi",
            "challenge-platform",
            "cloudflareinsights",
            "disable-devtool",
            "vidstats.top",
            "doubleclick",
            "google-analytics",
            "googletagmanager",
            "qpon",
            "areekwiney",
            "bagpipewraxle",
            "/cuid/",
            "/ads/",
            "/advert",
            "/banner"
        )

        if (
            blockedPatterns.any {
                lower.contains(it)
            }
        ) {
            return false
        }

        // --------------------------------------------
        // Extensões de recursos comuns
        // --------------------------------------------

        val blockedExtensions = listOf(
            ".js",
            ".css",
            ".ico",
            ".png",
            ".jpg",
            ".jpeg",
            ".gif",
            ".svg",
            ".webp",
            ".woff",
            ".woff2",
            ".ttf"
        )

        if (
            blockedExtensions.any {
                lower.substringBefore("?")
                    .substringBefore("#")
                    .endsWith(it)
            }
        ) {
            return false
        }

        // --------------------------------------------
        // Stream
        // --------------------------------------------

        return lower.contains(".m3u8") ||
                lower.contains(".mp4") ||
                lower.contains(".mpd") ||
                lower.contains("/hls/") ||
                lower.contains("/stream/") ||
                lower.contains("master.m3u8") ||
                lower.contains("index.m3u8") ||
                lower.contains("playlist.m3u8")
    }

    private fun isBlockedAdUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase()

        return lower.contains("qpon") ||
                lower.contains("areekwiney") ||
                lower.contains("bagpipewraxle") ||
                lower.contains("/cuid/") ||
                lower.contains("doubleclick") ||
                lower.contains("googlesyndication") ||
                lower.contains("googleadservices")
    }

    // ============================================================
    // 7. PLAYER / HEADERS
    // ============================================================

    private fun playerPageUrl(
        videoType: Video.Type
    ): String {

        return when (videoType) {

            is Video.Type.Movie -> {

                val contentId =
                    videoType.id
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?: videoType.imdbId
                            .orEmpty()

                "$BASE_URL/embed/$contentId?ad_played=1"
            }

            is Video.Type.Episode -> {

                val show =
                    videoType.tvShow

                val contentId =
                    show.id
                        .takeIf {
                            it.isNotBlank() &&
                                    !it.startsWith("tt")
                        }
                        ?: show.imdbId
                            ?.takeIf {
                                !it.startsWith("tt")
                            }
                        ?: show.id
                            .takeIf {
                                it.isNotBlank()
                            }
                        ?: show.imdbId
                            .orEmpty()

                "$BASE_URL/embed/$contentId/" +
                        "${videoType.season.number}/" +
                        "${videoType.number}" +
                        "?ad_played=1"
            }
        }
    }

    private fun resolvePlayerReferer(
        playerUrl: String
    ): String {

        val lower =
            playerUrl.lowercase()

        return when {

            "abysscdn" in lower ||
                    "embedplayabyss" in lower ->
                "https://abysscdn.com/"

            "upns" in lower ||
                    "embedplayapiupn" in lower ->
                "https://embedplayapiupn.upns.xyz/"

            "embedplaybyse" in lower ->
                "https://embedplaybyse.top/"

            "luluvdo" in lower ->
                "https://luluvdo.com/"

            "filemoon" in lower ->
                "https://filemoon.sx/"

            else ->
                "https://www.embedplay.one/"
        }
    }

    private fun resolveMimeType(
        url: String
    ): String {

        val clean =
            url.lowercase()
                .substringBefore("?")
                .substringBefore("#")

        return when {

            clean.endsWith(".mp4") ->
                MimeTypes.VIDEO_MP4

            clean.endsWith(".mpd") ->
                MimeTypes.APPLICATION_MPD

            else ->
                MimeTypes.APPLICATION_M3U8
        }
    }

    private fun buildHeaders(
        streamUrl: String,
        playerUrl: String
    ): Map<String, String> {

        val refererHeader =
            resolvePlayerReferer(
                if (playerUrl.isNotBlank()) {
                    playerUrl
                } else {
                    streamUrl
                }
            )

        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to refererHeader,
            "Origin" to refererHeader.trimEnd('/'),
            "Accept" to "*/*",
            "Accept-Language" to
                    "pt-BR,pt;q=0.9,en;q=0.8",
            "Connection" to "keep-alive"
        )
    }

    // ============================================================
    // 8. CONSTANTES / JAVASCRIPT
    // ============================================================

    companion object {

        private const val TAG =
            "EmbedPlayApiExtractor"

        private const val TIMEOUT_MS =
            45000L

        private const val MP4_FALLBACK_DELAY_MS =
            1200L

        private const val CONSOLE_PREFIX =
            "EmbedPlayAPI stream:"

        private const val BASE_URL =
            "https://embedplayapi.top"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) " +
                    "Chrome/122.0.0.0 Safari/537.36"

        // --------------------------------------------------------
        // Anti popup
        // --------------------------------------------------------

        private val ANTI_POPUP_JS = """
            (function() {
                try {
                    window.open = function() {
                        return null;
                    };

                    window.alert = function() {};

                    window.confirm = function() {
                        return true;
                    };

                    window.prompt = function() {
                        return null;
                    };

                    document.addEventListener(
                        'click',
                        function(e) {
                            var target = e.target;

                            if (!target) return;

                            var link =
                                target.closest &&
                                target.closest('a');

                            if (!link) return;

                            var href =
                                link.href || '';

                            if (
                                href.indexOf('javascript:') === 0
                            ) {
                                e.preventDefault();
                                e.stopPropagation();
                            }
                        },
                        true
                    );

                } catch(e) {}
            })();
        """.trimIndent()

        // --------------------------------------------------------
        // SNIFFER
        // --------------------------------------------------------

        private val SNIFFER_JS = """
            (function() {

                if (window.__EMBEDPLAY_EXTRACTOR_V4__) {
                    return;
                }

                window.__EMBEDPLAY_EXTRACTOR_V4__ = true;

                const seen = new Set();

                function normalize(value) {

                    if (!value) {
                        return '';
                    }

                    try {

                        return String(value)
                            .replace(/\\\//g, '/')
                            .replace(/\\u0026/g, '&')
                            .replace(/\\u003d/g, '=')
                            .trim();

                    } catch(e) {
                        return '';
                    }
                }

                function absoluteUrl(value) {

                    value = normalize(value);

                    if (!value) {
                        return '';
                    }

                    try {
                        return new URL(
                            value,
                            document.baseURI
                        ).href;
                    } catch(e) {
                        return value;
                    }
                }

                function isInteresting(url) {

                    url = normalize(url).toLowerCase();

                    if (!url) {
                        return false;
                    }

                    if (
                        url.indexOf('.m3u8') !== -1 ||
                        url.indexOf('.mp4') !== -1 ||
                        url.indexOf('.mpd') !== -1 ||
                        url.indexOf('/hls/') !== -1 ||
                        url.indexOf('/stream/') !== -1
                    ) {
                        return true;
                    }

                    return false;
                }

                function notify(value) {

                    let url =
                        absoluteUrl(value);

                    if (!url) {
                        return;
                    }

                    if (!isInteresting(url)) {
                        return;
                    }

                    if (seen.has(url)) {
                        return;
                    }

                    seen.add(url);

                    try {
                        console.log(
                            '${CONSOLE_PREFIX} ' +
                            url
                        );
                    } catch(e) {}
                }

                function scanText(text) {

                    if (!text) {
                        return;
                    }

                    text = normalize(text);

                    // URLs absolutas
                    const absoluteRegex =
                        /https?:\/\/[^"'\\s<>]+/ig;

                    let match;

                    while (
                        (match =
                            absoluteRegex.exec(text)) !== null
                    ) {
                        notify(match[0]);
                    }

                    // URLs relativas contendo HLS
                    const relativeRegex =
                        /["']([^"']*(?:\.m3u8|\.mpd|\.mp4|\/hls\/|\/stream\/)[^"']*)["']/ig;

                    while (
                        (match =
                            relativeRegex.exec(text)) !== null
                    ) {
                        notify(match[1]);
                    }
                }

                // ==================================================
                // FETCH
                // ==================================================

                const originalFetch =
                    window.fetch;

                if (originalFetch) {

                    window.fetch =
                        async function(resource, init) {

                            try {

                                const requestUrl =
                                    typeof resource === 'string'
                                        ? resource
                                        : resource &&
                                          resource.url;

                                notify(requestUrl);

                            } catch(e) {}

                            const response =
                                await originalFetch.apply(
                                    this,
                                    arguments
                                );

                            try {

                                notify(
                                    response.url
                                );

                                const cloned =
                                    response.clone();

                                cloned.text()
                                    .then(function(text) {
                                        scanText(text);
                                    })
                                    .catch(function(){});

                            } catch(e) {}

                            return response;
                        };
                }

                // ==================================================
                // XMLHttpRequest
                // ==================================================

                const OriginalXHR =
                    window.XMLHttpRequest;

                if (OriginalXHR) {

                    const originalOpen =
                        OriginalXHR.prototype.open;

                    const originalSend =
                        OriginalXHR.prototype.send;

                    OriginalXHR.prototype.open =
                        function(method, url) {

                            try {
                                notify(url);
                            } catch(e) {}

                            return originalOpen.apply(
                                this,
                                arguments
                            );
                        };

                    OriginalXHR.prototype.send =
                        function() {

                            try {

                                this.addEventListener(
                                    'load',
                                    function() {

                                        try {

                                            notify(
                                                this.responseURL
                                            );

                                            scanText(
                                                this.responseText ||
                                                ''
                                            );

                                        } catch(e) {}
                                    }
                                );

                            } catch(e) {}

                            return originalSend.apply(
                                this,
                                arguments
                            );
                        };
                }

                // ==================================================
                // VIDEO / SOURCE / IFRAME
                // ==================================================

                function scanMediaElements() {

                    try {

                        document
                            .querySelectorAll(
                                'video, source, iframe'
                            )
                            .forEach(
                                function(el) {

                                    notify(
                                        el.currentSrc
                                    );

                                    notify(
                                        el.src
                                    );

                                    const dataSrc =
                                        el.getAttribute(
                                            'data-src'
                                        );

                                    notify(dataSrc);

                                    const dataVideo =
                                        el.getAttribute(
                                            'data-video'
                                        );

                                    notify(dataVideo);
                                }
                            );

                    } catch(e) {}
                }

                // ==================================================
                // PERFORMANCE ENTRIES
                // ==================================================

                function scanPerformance() {

                    try {

                        if (
                            !window.performance ||
                            !performance.getEntriesByType
                        ) {
                            return;
                        }

                        performance
                            .getEntriesByType(
                                'resource'
                            )
                            .forEach(
                                function(entry) {

                                    notify(
                                        entry.name
                                    );
                                }
                            );

                    } catch(e) {}
                }

                scanMediaElements();
                scanPerformance();

                setInterval(
                    function() {
                        scanMediaElements();
                        scanPerformance();
                    },
                    400
                );

            })();
        """.trimIndent()

        // --------------------------------------------------------
        // PLAY
        // --------------------------------------------------------

        private val PLAY_JS = """
            (function() {

                function startPlayer() {

                    try {

                        const selectors = [
                            '.vjs-big-play-button',
                            '.jw-display-icon-container',
                            '.jw-icon-playback',
                            '.play-button',
                            '.play',
                            '[aria-label*="Play"]',
                            '[title*="Play"]',
                            '[class*="play"]'
                        ];

                        selectors.forEach(
                            function(selector) {

                                try {

                                    document
                                        .querySelectorAll(
                                            selector
                                        )
                                        .forEach(
                                            function(btn) {

                                                try {
                                                    btn.click();
                                                } catch(e) {}

                                            }
                                        );

                                } catch(e) {}
                            }
                        );

                    } catch(e) {}

                    try {

                        const videos =
                            document.querySelectorAll(
                                'video'
                            );

                        videos.forEach(
                            function(video) {

                                try {

                                    video.muted = true;

                                    const promise =
                                        video.play();

                                    if (
                                        promise &&
                                        promise.catch
                                    ) {
                                        promise.catch(
                                            function(){}
                                        );
                                    }

                                } catch(e) {}
                            }
                        );

                    } catch(e) {}
                }

                startPlayer();

                let tries = 0;

                const timer =
                    setInterval(
                        function() {

                            tries++;

                            startPlayer();

                            if (tries >= 20) {
                                clearInterval(timer);
                            }

                        },
                        700
                    );

            })();
        """.trimIndent()
    }
}