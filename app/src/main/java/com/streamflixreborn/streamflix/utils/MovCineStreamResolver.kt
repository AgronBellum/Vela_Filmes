package com.streamflixreborn.streamflix.utils

import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import org.json.JSONObject

object MovCineStreamResolver {

    data class Descriptor(
        val id: String? = null,
        val server: String? = null,
        val link: String? = null,
        val header: String? = null,
        val userAgent: String? = null,
        val external: Boolean = false,
        val embed: Boolean = false,
        val hls: Boolean = false,
        val lang: String? = null,
        val hd: String? = null,
    )

    fun toServer(descriptor: Descriptor, fallbackName: String = "MovCine"): Video.Server {
        val link = descriptor.link.orEmpty().trim()
        val label = buildLabel(descriptor, fallbackName)
        val server = Video.Server(
            id = descriptor.id?.takeIf { it.isNotBlank() } ?: link,
            name = label,
            src = link,
        )

        if (shouldPlayDirectly(descriptor)) {
            server.video = toVideo(descriptor)
        }

        return server
    }

    fun toVideo(descriptor: Descriptor): Video {
        val link = descriptor.link?.trim().orEmpty()
        require(link.startsWith("http", ignoreCase = true)) { "Invalid stream link" }

        return Video(
            source = link,
            headers = parseHeaders(descriptor.header, descriptor.userAgent),
            type = mediaType(link, descriptor.hls),
        )
    }

    fun parseHeaders(rawHeader: String?, userAgent: String? = null): Map<String, String>? {
        val headers = linkedMapOf<String, String>()
        val cleaned = rawHeader?.trim().orEmpty()

        if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
            runCatching {
                val json = JSONObject(cleaned)
                json.keys().forEach { key ->
                    val value = json.optString(key).trim()
                    if (key.isNotBlank() && value.isNotBlank()) headers[key] = value
                }
            }
        } else if (cleaned.isNotBlank()) {
            cleaned.split(Regex("[\\r\\n|]+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val separator = when {
                        ":" in line -> ":"
                        "=" in line -> "="
                        else -> null
                    } ?: return@forEach

                    val key = line.substringBefore(separator).trim()
                    val value = line.substringAfter(separator).trim()
                    if (key.isNotBlank() && value.isNotBlank()) headers[key] = value
                }
        }

        userAgent?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { headers["User-Agent"] = it }

        return headers.ifEmpty { null }
    }

    private fun shouldPlayDirectly(descriptor: Descriptor): Boolean {
        val link = descriptor.link.orEmpty()
        return !descriptor.embed &&
            !descriptor.external &&
            link.startsWith("http", ignoreCase = true) &&
            (descriptor.hls || isDirectMedia(link))
    }

    private fun buildLabel(descriptor: Descriptor, fallbackName: String): String {
        val parts = listOfNotNull(
            descriptor.server?.trim()?.takeIf { it.isNotBlank() },
            descriptor.lang?.trim()?.takeIf { it.isNotBlank() },
            descriptor.hd?.trim()?.takeIf { it.isNotBlank() },
        )

        return parts.ifEmpty { listOf(fallbackName) }.joinToString(" ")
    }

    private fun mediaType(link: String, hls: Boolean): String? = when {
        hls || link.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
        link.contains(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
        else -> null
    }

    private fun isDirectMedia(link: String): Boolean {
        val lower = link.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".mkv") ||
            lower.contains(".webm")
    }
}
