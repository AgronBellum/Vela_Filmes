package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SuperCine Extractor - Fembed/SprintCDN
 * Usa WebView leve para executar o JS do Fembed e interceptar o m3u8
 */
class SuperCineExtractor : Extractor() {

    companion object {
        private const val TAG = "SuperCine"
        private const val TIMEOUT_MS = 30000L // 30 segundos

        private const val CDN_MARKER = "r66nv9ed"
        private const val M3U8_EXTENSION = ".m3u8"

        private val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    override val name = "SuperCine"
    override val mainUrl = "https://fembed.sx"
    override val aliasUrls = emptyList<String>()

    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ==================== SERVERS ====================

    fun server(videoType: Video.Type): Video.Server = buildServer(videoType)

    fun server(videoType: Video.Type, language: String?): Video.Server? = buildServer(videoType)

    fun servers(videoType: Video.Type): List<Video.Server> = listOf(buildServer(videoType))

    fun servers(videoType: Video.Type, language: String?): List<Video.Server> = listOf(buildServer(videoType))

    private fun buildServer(videoType: Video.Type): Video.Server {
        val embedUrl = when (videoType) {
            is Video.Type.Movie -> {
                val tmdbId = videoType.id
                "$mainUrl/e/$tmdbId-dub"  // /v/ é o embed correto do Fembed
            }
            is Video.Type.Episode -> {
                val tmdbId = videoType.tvShow.id
                "$mainUrl/e/$tmdbId-dub/${videoType.season.number}-${videoType.number}"
            }
        }
        return Video.Server(id = embedUrl, name = "Dublado (Fembed)", src = embedUrl)
    }

    // ==================== EXTRACT ====================

    override suspend fun extract(link: String): Video {
        Log.i(TAG, "Extract: $link")
        val streamUrl = extractWithWebView(link)
        Log.i(TAG, "Stream: $streamUrl")

        return Video(
            source = streamUrl,
            subtitles = emptyList(),
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
                "Accept" to "application/vnd.apple.mpegurl,application/x-mpegurl,video/mp2t,*/*",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
            ),
            type = MimeTypes.APPLICATION_M3U8
        )
    }

