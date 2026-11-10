package com.streamflixreborn.streamflix.fragments.live_tv

import android.util.Base64
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object LiveTvChannelUpdater {
    private const val API_POINTER_URL =
        "https://raw.githubusercontent.com/AgronBellum/test_cachorro/refs/heads/main/api-canais"
    private const val PLAYER_URL = "https://megafrixapi.com/desktop/player/channel.php?id="
    private const val GITHUB_CONTENT_URL =
        "https://api.github.com/repos/AgronBellum/test_cachorro/contents/canais.json"
    private const val GITHUB_BRANCH = "main"
    private const val CACHE_FILE = "live_tv_channels_cache.json"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class Progress(
        val completed: Int,
        val total: Int,
        val percent: Int,
        val message: String,
    )

    data class Result(
        val json: String,
        val published: Boolean,
        val publishError: String? = null,
    )

    private data class ApiCategory(val numericId: Int, val id: String, val name: String)

    private data class ExtractedChannel(
        val categoryId: String,
        val categoryName: String,
        val json: JSONObject,
        val success: Boolean,
    )

    suspend fun refresh(onProgress: (Progress) -> Unit): Result = withContext(Dispatchers.IO) {
        onProgress(Progress(0, 0, 0, "Buscando a lista atualizada de canais..."))
        val apiUrl = fetchText(API_POINTER_URL).trim()
        require(apiUrl.startsWith("https://")) { "O endereço da API de canais é inválido" }

        val apiRoot = JSONObject(fetchText(apiUrl))
        val categories = parseCategories(apiRoot.optJSONArray("categories") ?: JSONArray())
        val categoryByNumber = categories.associateBy { it.numericId }
        val sourceChannels = apiRoot.optJSONArray("channels") ?: JSONArray()
        val total = sourceChannels.length()
        require(total > 0) { "A API não retornou canais" }

        val completed = AtomicInteger(0)
        val limiter = Semaphore(8)
        val extracted = coroutineScope {
            (0 until total).map { index ->
                async {
                    limiter.withPermit {
                        val item = sourceChannels.optJSONObject(index) ?: JSONObject()
                        val channel = extractChannel(item, categoryByNumber)
                        val done = completed.incrementAndGet()
                        val percent = ((done * 100.0) / total).toInt().coerceIn(0, 100)
                        onProgress(
                            Progress(
                                completed = done,
                                total = total,
                                percent = percent,
                                message = "Atualizando canais: $done de $total",
                            )
                        )
                        channel
                    }
                }
            }.awaitAll()
        }

        val orderedCategories = categories
            .filter { category -> extracted.any { it.categoryId == category.id && it.success } }
            .sortedWith(
                compareBy<ApiCategory> {
                    when (it.id) {
                        "documentarios" -> 0
                        "esportes" -> 2
                        else -> 1
                    }
                }.thenBy { it.numericId }
            )

        val categoriesJson = JSONObject()
        orderedCategories.forEach { category ->
            val channelsJson = JSONArray()
            extracted.asSequence()
                .filter { it.categoryId == category.id && it.success }
                .sortedBy { it.json.optString("name").lowercase(Locale.ROOT) }
                .forEach { channelsJson.put(it.json) }
            if (channelsJson.length() > 0) {
                categoriesJson.put(
                    category.id,
                    JSONObject()
                        .put("nome", category.name)
                        .put("canais", channelsJson)
                )
            }
        }

        val success = extracted.count { it.success }
        val minimumSafeResult = maxOf(1, (total * 3) / 4)
        require(success >= minimumSafeResult) {
            "A extração encontrou somente $success de $total canais; a lista anterior foi preservada"
        }
        val output = JSONObject()
            .put(
                "metadata",
                JSONObject()
                    .put("total", total)
                    .put("success", success)
                    .put("categories", categoriesJson.length())
                    .put("date", Instant.now().toString())
                    .put("version", "EXTRACTOR_APP_V2")
            )
            .put("categorias", categoriesJson)
            .toString(2)

        cacheFile().writeText(output, Charsets.UTF_8)
        onProgress(Progress(total, total, 100, "Publicando a lista atualizada..."))

        val publishResult = runCatching { publish(output) }
        Result(
            json = output,
            published = publishResult.isSuccess,
            publishError = publishResult.exceptionOrNull()?.message,
        )
    }

    fun readCachedJson(): String? = runCatching {
        cacheFile().takeIf(File::isFile)?.readText(Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun cacheFile(): File = File(StreamFlixApp.instance.filesDir, CACHE_FILE)

    private fun parseCategories(array: JSONArray): List<ApiCategory> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val numericId = item.optInt("id", -1)
            if (numericId <= 0) continue // "Todos" duplicaria os canais.
            val name = item.optString("name", "Categoria")
            add(ApiCategory(numericId, slug(name), name))
        }
    }

    private fun extractChannel(
        item: JSONObject,
        categoryByNumber: Map<Int, ApiCategory>,
    ): ExtractedChannel {
        val id = item.optString("id").trim()
        val categoryNumbers = item.optJSONArray("categories") ?: JSONArray()
        val category = (0 until categoryNumbers.length())
            .asSequence()
            .map { categoryNumbers.optInt(it, -1) }
            .firstNotNullOfOrNull(categoryByNumber::get)
            ?: ApiCategory(Int.MAX_VALUE, "outros", "Outros")

        val streamUrl = if (id.isBlank()) null else runCatching {
            val html = fetchText(PLAYER_URL + id)
            STREAM_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("&amp;", "&")
                ?.trim()
        }.getOrNull()?.takeIf { it.startsWith("http") }

        val success = !streamUrl.isNullOrBlank()
        val channelJson = JSONObject()
            .put("id", id)
            .put("name", item.optString("name", id.ifBlank { "Canal" }))
            .put("categoryId", category.id)
            .put("categoryName", category.name)
            .put("image", item.optString("image"))
            .put("preview", item.optString("preview"))
            .put("m3u8_url", streamUrl.orEmpty())
            .put(
                "headers",
                JSONObject()
                    .put("referer", "https://megafrixapi.com/")
                    .put("origin", "https://megafrixapi.com")
            )
            .put("status", if (success) "success" else "error")

        return ExtractedChannel(category.id, category.name, channelJson, success)
    }

    private fun publish(json: String) {
        val token = BuildConfig.GITHUB_CHANNELS_TOKEN.trim()
        require(token.isNotBlank()) { "Token do GitHub não configurado no .env" }
        val commonHeaders = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to "Vela-Channel-Updater/2",
        )

        val currentRequest = Request.Builder()
            .url("$GITHUB_CONTENT_URL?ref=$GITHUB_BRANCH")
            .get()
            .apply { commonHeaders.forEach(::header) }
            .build()

        val sha = client.newCall(currentRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code != 200 && response.code != 404) {
                val detail = runCatching { JSONObject(body).optString("message") }.getOrNull()
                error(detail?.takeIf { it.isNotBlank() } ?: "GitHub HTTP ${response.code}")
            }
            if (response.code == 200) JSONObject(body).optString("sha").takeIf(String::isNotBlank) else null
        }

        val metadataDate = JSONObject(json).optJSONObject("metadata")?.optString("date").orEmpty()
        val payload = JSONObject()
            .put("message", "Atualiza canais automaticamente ($metadataDate)")
            .put("content", Base64.encodeToString((json + "\n").toByteArray(), Base64.NO_WRAP))
            .put("branch", GITHUB_BRANCH)
            .apply { if (sha != null) put("sha", sha) }

        val updateRequest = Request.Builder()
            .url(GITHUB_CONTENT_URL)
            .put(payload.toString().toRequestBody(jsonMediaType))
            .apply { commonHeaders.forEach(::header) }
            .build()
        client.newCall(updateRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code !in listOf(200, 201)) {
                val detail = runCatching { JSONObject(body).optString("message") }.getOrNull()
                error(detail?.takeIf { it.isNotBlank() } ?: "GitHub HTTP ${response.code}")
            }
        }
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json,text/plain,*/*")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://megafrixapi.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}: $url")
            return response.body?.string().orEmpty()
        }
    }

    private fun slug(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^a-z0-9]+".toRegex(), "_")
            .trim('_')
        return normalized.ifBlank { "outros" }
    }

    private val STREAM_REGEX = Regex(
        """(?i)(?:source|file|src)\s*:\s*["'](https?://[^"']+?\.m3u8[^"']*)["']"""
    )
}
