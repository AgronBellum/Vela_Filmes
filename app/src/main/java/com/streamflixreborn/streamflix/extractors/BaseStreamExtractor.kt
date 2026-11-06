package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

abstract class BaseStreamExtractor : Extractor() {

    abstract override val name: String
    abstract override val mainUrl: String
    override val aliasUrls: List<String> = emptyList()   
    
    open val allowedDomains: List<String> = emptyList()
    open val timeoutMs: Long = 35000L
    open val userAgent: String = DEFAULT_USER_AGENT

    protected val okHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun extract(link: String): Video = extract(link, null)

    override suspend fun extract(link: String, server: Video.Server?): Video {
        val targetUrl = server?.src ?: server?.id ?: link

        val streamUrl = runCatching {
            extractViaHttp(targetUrl)
        }.getOrNull()?.takeIf { isMainStream(it) } ?: run {
            Log.w(TAG, "[$name] Plano A (HTTP) falhou. Iniciando Plano B (WebView)...")
            extractWithWebView(targetUrl)
        }

        return Video(
            source = streamUrl,
            subtitles = emptyList(),
            headers = buildHeaders(streamUrl, targetUrl),
            type = resolveMimeType(streamUrl),
            extraBuffering = true
        )
    }

    /**
     * PLANO A: Sobrescreva nas classes filhas para extrair via HTTP direto sem abrir WebView.
     */
    open suspend fun extractViaHttp(targetUrl: String): String? = null

    /**
     * Helper Reutilizável: Extrai mídia via API EmbedPlayer2 / FirePlayer via requisição POST.
     */
    protected open suspend fun extractEmbedPlayer2Direct(hash: String, refererUrl: String = mainUrl): String? = withContext(Dispatchers.IO) {
        val origins = listOf(
            mainUrl,
            "https://embedplayer2.xyz",
            "https://xn--kcksk7a2bl5le7b6doc1h3f.com",
            "https://embedplay.one",
            "https://playerwish.com",
            "https://embed-api.clickhost.xyz",
            "https://megafrixapi.com",
            "https://playerwish.com"

        )

        for (origin in origins) {
            try {
                val body = FormBody.Builder()
                    .add("hash", hash)
                    .add("r", "$mainUrl/")
                    .build()

                val request = Request.Builder()
                    .url("$origin/player/index.php?data=$hash&do=getVideo")
                    .post(body)
                    .header("User-Agent", userAgent)
                    .header("Referer", refererUrl)
                    .header("Origin", origin)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val jsonText = response.body?.string().orEmpty()

                if (jsonText.startsWith("{")) {
                    val json = JSONObject(jsonText)
                    val source = json.optString("securedLink").ifBlank { json.optString("videoSource") }
                    if (source.isNotBlank()) {
                        return@withContext source.replace("\\/", "/")
                    }
                }
            } catch (_: Exception) {}
        }
        null
    }

    /**
     * Validação genérica de stream: Filtra pings/lixo e aceita HLS, MP4, MPD ou URLs de CDN.
     */
    open fun isMainStream(url: String): Boolean {
        if (url.isBlank()) return false
        val cleanUrl = url.replace("\\/", "/").trim()
        val lower = cleanUrl.lowercase()

        // 1. Rejeição de lixo estático e pings de telemetria
        if (lower.contains("ajax/played") ||
            lower.contains("cdn-cgi") ||
            lower.contains("challenge-platform") ||
            lower.contains("cloudflareinsights") ||
            lower.contains("disable-devtool") ||
            lower.contains("vidstats.top") ||
            lower.contains("google-analytics") ||
            lower.contains("doubleclick") ||
            lower.endsWith(".js") || lower.endsWith(".css") ||
            lower.endsWith(".ico") || lower.endsWith(".png") ||
            lower.endsWith(".jpg") || lower.endsWith(".woff")
        ) return false

        // 2. Rejeição de fragmentos isolados de vídeo (.ts, .m4s)
        if (lower.endsWith(".ts") || lower.endsWith(".m4s") || lower.contains("/segment/") || lower.contains("/chunk/")) {
            return false
        }

        // 3. Aceitação de extensões conhecidas de mídia
        val hasMediaExtension = lower.contains(".m3u8") || lower.contains(".mp4") ||
                lower.contains(".mpd") || lower.contains("/hls/") ||
                lower.contains("master.m3u8") || lower.contains("index.m3u8")

        if (hasMediaExtension) return true

        // 4. Aceitação de URLs de CDN dinâmicas sem extensão (com hash ou token)
        val pathSegments = runCatching { URI(cleanUrl).path.split("/") }.getOrNull() ?: emptyList()
        val hasDeepPath = pathSegments.size >= 3 && pathSegments.last().length > 20

        return lower.contains("token=") || lower.contains("expires=") || hasDeepPath
    }

