package com.streamflixreborn.streamflix.utils

data class StremioSubtitles(
    val id: String?,
    val url: String?,
    val lang: String?,
    val format: String?
) {
    data class Subtitle(
        val id: String?,
        val url: String?,
        val lang: String?,
        val format: String?,
        val languageTag: String? = null
    )
}
