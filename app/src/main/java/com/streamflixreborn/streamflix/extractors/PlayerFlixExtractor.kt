package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
import org.jsoup.Jsoup
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PlayerFlixExtractor : Extractor() {
    override val name = "PlayerFlix"
    override val mainUrl = SUPERFLIX_URL
    override val aliasUrls = listOf(
        PLAYERFLIX_URL,
        LEGACY_SUPERFLIX_URL,
        "https://embedplayer2.xyz",
        "https://xn--kcksk7a2bl5le7b6doc1h3f.com",
    )

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun servers(videoType: Video.Type): List<Video.Server> = runCatching {
        val json = JSONObject(get(apiUrl(videoType), playerPageUrl(videoType)))
        if (!json.optBoolean("status", false)) return@runCatching emptyList()

        val options = json.optJSONObject("data")?.optJSONArray("options")
            ?: return@runCatching emptyList()

        buildList {
            for (index in 0 until options.length()) {
                val option = options.optJSONObject(index) ?: continue
                val embedUrl = normalizePlayerUrl(option.optString("embed"))
                if (!embedUrl.startsWith("http")) continue

                // O ClickHost possui um extrator dedicado que usa as rotas
                // documentadas /embed/filme e /embed/serie e expande todos os
                // servidores internos. Evita duplicar aqui uma opcao que seria
                // resolvida pelo antigo POST /api, removido pelo provedor.
                if (embedUrl.contains("embed-api.clickhost.xyz", ignoreCase = true)) continue

                val audio = option.optString("lang").trim().lowercase().ifBlank { "pt-br" }
                val language = when (audio) {
                    "pt", "pt-br", "dub", "dublado" -> "Dublado PT-BR"
                    "en", "en-us", "leg", "legendado" -> "Legendado"
                    else -> audio.uppercase()
                }
                val playerName = option.optString("label").trim().ifBlank {
                    embedUrl.substringAfter("://").substringBefore("/")
                }

                add(
                    Video.Server(
                        id = "playerflix-$audio-$index-${embedUrl.hashCode()}",
                        name = "PlayerFlix • $language • $playerName",
                        src = embedUrl,
                    )
                )
            }
        }.distinctBy { it.src }
            .sortedWith(compareBy<Video.Server> { playerFlixPriority(it.name) }.thenBy { it.name.lowercase() })
    }.onFailure {
        Log.w(TAG, "Falha ao buscar players PlayerFlix: ${it.message}")
    }.getOrElse { emptyList() }

    private fun playerFlixPriority(name: String): Int {
        val normalized = name.lowercase()
        return when {
            "vip" in normalized -> 0
            "watchplay" in normalized -> 1
            "embed" in normalized -> 2
            "premium" in normalized -> 3
            else -> 4
        }
    }

    fun server(videoType: Video.Type): Video.Server {
        val url = playerPageUrl(videoType)
        return Video.Server(
            id = url,
            name = "PlayerFlix Dublado",
            src = url,
        )
    }

    override suspend fun extract(link: String): Video {
        val normalizedLink = normalizePlayerUrl(link)

        // Cada agregador usa seu resolvedor próprio. Isso evita depender do
        // JavaScript do PlayerFlix e melhora a compatibilidade com TV Box.
        when {
            normalizedLink.contains("watchplay.shop", ignoreCase = true) ||
                normalizedLink.contains("watchplayer.net", ignoreCase = true) ->
                return WatchPlayExtractor().extract(normalizedLink)

            normalizedLink.contains("embedplayabyss", ignoreCase = true) ->
                return EmbedPlayApiExtractor().extract(normalizedLink)
        }

        val stream = resolveDirectStream(normalizedLink)
            ?: DirectStream(extractWithWebView(normalizedLink), MimeTypes.APPLICATION_M3U8)
        return Video(
            source = stream.url,
            subtitles = emptyList(),
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to refererFor(normalizedLink),
                "Origin" to originFor(normalizedLink),
                "Accept" to "application/vnd.apple.mpegurl,application/x-mpegurl,video/mp2t,*/*",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            ),
            type = stream.mimeType,
            extraBuffering = true,
        )
    }

    private suspend fun get(url: String, referer: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private fun playerPageUrl(videoType: Video.Type): String = when (videoType) {
        is Video.Type.Movie -> "$PLAYERFLIX_URL/filme/${videoType.id}"
        is Video.Type.Episode -> "$PLAYERFLIX_URL/serie/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
    }

    private fun apiUrl(videoType: Video.Type): String = when (videoType) {
        is Video.Type.Movie ->
            "$PLAYERFLIX_URL/inc/Ajax.php?type=movie&id=${videoType.id}&season=1&episode=1"
        is Video.Type.Episode ->
            "$PLAYERFLIX_URL/inc/Ajax.php?type=tv&id=${videoType.tvShow.id}" +
                "&season=${videoType.season.number}&episode=${videoType.number}"
    }

    private fun decodeBase64(value: String): String? = runCatching {
        String(Base64.decode(value.trim(), Base64.DEFAULT), Charsets.UTF_8).takeIf { it.startsWith("http") }
    }.getOrNull()

    private fun normalizePlayerUrl(value: String): String {
        val normalized = normalizeUrl(value)
        return normalized
            .replace(LEGACY_SUPERFLIX_URL, SUPERFLIX_URL, ignoreCase = true)
            .replace("http://superflixapi.boats", SUPERFLIX_URL, ignoreCase = true)
            .replace("https://v1.watchplay.shop", "https://v2.watchplay.shop", ignoreCase = true)
    }

    private suspend fun resolveDirectStream(link: String): DirectStream? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                link.contains("https://embed-api.clickhost.xyz") -> extractWatchPlayerDirect(link)
                link.contains("embedplayer2.xyz") || link.contains("xn--kcksk7a2bl5le7b6doc1h3f.com") -> extractFirePlayerDirect(link)
                else -> null
            }
        }.onFailure {
            Log.w(TAG, "Falha na extracao direta PlayerFlix: ${it.message}")
        }.getOrNull()
    }

    private fun extractWatchPlayerDirect(link: String): DirectStream? {
        val html = getBlocking(link, refererFor(link))
        val doc = Jsoup.parse(html, link)
        val videoId = doc.selectFirst(".player_select_item[data-id]")?.attr("data-id")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val body = FormBody.Builder()
            .add("action", "getPlayer")
            .add("video_id", videoId)
            .build()
        val response = postBlocking("https://embed-api.clickhost.xyz/api", body, link)
        val json = JSONObject(response)
        val data = json.optJSONObject("data")
        val videoUrl = normalizeUrl(
            data?.optString("video_url").orEmpty().ifBlank { json.optString("video_url") }
        ).takeIf { it.isNotBlank() } ?: return null

        return when {
            videoUrl.contains(M3U8_EXTENSION, ignoreCase = true) || videoUrl.contains("/hls/", ignoreCase = true) -> DirectStream(videoUrl, MimeTypes.APPLICATION_M3U8)
            videoUrl.contains(".mp4", ignoreCase = true) -> DirectStream(videoUrl, MimeTypes.VIDEO_MP4)
            else -> null
        }
    }

    private fun extractFirePlayerDirect(link: String): DirectStream? {
        val videoId = link.substringBefore("?").trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: return null
        val origin = originFor(link)
        val body = FormBody.Builder()
            .add("hash", videoId)
            .add("r", PLAYERFLIX_URL)
            .build()
        val jsonText = postBlocking("$origin/player/index.php?data=$videoId&do=getVideo", body, link)
        val json = JSONObject(jsonText)
        val source = normalizeUrl(
            json.optString("securedLink").ifBlank { json.optString("videoSource") }
        ).takeIf { it.isNotBlank() } ?: return null

        return DirectStream(
            url = source,
            mimeType = if (source.contains(".mp4", ignoreCase = true)) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8,
        )
    }

    private fun getBlocking(url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun postBlocking(url: String, body: FormBody, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Origin", originFor(referer))
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun normalizeUrl(value: String): String = value.trim().replace("\\/", "/")

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractWithWebView(playerUrl: String): String = mutex.withLock {
        withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                var webView: WebView? = null
                var isResumed = false

                fun cleanup() {
                    mainHandler.post {
                        try {
                            webView?.stopLoading()
                            webView?.loadUrl("about:blank")
                            webView?.destroy()
                        } catch (_: Exception) {
                        }
                        webView = null
                    }
                }

                fun safeResume(result: Result<String>) {
                    if (!isResumed && continuation.isActive) {
                        isResumed = true
                        result.fold(
                            onSuccess = { continuation.resume(it) },
                            onFailure = { continuation.resumeWithException(it) },
                        )
                        cleanup()
                    }
                }

                fun capture(url: String?) {
                    val value = url.orEmpty().replace("\\/", "/")
                    if (value.contains(M3U8_EXTENSION, ignoreCase = true)) {
                        Log.i(TAG, "M3U8 detectado: $value")
                        safeResume(Result.success(value))
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
                                userAgentString = USER_AGENT
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                mediaPlaybackRequiresUserGesture = false
                                cacheMode = WebSettings.LOAD_NO_CACHE
                                setSupportMultipleWindows(true)
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                                    message?.message()
                                        ?.substringAfter(CONSOLE_PREFIX, "")
                                        ?.trim()
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let(::capture)
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onLoadResource(view: WebView?, url: String?) {
                                    capture(url)
                                    super.onLoadResource(view, url)
                                }

                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    capture(request?.url?.toString())
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    view?.evaluateJavascript(SNIFFER_JS, null)
                                    view?.evaluateJavascript(PLAY_JS, null)
                                }
                            }
                            loadUrl(playerUrl, mapOf("Referer" to refererFor(playerUrl), "User-Agent" to USER_AGENT))
                        }
                    } catch (e: Exception) {
                        safeResume(Result.failure(e))
                    }
                }

                mainHandler.postDelayed({
                    safeResume(Result.failure(Exception("Timeout PlayerFlix: m3u8 nao encontrado")))
                }, TIMEOUT_MS)

                continuation.invokeOnCancellation { cleanup() }
            }
        } ?: throw Exception("Timeout PlayerFlix: operacao excedeu ${TIMEOUT_MS}ms")
    }

    private fun refererFor(url: String): String = when {
        url.contains("superflixapi.boats") -> "$SUPERFLIX_URL/"
        url.contains("embed-api.clickhost.xyz") -> "$PLAYERFLIX_URL/"
        url.contains("embedplayer2.xyz") -> "$PLAYERFLIX_URL/"
        url.contains("xn--kcksk7a2bl5le7b6doc1h3f.com") -> "$PLAYERFLIX_URL/"
        else -> "$PLAYERFLIX_URL/"
    }

    private fun originFor(url: String): String = when {
        url.contains("superflixapi.boats") -> SUPERFLIX_URL
        url.contains("embed-api.clickhost.xyz") -> "https://embed-api.clickhost.xyz"
        url.contains("embedplayer2.xyz") -> "https://embedplayer2.xyz"
        url.contains("xn--kcksk7a2bl5le7b6doc1h3f.com") -> "https://xn--kcksk7a2bl5le7b6doc1h3f.com"
        else -> PLAYERFLIX_URL
    }

    private data class DirectStream(val url: String, val mimeType: String)

    companion object {
        private const val TAG = "PlayerFlixExtractor"
        private const val TIMEOUT_MS = 35000L
        private const val M3U8_EXTENSION = ".m3u8"
        private const val CONSOLE_PREFIX = "PlayerFlix m3u8:"
        private const val PLAYERFLIX_URL = "https://playerflix.ink"
        private const val SUPERFLIX_URL = "https://superflixapi.boats"
        private const val LEGACY_SUPERFLIX_URL = "https://superflixapi.fun"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private val SNIFFER_JS = """
            (function(){
              if (window.__PLAYERFLIX_EXTRACTOR_INJECTED__) return;
              window.__PLAYERFLIX_EXTRACTOR_INJECTED__ = true;

              const seen = new Set();
              const m3u8Regex = /(https?:\/\/[^"'\\\s<>]+\.m3u8[^"'\\\s<>]*)/ig;
              function normalize(url){ return String(url || '').replace(/\\\//g, '/'); }
              function notify(url){
                url = normalize(url);
                if (!url || seen.has(url)) return;
                seen.add(url);
                if (url.indexOf('.m3u8') !== -1) console.log('$CONSOLE_PREFIX ' + url);
              }
              function scanText(text){
                text = normalize(text);
                let match;
                while ((match = m3u8Regex.exec(text)) !== null) notify(match[1]);
              }
              function scanObject(value){ try { scanText(JSON.stringify(value)); } catch(e) {} }

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

              function checkMediaElement(el){
                if (!el) return;
                notify(el.currentSrc || el.src);
                if (el.querySelectorAll) el.querySelectorAll('source,track').forEach(function(source){ notify(source.currentSrc || source.src); });
              }
              function checkGlobals(){
                ['sources','videoSources','playerConfig','playerData','streamData','videoData','jwplayer','clappr','videojs','plyr','fluidPlayer'].forEach(function(name){
                  if (window[name] !== undefined) scanObject(window[name]);
                });
              }

              const observer = new MutationObserver(function(mutations){
                mutations.forEach(function(mutation){
                  mutation.addedNodes.forEach(function(node){
                    if (!node || !node.tagName) return;
                    checkMediaElement(node);
                    notify(node.src);
                    if (node.querySelectorAll) node.querySelectorAll('video,source,iframe,script').forEach(function(child){ checkMediaElement(child); notify(child.src); });
                  });
                });
              });
              observer.observe(document.documentElement || document.body, { childList: true, subtree: true });

              document.querySelectorAll('video,source,iframe,script').forEach(function(el){ checkMediaElement(el); notify(el.src); });
              checkGlobals();
              setTimeout(checkGlobals, 1000);
              setTimeout(checkGlobals, 3000);
              setInterval(function(){ document.querySelectorAll('video,source,iframe').forEach(checkMediaElement); checkGlobals(); }, 500);
            })();
        """.trimIndent()

        private val PLAY_JS = """
            (function(){
              function clickFirst(selector){
                const el = document.querySelector(selector);
                if (!el) return false;
                try { el.click(); return true; } catch(e) { return false; }
              }

              function startPlayer(){
                if (typeof loadFirstPlayerOption === 'function') {
                  try { loadFirstPlayerOption(); } catch(e) {}
                }

                clickFirst('.player_select_item[data-id]') ||
                clickFirst('.player-option[data-embed]') ||
                clickFirst('.player-option') ||
                clickFirst('.option') ||
                clickFirst('[data-embed]') ||
                clickFirst('[data-id]');

                document.querySelectorAll('button,.play,.vjs-big-play-button,[class*="play"],[id*="play"],video').forEach(function(el){
                  try { if (el.tagName === 'VIDEO') { el.muted = true; el.play(); } else { el.click(); } } catch(e) {}
                });
              }

              startPlayer();
              let tries = 0;
              const timer = setInterval(function(){
                tries++;
                startPlayer();
                if (tries >= 12 || document.querySelector('video')) clearInterval(timer);
              }, 500);
            })();
        """.trimIndent()
    }
}