    // ==================== WEBVIEW ====================

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractWithWebView(embedUrl: String): String = mutex.withLock {
        withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                var webView: WebView? = null
                var isResumed = false

                fun safeResume(result: Result<String>) {
                    if (!isResumed && continuation.isActive) {
                        isResumed = true
                        result.fold(
                            onSuccess = { continuation.resume(it) },
                            onFailure = { continuation.resumeWithException(it) }
                        )
                    }
                }

                fun cleanup() {
                    mainHandler.post {
                        try {
                            webView?.stopLoading()
                            webView?.loadUrl("about:blank")
                            webView?.destroy()
                        } catch (e: Exception) {
                            Log.w(TAG, "Erro ao destruir WebView", e)
                        }
                        webView = null
                    }
                }

                mainHandler.post {
                    try {
                        val ctx = StreamFlixApp.instance.applicationContext

                        webView = WebView(ctx).apply {
                            setBackgroundColor(Color.TRANSPARENT)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                userAgentString = USER_AGENT
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                mediaPlaybackRequiresUserGesture = false
                                cacheMode = WebSettings.LOAD_NO_CACHE
                                setSupportMultipleWindows(false)
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                                    message?.let {
                                        val msg = it.message()
                                        if (msg.contains("Sniffer M3U8:") && msg.contains(CDN_MARKER)) {
                                            val url = msg.substringAfter("Sniffer M3U8:").trim()
                                            if (url.isNotBlank() && !isResumed) {
                                                Log.i(TAG, "M3U8 via console: $url")
                                                safeResume(Result.success(url))
                                                cleanup()
                                            }
                                        }
                                    }
                                    return true
                                }
                            }

                            webViewClient = object : WebViewClient() {

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val url = request?.url?.toString()
                                        ?: return super.shouldInterceptRequest(view, request)

                                    // Detecta qualquer m3u8 do sprintcdn
                                    if (url.contains(CDN_MARKER, ignoreCase = true) &&
                                        url.contains(M3U8_EXTENSION, ignoreCase = true)) {

                                        Log.i(TAG, "M3U8 detectado: $url")

                                        if (!isResumed) {
                                            safeResume(Result.success(url))
                                            cleanup()
                                        }
                                    }

                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    Log.d(TAG, "Page finished: $url")

                                    // Injeta JS sniffer
                                    view?.evaluateJavascript(getSnifferJS(), null)

                                    // Força play e clica em botões de play
                                    view?.evaluateJavascript(
                                        """
                                        (function(){
                                            var v = document.querySelector('video');
                                            if(v) { 
                                                v.muted = true;
                                                v.play().catch(function(e){ console.log('Play error:', e); }); 
                                            }
                                            var btns = document.querySelectorAll('button, .play, [class*="play"], [id*="play"], .vjs-big-play-button, [data-testid="play-button"]');
                                            for(var i=0; i<btns.length; i++) {
                                                try { btns[i].click(); } catch(e) {}
                                            }
                                            // Tenta iniciar via API do player se existir
                                            if(typeof player !== 'undefined' && player.play) {
                                                try { player.play(); } catch(e) {}
                                            }
                                        })();
                                        """.trimIndent(),
                                        null
                                    )
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                    Log.e(TAG, "WebView error: ${error?.description} - ${request?.url}")
                                }
                            }

                            loadUrl(embedUrl)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao criar WebView", e)
                        safeResume(Result.failure(e))
                    }
                }

                // Timeout handler
                mainHandler.postDelayed({
                    if (!isResumed) {
                        Log.w(TAG, "Timeout: não achou m3u8 em ${TIMEOUT_MS}ms")
                        safeResume(Result.failure(
                            Exception("Timeout: não achou m3u8 em ${TIMEOUT_MS}ms")
                        ))
                        cleanup()
                    }
                }, TIMEOUT_MS)

                continuation.invokeOnCancellation {
                    Log.d(TAG, "Corrotina cancelada")
                    cleanup()
                }
            }
        } ?: throw Exception("Timeout WebView: operação excedeu ${TIMEOUT_MS}ms")
    }

    // ==================== JS SNIFFER ====================

    private fun getSnifferJS(): String {
        return """
        (function(){
            console.log('Sniffer iniciado');
            const sent = new Set();
            const CDN_MARKER = '$CDN_MARKER';
            const M3U8_EXT = '$M3U8_EXTENSION';

            function notify(url){
                if(!url || typeof url !== 'string') return;
                if(sent.has(url)) return;
                
                if(url.includes(CDN_MARKER) && url.includes(M3U8_EXT)) {
                    console.log('Sniffer M3U8:', url);
                    sent.add(url);
                }
            }

            // Monitora videos existentes e futuros
            const observer = new MutationObserver((mutations) => {
                document.querySelectorAll('video').forEach(v => {
                    notify(v.currentSrc || v.src);
                    if(v.src) notify(v.src);
                });
            });
            
            observer.observe(document.body || document.documentElement, { 
                childList: true, 
                subtree: true 
            });

            // Checagem periódica
            setInterval(() => {
                document.querySelectorAll('video').forEach(v => {
                    notify(v.currentSrc || v.src);
                    if(v.src) notify(v.src);
                });
            }, 500);

            // Intercepta fetch
            const origFetch = window.fetch;
            window.fetch = async function(...args) {
                if(args[0]) notify(args[0].toString());
                return origFetch.apply(this, args);
            };

            // Intercepta XHR
            const origXHR = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                notify(url);
                return origXHR.call(this, method, url);
            };

            // Intercepta WebSocket
            const origWS = window.WebSocket;
            window.WebSocket = function(url, protocols) {
                notify(url);
                return new origWS(url, protocols);
            };
            
            // Intercepta createElement('video')
            const origCreateElement = document.createElement;
            document.createElement = function(tagName) {
                const el = origCreateElement.call(document, tagName);
                if(tagName.toLowerCase() === 'video') {
                    const origSrcSetter = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                    if(origSrcSetter && origSrcSetter.set) {
                        Object.defineProperty(el, 'src', {
                            set: function(value) {
                                notify(value);
                                origSrcSetter.set.call(this, value);
                            },
                            get: origSrcSetter.get
                        });
                    }
                }
                return el;
            };
        })();
        """.trimIndent()
    }
}