package com.streamflixreborn.streamflix.fragments.live_tv

import android.util.Log
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit

object LiveTvRepository {
    private const val TAG = "LiveTvRepository"
    private const val CHANNELS_URL = "https://raw.githubusercontent.com/AgronBellum/test_cachorro/refs/heads/main/canais.json"
    private const val EPG_INDEX_URL = "https://raw.githubusercontent.com/AgronBellum/test_cachorro/refs/heads/main/EP/lista.json"
    private const val CACHE_TTL_MS = 15 * 60 * 1000L

    val brazilZone: ZoneId = ZoneId.of("America/Sao_Paulo")

    internal val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val cacheMutex = Mutex()
    private var epgIndexCache: List<EpgEntry>? = null
    private var epgIndexLoadedAt: Long = 0L
    private val scheduleCache = mutableMapOf<String, CachedSchedule>()

    data class Category(
        val id: String,
        val name: String,
        val channels: List<Channel>,
    )

    data class LoadResult(
        val categories: List<Category>,
        val refreshed: Boolean,
        val published: Boolean,
        val warning: String? = null,
    )

    data class Channel(
        val id: String,
        val name: String,
        val categoryId: String,
        val categoryName: String,
        val streamUrl: String,
        val headers: Map<String, String>,
        val number: Int = 0,
        var schedule: Schedule = Schedule.EMPTY,
    )

    data class Program(
        val title: String,
        val description: String?,
        val start: Instant,
        val end: Instant?,
    )

    data class Schedule(
        val current: Program?,
        val next: Program?,
    ) {
        companion object {
            val EMPTY = Schedule(null, null)
        }
    }

    private data class CachedSchedule(
        val loadedAt: Long,
        val schedule: Schedule,
    )

    private data class EpgEntry(
        val name: String,
        val url: String,
        val normalized: String,
        val canonical: String,
    )

    suspend fun loadCategories(): List<Category> =
        loadCategoriesDetailed(forceRefresh = false).categories

    suspend fun loadCategoriesDetailed(
        forceRefresh: Boolean,
        onProgress: (LiveTvChannelUpdater.Progress) -> Unit = {},
    ): LoadResult = withContext(Dispatchers.IO) {
        val remoteJson = runCatching { fetch(CHANNELS_URL) }.getOrNull()
        val cachedJson = LiveTvChannelUpdater.readCachedJson()
        val availableJson = listOfNotNull(remoteJson, cachedJson)
            .maxByOrNull(::metadataTimestamp)
            ?: error("Não foi possível obter a lista de canais")

        val mustRefresh = forceRefresh || isChannelListOutdated(availableJson)
        val refreshResult = if (mustRefresh) {
            runCatching { LiveTvChannelUpdater.refresh(onProgress) }.getOrNull()
        } else {
            null
        }

        val selectedJson = refreshResult?.json ?: availableJson
        LoadResult(
            categories = parseCategories(selectedJson),
            refreshed = refreshResult != null,
            published = refreshResult?.published == true,
            warning = when {
                mustRefresh && refreshResult == null -> "Não foi possível atualizar; usando a última lista disponível."
                refreshResult != null && !refreshResult.published ->
                    "Canais atualizados neste aparelho, mas o envio ao GitHub falhou: ${refreshResult.publishError.orEmpty()}"
                else -> null
            },
        )
    }

    private fun parseCategories(json: String): List<Category> {
        val root = JSONObject(json)
        val categoriesObject = root.getJSONObject("categorias")
        val categories = mutableListOf<Category>()
        val keys = categoriesObject.keys()
        val categoryIds = buildList {
            while (keys.hasNext()) add(keys.next())
        }.sortedWith(
            compareBy<String> {
                when (it) {
                    "documentarios" -> 0
                    "esportes" -> 2
                    else -> 1
                }
            }.thenBy { it }
        )
        var channelNumber = 1

        for (categoryId in categoryIds) {
            val categoryObject = categoriesObject.getJSONObject(categoryId)
            val name = categoryObject.optString("nome", categoryId)
            val channelsArray = categoryObject.optJSONArray("canais") ?: JSONArray()
            val channels = buildList {
                for (index in 0 until channelsArray.length()) {
                    val item = channelsArray.optJSONObject(index) ?: continue
                    val streamUrl = item.optString("m3u8_url").takeIf { it.isNotBlank() } ?: continue
                    val status = item.optString("status", "success")
                    if (!status.equals("success", ignoreCase = true)) continue

                    add(
                        Channel(
                            id = item.optString("id", item.optString("name", streamUrl)),
                            name = item.optString("name", "Canal"),
                            categoryId = item.optString("categoryId", categoryId),
                            categoryName = item.optString("categoryName", name),
                            streamUrl = streamUrl,
                            headers = parseHeaders(item.optJSONObject("headers")),
                            number = channelNumber++,
                        )
                    )
                }
            }
            if (channels.isNotEmpty()) categories += Category(categoryId, name, channels)
        }

        return categories
    }

