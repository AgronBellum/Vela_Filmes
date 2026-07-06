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
    private var epgIndexCache: Map<String, String>? = null
    private var epgIndexLoadedAt: Long = 0L
    private val scheduleCache = mutableMapOf<String, CachedSchedule>()

    data class Category(
        val id: String,
        val name: String,
        val channels: List<Channel>,
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

    suspend fun loadCategories(): List<Category> = withContext(Dispatchers.IO) {
        val json = fetch(CHANNELS_URL)
        val root = JSONObject(json)
        val categoriesObject = root.getJSONObject("categorias")
        val categories = mutableListOf<Category>()
        val keys = categoriesObject.keys()
        var channelNumber = 1

        while (keys.hasNext()) {
            val categoryId = keys.next()
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

        categories
    }

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
        val candidates = buildList {
            add(normalizeName(channel.name))
            add(normalizeName(channel.id))
            add(normalizeBaseChannelName(channel.name))
            add(normalizeBaseChannelName(channel.id))
        }.filter { it.isNotBlank() }.distinct()

        candidates.firstNotNullOfOrNull { index[it] }?.let { return it }

        val fuzzy = index.entries.firstOrNull { (key, _) ->
            val baseKey = normalizeBaseChannelName(key)
            candidates.any { candidate ->
                key == candidate || baseKey == candidate ||
                    key.contains(candidate) || candidate.contains(key) ||
                    baseKey.contains(candidate) || candidate.contains(baseKey)
            }
        }
        return fuzzy?.value
    }

    private suspend fun loadEpgIndex(): Map<String, String> {
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            epgIndexCache?.takeIf { now - epgIndexLoadedAt < CACHE_TTL_MS }?.let { return it }
        }

        val root = JSONObject(fetch(EPG_INDEX_URL))
        val map = mutableMapOf<String, String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val url = root.optString(name)
            if (url.isNotBlank()) {
                map[normalizeName(name)] = url
                map[normalizeBaseChannelName(name)] = url
            }
        }

        cacheMutex.withLock {
            epgIndexCache = map
            epgIndexLoadedAt = System.currentTimeMillis()
        }
        return map
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
}


