package com.streamflixreborn.streamflix.fragments.movies

import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.utils.CatalogFilters
import com.streamflixreborn.streamflix.utils.CatalogViewModel

class MoviesViewModel(@Suppress("UNUSED_PARAMETER") database: AppDatabase) :
    CatalogViewModel("movie", CatalogFilters.movies())
