package com.streamflixreborn.streamflix.utils

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Oscar dataset supplied by the project owner. IDs are resolved through TMDB only for artwork/details. */
object OscarCatalog {
    private const val SOURCE = "https://raw.githubusercontent.com/delventhalz/json-nominations/refs/heads/main/oscar-nominations.json"
    private val client = OkHttpClient()
    private val mutex = Mutex()
    @Volatile private var cached: List<Entry>? = null

    private data class Film(val title: String = "", val tmdb_id: Int? = null)
    private data class Entry(
        val category: String = "",
        val year: String = "",
        val movies: List<Film> = emptyList(),
        val won: Boolean = false,
    )

    private val recent = mapOf(
        2024 to listOf(1058616,1056360,1171816,915935,346698,1004683,991708,976573,626332,1069193,940721,899524,447365,335977,1084765,937746,1203439,466420,971468,1040371,523607,839369,575264,753342,961323,1140605,895549,1085779,872585,950822,950810,666277,976893,792307,1194636,838240,898713,906126,569094,1186227,1169455,1186247,508883,558915,670292,1032760,840430,1171861,998022,923939,467244,1015356,1214020),
        2025 to listOf(661539,989662,1118941,1013850,945961,1064213,1337997,1157132,799766,1214527,974576,1356881,693134,977326,974950,823219,558449,1300245,1178556,1000837,1156619,1108462,1022789,1284874,653346,1249728,1038263,1064486,1028196,1232493,426063,1158874,1211472,1155828,1214539,1214499,1182047,549509,1278263,1316292,1279229,1195310,1232827,1061699,933260,1184918,929204,1156605,402431,1220646),
        2026 to listOf(1470465,1525091,804370,1422051,83533,1299655,701387,1560394,1233361,1560400,1400780,1400793,826338,1022787,911430,1477914,1062722,858024,1160360,1456349,1272266,1234821,1379266,803796,682012,1317288,1393151,1054867,1278954,1316745,1124566,1233413,1151272,1371185,1413805,1373150,1142149,1236470,1400782,1220564,1442908,760329,1302562,1284120,1480382,1241983,1340625,1358554,1078605,1084242),
    )

    suspend fun movieIds(kind: String, value: String): List<Int> {
        if (kind == "oscar_year") {
            val ceremonyYear = value.toIntOrNull() ?: return emptyList()
            recent[ceremonyYear]?.let { return it.distinct() }
        }
        val entries = load().asReversed()
        val filtered = when (kind) {
            "oscar_year" -> entries.filter { ceremonyYear(it.year) == value.toIntOrNull() }
            "oscar_year_winners" -> entries.filter { ceremonyYear(it.year) == value.toIntOrNull() && it.won }
            "oscar_winners" -> entries.filter { it.won }
            "oscar_nominees" -> entries
            "oscar_category" -> entries.filter { categoryMatches(it.category, value) }
            else -> emptyList()
        }
        val remoteIds = filtered.flatMap { it.movies }.mapNotNull { it.tmdb_id }.distinct()
        return if (kind == "oscar_nominees") {
            (recent.toSortedMap(compareByDescending { it }).values.flatten() + remoteIds).distinct()
        } else remoteIds
    }

    private suspend fun load(): List<Entry> {
        cached?.let { return it }
        return mutex.withLock {
            cached?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(SOURCE).build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Fonte do Oscar indisponível (${response.code})" }
                    val type = object : TypeToken<List<Entry>>() {}.type
                    Gson().fromJson<List<Entry>>(response.body!!.string(), type).also { cached = it }
                }
            }
        }
    }

    private fun ceremonyYear(value: String): Int? {
        val finalYear = value.substringAfter('/', value).toIntOrNull() ?: return null
        val fullYear = if (value.contains('/') && finalYear < 100) {
            (value.substringBefore('/').take(2) + finalYear.toString().padStart(2, '0')).toIntOrNull() ?: finalYear
        } else finalYear
        return fullYear + 1
    }

    private fun categoryMatches(category: String, key: String): Boolean {
        val normalized = category.lowercase()
        return when (key) {
            "picture" -> normalized == "best picture"
            "director" -> normalized.startsWith("best director")
            "actor" -> normalized == "best actor"
            "actress" -> normalized == "best actress"
            "international" -> normalized.contains("international feature") || normalized.contains("foreign language")
            "animation" -> normalized == "best animated feature"
            "documentary" -> normalized == "best documentary feature"
            "original_screenplay" -> normalized == "best original screenplay"
            "adapted_screenplay" -> normalized == "best adapted screenplay"
            "cinematography" -> normalized.startsWith("best cinematography")
            "score" -> normalized.contains("original score") || normalized.startsWith("best music (score")
            else -> false
        }
    }
}
