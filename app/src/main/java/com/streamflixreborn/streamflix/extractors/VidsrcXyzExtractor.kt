package com.streamflixreborn.streamflix.extractors

import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
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

class VidsrcXyzExtractor : Extractor() {

    override val name = "Vidsrc.xyz"
    override val mainUrl = "https://vidsrc.xyz"
    override val aliasUrls = listOf("https://cdn.vidsrc.stream")

    fun server(videoType: Video.Type): Video.Server {
        return Video.Server(
            id = name,
            name = name,
            src = when (videoType) {
                is Video.Type.Movie -> "$mainUrl/embed/movie/${videoType.id}"
                is Video.Type.Episode -> "$mainUrl/embed/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
            },
        )
    }

    override suspend fun extract(link: String): Video {
        if (link.contains(".m3u8", ignoreCase = true)) {
            return Video(
                source = link,
                headers = mapOf("Referer" to mainUrl),
                type = MimeTypes.APPLICATION_M3U8,
            )
        }

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(StreamFlixApp.instance.applicationContext)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                val timeoutHandler = Handler(Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    if (continuation.isActive) {
                        continuation.resumeWithException(Exception("Timeout waiting for Vidsrc.xyz stream"))
                        webView.destroy()
                    }
                }
                timeoutHandler.postDelayed(timeoutRunnable, 30000)

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val url = request?.url?.toString().orEmpty()
                        if (url.contains(".m3u8", ignoreCase = true)) {
                            timeoutHandler.removeCallbacks(timeoutRunnable)
                            if (continuation.isActive) {
                                continuation.resume(
                                    Video(
                                        source = url,
                                        headers = mapOf("Referer" to link),
                                        type = MimeTypes.APPLICATION_M3U8,
                                    )
                                )
                                Handler(Looper.getMainLooper()).post { webView.destroy() }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                continuation.invokeOnCancellation {
                    timeoutHandler.post {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        runCatching { webView.stopLoading() }
                        runCatching { webView.destroy() }
                    }
                }

                webView.loadUrl(link)
            }
        }
    }
}
