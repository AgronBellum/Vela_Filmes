package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Video

/**
 * Keeps the audio-language decision independent from an extractor's display name.
 * Extractors expose different labels, so we normalize the common Portuguese dub tags
 * in one place before a server is selected.
 */
object ServerLanguage {
    fun portugueseDubPriority(server: Video.Server): Int {
        val value = listOf(server.name, server.id, server.src)
            .joinToString(" ")
            .lowercase()

        return when {
            value.contains("pt-br") || value.contains("ptbr") ||
                value.contains("português brasil") || value.contains("portugues brasil") -> 120

            value.contains("dublado") || value.contains("[dub]") ||
                value.contains(" dub ") || value.contains("dubbed") -> 110

            value.contains("playerflix") -> 90

            value.contains("legendado") || value.contains("[leg]") ||
                value.contains("subtitle") || value.contains(" sub ") -> -20

            else -> 0
        }
    }

    fun preferredPortugueseDub(servers: List<Video.Server>): Video.Server? =
        servers.maxByOrNull(::portugueseDubPriority)
            ?.takeIf { portugueseDubPriority(it) > 0 }
}
