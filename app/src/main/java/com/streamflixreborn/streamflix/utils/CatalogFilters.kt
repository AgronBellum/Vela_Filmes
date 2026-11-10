package com.streamflixreborn.streamflix.utils

import java.util.Calendar

data class CatalogFilter(
    val id: String,
    val title: String,
    val group: String,
    val section: Boolean = false,
    val children: List<CatalogFilter> = emptyList(),
) {
    val isFolder: Boolean get() = children.isNotEmpty()
}

data class CatalogAdvancedFilters(
    val genreId: String? = null,
    val startYear: Int? = null,
    val endYear: Int? = null,
    val country: String? = null,
    val language: String? = null,
    val minimumRating: Int? = null,
    val sort: String = "popularity.desc",
) {
    val isActive: Boolean
        get() = genreId != null || startYear != null || endYear != null || country != null ||
            language != null || minimumRating != null || sort != "popularity.desc"

    fun label(): String = buildList {
        genreId?.let { add("Gênero") }
        if (startYear != null || endYear != null) add("${startYear ?: "…"}–${endYear ?: "…"}")
        country?.let { add("País: $it") }
        language?.let { add("Idioma: $it") }
        minimumRating?.let { add("Nota ≥ $it") }
        if (sort != "popularity.desc") add("Ordenação alterada")
    }.joinToString("  •  ")
}

object CatalogFilters {
    private fun item(type: String, kind: String, value: String, title: String, group: String) =
        CatalogFilter("catalog:$type:$kind:$value", title, group)

    private fun header(title: String) = CatalogFilter("header:$title", title, title, section = true)

    private fun folder(id: String, title: String, group: String, children: List<CatalogFilter>) =
        CatalogFilter("folder:$id", title, group, children = children)

    fun movies(): List<CatalogFilter> {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val genres = listOf(
            28 to "Ação", 12 to "Aventura", 16 to "Animação", 35 to "Comédia", 80 to "Crime",
            99 to "Documentário", 18 to "Drama", 10751 to "Família", 14 to "Fantasia",
            878 to "Ficção científica", 10752 to "Guerra", 36 to "História", 9648 to "Mistério",
            10402 to "Música", 10749 to "Romance", 53 to "Suspense", 27 to "Terror", 37 to "Faroeste"
        ).map { (id, title) -> item("movie", "genre", id.toString(), title, "GÊNEROS") }
        val years = (year downTo 1920).map { item("movie", "year", it.toString(), it.toString(), "ANOS") }
        val decades = (year / 10 * 10 downTo 1920 step 10).map {
            item("movie", "decade", it.toString(), "Década de $it", "DÉCADAS")
        }
        val locations = listOf(
            item("movie", "language", "pt", "Em português", "IDIOMA E REGIÃO"),
            item("movie", "country", "BR", "Filmes brasileiros", "IDIOMA E REGIÃO"),
            item("movie", "country", "US", "Estados Unidos", "IDIOMA E REGIÃO"),
            item("movie", "country", "KR", "Coreia do Sul", "IDIOMA E REGIÃO"),
            item("movie", "country", "JP", "Japão", "IDIOMA E REGIÃO"),
            item("movie", "country", "FR", "França", "IDIOMA E REGIÃO"),
        )
        val streaming = listOf(
            "8" to "Netflix", "119" to "Prime Video", "337" to "Disney+", "1899" to "Max",
            "350" to "Apple TV+", "531" to "Paramount+", "307" to "Globoplay"
        ).map { (id, title) -> item("movie", "stream", id, title, "STREAMING") }
        val oscar = buildList {
            add(item("movie", "oscar_winners", "all", "Todos os vencedores", "OSCAR"))
            add(item("movie", "oscar_nominees", "all", "Todos os indicados", "OSCAR"))
            listOf(
                "picture" to "Melhor Filme", "director" to "Melhor Direção", "actor" to "Melhor Ator",
                "actress" to "Melhor Atriz", "international" to "Melhor Filme Internacional",
                "animation" to "Melhor Animação", "documentary" to "Melhor Documentário",
                "original_screenplay" to "Melhor Roteiro Original", "adapted_screenplay" to "Melhor Roteiro Adaptado",
                "cinematography" to "Melhor Fotografia", "score" to "Melhor Trilha Sonora"
            ).forEach { (id, title) -> add(item("movie", "oscar_category", id, title, "OSCAR")) }
            (2026 downTo 1929).forEach { add(item("movie", "oscar_year", it.toString(), "Oscar $it", "OSCAR POR ANO")) }
        }
        return listOf(
            header("CATÁLOGO"),
            item("movie", "list", "popular", "Todos os filmes", "CATÁLOGO"),
            item("movie", "list", "now_playing", "Nos cinemas", "CATÁLOGO"),
            item("movie", "list", "upcoming", "Próximos lançamentos", "CATÁLOGO"),
            item("movie", "list", "trending", "Em alta", "CATÁLOGO"),
            item("movie", "list", "top_rated", "Melhor avaliação", "CATÁLOGO"),
            header("EXPLORAR"),
            folder("movie:genres", "Por gênero", "EXPLORAR", genres),
            folder("movie:years", "Por ano", "EXPLORAR", years),
            folder("movie:decades", "Por década", "EXPLORAR", decades),
            folder("movie:locations", "Por idioma e região", "EXPLORAR", locations),
            folder("movie:streaming", "Por streaming", "EXPLORAR", streaming),
            item("movie", "classic", "classic", "Clássicos do cinema", "EXPLORAR"),
            header("PREMIAÇÕES"),
            folder("movie:oscar", "Oscar", "PREMIAÇÕES", oscar),
        )
    }

