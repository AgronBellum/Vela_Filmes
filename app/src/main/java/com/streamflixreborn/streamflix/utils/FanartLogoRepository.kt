package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object FanartLogoRepository {
    private val logoCache = ConcurrentHashMap<String, String>()
    private val missingLogos = ConcurrentHashMap.newKeySet<String>()

    suspend fun findLogo(videoType: Video.Type): String? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.FANART_API_KEY.trim()

        val lookup = when (videoType) {
            is Video.Type.Movie -> Lookup(
                cacheKey = "movie:${videoType.imdbId ?: videoType.id}",
                resource = "movies",
                imdbId = videoType.imdbId,
                tmdbIdCandidate = videoType.id.takeIf { it.isNumericId() },
                fields = listOf("hdmovielogo", "movielogo"),
            )
            is Video.Type.Episode -> Lookup(
                cacheKey = "tv:${videoType.tvShow.imdbId ?: videoType.tvShow.id}",
                resource = "tv",
                imdbId = videoType.tvShow.imdbId,
                tmdbIdCandidate = videoType.tvShow.id.takeIf { it.isNumericId() },
                fields = listOf("hdtvlogo", "clearlogo", "tvlogo"),
            )
        }

        logoCache[lookup.cacheKey]?.let { return@withContext it }
        if (lookup.cacheKey in missingLogos) return@withContext null

        // Tenta buscar primeiro no TMDB (maior disponibilidade em PT-BR) e depois no Fanart.tv
        val logo = findTmdbLogo(lookup) ?: findFanartLogo(lookup, apiKey)

        if (logo == null) {
            missingLogos += lookup.cacheKey
            Log.w(TAG, "Logo não encontrada para ${lookup.cacheKey}")
        } else {
            logoCache[lookup.cacheKey] = logo
        }
        logo
    }

    private fun findTmdbLogo(lookup: Lookup): String? {
        val apiKey = UserPreferences.tmdbApiKey.ifBlank { BuildConfig.TMDB_API_KEY }.trim()
        if (apiKey.isBlank() || apiKey.equals("null", ignoreCase = true)) return null

        return runCatching {
            val mediaType = if (lookup.resource == "movies") "movie" else "tv"
            val resultsField = if (mediaType == "movie") "movie_results" else "tv_results"

            val tmdbId = lookup.tmdbIdCandidate?.toIntOrNull()?.takeIf { it > 0 } ?: run {
                val imdbId = lookup.imdbId?.takeIf { it.startsWith("tt") }
                    ?: return@runCatching null
                val findUrl = "https://api.themoviedb.org/3/find/$imdbId" +
                    "?api_key=$apiKey&external_source=imdb_id"
                val findRoot = getJson(findUrl) ?: return@runCatching null

                val firstResult = findRoot.optJSONArray(resultsField)?.optJSONObject(0)
                    ?: return@runCatching null

                // Garante a leitura correta do ID mesmo se a API retornar como int ou string
                firstResult.optInt("id", -1).takeIf { it > 0 }
                    ?: firstResult.optString("id").toIntOrNull()?.takeIf { it > 0 }
                    ?: return@runCatching null
            }

            val imagesUrl = "https://api.themoviedb.org/3/$mediaType/$tmdbId/images" +
                "?api_key=$apiKey&include_image_language=pt-BR,pt,en,null"
            val logos = getJson(imagesUrl)?.optJSONArray("logos") ?: return@runCatching null

            val selected = (0 until logos.length())
                .mapNotNull(logos::optJSONObject)
                .sortedWith(
                    compareBy<JSONObject> { languagePriority(it.optString("iso_639_1")) }
                        .thenByDescending { it.optDouble("vote_average", 0.0) }
                )
                .firstOrNull()
                ?: return@runCatching null

            selected.optString("file_path").takeIf(String::isNotBlank)
                ?.let { "https://image.tmdb.org/t/p/w500$it" }
        }.getOrNull()
    }

    private fun findFanartLogo(lookup: Lookup, apiKey: String): String? {
        if (apiKey.isBlank() || apiKey.equals("null", ignoreCase = true)) return null
        val imdbId = lookup.imdbId?.takeIf { it.startsWith("tt") } ?: return null
        val request = Request.Builder()
            .url("https://webservice.fanart.tv/v3.2/${lookup.resource}/$imdbId?source=imdb")
            .header("api-key", apiKey)
            .header("Accept", "application/json")
            .header("User-Agent", "Vela/${BuildConfig.VERSION_NAME}")
            .build()

        return runCatching {
            NetworkClient.default.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val root = JSONObject(response.body?.string().orEmpty())
                chooseLogo(lookup.fields.mapNotNull(root::optJSONArray))
            }
        }.getOrNull()
    }

    private fun getJson(url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Vela/${BuildConfig.VERSION_NAME}")
            .build()
        return NetworkClient.default.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun chooseLogo(arrays: List<JSONArray>): String? = arrays
        .flatMap { array ->
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
        }
        .sortedWith(
            compareBy<JSONObject> { languagePriority(it.optString("lang")) }
                .thenByDescending { it.optString("likes").toIntOrNull() ?: 0 }
        )
        .firstNotNullOfOrNull { it.optString("url").takeIf(String::isNotBlank) }

    private fun languagePriority(language: String): Int = when (language.lowercase()) {
        "pt", "pt-br" -> 0
        "en" -> 1
        "", "00", "null" -> 2
        else -> 3
    }

    private data class Lookup(
        val cacheKey: String,
        val resource: String,
        val imdbId: String?,
        val tmdbIdCandidate: String?,
        val fields: List<String>,
    )

    private fun String.isNumericId(): Boolean = isNotBlank() && all(Char::isDigit)

    private const val TAG = "FanartLogoRepository"
}