    /**
     * Monta os cabeçalhos HTTP necessários para o ExoPlayer reproduzir a stream sem erro 403 Forbidden.
     */
    open fun buildHeaders(streamUrl: String, targetUrl: String): Map<String, String> {
        val referer = when {
            "sssrr.org" in streamUrl || "abysscdn" in streamUrl -> "https://abysscdn.com/"
            "luluvdo" in streamUrl -> "https://luluvdo.com/"
            "filemoon" in streamUrl -> "https://filemoon.sx/"
            else -> "$mainUrl/"
        }

        return mapOf(
            "User-Agent" to userAgent,
            "Referer" to referer,
            "Origin" to referer.trimEnd('/'),
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
        )
    }

    open fun resolveMimeType(url: String): String = when {
        url.contains(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
        url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
        else -> MimeTypes.APPLICATION_M3U8
    }

    protected suspend fun getHtml(url: String, referer: String = mainUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    open suspend fun extractWithWebView(playerUrl: String): String = mutex.withLock {
        withTimeoutOrNull(timeoutMs) {
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

                fun safeResume(result: Result<String>) {
                    if (!isResumed && continuation.isActive) {
                        isResumed = true
                        result.fold(
                            onSuccess = { continuation.resume(it) },
                            onFailure = { continuation.resumeWithException(it) }
                        )
                        cleanup()
                    }
                }

                fun capture(url: String?) {
                    val candidate = url.orEmpty().replace("\\/", "/")
                    if (isMainStream(candidate)) {
                        Log.i(TAG, "[$name] Stream capturada via WebView: $candidate")
                        safeResume(Result.success(candidate))
                    }
                }

                mainHandler.post {
                    try {
                        webView = WebView(StreamFlixApp.instance.applicationContext).apply {
                            setBackgroundColor(Color.TRANSPARENT)
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                userAgentString = userAgent
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                mediaPlaybackRequiresUserGesture = false
                                cacheMode = WebSettings.LOAD_NO_CACHE
                                setSupportMultipleWindows(false)
                                javaScriptCanOpenWindowsAutomatically = false
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onCreateWindow(v: WebView?, isDialog: Boolean, isGesture: Boolean, msg: android.os.Message?) = false

                                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                                    val text = message?.message().orEmpty()
                                    if (text.contains(CONSOLE_PREFIX)) {
                                        capture(text.substringAfter(CONSOLE_PREFIX).trim())
                                    }
                                    return true
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val url = request?.url?.toString().orEmpty().lowercase()
                                    if (allowedDomains.isNotEmpty() && allowedDomains.none { url.contains(it) }) {
                                        Log.w(TAG, "[$name] Anúncio/Redirecionamento bloqueado: $url")
                                        return true
                                    }
                                    return false
                                }

                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    capture(request?.url?.toString())
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onLoadResource(view: WebView?, url: String?) {
                                    capture(url)
                                    super.onLoadResource(view, url)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    view?.evaluateJavascript(ANTI_POPUP_JS, null)
                                    view?.evaluateJavascript(GENERIC_SNIFFER_JS, null)
                                    view?.evaluateJavascript(GENERIC_PLAY_JS, null)
                                }
                            }

                            loadUrl(playerUrl, mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent))
                        }
                    } catch (e: Exception) {
                        safeResume(Result.failure(e))
                    }
                }

                mainHandler.postDelayed({
                    safeResume(Result.failure(Exception("[$name] Timeout: Nenhuma stream encontrada após ${timeoutMs}ms")))
                }, timeoutMs)

                continuation.invokeOnCancellation { cleanup() }
            }
        } ?: throw Exception("[$name] Operação excedeu ${timeoutMs}ms")
    }

    companion object {
        private const val TAG = "BaseStreamExtractor"
        private const val CONSOLE_PREFIX = "STREAM_FOUND:"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        private val ANTI_POPUP_JS = """
            (function(){
                window.open = function() { return null; };
                window.alert = function() {};
                window.confirm = function() { return true; };
            })();
        """.trimIndent()

        private val GENERIC_SNIFFER_JS = """
            (function(){
                if (window.__GENERIC_SNIFFER_INJECTED__) return;
                window.__GENERIC_SNIFFER_INJECTED__ = true;

                const seen = new Set();
                function normalize(url){ return String(url || '').replace(/\\\//g, '/'); }

                function notify(url){
                    url = normalize(url);
                    if (!url || seen.has(url)) return;
                    var lower = url.toLowerCase();
                    if (lower.indexOf('.m3u8') !== -1 || lower.indexOf('.mp4') !== -1 || lower.indexOf('.mpd') !== -1 || lower.indexOf('/hls/') !== -1) {
                        seen.add(url);
                        console.log('$CONSOLE_PREFIX ' + url);
                    }
                }

                function scanText(text){
                    text = normalize(text);
                    const regex = /(https?:\/\/[^"'\\\s<>]+(\.(m3u8|mp4|mpd)|\/hls\/)[^"'\\\s<>]*)/ig;
                    let match;
                    while ((match = regex.exec(text)) !== null) notify(match[1]);
                }

                const fetch0 = window.fetch;
                if (fetch0) {
                    window.fetch = async function(resource, init){
                        notify(typeof resource === 'string' ? resource : resource && resource.url);
                        const response = await fetch0.apply(this, arguments);
                        try { response.clone().text().then(scanText).catch(function(){}); } catch(e) {}
                        return response;
                    };
                }

                const XHR0 = window.XMLHttpRequest;
                if (XHR0) {
                    const open0 = XHR0.prototype.open;
                    const send0 = XHR0.prototype.send;
                    XHR0.prototype.open = function(method, url){ notify(url); return open0.apply(this, arguments); };
                    XHR0.prototype.send = function(){
                        this.addEventListener('load', function(){ try { scanText(this.responseText || ''); } catch(e) {} });
                        return send0.apply(this, arguments);
                    };
                }

                function checkGlobals() {
                    if (window.jwplayer && typeof window.jwplayer === 'function') {
                        ['player', 'jwplayer', 've-player'].forEach(function(id){
                            try {
                                var playlist = window.jwplayer(id).getPlaylist();
                                if (playlist) scanText(JSON.stringify(playlist));
                            } catch(e){}
                        });
                    }
                }

                setInterval(checkGlobals, 1000);
            })();
        """.trimIndent()

        private val GENERIC_PLAY_JS = """
            (function(){
                function play() {
                    const playBtn = document.querySelector('.vjs-big-play-button, .jw-display-icon-container, .play, [class*="play"]');
                    if (playBtn) try { playBtn.click(); } catch(e){}

                    const video = document.querySelector('video');
                    if (video) try { video.muted = true; video.play(); } catch(e){}
                }
                play();
                let tries = 0;
                const timer = setInterval(function(){
                    tries++;
                    play();
                    if (tries >= 10 || document.querySelector('video')) clearInterval(timer);
                }, 800);
            })();
        """.trimIndent()
    }
}