    fun tvShows(): List<CatalogFilter> {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val genres = listOf(
            10759 to "Ação e aventura", 16 to "Animação", 35 to "Comédia", 80 to "Crime",
            99 to "Documentário", 18 to "Drama", 10751 to "Família", 10762 to "Infantil",
            9648 to "Mistério", 10764 to "Reality show", 10765 to "Ficção científica e fantasia",
            10766 to "Novelas", 10767 to "Talk shows", 10768 to "Guerra e política", 37 to "Faroeste"
        ).map { (id, title) -> item("tv", "genre", id.toString(), title, "GÊNEROS") }
        val years = (year downTo 1950).map { item("tv", "year", it.toString(), it.toString(), "ANOS") }
        val decades = (year / 10 * 10 downTo 1950 step 10).map {
            item("tv", "decade", it.toString(), "Década de $it", "DÉCADAS")
        }
        val statuses = listOf("0" to "Em andamento", "3" to "Encerradas", "4" to "Canceladas", "1" to "Planejadas", "2" to "Em produção")
            .map { (id, title) -> item("tv", "status", id, title, "SITUAÇÃO") }
        val formats = listOf(
            item("tv", "type", "2", "Minisséries", "FORMATO"),
            item("tv", "genre", "99", "Séries documentais", "FORMATO"),
            item("tv", "genre", "16", "Animes e animações", "FORMATO"),
            item("tv", "type", "4", "Reality shows", "FORMATO"),
            item("tv", "genre", "10766", "Novelas", "FORMATO"),
        )
        val streaming = listOf(
            "8" to "Netflix", "119" to "Prime Video", "337" to "Disney+", "1899" to "Max",
            "350" to "Apple TV+", "531" to "Paramount+", "307" to "Globoplay"
        ).map { (id, title) -> item("tv", "stream", id, title, "STREAMING") }
        return listOf(
            header("CATÁLOGO"),
            item("tv", "list", "popular", "Todas as séries", "CATÁLOGO"),
            item("tv", "list", "trending", "Em alta", "CATÁLOGO"),
            item("tv", "list", "airing_today", "Lançamentos recentes", "CATÁLOGO"),
            item("tv", "list", "on_the_air", "Em exibição", "CATÁLOGO"),
            item("tv", "list", "top_rated", "Melhor avaliação", "CATÁLOGO"),
            header("EXPLORAR"),
            folder("tv:genres", "Por gênero", "EXPLORAR", genres),
            folder("tv:years", "Por ano", "EXPLORAR", years),
            folder("tv:decades", "Por década", "EXPLORAR", decades),
            folder("tv:status", "Por situação", "EXPLORAR", statuses),
            folder("tv:format", "Por formato", "EXPLORAR", formats),
            folder("tv:streaming", "Por streaming", "EXPLORAR", streaming),
        )
    }

    fun advancedId(type: String, filters: CatalogAdvancedFilters): String {
        val params = linkedMapOf<String, String>()
        filters.genreId?.let { params["with_genres"] = it }
        filters.startYear?.let { params[if (type == "movie") "primary_release_date.gte" else "first_air_date.gte"] = "$it-01-01" }
        filters.endYear?.let { params[if (type == "movie") "primary_release_date.lte" else "first_air_date.lte"] = "$it-12-31" }
        filters.country?.let { params["with_origin_country"] = it }
        filters.language?.let { params["with_original_language"] = it }
        filters.minimumRating?.let { params["vote_average.gte"] = it.toString(); params["vote_count.gte"] = "50" }
        params["sort_by"] = filters.sort
        return "catalog:$type:params:" + params.entries.joinToString(";") { "${it.key}=${it.value}" }
    }
}
