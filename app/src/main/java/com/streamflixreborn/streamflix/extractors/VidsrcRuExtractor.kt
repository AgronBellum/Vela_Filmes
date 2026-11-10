package com.streamflixreborn.streamflix.extractors

import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VidsrcRuExtractor : Extractor() {

    override val name = "Vidsrc.Ru"
    override val mainUrl = "https://vidsrc.ru"

    fun server(videoType: Video.Type): Video.Server {
        return Video.Server(
            id = name,
            name = name,
            src = when (videoType) {
                is Video.Type.Movie -> "$mainUrl/movie/${videoType.id}"
                is Video.Type.Episode -> "$mainUrl/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
            },
        )
    }

    override suspend fun extract(link: String): Video {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val mainHandler = Handler(Looper.getMainLooper())
                val webView = WebView(StreamFlixApp.instance.applicationContext)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                }

                // Função auxiliar segura para destruir a WebView apenas na Main Thread
                fun safeDestroyWebView() {
                    mainHandler.post {
                        runCatching {
                            webView.stopLoading()
                            webView.webViewClient = object : WebViewClient() {}
                            webView.destroy()
                        }
                    }
                }

                val timeoutRunnable = Runnable {
                    if (continuation.isActive) {
                        continuation.resumeWithException(Exception("Timeout waiting for VidsrcRu stream"))
                        safeDestroyWebView()
                    }
                }
                
                mainHandler.postDelayed(timeoutRunnable, 30000)

                webView.webViewClient = object : WebViewClient() {
                    // ATENÇÃO: Chamado em BACKGROUND THREAD pelo motor WebKit
                    override fun shouldInterceptRequest(
                        view: WebView?, 
                        request: WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val url = request?.url?.toString() ?: ""
                        
                        if (url.contains("/file2/") && url.endsWith(".m3u8")) {
                            // Envia o resultado de volta para a Main Thread
                            mainHandler.post {
                                if (continuation.isActive) {
                                    mainHandler.removeCallbacks(timeoutRunnable)
                                    
                                    val video = Video(
                                        source = url,
                                        subtitles = emptyList(),
                                        type = MimeTypes.APPLICATION_M3U8
                                    )
                                    
                                    continuation.resume(video)
                                    safeDestroyWebView()
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                webView.loadUrl(link)

                continuation.invokeOnCancellation {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    safeDestroyWebView()
                }
            }
        }
    }
}