package com.streamflixreborn.streamflix.fragments.tv_shows

import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.utils.CatalogFilters
import com.streamflixreborn.streamflix.utils.CatalogViewModel

class TvShowsViewModel(@Suppress("UNUSED_PARAMETER") database: AppDatabase) :
    CatalogViewModel("tv", CatalogFilters.tvShows())
