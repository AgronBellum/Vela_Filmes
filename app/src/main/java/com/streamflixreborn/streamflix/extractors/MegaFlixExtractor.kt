package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Headers.Companion.toHeaders
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ResolvedMedia(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

class MegaFlixExtractor : BaseStreamExtractor() {

    override val name = "MegaFlix"
    override val mainUrl = "https://megafrixapi.com"
    override val aliasUrls = listOf(
        "https://megaflix.name",
        "https://vods.faz-o-eli.online",
        "https://xn--kcksk7a2bl5le7b6doc1h3f.com",
        "https://megafrixapi.com",
        "https://embed.megafrixapi.com"
    )

    override val allowedDomains = listOf(
        "megaflix", "megafrixapi", "faz-o-eli", "luluvdo", "playerwish", "streamwish",
        "filemoon", "filemolson", "streamtape", "doodstream", "mixdrop", "voe", "embedplay",
        "vod07e001", "vod07", "vod", "fun", "hidehide", "hide", "listeamed", "voltz", "cnvs",
        "vidara", "upbolt", "futureengineering", "softwaredownloadhub", "acek-cdn", "tnmr.org",
        "lulucdn", "lulustream", "shop", "site"
    )

    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var dynamicHeaders: Map<String, String> = emptyMap()

    // ============================================================
    // 1. BUSCA DE SERVIDORES (HTML SCRAPING)
    // ============================================================

    suspend fun servers(videoType: Video.Type): List<Video.Server> = runCatching {
        val titleName = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }
        Log.d(TAG, "Iniciando busca no HTML do MegaFlix para: $titleName")

        val item = findMegaFlixItem(videoType)
        val itemId = item?.id ?: when (videoType) {
            is Video.Type.Movie -> videoType.imdbId?.takeIf { it.isNotBlank() } ?: videoType.id
            is Video.Type.Episode -> videoType.tvShow.imdbId?.takeIf { it.isNotBlank() } ?: videoType.tvShow.id
        }

        val serversList = mutableListOf<Video.Server>()

        if (videoType is Video.Type.Episode) {
            val seasonNum = videoType.season.number
            val episodeNum = videoType.number
            serversList.addAll(fetchEpisodeServers(itemId, seasonNum, episodeNum))
        } else {
            val viewUrl = "$mainUrl/desktop/1.2.2/?page=viewItem&id=$itemId"
            val html = runCatching { getHtml(viewUrl) }.getOrNull().orEmpty()
            serversList.addAll(parseOpenOptionsHtml(html))
        }

        if (serversList.isNotEmpty()) {
            Log.d(TAG, "Total de servidores reais extraídos do HTML: ${serversList.size}")
            return@runCatching serversList
        }

        val seasonNum = if (videoType is Video.Type.Episode) videoType.season.number else 1
        val episodeNum = if (videoType is Video.Type.Episode) videoType.number else 1
        val viewUrl = when (videoType) {
            is Video.Type.Movie -> "$mainUrl/desktop/1.2.2/?page=viewItem&id=$itemId"
            is Video.Type.Episode -> "$mainUrl/desktop/1.2.2/?page=viewItem&id=$itemId&season=$seasonNum&episode=$episodeNum"
        }

        listOf(
            Video.Server(
                id = viewUrl,
                name = "MegaFlix Dublado",
                src = viewUrl
            )
        )
    }.onFailure {
        Log.w(TAG, "Falha ao buscar servidores HTML do MegaFlix: ${it.message}")
    }.getOrElse {
        listOf(
            Video.Server(
                id = "$mainUrl/desktop/1.2.2/",
                name = "MegaFlix Dublado",
                src = "$mainUrl/desktop/1.2.2/"
            )
        )
    }

    private suspend fun fetchEpisodeServers(itemId: String, seasonNum: Int, episodeNum: Int): List<Video.Server> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$mainUrl/desktop/1.2.2/?page=getEpisodes&season=$seasonNum&idItem=$itemId"
            val formBody = FormBody.Builder()
                .add("userEpisodes", "[]")
                .build()

            val request = Request.Builder()
                .url(url)
                .post(formBody)
                .header("User-Agent", userAgent.replace("; wv", ""))
                .header("Referer", "https://megaflix.name/")
                .header("Origin", "https://megaflix.name")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Sec-Fetch-Site", "cross-site")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .build()

            val html = okHttpClient.newCall(request).execute().use { response ->
                response.body?.string().orEmpty()
            }

            if (html.isBlank()) return@withContext emptyList()

            val serversList = mutableListOf<Video.Server>()
            val episodeBlockRegex = """openEpisode\s*\(\s*\{([\s\S]*?)\}\s*\)""".toRegex()

            episodeBlockRegex.findAll(html).forEach { matchResult ->
                val block = matchResult.groupValues[1]

                val epNumMatch = """episode_num\s*:\s*["']?(\d+)["']?""".toRegex().find(block)?.groupValues?.getOrNull(1)
                val currentEpNum = epNumMatch?.toIntOrNull() ?: -1

                if (currentEpNum == episodeNum) {
                    val brMatch = """br\s*:\s*["']([^"']+)["']""".toRegex().find(block)?.groupValues?.getOrNull(1).orEmpty()
                    val engMatch = """eng\s*:\s*["']([^"']+)["']""".toRegex().find(block)?.groupValues?.getOrNull(1).orEmpty()

                    fun addUrls(raw: String, isDubbed: Boolean) {
                        if (raw.isBlank()) return
                        raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEachIndexed { index, embedUrl ->
                            var finalUrl = embedUrl
                            if (finalUrl.contains("cnvs") && !finalUrl.startsWith("http")) {
                                finalUrl = "$mainUrl/cnvs/" + URLEncoder.encode(finalUrl, "UTF-8")
                            }

                            val hostName = runCatching { URI(finalUrl).host }.getOrNull()
                                ?.removePrefix("www.")
                                ?: "Opção ${index + 1}"
                            val label = if (isDubbed) "[Dub]" else "[Leg]"

                            serversList.add(
                                Video.Server(
                                    id = finalUrl,
                                    name = "$label MegaFlix - $hostName",
                                    src = finalUrl
                                )
                            )
                        }
                    }

                    addUrls(brMatch, isDubbed = true)
                    addUrls(engMatch, isDubbed = false)
                }
            }
            // HideHide currently returns a master playlist whose child playlist may answer 403.
            // Prefer the Lulu/Upbolt mirrors; keep HideHide available as a fallback in the picker.
            serversList.sortedBy { server ->
                val source = server.src.lowercase()
                when {
                    "luluvido" in source || "luluvdo" in source -> 0
                    "upbolt" in source -> 1
                    "hide" in source -> 2
                    else -> 3
                }
            }
        }.getOrElse {
            Log.w(TAG, "Erro ao buscar episódios em getEpisodes: ${it.message}")
            emptyList()
        }
    }

    private fun parseOpenOptionsHtml(html: String): List<Video.Server> {
        val openOptionsRegex = """openOptions\s*\(\s*\{([\s\S]*?)\}\s*\)""".toRegex()
        val optionsMatch = openOptionsRegex.find(html)?.groupValues?.getOrNull(1).orEmpty()

        val brGroup = """br:\s*['"]([^'"]*)['"]""".toRegex().find(optionsMatch)?.groupValues?.getOrNull(1).orEmpty()
        val engGroup = """eng:\s*['"]([^'"]*)['"]""".toRegex().find(optionsMatch)?.groupValues?.getOrNull(1).orEmpty()

        val serversList = mutableListOf<Video.Server>()

        fun parseAndAddUrls(rawUrls: String, isDubbed: Boolean) {
            if (rawUrls.isBlank()) return
            val urls = rawUrls.split(",").map { it.trim() }.filter { it.isNotBlank() }
            urls.forEachIndexed { index, embedUrl ->
                var finalUrl = embedUrl
                if (finalUrl.contains("cnvs") && !finalUrl.startsWith("http")) {
                    finalUrl = "$mainUrl/cnvs/" + URLEncoder.encode(finalUrl, "UTF-8")
                }

                val hostName = runCatching { URI(finalUrl).host }.getOrNull()
                    ?.removePrefix("www.")
                    ?: "Servidor ${index + 1}"
                val label = if (isDubbed) "[Dub]" else "[Leg]"

                serversList.add(
                    Video.Server(
                        id = finalUrl,
                        name = "$label MegaFlix - $hostName",
                        src = finalUrl
                    )
                )
            }
        }

        parseAndAddUrls(brGroup, isDubbed = true)
        parseAndAddUrls(engGroup, isDubbed = false)

        return serversList
    }

    // ============================================================
    // 2. EXTRAÇÃO HTTP DIRETA (SISTEMA UNIFICADO DE PROVEDORES)
    // ============================================================

    override suspend fun extractViaHttp(targetUrl: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "[$name] Iniciando extração HTTP para: $targetUrl")

        tryDecodePlayerLink(targetUrl)?.let { decodedStream ->
            Log.i(TAG, "[$name] Stream extraído instantaneamente do parâmetro Base64 'link': $decodedStream")
            return@withContext decodedStream
        }

        val resolvedUrl = resolveMegaFlixWrapper(targetUrl) ?: targetUrl
        if (isStrictMediaUrl(resolvedUrl)) return@withContext resolvedUrl

        val streamUrl = when {
            "hidehide" in resolvedUrl || "hide" in resolvedUrl -> extractHideHide(resolvedUrl)
            "vidara" in resolvedUrl -> extractVidara(resolvedUrl)
            "upbolt" in resolvedUrl || "luluvdo" in resolvedUrl ||
                    "playerwish" in resolvedUrl || "streamwish" in resolvedUrl ||
                    "filemoon" in resolvedUrl || "filemolson" in resolvedUrl -> extractPackedHost(resolvedUrl)
            "voe" in resolvedUrl -> extractVoe(resolvedUrl)
            "mixdrop" in resolvedUrl -> extractMixdrop(resolvedUrl)
            else -> extractGenericHost(resolvedUrl)
        }

        if (!streamUrl.isNullOrBlank()) {
            Log.i(TAG, "[$name] Stream resolvido via HTTP Direto: $streamUrl")
        } else {
            Log.w(TAG, "[$name] Falha na extração HTTP para: $resolvedUrl")
        }

        streamUrl
    }

    private fun tryDecodePlayerLink(url: String): String? {
        if (!url.contains("megafrixapi.com/desktop/player") || !url.contains("link=")) return null
        val rawLink = url.substringAfter("link=").substringBefore("&")
        if (rawLink.isBlank()) return null
        return runCatching {
            val decoded = String(Base64.decode(rawLink, Base64.DEFAULT)).trim()
            if (decoded.startsWith("http")) decoded else null
        }.getOrNull()
    }

    private suspend fun resolveMegaFlixWrapper(url: String): String? = withContext(Dispatchers.IO) {
        var currentUrl = url

        if (currentUrl.contains("/cnvs/")) {
            val encodedPart = currentUrl.substringAfter("/cnvs/")
            currentUrl = runCatching { URLDecoder.decode(encodedPart, "UTF-8") }.getOrDefault(currentUrl)
            if (isStrictMediaUrl(currentUrl)) return@withContext currentUrl
        }

        if (currentUrl.contains("/hide/") || currentUrl.contains("megafrixapi.com/hide/")) {
            val hideId = currentUrl.substringAfterLast("/")
            val hideUrl = "https://hidehide.shop/v/$hideId"

            val request = Request.Builder()
                .url(hideUrl)
                .header("User-Agent", userAgent.replace("; wv", ""))
                .header("Referer", "https://hidehide.shop/")
                .header("Origin", "https://hidehide.shop")
                .build()

            return@withContext runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    val finalUrl = response.request.url.toString()
                    if (finalUrl != hideUrl && !finalUrl.contains("megafrixapi.com")) {
                        return@use finalUrl
                    }
                    val html = response.body?.string().orEmpty()
                    val iframeSrc = Jsoup.parse(html).select("iframe[src]").attr("src")
                    if (iframeSrc.isNotBlank()) {
                        if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                    } else {
                        hideUrl
                    }
                }
            }.getOrNull() ?: hideUrl
        }

        currentUrl
    }

    private suspend fun extractHideHide(targetUrl: String): String? = withContext(Dispatchers.IO) {
        val hideId = targetUrl.substringAfterLast("/")
        val realPlayerUrl = if (targetUrl.contains("hidehide.shop")) targetUrl else "https://hidehide.shop/v/$hideId"

        val request = Request.Builder()
            .url(realPlayerUrl)
            .header("User-Agent", userAgent.replace("; wv", ""))
            .header("Referer", "https://hidehide.shop/")
            .header("Origin", "https://hidehide.shop")
            .build()

        val html = runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                response.body?.string().orEmpty()
            }
        }.getOrNull().orEmpty()

        if (html.isBlank()) return@withContext null

        val iframeSrc = Jsoup.parse(html).select("iframe[src]").attr("src")
        if (iframeSrc.isNotBlank()) {
            val innerUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            return@withContext extractViaHttp(innerUrl)
        }

        unpackPackedScript(html, "https://hidehide.shop/")
    }

    private suspend fun extractVidara(url: String): String? = withContext(Dispatchers.IO) {
        val headers = buildHeaders(url, url)
        val html = runCatching { getHtml(url, headers["Referer"].orEmpty()) }.getOrNull().orEmpty()
        if (html.isBlank()) return@withContext null

        val directRegex = """(?:file|source|src)\s*:\s*["'](https?://[^"']+\.(?:m3u8|txt)[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE)
        directRegex.find(html)?.groupValues?.getOrNull(1)?.let { return@withContext it }

        val base64Regex = """atob\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""".toRegex()
        base64Regex.findAll(html).forEach { match ->
            val decoded = runCatching {
                String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
            }.getOrNull().orEmpty()

            if (decoded.contains(".m3u8") || decoded.contains(".txt") || decoded.contains(".urlset")) {
                val extracted = """https?://[^\s"'<>]+\.(?:m3u8|txt)[^\s"'<>]*""".toRegex().find(decoded)?.value
                if (!extracted.isNullOrBlank()) return@withContext extracted
            }
        }

        unpackPackedScript(html, headers["Referer"] ?: url)
    }

    private suspend fun extractPackedHost(url: String): String? = withContext(Dispatchers.IO) {
        val referer = buildHeaders(url, url)["Referer"] ?: url
        val html = runCatching { getHtml(url, referer) }.getOrNull().orEmpty()
        if (html.isBlank()) return@withContext null

        val iframeSrc = Jsoup.parse(html).select("iframe[src]").attr("src")
        if (iframeSrc.isNotBlank()) {
            val innerUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            return@withContext extractViaHttp(innerUrl)
        }

        unpackPackedScript(html, referer)
    }

    private suspend fun unpackPackedScript(html: String, referer: String): String? = withContext(Dispatchers.IO) {
        val cleanHtml = html.replace("\\/", "/")
        val evalRegex = """<script[^>]*>\s*(eval\(function\(p,a,c,k,e,d\).*?)\s*</script>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val packedJs = evalRegex.find(cleanHtml)?.groupValues?.getOrNull(1)

        if (!packedJs.isNullOrBlank()) {
            val moonResult = extractViaMoonPhp(packedJs, referer)
            if (!moonResult.isNullOrBlank()) {
                Log.i(TAG, "[$name] Stream extraído com sucesso via moon.php: $moonResult")
                return@withContext moonResult
            }
        }

        val unpackedScripts = evalRegex.findAll(cleanHtml).mapNotNull { match ->
            runCatching { JsUnpacker(match.groupValues[1]).unpack() }.getOrNull()
        }.toList()

        val contentToSearch = (listOf(cleanHtml) + unpackedScripts).joinToString("\n")

        val structuredRegex = """(?:file|source|src|hls|video_url)\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4|txt)[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE)
        structuredRegex.find(contentToSearch)?.groupValues?.getOrNull(1)?.let { return@withContext it }

        val rawMediaRegex = """https?://[^\s"'<>]+\.(?:m3u8|mp4|txt)[^\s"'<>]*""".toRegex(RegexOption.IGNORE_CASE)
        rawMediaRegex.findAll(contentToSearch)
            .map { it.value }
            .firstOrNull { isStrictMediaUrl(it) }
    }

    private suspend fun extractViaMoonPhp(packedJs: String, referer: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val b64Data = Base64.encodeToString(packedJs.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val formBody = FormBody.Builder()
                .add("data", b64Data)
                .build()

            val originHeader = runCatching {
                val uri = URI(referer)
                "${uri.scheme}://${uri.host}"
            }.getOrNull() ?: "https://luluvdo.com"

            val request = Request.Builder()
                .url("https://app.megafrixapi.com/moon.php")
                .post(formBody)
                .header("User-Agent", userAgent.replace("; wv", ""))
                .header("Referer", referer)
                .header("Origin", originHeader)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .build()

            val html = okHttpClient.newCall(request).execute().use { it.body?.string().orEmpty() }

            val fileRegex = """file\s*:\s*["'](https?://[^"']+\.(?:m3u8|txt|mp4)[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE)
            fileRegex.find(html)?.groupValues?.getOrNull(1)
        }.getOrNull()
    }

    private suspend fun extractVoe(url: String): String? = withContext(Dispatchers.IO) {
        val html = runCatching { getHtml(url, "https://voe.sx/") }.getOrNull().orEmpty()

        val voeRegex = """'hls'\s*:\s*['"](https?://[^'"]+)['"]""".toRegex()
        voeRegex.find(html)?.groupValues?.getOrNull(1)?.let { return@withContext it }

        val base64UrlRegex = """window\.location\.href\s*=\s*atob\(['"]([^'"]+)['"]\)""".toRegex()
        base64UrlRegex.find(html)?.groupValues?.getOrNull(1)?.let { b64 ->
            val decodedUrl = String(Base64.decode(b64, Base64.DEFAULT))
            if (isStrictMediaUrl(decodedUrl)) return@withContext decodedUrl
        }

        unpackPackedScript(html, "https://voe.sx/")
    }

    private suspend fun extractMixdrop(url: String): String? = withContext(Dispatchers.IO) {
        val html = runCatching { getHtml(url, "https://mixdrop.co/") }.getOrNull().orEmpty()
        val unpacked = unpackPackedScript(html, "https://mixdrop.co/") ?: html
        val mixdropRegex = """MDCore\.wurl\s*=\s*["']([^"']+)["']""".toRegex()
        mixdropRegex.find(unpacked)?.groupValues?.getOrNull(1)?.let {
            if (it.startsWith("//")) "https:$it" else it
        }
    }

    private suspend fun extractGenericHost(url: String): String? = withContext(Dispatchers.IO) {
        val html = runCatching { getHtml(url, url) }.getOrNull().orEmpty()
        if (html.isBlank()) return@withContext null

        unpackPackedScript(html, url) ?: run {
            val mediaRegex = """https?://[^\s"'<>]+\.(?:m3u8|mp4|txt)[^\s"'<>]*""".toRegex(RegexOption.IGNORE_CASE)
            mediaRegex.findAll(html)
                .map { it.value }
                .firstOrNull { isStrictMediaUrl(it) }
        }
    }

    // ============================================================
    // 3. PLANO B: RESOLUÇÃO WEBVIEW COM PIPELINE RESOLVEDMEDIA
    // ============================================================

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun extractWithWebView(playerUrl: String): String = mutex.withLock {
        val resolved = resolveMediaContext(playerUrl)
        if (resolved.headers.isNotEmpty()) {
            dynamicHeaders = resolved.headers
        }
        return@withLock resolved.url
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveMediaContext(playerUrl: String): ResolvedMedia = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            var webView: WebView? = null
            var isResumed = false

            fun cleanup() {
                mainHandler.post {
                    runCatching {
                        webView?.stopLoading()
                        webView?.loadUrl("about:blank")
                        webView?.destroy()
                    }
                    webView = null
                }
            }

            fun safeResume(result: Result<ResolvedMedia>) {
                if (!isResumed && continuation.isActive) {
                    isResumed = true
                    result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) }
                    )
                    cleanup()
                }
            }

            fun capture(url: String?, requestHeaders: Map<String, String> = emptyMap()) {
                val value = url.orEmpty().replace("\\/", "/").trim()
                val decodedUrl = tryDecodePlayerLink(value) ?: value

                if (isStrictMediaUrl(decodedUrl)) {
                    Log.i(TAG, "[$name] Mídia final resolvida via Headless/Electron Bridge: $decodedUrl")
                    safeResume(Result.success(ResolvedMedia(decodedUrl, requestHeaders)))
                }
            }

            fun injectShimsEarly(view: WebView?) {
                view?.evaluateJavascript(ANTI_POPUP_JS, null)
                view?.evaluateJavascript(COMPREHENSIVE_ELECTRON_SHIM_JS, null)
                view?.evaluateJavascript(INJECT_DESKTOP_EXTRACTOR_JS, null)
                view?.evaluateJavascript(SNIFFER_JS, null)
            }

            mainHandler.post {
                try {
                    webView = WebView(StreamFlixApp.instance.applicationContext).apply {
                        setBackgroundColor(Color.TRANSPARENT)

                        onResume()
                        resumeTimers()

                        val cleanUserAgent = userAgent.replace("; wv", "")

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            userAgentString = cleanUserAgent
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            mediaPlaybackRequiresUserGesture = false
                            cacheMode = WebSettings.LOAD_NO_CACHE
                            setSupportMultipleWindows(false)
                            javaScriptCanOpenWindowsAutomatically = false
                        }

                        addJavascriptInterface(
                            MegaFlixBridge(
                                onStreamFound = { url -> capture(url) },
                                onUpdateHeaders = { headersMap -> dynamicHeaders = headersMap },
                                okHttpClient = okHttpClient
                            ),
                            "MegaFlix"
                        )

                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isGesture: Boolean, msg: android.os.Message?) = false

                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                if (newProgress >= 10) {
                                    injectShimsEarly(view)
                                }
                                super.onProgressChanged(view, newProgress)
                            }

                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                val message = consoleMessage?.message().orEmpty()
                                if (message.contains(CONSOLE_PREFIX)) {
                                    capture(message.substringAfter(CONSOLE_PREFIX).trim())
                                }
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                injectShimsEarly(view)
                                super.onPageStarted(view, url, favicon)
                            }

                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                handler?.proceed()
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString().orEmpty().lowercase()
                                val isAllowedDomain = allowedDomains.any { url.contains(it) }
                                if (!isAllowedDomain) {
                                    Log.w(TAG, "[$name] Redirecionamento/Anúncio bloqueado: $url")
                                    return true
                                }
                                return false
                            }

                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                request?.let { req ->
                                    val reqUrl = req.url.toString()
                                    val headers = req.requestHeaders ?: emptyMap()

                                    val decodedUrl = tryDecodePlayerLink(reqUrl) ?: reqUrl
                                    if (isStrictMediaUrl(decodedUrl)) {
                                        capture(decodedUrl, headers)
                                    }

                                    if (req.isForMainFrame && allowedDomains.any { reqUrl.lowercase().contains(it) }) {
                                        runCatching {
                                            val response = okHttpClient.newCall(
                                                Request.Builder()
                                                    .url(reqUrl)
                                                    .headers(buildHeaders(reqUrl, reqUrl).toHeaders())
                                                    .build()
                                            ).execute()

                                            val html = response.body?.string().orEmpty()
                                            if (html.isNotBlank() && html.contains("<head")) {
                                                val injectedHtml = html.replace(
                                                    "<head>",
                                                    "<head><script>$COMPREHENSIVE_ELECTRON_SHIM_JS</script><script>$SNIFFER_JS</script>"
                                                )
                                                return WebResourceResponse(
                                                    "text/html",
                                                    "UTF-8",
                                                    injectedHtml.byteInputStream()
                                                )
                                            }
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onLoadResource(view: WebView?, url: String?) {
                                capture(url)
                                super.onLoadResource(view, url)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                injectShimsEarly(view)
                                view?.evaluateJavascript(PLAY_JS, null)
                            }
                        }

                        val refererHeader = buildHeaders(playerUrl, playerUrl)["Referer"] ?: "https://megaflix.name/"

                        loadUrl(
                            playerUrl,
                            mapOf(
                                "Referer" to refererHeader,
                                "Origin" to refererHeader.trimEnd('/'),
                                "User-Agent" to cleanUserAgent
                            )
                        )
                    }
                } catch (e: Exception) {
                    safeResume(Result.failure(e))
                }

                mainHandler.postDelayed({
                    safeResume(Result.failure(Exception("[$name] Timeout: mídia MegaFlix não resolvida após ${timeoutMs}ms")))
                }, timeoutMs)

                continuation.invokeOnCancellation { cleanup() }
            }
        }
    } ?: throw Exception("[$name] Operação excedeu ${timeoutMs}ms")

    // ============================================================
    // 4. PONTE JAVASCRIPT ASSÍNCRONA NATIVA
    // ============================================================

    private class MegaFlixBridge(
        private val onStreamFound: (String) -> Unit,
        private val onUpdateHeaders: (Map<String, String>) -> Unit,
        private val okHttpClient: OkHttpClient
    ) {
        private val bridgeScope = CoroutineScope(Dispatchers.IO)

        @JavascriptInterface
        fun fetchUrl(url: String?, headersJson: String?): String {
            if (url.isNullOrBlank()) return ""
            return runCatching {
                val reqBuilder = Request.Builder().url(url)
                val headers = parseJsonToMap(headersJson)
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    resp.body?.string().orEmpty()
                }
            }.getOrDefault("")
        }

        @JavascriptInterface
        fun openPlayerMovie(src: String?, data: String?) {
            Log.i(TAG, "Bridge: openPlayerMovie chamado -> src: $src, data: $data")
            parseDataHeaders(data)
            val streamUrl = extractUrl(src) ?: extractUrl(data)
            if (!streamUrl.isNullOrBlank()) onStreamFound(streamUrl)
        }

        @JavascriptInterface
        fun openPlayer(src: String?, data: String?) {
            Log.i(TAG, "Bridge: openPlayer chamado -> src: $src, data: $data")
            parseDataHeaders(data)
            val streamUrl = extractUrl(src) ?: extractUrl(data)
            if (!streamUrl.isNullOrBlank()) onStreamFound(streamUrl)
        }

        @JavascriptInterface
        fun getSource(src: String?, data: String?) {
            openPlayer(src, data)
        }

        @JavascriptInterface
        fun openLive(src: String?, data: String?) {
            openPlayer(src, data)
        }

        @JavascriptInterface
        fun updateHeaders(json: String?) {
            if (!json.isNullOrBlank()) {
                val map = parseJsonToMap(json)
                if (map.isNotEmpty()) onUpdateHeaders(map)
            }
        }

        @JavascriptInterface
        fun fetchUrlAsync(url: String?, headersJson: String?, callbackName: String?) {
            if (url.isNullOrBlank()) return
            bridgeScope.launch {
                runCatching {
                    val reqBuilder = Request.Builder().url(url)
                    val headers = parseJsonToMap(headersJson)
                    headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                    okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        resp.body?.string().orEmpty()
                    }
                }.onFailure {
                    Log.w(TAG, "Erro no fetchUrlAsync nativo: ${it.message}")
                }
            }
        }

        @JavascriptInterface
        fun getDeviceID(): String = "8d3f1a029c4e7b5f"

        @JavascriptInterface
        fun showToast(message: String?) {
            Log.d(TAG, "Bridge Toast: $message")
        }

        private fun extractUrl(input: String?): String? {
            if (input.isNullOrBlank()) return null
            val trimmed = input.trim()
            if (trimmed.startsWith("http")) return trimmed

            return runCatching {
                val json = JSONObject(trimmed)
                json.optString("url").takeIf { it.startsWith("http") }
                    ?: json.optString("src").takeIf { it.startsWith("http") }
                    ?: json.optString("source").takeIf { it.startsWith("http") }
                    ?: json.optString("link").takeIf { it.startsWith("http") }
            }.getOrNull()
        }

        private fun parseDataHeaders(data: String?) {
            if (data.isNullOrBlank()) return
            runCatching {
                val json = JSONObject(data)
                val headersObj = json.optJSONObject("headers")
                if (headersObj != null) {
                    val map = parseJsonToMap(headersObj.toString())
                    if (map.isNotEmpty()) onUpdateHeaders(map)
                }
            }
        }

        private fun parseJsonToMap(jsonStr: String?): Map<String, String> {
            if (jsonStr.isNullOrBlank()) return emptyMap()
            return runCatching {
                val obj = JSONObject(jsonStr)
                val map = mutableMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = obj.optString(key, "")
                    if (key.isNotBlank() && value.isNotBlank()) map[key] = value
                }
                map
            }.getOrDefault(emptyMap())
        }
    }

    // ============================================================
    // 5. CABEÇALHOS HTTP E VALIDAÇÃO DE STREAM ESTRITA
    // ============================================================

    override fun buildHeaders(streamUrl: String, targetUrl: String): Map<String, String> {
        val isHideHide = "hide" in streamUrl || "hide" in targetUrl || "hidehide" in streamUrl || "hidehide" in targetUrl ||
                "master.txt" in streamUrl || ".urlset" in streamUrl || "/hls3/" in streamUrl || "softwaredownloadhub" in streamUrl || "acek-cdn" in streamUrl || "futureengineering" in streamUrl

        val isLuluvdo = "luluvdo" in streamUrl || "luluvdo" in targetUrl || "tnmr.org" in streamUrl || "lulucdn" in streamUrl || "lulustream" in streamUrl

        val refererHeader = when {
            isHideHide -> "https://hidehide.shop/"
            isLuluvdo -> "https://luluvdo.com/"
            "vidara" in streamUrl || "vidara" in targetUrl -> "https://vidara.so/"
            "upbolt" in streamUrl || "upbolt" in targetUrl -> "https://upbolt.to/"
            "listeamed" in streamUrl || "listeamed" in targetUrl -> "https://listeamed.net/"
            "playerwish" in streamUrl || "playerwish" in targetUrl -> "https://playerwish.com/"
            "streamwish" in streamUrl || "streamwish" in targetUrl -> "https://streamwish.to/"
            "filemoon" in streamUrl || "filemolson" in streamUrl || "filemoon" in targetUrl || "filemolson" in targetUrl -> "https://filemoon.sx/"
            "faz-o-eli" in streamUrl || "faz-o-eli" in targetUrl || "voltz" in streamUrl -> "https://vods.faz-o-eli.online/"
            targetUrl.isNotBlank() && targetUrl.startsWith("http") && !targetUrl.contains("megafrixapi.com/desktop") -> targetUrl
            else -> "https://megaflix.name/"
        }

        val originHeader = runCatching {
            val uri = URI(refererHeader)
            "${uri.scheme}://${uri.host}"
        }.getOrNull() ?: "https://megaflix.name"

        val baseHeaders = mutableMapOf(
            "User-Agent" to userAgent.replace("; wv", ""),
            "Referer" to refererHeader,
            "Origin" to originHeader,
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin"
        )

        if (dynamicHeaders.isNotEmpty()) {
            baseHeaders.putAll(dynamicHeaders)
        }

        return baseHeaders
    }

    override fun isMainStream(url: String): Boolean = isStrictMediaUrl(url)

    private fun isStrictMediaUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val cleanUrl = url.replace("\\/", "/").trim()
        val lower = cleanUrl.lowercase()

        if (lower.contains("google-analytics") ||
            lower.contains("googletagmanager") ||
            lower.contains("facebook") ||
            lower.contains("adsystem") ||
            lower.contains("doubleclick") ||
            lower.contains("cdn-cgi") ||
            lower.contains(".ts") ||
            lower.contains(".m4s") ||
            lower.contains(".css") ||
            (lower.contains(".js") && !lower.contains("m3u8"))
        ) {
            return false
        }

        val hasMediaExtension = lower.contains(".m3u8") ||
                lower.contains(".mp4") ||
                lower.contains(".mpd") ||
                lower.contains("master.txt") ||
                lower.contains(".urlset") ||
                lower.contains("master.m3u8") ||
                lower.contains("index.m3u8") ||
                lower.contains("playlist.m3u8")

        if (hasMediaExtension) return true

        return lower.contains("/hls/") || lower.contains("/hls2/") || lower.contains("/hls3/")
    }

    // ============================================================
    // 6. MÉTODOS AUXILIARES DE BUSCA E PESQUISA DE TÍTULOS
    // ============================================================

    private suspend fun findMegaFlixItem(videoType: Video.Type): SearchMatch? {
        val targetTitle = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }

        val targetYear = when (videoType) {
            is Video.Type.Movie -> videoType.releaseDate.takeIf { it.length >= 4 }?.take(4).orEmpty()
            is Video.Type.Episode -> videoType.tvShow.releaseDate?.takeIf { it.length >= 4 }?.take(4).orEmpty()
        }

        val targetType = when (videoType) {
            is Video.Type.Movie -> MediaType.MOVIE
            is Video.Type.Episode -> MediaType.SERIES
        }

        val candidates = searchMegaFlix(targetTitle)
        if (candidates.isEmpty()) return null

        val targetKey = normalizeTitle(targetTitle)

        return candidates
            .map { candidate ->
                val candidateKey = normalizeTitle(candidate.title)
                val exactTitle = candidateKey == targetKey
                val titleStartsWith = candidateKey.startsWith(targetKey)
                val targetStartsWith = targetKey.startsWith(candidateKey)
                val sameYear = targetYear.isNotBlank() && candidate.year == targetYear
                val compatibleType = isCompatibleType(targetType, candidate.type)
                val distance = levenshtein(candidateKey, targetKey)

                val score = calculateSearchScore(
                    exactTitle, titleStartsWith, targetStartsWith,
                    sameYear, compatibleType, distance, candidateKey, targetKey
                )

                ScoredMatch(candidate, score, distance, exactTitle, sameYear, compatibleType)
            }
            .sortedWith(
                compareByDescending<ScoredMatch> { it.score }
                    .thenByDescending { it.exactTitle }
                    .thenByDescending { it.sameYear }
                    .thenByDescending { it.compatibleType }
                    .thenBy { it.distance }
            )
            .firstOrNull()
            ?.match
    }

    private suspend fun searchMegaFlix(title: String): List<SearchMatch> {
        val encodedTitle = URLEncoder.encode(title.trim(), "UTF-8")
        val urls = listOf(
            "$mainUrl/desktop/1.2.2/?page=searchItem&title=$encodedTitle",
            "$mainUrl/desktop/1.2.1/?page=searchItem&title=$encodedTitle"
        )

        for (url in urls) {
            try {
                val html = getHtml(url)
                if (html.isBlank()) continue
                val results = parseSearchResults(html)
                if (results.isNotEmpty()) return results
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    private fun parseSearchResults(html: String): List<SearchMatch> {
        val doc = Jsoup.parse(html)
        return doc.select("a.card.card-movie[onclick*=openItem], .item-card[onclick*=openItem]").mapNotNull { card ->
            val onclick = card.attr("onclick")
            val id = Regex("""openItem\s*\(\s*(\d+)\s*\)""")
                .find(onclick)?.groupValues?.getOrNull(1)
                ?: return@mapNotNull null

            val title = card.selectFirst("h3.title, .title")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null

            val year = card.select(".card-body li, .list-inline-item").firstOrNull()?.text()?.trim()?.let {
                Regex("""\d{4}""").find(it)?.value.orEmpty()
            }.orEmpty()

            val type = card.selectFirst(".card-type")?.text()?.trim().orEmpty()

            SearchMatch(id, title, year, type)
        }.distinctBy { it.id }
    }

    private fun calculateSearchScore(
        exactTitle: Boolean,
        titleStartsWith: Boolean,
        targetStartsWith: Boolean,
        sameYear: Boolean,
        compatibleType: Boolean,
        distance: Int,
        candidateTitle: String,
        targetTitle: String
    ): Int {
        var score = 0
        if (exactTitle) score += 1000
        if (sameYear) score += 500
        if (compatibleType) score += 300
        if (titleStartsWith) score += 120
        if (targetStartsWith) score += 80

        val lengthDifference = kotlin.math.abs(candidateTitle.length - targetTitle.length)
        score -= minOf(lengthDifference, 100)
        score -= minOf(distance * 5, 300)
        return score
    }

    private fun isCompatibleType(requested: MediaType, returned: String): Boolean {
        val type = normalizeTitle(returned)
        return when (requested) {
            MediaType.MOVIE -> type == "filme" || type == "movie" || type.isEmpty()
            MediaType.SERIES -> type == "serie" || type == "series" || type.isEmpty()
        }
    }

    private fun normalizeTitle(value: String): String {
        val noAccents = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noAccents.replace(Regex("[^a-z0-9]+"), "")
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            val temp = previous
            previous = current
            current = temp
        }
        return previous[b.length]
    }

    private enum class MediaType { MOVIE, SERIES }
    private data class SearchMatch(val id: String, val title: String, val year: String, val type: String)
    private data class ScoredMatch(val match: SearchMatch, val score: Int, val distance: Int, val exactTitle: Boolean, val sameYear: Boolean, val compatibleType: Boolean)

    companion object {
        private const val TAG = "MegaFlixExtractor"
        private const val CONSOLE_PREFIX = "STREAM_FOUND:"

        private val ANTI_POPUP_JS = """
            (function(){
                window.open = function() { return null; };
                window.alert = function() {};
                window.confirm = function() { return true; };
            })();
        """.trimIndent()

        private val COMPREHENSIVE_ELECTRON_SHIM_JS = """
            (function(){
                if (window.__ELECTRON_SHIM_ACTIVE__) return;
                window.__ELECTRON_SHIM_ACTIVE__ = true;

                window.dataHeaders = window.dataHeaders || {};

                function handleIpc(channel, args) {
                    var payload = (typeof args === 'object') ? JSON.stringify(args) : String(args || '');
                    if (channel === 'openPlayer' || channel === 'openPlayerMovie' || channel === 'getSource' || channel === 'stream-url' || channel === 'player-source') {
                        if (window.MegaFlix && window.MegaFlix.openPlayer) {
                            window.MegaFlix.openPlayer(payload, payload);
                        }
                    } else if (channel === 'updateHeaders') {
                        if (window.MegaFlix && window.MegaFlix.updateHeaders) {
                            window.MegaFlix.updateHeaders(payload);
                        }
                    }
                }

                var mockIpc = {
                    send: function(channel) {
                        var args = Array.prototype.slice.call(arguments, 1);
                        handleIpc(channel, args[0]);
                    },
                    invoke: function(channel, data) {
                        if (channel === 'fetchUrl') {
                            return new Promise(function(resolve) {
                                try {
                                    var url = (data && data.url) ? data.url : '';
                                    var headersJson = (data && data.headersJson) ? data.headersJson : '{}';
                                    if (window.MegaFlix && window.MegaFlix.fetchUrl) {
                                        var res = window.MegaFlix.fetchUrl(url, headersJson);
                                        resolve(res);
                                    } else {
                                        resolve("");
                                    }
                                } catch(e) {
                                    resolve("");
                                }
                            });
                        }
                        var args = Array.prototype.slice.call(arguments, 1);
                        handleIpc(channel, args[0]);
                        return Promise.resolve(true);
                    },
                    sendSync: function(channel) {
                        var args = Array.prototype.slice.call(arguments, 1);
                        handleIpc(channel, args[0]);
                        return true;
                    },
                    on: function() {},
                    once: function() {},
                    removeListener: function() {}
                };

                window.ipcRenderer = mockIpc;
                window.electron = { ipcRenderer: mockIpc };

                if (typeof window.require === 'undefined') {
                    window.require = function(mod) {
                        if (mod === 'electron') return { ipcRenderer: mockIpc };
                        return {};
                    };
                }
            })();
        """.trimIndent()

        private val INJECT_DESKTOP_EXTRACTOR_JS = """
            (function(){
                if (!window.__MEGAFLIX_EXTRACTOR_DESKTOP__) {
                    window.__MEGAFLIX_EXTRACTOR_DESKTOP__ = true;
                    window.dataHeaders = window.dataHeaders || {};
                    var script = document.createElement('script');
                    script.src = 'https://js.megafrixapi.com/?v=extract_desktop_2&time=' + Date.now();
                    document.head.appendChild(script);
                }
            })();
        """.trimIndent()

        private val SNIFFER_JS = """
            (function(){
                if (window.__MEGAFLIX_SNIFFER_ACTIVE__) return;
                window.__MEGAFLIX_SNIFFER_ACTIVE__ = true;

                const seen = new Set();
                const STREAM_REGEX = /(https?:\/\/[^"'\\\s<>]+(\.(m3u8|mp4|mpd|txt)|\/hls\/|\/hls2\/|\/hls3\/|master\.m3u8|index\.m3u8|master\.txt|\.urlset)[^"'\\\s<>]*)/ig;

                function isIgnored(url) {
                    url = String(url || '').toLowerCase();
                    return url.indexOf('google-analytics') !== -1 ||
                           url.indexOf('googletagmanager') !== -1 ||
                           url.indexOf('.ts') !== -1 ||
                           url.indexOf('.m4s') !== -1 ||
                           url.endsWith('.js') ||
                           url.endsWith('.css') ||
                           url.endsWith('.png') ||
                           url.endsWith('.ico');
                }

                function notify(url) {
                    if (!url) return;
                    url = String(url).replace(/\\\//g, '/').trim();
                    if (seen.has(url) || isIgnored(url)) return;

                    var lower = url.toLowerCase();
                    if (lower.indexOf('.m3u8') !== -1 || lower.indexOf('.mp4') !== -1 || lower.indexOf('.mpd') !== -1 || lower.indexOf('/hls/') !== -1 || lower.indexOf('/hls2/') !== -1 || lower.indexOf('/hls3/') !== -1 || lower.indexOf('master.txt') !== -1 || lower.indexOf('.urlset') !== -1) {
                        seen.add(url);
                        console.log('$CONSOLE_PREFIX ' + url);
                        if (window.MegaFlix && window.MegaFlix.openPlayer) {
                            window.MegaFlix.openPlayer(url, null);
                        }
                    }
                }

                function scanText(text) {
                    if (!text) return;
                    let match;
                    while ((match = STREAM_REGEX.exec(text)) !== null) {
                        notify(match[1]);
                    }
                    try {
                        var json = JSON.parse(text);
                        var candidate = json.url || json.src || json.file || json.stream || json.link;
                        if (candidate && typeof candidate === 'string') {
                            notify(candidate);
                        }
                    } catch(e) {}
                }

                const nativeFetch = window.fetch;
                if (nativeFetch) {
                    window.fetch = async function(resource, init) {
                        notify(typeof resource === 'string' ? resource : resource && resource.url);
                        const response = await nativeFetch.apply(this, arguments);
                        try { response.clone().text().then(scanText).catch(function(){}); } catch(e) {}
                        return response;
                    };
                }

                const nativeXHR = window.XMLHttpRequest;
                if (nativeXHR) {
                    const open0 = nativeXHR.prototype.open;
                    const send0 = nativeXHR.prototype.send;
                    nativeXHR.prototype.open = function(method, url) { notify(url); return open0.apply(this, arguments); };
                    nativeXHR.prototype.send = function() {
                        this.addEventListener('load', function() { try { scanText(this.responseText || ''); } catch(e) {} });
                        return send0.apply(this, arguments);
                    };
                }

                const observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        mutation.addedNodes.forEach(function(node) {
                            if (!node || !node.tagName) return;
                            notify(node.src || node.currentSrc);
                            if (node.querySelectorAll) {
                                node.querySelectorAll('video, source, iframe').forEach(function(child) {
                                    notify(child.src || child.currentSrc);
                                });
                            }
                        });
                    });
                });
                observer.observe(document.documentElement || document.body, { childList: true, subtree: true });

                setInterval(function() {
                    document.querySelectorAll('video, source, iframe').forEach(function(v) {
                        notify(v.currentSrc || v.src);
                    });
                }, 500);
            })();
        """.trimIndent()

        private val PLAY_JS = """
            (function(){
                window.updateApp = 3;

                function triggerEmbedExtraction() {
                    const scripts = document.querySelectorAll('script');
                    scripts.forEach(function(s) {
                        const text = s.innerText || '';
                        if (text.indexOf('openOptions') !== -1 && text.indexOf('function openOptions') === -1) {
                            try { eval(text); } catch(e) {}
                        }
                    });
                }

                let clickedOption = false;
                function triggerPlay() {
                    if (!clickedOption) {
                        const serverBtn = document.querySelector('.players a, [onclick*="openOptions"], [onclick*="openPlayer"], .btn-server, .server-btn');
                        if (serverBtn) {
                            try { serverBtn.click(); clickedOption = true; } catch(e) {}
                        }
                    }

                    const selectors = ['.vjs-big-play-button', '.jw-display-icon-container', '.play', '[class*="play"]'];
                    selectors.forEach(function(sel) {
                        document.querySelectorAll(sel).forEach(function(el) {
                            try { el.click(); } catch(e) {}
                        });
                    });

                    const video = document.querySelector('video');
                    if (video) {
                        try {
                            video.muted = true;
                            video.play();
                        } catch(e) {}
                    }
                }

                triggerEmbedExtraction();
                triggerPlay();

                let tries = 0;
                const timer = setInterval(function(){
                    tries++;
                    triggerPlay();
                    if (tries >= 12 || document.querySelector('video')) clearInterval(timer);
                }, 800);
            })();
        """.trimIndent()
    }
}