    private fun metadataTimestamp(json: String): Long = runCatching {
        val value = JSONObject(json).optJSONObject("metadata")?.optString("date").orEmpty()
        parseInstant(value)?.toEpochMilli() ?: 0L
    }.getOrDefault(0L)

    private fun isChannelListOutdated(json: String): Boolean = runCatching {
        val value = JSONObject(json).optJSONObject("metadata")?.optString("date").orEmpty()
        val date = parseInstant(value)?.atZone(brazilZone)?.toLocalDate() ?: return@runCatching true
        date.isBefore(LocalDate.now(brazilZone))
    }.getOrDefault(true)

    suspend fun loadSchedule(channel: Channel): Schedule = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            scheduleCache[channel.id]?.takeIf { now - it.loadedAt < CACHE_TTL_MS }?.schedule
        }?.let { return@withContext it }

        val epgUrl = findEpgUrl(channel)
        if (epgUrl == null) {
            Log.w(TAG, "EPG url not found for ${channel.name} (${channel.id})")
            return@withContext Schedule.EMPTY
        }

        val schedule = loadScheduleFromUrl(epgUrl)
        if (schedule.current != null || schedule.next != null) {
            cacheMutex.withLock {
                scheduleCache[channel.id] = CachedSchedule(System.currentTimeMillis(), schedule)
            }
        } else {
            Log.w(TAG, "EPG empty for ${channel.name}: $epgUrl")
        }
        schedule
    }

    private fun loadScheduleFromUrl(epgUrl: String): Schedule {
        val candidateUrls = buildList {
            add(epgUrl)
            val today = LocalDate.now(brazilZone)
            add(epgUrl.withEpgDate(today))
            add(epgUrl.withEpgDate(today.minusDays(1)))
            add(epgUrl.withEpgDate(today.plusDays(1)))
        }.distinct()

        candidateUrls.forEach { url ->
            val schedule = runCatching { parseSchedule(fetch(url)) }
                .onFailure { error -> Log.w(TAG, "EPG fetch/parse failed: $url", error) }
                .getOrDefault(Schedule.EMPTY)
            if (schedule.current != null || schedule.next != null) return schedule
        }
        return Schedule.EMPTY
    }

    private fun String.withEpgDate(date: LocalDate): String {
        val formatted = DateTimeFormatter.BASIC_ISO_DATE.format(date)
        return toHttpUrlOrNull()
            ?.newBuilder()
            ?.setQueryParameter("date", formatted)
            ?.build()
            ?.toString()
            ?: this
    }

    private suspend fun findEpgUrl(channel: Channel): String? {
        val index = loadEpgIndex()
        val candidates = listOf(channel.name, channel.id)
            .flatMap { value ->
                listOf(
                    normalizeName(value) to canonicalChannelName(value),
                    normalizeBaseChannelName(value) to canonicalChannelName(value),
                )
            }
            .filter { (normalized, canonical) -> normalized.isNotBlank() || canonical.isNotBlank() }
            .distinct()

        // Nunca use apenas "contains": HBO, HBO Family, HBO Plus e HBO Signature sao
        // canais diferentes. A pontuacao preserva as variantes e ainda aceita grafias como
        // H&H/Home and Health, HBO+/HBO Plus e sufixos HD/FHD.
        val match = index.mapNotNull { entry ->
            val score = candidates.maxOfOrNull { (normalized, canonical) ->
                when {
                    canonical.isNotBlank() && entry.canonical == canonical -> 10_000 + canonical.length
                    normalized.isNotBlank() && entry.normalized == normalized -> 9_000 + normalized.length
                    else -> 0
                }
            } ?: 0
            entry.takeIf { score > 0 }?.let { it to score }
        }.maxWithOrNull(compareBy<Pair<EpgEntry, Int>> { it.second }.thenBy { it.first.canonical.length })

        match?.first?.let {
            Log.d(TAG, "EPG ${channel.name} -> ${it.name}")
            return it.url
        }
        return null
    }

    private suspend fun loadEpgIndex(): List<EpgEntry> {
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            epgIndexCache?.takeIf { now - epgIndexLoadedAt < CACHE_TTL_MS }?.let { return it }
        }

        val root = JSONObject(fetch(EPG_INDEX_URL))
        val entries = mutableListOf<EpgEntry>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val url = root.optString(name)
            if (url.isNotBlank()) {
                entries += EpgEntry(
                    name = name,
                    url = url,
                    normalized = normalizeName(name),
                    canonical = canonicalChannelName(name),
                )
            }
        }

        cacheMutex.withLock {
            epgIndexCache = entries
            epgIndexLoadedAt = System.currentTimeMillis()
        }
        return entries
    }

    private fun parseSchedule(json: String): Schedule {
        val root = JSONObject(json)
        val list = root.optJSONArray("epg_list") ?: root.optJSONArray("programs") ?: JSONArray(json)
        val programs = buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val start = parseInstant(
                    item.optString("start_date").ifBlank { item.optString("start") }
                        .ifBlank { item.optString("startTime") }
                ) ?: continue
                add(
                    Program(
                        title = item.optString("title", item.optString("name", "Programa")),
                        description = item.optString("desc", item.optString("description", "")).takeIf { it.isNotBlank() && it != "null" },
                        start = start,
                        end = parseInstant(
                            item.optString("end_date").ifBlank { item.optString("end") }
                                .ifBlank { item.optString("endTime") }
                        ),
                    )
                )
            }
        }.sortedBy { it.start }

        if (programs.isEmpty()) return Schedule.EMPTY

        val now = Instant.now()
        var current: Program? = null
        var next: Program? = null

        programs.forEachIndexed { index, program ->
            val inferredEnd = program.end ?: programs.getOrNull(index + 1)?.start
            if (!program.start.isAfter(now) && (inferredEnd == null || inferredEnd.isAfter(now))) {
                current = program.copy(end = inferredEnd)
                next = programs.getOrNull(index + 1)
                return@forEachIndexed
            }
            if (program.start.isAfter(now) && next == null) next = program
        }

        return Schedule(current, next)
    }

    private fun parseHeaders(headers: JSONObject?): Map<String, String> {
        if (headers == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = headers.optString(key)
            if (value.isNotBlank()) {
                val canonicalKey = when (key.lowercase(Locale.ROOT)) {
                    "referer", "referrer" -> "Referer"
                    "origin" -> "Origin"
                    "user-agent" -> "User-Agent"
                    else -> key
                }
                map[canonicalKey] = value
            }
        }
        return map
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json,text/plain,*/*")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.7")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}: $url")
            return response.body?.string().orEmpty()
        }
    }

    private fun parseInstant(value: String): Instant? {
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value) }
            .recoverCatching { OffsetDateTime.parse(value).toInstant() }
            .recoverCatching { Instant.ofEpochSecond(value.toLong()) }
            .getOrNull()
    }

    private fun normalizeName(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(Locale.ROOT)
        return normalized.replace("[^a-z0-9]".toRegex(), "")
    }

    private fun normalizeBaseChannelName(value: String): String {
        val noSuffix = value
            .replace("(?i)\\s+fhd$".toRegex(), "")
            .replace("(?i)\\s+full\\s*hd$".toRegex(), "")
            .replace("(?i)\\s+hd\\s*Â³?$".toRegex(), "")
            .replace("(?i)\\s+hd$".toRegex(), "")
            .replace("(?i)\\s+sd$".toRegex(), "")
            .replace("(?i)\\s+4k$".toRegex(), "")
            .replace("(?i)\\s+ao vivo$".toRegex(), "")
            .trim()
        return normalizeName(noSuffix)
    }

    private fun canonicalChannelName(value: String): String {
        val expanded = value
            .replace("(?i)\\bH\\s*&\\s*H\\b".toRegex(), "Home and Health")
            .replace("+", " plus ")
            .replace("&", " and ")
            .replace("(?i)\\b(full\\s*hd|fhd|uhd|hd|sd|4k)\\b".toRegex(), " ")
            .replace("(?i)\\b(ao vivo|live)\\b".toRegex(), " ")
            .replace("(?i)\\btheather\\b".toRegex(), "theater")
        return normalizeName(expanded)
    }
}


