package com.streamflixreborn.streamflix.utils

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CatalogFolder(
    val filter: CatalogFilter,
    val poster: String? = null,
    val totalResults: Int? = null,
    val previewRequested: Boolean = false,
)

abstract class CatalogViewModel(
    private val mediaType: String,
    initialFilters: List<CatalogFilter>,
) : ViewModel() {
    enum class Mode { TITLES, FOLDERS }

    data class UiState(
        val filters: List<CatalogFilter>,
        val selectedId: String,
        val title: String,
        val mode: Mode = Mode.TITLES,
        val folders: List<CatalogFolder> = emptyList(),
        val activeFilter: CatalogFilter? = null,
        val items: List<AppAdapter.Item> = emptyList(),
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = true,
        val error: String? = null,
        val query: String = "",
        val advanced: CatalogAdvancedFilters = CatalogAdvancedFilters(),
        val totalResults: Int? = null,
    )

    private val first = initialFilters.first { !it.section && !it.isFolder }
    private val _state = MutableStateFlow(
        UiState(initialFilters, first.id, first.title, activeFilter = first)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var page = 1
    private var searchJob: Job? = null
    private var requestJob: Job? = null
    private val previewJobs = mutableMapOf<String, Job>()

    init {
        reload()
        viewModelScope.launch { ProviderChangeNotifier.providerChangeFlow.collect { reload() } }
    }

    fun select(filter: CatalogFilter) {
        if (filter.section) return
        searchJob?.cancel()
        requestJob?.cancel()
        if (filter.isFolder) {
            previewJobs.values.forEach(Job::cancel)
            previewJobs.clear()
            _state.value = _state.value.copy(
                selectedId = filter.id,
                title = filter.title,
                mode = Mode.FOLDERS,
                folders = filter.children.map(::CatalogFolder),
                activeFilter = null,
                items = emptyList(),
                loading = false,
                loadingMore = false,
                hasMore = false,
                error = null,
                query = "",
                advanced = CatalogAdvancedFilters(),
                totalResults = filter.children.size,
            )
        } else {
            _state.value = _state.value.copy(
                selectedId = filter.id, title = filter.title, mode = Mode.TITLES,
                folders = emptyList(), activeFilter = filter, query = "",
                advanced = CatalogAdvancedFilters()
            )
            reload()
        }
    }

    fun openFolder(filter: CatalogFilter) {
        requestJob?.cancel()
        _state.value = _state.value.copy(
            title = filter.title, mode = Mode.TITLES, activeFilter = filter,
            items = emptyList(), loading = true, loadingMore = false, hasMore = true,
            error = null, query = "", totalResults = null,
        )
        reload()
    }

    fun backToFolders(): Boolean {
        val current = _state.value
        val parent = current.filters.firstOrNull { it.id == current.selectedId && it.isFolder } ?: return false
        if (current.mode != Mode.TITLES || current.activeFilter == null) return false
        select(parent)
        return true
    }

    fun requestFolderPreview(filter: CatalogFilter) {
        val current = _state.value
        if (current.mode != Mode.FOLDERS || previewJobs.containsKey(filter.id)) return
        val index = current.folders.indexOfFirst { it.filter.id == filter.id }
        if (index < 0 || current.folders[index].previewRequested) return
        _state.value = current.copy(folders = current.folders.toMutableList().also {
            it[index] = it[index].copy(previewRequested = true)
        })
        previewJobs[filter.id] = viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { fetchFilter(filter, 1) } }.getOrNull()
            val latest = _state.value
            if (latest.mode == Mode.FOLDERS && latest.selectedId == current.selectedId) {
                val folderIndex = latest.folders.indexOfFirst { it.filter.id == filter.id }
                if (folderIndex >= 0) {
                    val firstItem = result?.items?.firstOrNull()
                    val poster = when (firstItem) {
                        is Movie -> firstItem.poster ?: firstItem.banner
                        is TvShow -> firstItem.poster ?: firstItem.banner
                        else -> null
                    }
                    _state.value = latest.copy(folders = latest.folders.toMutableList().also {
                        it[folderIndex] = it[folderIndex].copy(poster = poster, totalResults = result?.totalResults)
                    })
                }
            }
            previewJobs.remove(filter.id)
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        val current = _state.value
        val firstFilter = current.filters.first { !it.section && !it.isFolder }
        _state.value = current.copy(
            query = query,
            mode = Mode.TITLES,
            activeFilter = firstFilter,
            folders = emptyList(),
            advanced = if (query.isNotBlank()) CatalogAdvancedFilters() else current.advanced,
        )
        searchJob = viewModelScope.launch { delay(400); reload() }
    }

    fun applyAdvanced(filters: CatalogAdvancedFilters) {
        if (!filters.isActive) return clearAdvanced()
        searchJob?.cancel()
        val generated = CatalogFilter(CatalogFilters.advancedId(mediaType, filters), "Resultados filtrados", "FILTROS")
        _state.value = _state.value.copy(
            title = generated.title, mode = Mode.TITLES, folders = emptyList(), activeFilter = generated,
            query = "", advanced = filters
        )
        reload()
    }

    fun clearAdvanced() {
        val firstFilter = _state.value.filters.first { !it.section && !it.isFolder }
        _state.value = _state.value.copy(
            selectedId = firstFilter.id, title = firstFilter.title, mode = Mode.TITLES,
            folders = emptyList(), activeFilter = firstFilter,
            advanced = CatalogAdvancedFilters(), query = ""
        )
        reload()
    }

    fun reload() {
        requestJob?.cancel()
        val snapshot = _state.value
        if (snapshot.mode == Mode.FOLDERS) return
        page = 1
        _state.value = snapshot.copy(loading = true, loadingMore = false, error = null, items = emptyList())
        requestJob = viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { fetch(1) } }
                .onSuccess { result ->
                    _state.value = _state.value.copy(
                        items = result.items, loading = false,
                        hasMore = hasAnotherPage(result, 1),
                        error = null, totalResults = result.totalResults,
                    )
                }
                .onFailure { error ->
                    Log.e("CatalogViewModel", "Unable to load $mediaType catalog", error)
                    _state.value = _state.value.copy(loading = false, error = error.message ?: "Falha ao carregar")
                }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.mode != Mode.TITLES || current.loading || current.loadingMore || !current.hasMore || current.error != null) return
        // The guard is changed on the main thread before IO starts, so one scroll can create only one request.
        _state.value = current.copy(loadingMore = true)
        val requestedPage = page + 1
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { fetch(requestedPage) } }
                .onSuccess { result ->
                    val latest = _state.value
                    if (result.items.isNotEmpty()) page = requestedPage
                    val merged = (latest.items + result.items).distinctBy(::itemId)
                    _state.value = latest.copy(
                        items = merged,
                        loadingMore = false,
                        hasMore = hasAnotherPage(result, requestedPage),
                        totalResults = result.totalResults ?: latest.totalResults,
                    )
                }
                .onFailure { error ->
                    Log.e("CatalogViewModel", "Unable to load next page", error)
                    _state.value = _state.value.copy(loadingMore = false, error = error.message ?: "Falha ao carregar")
                }
        }
    }

    private data class FetchResult(val items: List<AppAdapter.Item>, val totalResults: Int?)

    private suspend fun fetch(requestPage: Int): FetchResult {
        val current = _state.value
        if (current.query.isNotBlank()) {
            val provider = UserPreferences.currentProvider ?: return FetchResult(emptyList(), null)
            val result = provider.search(current.query.trim(), requestPage)
            return typed(result, null)
        }
        return fetchFilter(current.activeFilter ?: first, requestPage)
    }

    private suspend fun fetchFilter(filter: CatalogFilter, requestPage: Int): FetchResult {
        val provider = UserPreferences.currentProvider ?: return FetchResult(emptyList(), null)
        val genre = provider.getGenre(filter.id, requestPage)
        return typed(genre.shows, genre.totalResults)
    }

    private suspend fun typed(result: List<*>, total: Int?): FetchResult {
        val items = if (mediaType == "movie") result.filterIsInstance<Movie>() else result.filterIsInstance<TvShow>()
        return FetchResult(ParentalControlUtils.filterItems(items).filterIsInstance<AppAdapter.Item>(), total)
    }

    private fun itemId(item: AppAdapter.Item): String = when (item) {
        is Movie -> "movie:${item.id}"
        is TvShow -> "tv:${item.id}"
        else -> item.hashCode().toString()
    }

    private fun hasAnotherPage(result: FetchResult, requestedPage: Int): Boolean =
        result.totalResults?.let { requestedPage * PAGE_SIZE < it } ?: (result.items.size >= PAGE_SIZE)

    companion object { private const val PAGE_SIZE = 20 }
}
