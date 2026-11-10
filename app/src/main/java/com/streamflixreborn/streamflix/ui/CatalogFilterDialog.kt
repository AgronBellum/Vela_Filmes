package com.streamflixreborn.streamflix.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.streamflixreborn.streamflix.utils.CatalogAdvancedFilters
import com.streamflixreborn.streamflix.utils.dp
import java.util.Calendar

object CatalogFilterDialog {
    private data class Option(val label: String, val value: String?) { override fun toString() = label }

    fun show(context: Context, movie: Boolean, current: CatalogAdvancedFilters, apply: (CatalogAdvancedFilters) -> Unit) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(context), 8.dp(context), 24.dp(context), 8.dp(context))
        }
        fun spinner(label: String, options: List<Option>, selected: String?): Spinner {
            container.addView(TextView(context).apply {
                text = label; textSize = 14f; setTextColor(Color.LTGRAY); setPadding(0, 12.dp(context), 0, 4.dp(context))
            })
            return Spinner(context).also { view ->
                view.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
                view.setSelection(options.indexOfFirst { it.value == selected }.coerceAtLeast(0))
                view.isFocusable = true
                container.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dp(context)))
            }
        }

        val genres = if (movie) listOf(
            "28" to "Ação", "12" to "Aventura", "16" to "Animação", "35" to "Comédia", "80" to "Crime",
            "99" to "Documentário", "18" to "Drama", "10751" to "Família", "14" to "Fantasia", "878" to "Ficção científica",
            "10752" to "Guerra", "36" to "História", "9648" to "Mistério", "10402" to "Música", "10749" to "Romance",
            "53" to "Suspense", "27" to "Terror", "37" to "Faroeste"
        ) else listOf(
            "10759" to "Ação e aventura", "16" to "Animação", "35" to "Comédia", "80" to "Crime", "99" to "Documentário",
            "18" to "Drama", "10751" to "Família", "10762" to "Infantil", "9648" to "Mistério", "10764" to "Reality show",
            "10765" to "Ficção científica e fantasia", "10766" to "Novelas", "10767" to "Talk shows", "10768" to "Guerra e política", "37" to "Faroeste"
        )
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val years = listOf(Option("Qualquer ano", null)) + (currentYear downTo 1930).map { Option(it.toString(), it.toString()) }
        val genre = spinner("Gênero", listOf(Option("Todos", null)) + genres.map { Option(it.second, it.first) }, current.genreId)
        val start = spinner("Ano inicial", years, current.startYear?.toString())
        val end = spinner("Ano final", years, current.endYear?.toString())
        val country = spinner("País de origem", listOf(
            Option("Todos", null), Option("Brasil", "BR"), Option("Estados Unidos", "US"), Option("Reino Unido", "GB"),
            Option("França", "FR"), Option("Espanha", "ES"), Option("Coreia do Sul", "KR"), Option("Japão", "JP"), Option("Índia", "IN")
        ), current.country)
        val language = spinner("Idioma original", listOf(
            Option("Todos", null), Option("Português", "pt"), Option("Inglês", "en"), Option("Espanhol", "es"),
            Option("Francês", "fr"), Option("Coreano", "ko"), Option("Japonês", "ja"), Option("Italiano", "it")
        ), current.language)
        val rating = spinner("Avaliação mínima", listOf(Option("Qualquer nota", null)) + (5..9).map { Option("$it ou mais", it.toString()) }, current.minimumRating?.toString())
        val sortOptions = if (movie) listOf(
            Option("Mais populares", "popularity.desc"), Option("Melhor avaliados", "vote_average.desc"),
            Option("Mais recentes", "primary_release_date.desc"), Option("Mais antigos", "primary_release_date.asc"),
            Option("Título A–Z", "title.asc"), Option("Título Z–A", "title.desc")
        ) else listOf(
            Option("Mais populares", "popularity.desc"), Option("Melhor avaliados", "vote_average.desc"),
            Option("Mais recentes", "first_air_date.desc"), Option("Mais antigos", "first_air_date.asc"),
            Option("Título A–Z", "name.asc"), Option("Título Z–A", "name.desc")
        )
        val sort = spinner("Ordenação", sortOptions, current.sort)

        fun value(view: Spinner) = (view.selectedItem as Option).value
        AlertDialog.Builder(context)
            .setTitle("Filtros avançados")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Limpar") { _, _ -> apply(CatalogAdvancedFilters()) }
            .setPositiveButton("Aplicar") { _, _ ->
                val startYear = value(start)?.toIntOrNull()
                val endYear = value(end)?.toIntOrNull()
                val normalizedStart = if (startYear != null && endYear != null) minOf(startYear, endYear) else startYear
                val normalizedEnd = if (startYear != null && endYear != null) maxOf(startYear, endYear) else endYear
                apply(CatalogAdvancedFilters(
                    genreId = value(genre),
                    startYear = normalizedStart, endYear = normalizedEnd,
                    country = value(country), language = value(language), minimumRating = value(rating)?.toIntOrNull(),
                    sort = value(sort) ?: "popularity.desc"
                ))
            }.show()
    }
}
