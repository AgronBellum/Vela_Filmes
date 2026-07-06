package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MimeTypes
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
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
import org.jsoup.Jsoup
import java.text.Normalizer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MegaFlixExtractor : Extractor() {
    override val name = "MegaFlix"
    override val mainUrl = "https://megafrixapi.com"
    override val aliasUrls = listOf(
        "https://vods.faz-o-eli.online",
        "https://xn--kcksk7a2bl5le7b6doc1h3f.com",
        "https://megafrixapi.com",
    )

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val gson = Gson()
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun servers(videoType: Video.Type): List<Video.Server> = runCatching {
        val itemId = findMegaFlixItemId(videoType) ?: return emptyList()
        val response = fetchPlayers(itemId, videoType)
        val urls = response.br.orEmpty()
            .mapNotNull { normalizePlayerUrl(it) }
            .filter { playerData(it) != null }

        urls.mapIndexed { index, url ->
            val label = playerData(url) ?: "Player ${index + 1}"
            Video.Server(
                id = "megaflix-dub-$itemId-$index",
                name = "MegaFlix Dublado - $label",
                src = url,
            )
        }
    }.onFailure {
        Log.w(TAG, "Falha ao buscar servidores MegaFlix: ${it.message}")
    }.getOrDefault(emptyList())

    override suspend fun extract(link: String): Video {
        val streamUrl = extractWithWebView(link)
        return Video(
            source = streamUrl,
            subtitles = emptyList(),
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://megaflix.lat/",
                "Origin" to "https://megaflix.lat",
                "Accept" to "application/vnd.apple.mpegurl,application/x-mpegurl,video/mp2t,*/*",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            ),
            type = MimeTypes.APPLICATION_M3U8,
        )
    }

    private suspend fun findMegaFlixItemId(videoType: Video.Type): String? {
        val targetTitle = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }
        val targetYear = when (videoType) {
            is Video.Type.Movie -> videoType.releaseDate.take(4)
            is Video.Type.Episode -> videoType.tvShow.releaseDate?.take(4).orEmpty()
        }
        val type = when (videoType) {
            is Video.Type.Movie -> "1"
            is Video.Type.Episode -> "2"
        }

        val body = FormBody.Builder()
            .add("page", "1")
            .add("title", targetTitle)
            .add("tipo", type)
            .add("genero", "0")
            .add("ordem", "0")
            .add("min_date", "1896")
            .add("max_date", "2026")
            .build()

        val html = post("https://app.megafrixapi.com/4.6.2/?page=getItems", body)
        val doc = Jsoup.parse(html)
        val targetKey = normalizeTitle(targetTitle)

        return doc.select(".item[onclick*=openMovie]").mapNotNull { item ->
            val id = Regex("""openMovie\((\d+)\)""").find(item.attr("onclick"))?.groupValues?.getOrNull(1)
            val spans = item.select(".info span").map { it.text().trim() }
            val title = spans.getOrNull(0).orEmpty()
            val year = spans.getOrNull(1).orEmpty().take(4)
            if (id == null || title.isBlank()) null else SearchMatch(id, title, year)
        }.sortedWith(
            compareByDescending<SearchMatch> { normalizeTitle(it.title) == targetKey }
                .thenByDescending { targetYear.isNotBlank() && it.year == targetYear }
                .thenBy { levenshtein(normalizeTitle(it.title), targetKey) }
        ).firstOrNull()?.id
    }

    private suspend fun fetchPlayers(itemId: String, videoType: Video.Type): PlayersResponse {
        val builder = FormBody.Builder().add("item_id", itemId)
        when (videoType) {
            is Video.Type.Movie -> {
                builder.add("season_num", "")
                builder.add("episode_num", "")
            }
            is Video.Type.Episode -> {
                builder.add("season_num", videoType.season.number.toString())
                builder.add("episode_num", videoType.number.toString())
            }
        }
        return gson.fromJson(post("https://megafrixapi.com/iptv/warez2.php", builder.build()), PlayersResponse::class.java)
    }

    private suspend fun post(url: String, body: FormBody): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .header("Referer", "https://megaflix.lat/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("MegaFlix HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private fun normalizePlayerUrl(url: String): String? {
        var value = url.trim().ifBlank { return null }
        value = value.replace("\\/", "/")
        value = when {
            value.contains("direto.mp4") -> "$mainUrl/rdc/${java.net.URLEncoder.encode(value, "UTF-8")}"
            value.contains(".mp4") -> "$mainUrl/cnvs/${java.net.URLEncoder.encode(value, "UTF-8")}"
            else -> value
        }
        return value
    }

    private fun playerData(url: String): String? = when {
        url.contains("mp4.php") -> "Hy"
        url.contains("voltz.php") -> "Voltz"
        url.contains("rafa.php") -> "New"
        url.contains("hubby") -> "Hubby"
        url.contains("get_token_vod") -> "Mega"
        url.contains("byse/") -> "Byse"
        url.contains("bolt") -> "Bolt"
        url.contains("lulu") -> "Lulu"
        url.contains("rola/") -> "Sp-f"
        url.contains("rola3/") -> "Embv"
        url.contains("rola4/") -> "Xnn"
        url.contains("hide") -> "Hide"
        url.contains("wish") -> "Wish"
        url.contains("vids") -> "Vids"
        url.contains("brainzaps") -> "Bzp"
        else -> null
    }

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
                                setSupportMultipleWindows(false)
                            }
                            webChromeClient = object : WebChromeClient() {}
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    val url = request?.url?.toString().orEmpty()
                                    if (isStreamUrl(url)) safeResume(Result.success(url))
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    view?.evaluateJavascript(SNIFFER_JS, null)
                                    view?.evaluateJavascript(
                                        """
                                        (function(){
                                          document.querySelectorAll('button,.play,[class*="play"],[id*="play"],video').forEach(function(el){
                                            try { if (el.tagName === 'VIDEO') { el.muted = true; el.play(); } else { el.click(); } } catch(e) {}
                                          });
                                        })();
                                        """.trimIndent(),
                                        null,
                                    )
                                }
                            }
                            loadUrl(playerUrl, mapOf("Referer" to "https://megaflix.lat/", "User-Agent" to USER_AGENT))
                        }
                    } catch (e: Exception) {
                        safeResume(Result.failure(e))
                    }
                }

                mainHandler.postDelayed({
                    safeResume(Result.failure(Exception("Timeout MegaFlix: m3u8 nao encontrado")))
                }, TIMEOUT_MS)

                continuation.invokeOnCancellation { cleanup() }
            }
        } ?: throw Exception("Timeout MegaFlix: operacao excedeu ${TIMEOUT_MS}ms")
    }

    private fun isStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4")
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

    private data class SearchMatch(val id: String, val title: String, val year: String)
    private data class PlayersResponse(
        @SerializedName("br") val br: List<String>? = emptyList(),
        @SerializedName("eng") val eng: List<String>? = emptyList(),
    )

    companion object {
        private const val TAG = "MegaFlixExtractor"
        private const val TIMEOUT_MS = 35000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

        private val SNIFFER_JS = """
            (function(){
              const seen = new Set();
              function notify(url){
                if (!url || seen.has(url)) return;
                seen.add(url);
                if (String(url).includes('.m3u8') || String(url).includes('.mp4')) console.log('MegaFlix stream:', url);
              }
              const fetch0 = window.fetch;
              window.fetch = function(){ notify(arguments[0]); return fetch0.apply(this, arguments); };
              const open0 = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(method, url){ notify(url); return open0.apply(this, arguments); };
              setInterval(function(){
                document.querySelectorAll('video,source').forEach(function(v){ notify(v.currentSrc || v.src); });
              }, 500);
            })();
        """.trimIndent()
    }
}
