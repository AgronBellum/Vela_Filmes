package com.streamflixreborn.streamflix.fragments.player

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.OpenSubtitles
import com.streamflixreborn.streamflix.utils.StremioSubtitleManager
import com.streamflixreborn.streamflix.utils.StremioSubtitles
import com.streamflixreborn.streamflix.utils.SubDL
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger

class PlayerViewModel(
    videoType: Video.Type,
    id: String,
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.LoadingServers)
    val state: Flow<State> = _state

    private val _subtitleState = MutableSharedFlow<SubtitleState>()
    val subtitleState: SharedFlow<SubtitleState> = _subtitleState

    private val _playPreviousOrNextEpisode = MutableSharedFlow<Video.Type.Episode>()
    val playPreviousOrNextEpisode: SharedFlow<Video.Type.Episode> = _playPreviousOrNextEpisode

    private val serverRequestVersion = AtomicInteger(0)
    private val videoRequestVersion = AtomicInteger(0)

    private val serverValidationSemaphore = Semaphore(3)
    private val serverValidationTimeoutMs = 15_000L

    private var lastVideoType: Video.Type? = null
    private var lastId: String? = null

    init {
        getServers(videoType, id)
        getSubtitles(videoType)
    }

    fun playEpisode(direction: Direction) {
        val hasEpisode = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.hasPreviousEpisode()
            Direction.NEXT -> EpisodeManager.hasNextEpisode()
        }

        if (!hasEpisode) return

        val ep = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.getPreviousEpisode()
            Direction.NEXT -> EpisodeManager.getNextEpisode()
        } ?: return

        val nextEpisode = Video.Type.Episode(
            id = ep.id,
            number = ep.number,
            title = ep.title,
            poster = ep.poster,
            overview = ep.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = ep.tvShow.id,
                title = ep.tvShow.title,
                poster = ep.tvShow.poster,
                banner = ep.tvShow.banner,
                releaseDate = ep.tvShow.releaseDate,
                imdbId = ep.tvShow.imdbId
            ),
            season = Video.Type.Episode.Season(
                number = ep.season.number,
                title = ep.season.title
            )
        )

        playEpisode(nextEpisode)

        viewModelScope.launch {
            _playPreviousOrNextEpisode.emit(nextEpisode)
        }
    }

    enum class Direction { PREVIOUS, NEXT }

    fun playPreviousEpisode() = playEpisode(Direction.PREVIOUS)

    fun playNextEpisode() = playEpisode(Direction.NEXT)

    fun autoplayNextEpisode() {
        if (UserPreferences.autoplay) {
            playEpisode(Direction.NEXT)
        }
    }

    fun playEpisode(episode: Video.Type.Episode) {
        getServers(episode, episode.id)
        getSubtitles(episode)
    }

    private fun getServers(videoType: Video.Type, id: String) = viewModelScope.launch(Dispatchers.IO) {
        val requestVersion = serverRequestVersion.incrementAndGet()
        videoRequestVersion.incrementAndGet()
        Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")
        lastVideoType = videoType
        lastId = id
        _state.emit(State.LoadingServers)
        try {
            val provider = UserPreferences.currentProvider!!
            val candidates = provider.getServers(id, videoType)
            if (requestVersion != serverRequestVersion.get()) return@launch
            if (candidates.isEmpty()) throw Exception("No servers found")

            val servers = validateServers(provider, candidates)
            if (requestVersion != serverRequestVersion.get()) return@launch
            if (servers.isEmpty()) throw Exception("No playable servers found")

            Log.i("StreamFlixES", "[SERVERS LIST] -> Provider: ${UserPreferences.currentProvider!!.name}")
            Log.i("StreamFlixES", "[SERVERS LIST] -> Found ${servers.size} servers: ${servers.joinToString { it.name }}")

            Log.d("PlayerViewModel", "Ricerca server completata: ${servers.size} server trovati")
            _state.emit(State.SuccessLoadingServers(servers))
        } catch (e: Exception) {
            if (requestVersion != serverRequestVersion.get()) return@launch
            Log.e("PlayerViewModel", "Errore ricerca server: ", e)
            _state.emit(State.FailedLoadingServers(e))
        }
    }

    private suspend fun validateServers(
        provider: com.streamflixreborn.streamflix.providers.Provider,
        candidates: List<Video.Server>,
    ): List<Video.Server> = coroutineScope {
        candidates.map { server ->
            async {
                serverValidationSemaphore.withPermit {
                    val video = withTimeoutOrNull(serverValidationTimeoutMs) {
                        runCatching { provider.getVideo(server) }
                            .onFailure { error ->
                                Log.d("PlayerViewModel", "Servidor indisponível: ${server.name}", error)
                            }
                            .getOrNull()
                    }

                    if (video?.source.isNullOrBlank()) {
                        Log.d("PlayerViewModel", "Servidor removido por não ter vídeo: ${server.name}")
                        null
                    } else {
                        server.video = video
                        server
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    fun getVideo(server: Video.Server) = viewModelScope.launch(Dispatchers.IO) {
        val requestVersion = videoRequestVersion.incrementAndGet()
        Log.d("PlayerViewModel", "Inizio estrazione video dal server: ${server.name}")
        _state.emit(State.LoadingVideo(server))
        try {
            val video = UserPreferences.currentProvider!!.getVideo(server)
            if (requestVersion != videoRequestVersion.get()) return@launch
            if (video.source.isEmpty()) throw Exception("No source found")

            val currentProviderLang = UserPreferences.currentProvider?.language ?: ""
            val hasDefaultAlready = video.subtitles.any { it.default }

            val preferredSubtitle = UserPreferences.subtitleName
            if (!hasDefaultAlready && currentProviderLang != "es" && !preferredSubtitle.isNullOrBlank()) {
                if (!(video.useServerSubtitleSetting && UserPreferences.serverAutoSubtitlesDisabled)) {
                    video.subtitles
                        .firstOrNull { it.label.startsWith(preferredSubtitle, ignoreCase = true) }
                        ?.default = true
                }
            }

            Log.d("PlayerViewModel", "Estrazione video completata con successo")
            _state.emit(State.SuccessLoadingVideo(video, server))
        } catch (e: Exception) {
            if (requestVersion != videoRequestVersion.get()) return@launch
            Log.e("PlayerViewModel", "Errore estrazione video: ", e)
            _state.emit(State.FailedLoadingVideo(e, server))
        }
    }

    fun getSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca sottotitoli")
        _subtitleState.emit(SubtitleState.Loading)

        // OpenSubtitles
        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca OpenSubtitles")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        OpenSubtitles.search(
                            query = videoType.tvShow.title,
                            season = videoType.season.number,
                            episode = videoType.number,
                        )
                    }
                    is Video.Type.Movie -> {
                        OpenSubtitles.search(
                            query = videoType.title,
                        )
                    }
                }
                    .let { filterOpenSubtitles(videoType, it) }
                    .sortedWith(compareBy({ it.languageName }, { it.subDownloadsCnt }))

                Log.d("PlayerViewModel", "Ricerca OpenSubtitles completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessOpenSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore OpenSubtitles: ", e)
                _subtitleState.emit(SubtitleState.FailedOpenSubtitles(e))
            }
        }

        // SubDL
        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca SubDL")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        SubDL.search(
                            filmName = videoType.tvShow.title,
                            seasonNumber = videoType.season.number,
                            episodeNumber = videoType.number,
                            type = "tv"
                        )
                    }
                    is Video.Type.Movie -> {
                        SubDL.search(
                            filmName = videoType.title,
                            type = "movie"
                        )
                    }
                }

                val filteredSubtitles = filterSubDLSubtitles(videoType, subtitles)
                Log.d("PlayerViewModel", "Ricerca SubDL completata: ${filteredSubtitles.size}/${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessSubDLSubtitles(filteredSubtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore SubDL: ", e)
                _subtitleState.emit(SubtitleState.FailedSubDLSubtitles(e))
            }
        }

        // Stremio Subtitles
        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca Stremio")
                val imdbId = when (videoType) {
                    is Video.Type.Episode -> videoType.tvShow.imdbId ?: ""
                    is Video.Type.Movie -> videoType.imdbId ?: ""
                }
                
                if (imdbId.isNotBlank()) {
                    val isSeries = videoType is Video.Type.Episode
                    val season = if (isSeries) (videoType as Video.Type.Episode).season.number else 1
                    val episode = if (isSeries) (videoType as Video.Type.Episode).number else 1

                    val externalSubs = StremioSubtitleManager.getExternalSubtitles(
                        isSeries = isSeries,
                        imdbId = imdbId,
                        season = season,
                        episode = episode
                    )

                    _subtitleState.emit(SubtitleState.SuccessStremioSubtitles(externalSubs))
                } else {
                    _subtitleState.emit(SubtitleState.SuccessStremioSubtitles(emptyList()))
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore Stremio: ", e)
                _subtitleState.emit(SubtitleState.FailedStremioSubtitles(e))
            }
        }
    }

    private fun filterOpenSubtitles(
        videoType: Video.Type,
        subtitles: List<OpenSubtitles.Subtitle>,
    ): List<OpenSubtitles.Subtitle> = subtitles.filter { subtitle ->
        when (videoType) {
            is Video.Type.Episode -> {
                val combined = listOfNotNull(
                    subtitle.movieName,
                    subtitle.movieNameEng,
                    subtitle.movieReleaseName,
                    subtitle.subFileName,
                    subtitle.infoOther,
                ).joinToString(" ")
                titleMatches(videoType.tvShow.title, combined) && episodeMatches(
                    combined = combined,
                    season = videoType.season.number,
                    episode = videoType.number,
                    seasonField = subtitle.seriesSeason,
                    episodeField = subtitle.seriesEpisode,
                )
            }

            is Video.Type.Movie -> {
                val combined = listOfNotNull(
                    subtitle.movieName,
                    subtitle.movieNameEng,
                    subtitle.movieReleaseName,
                    subtitle.subFileName,
                ).joinToString(" ")
                titleMatches(videoType.title, combined)
            }
        }
    }

    private fun filterSubDLSubtitles(
        videoType: Video.Type,
        subtitles: List<SubDL.Subtitle>,
    ): List<SubDL.Subtitle> = subtitles.filter { subtitle ->
        val combined = listOfNotNull(subtitle.name, subtitle.releaseName).joinToString(" ")
        when (videoType) {
            is Video.Type.Episode -> titleMatches(videoType.tvShow.title, combined) &&
                    episodeMatches(combined, videoType.season.number, videoType.number)

            is Video.Type.Movie -> titleMatches(videoType.title, combined)
        }
    }

    private fun titleMatches(expectedTitle: String, candidate: String): Boolean {
        val expected = normalizeSubtitleMatchText(expectedTitle)
        val actual = normalizeSubtitleMatchText(candidate)
        if (expected.isBlank() || actual.isBlank()) return false
        return actual.contains(expected) || expected.contains(actual)
    }

    private fun episodeMatches(
        combined: String,
        season: Int,
        episode: Int,
        seasonField: String? = null,
        episodeField: String? = null,
    ): Boolean {
        val fieldSeason = seasonField?.toIntOrNull()
        val fieldEpisode = episodeField?.toIntOrNull()
        if (fieldSeason != null || fieldEpisode != null) {
            return fieldSeason == season && fieldEpisode == episode
        }

        val text = normalizeSubtitleMatchText(combined)
        val seasonPadded = season.toString().padStart(2, '0')
        val episodePadded = episode.toString().padStart(2, '0')
        val compact = text.replace(" ", "")
        return compact.contains("s${seasonPadded}e${episodePadded}") ||
                compact.contains("s${season}e${episodePadded}") ||
                compact.contains("${season}x${episodePadded}") ||
                compact.contains("${season}x${episode}")
    }

    private fun normalizeSubtitleMatchText(value: String?): String = Normalizer
        .normalize(value.orEmpty().lowercase(), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()

    fun downloadSubtitle(subtitle: OpenSubtitles.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo OpenSubtitles: ${subtitle.subFileName}")
        _subtitleState.emit(SubtitleState.DownloadingOpenSubtitle)
        try {
            val uri = OpenSubtitles.download(subtitle)
            Log.d("PlayerViewModel", "Download OpenSubtitles completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingOpenSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download OpenSubtitles: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingOpenSubtitle(e, subtitle))
        }
    }

    fun downloadStremioSubtitle(context: android.content.Context, subtitle: StremioSubtitles.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Início download legenda Stremio: ${subtitle.lang}")
         _subtitleState.emit(SubtitleState.DownloadingStremioSubtitle)
        try {
             val uri = StremioSubtitleManager.downloadAndNormalize(context, subtitle.url ?: "")
             val mimeType = StremioSubtitleManager.getMimeType(subtitle.url ?: "")
        
            Log.d("PlayerViewModel", "Download Stremio concluído: $uri")
            _subtitleState.emit(
                SubtitleState.SuccessDownloadingStremioSubtitle(
                    uri, subtitle.lang ?: "Portuguese", mimeType, subtitle.languageTag ?: "pt"
                )
            )
        }  catch (e: Exception) {
           Log.e("PlayerViewModel", "Erro no download da legenda Stremio: ", e)
             _subtitleState.emit(SubtitleState.FailedDownloadingStremioSubtitle(e))
        }
    }

    fun downloadSubDLSubtitle(subtitle: SubDL.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo SubDL: ${subtitle.name}")
        _subtitleState.emit(SubtitleState.DownloadingSubDLSubtitle)
        try {
            val uri = SubDL.download(subtitle)
            Log.d("PlayerViewModel", "Download SubDL completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingSubDLSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download SubDL: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingSubDLSubtitle(e, subtitle))
        }
    }

    fun reloadServersAfterBypass() {
        val type = lastVideoType ?: return
        val id = lastId ?: return
        getServers(type, id)
    }

    sealed class State {
        data object LoadingServers : State()
        data class SuccessLoadingServers(val servers: List<Video.Server>) : State()
        data class FailedLoadingServers(val error: Exception) : State()
        data class LoadingVideo(val server: Video.Server) : State()
        data class SuccessLoadingVideo(val video: Video, val server: Video.Server) : State()
        data class FailedLoadingVideo(val error: Exception, val server: Video.Server) : State()
    }

    sealed class SubtitleState {
        data object Loading : SubtitleState()
        
        data class SuccessOpenSubtitles(val subtitles: List<OpenSubtitles.Subtitle>) : SubtitleState()
        data class FailedOpenSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingOpenSubtitle : SubtitleState()
        data class SuccessDownloadingOpenSubtitle(val subtitle: OpenSubtitles.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingOpenSubtitle(val error: Exception, val subtitle: OpenSubtitles.Subtitle) : SubtitleState()

        data class SuccessSubDLSubtitles(val subtitles: List<SubDL.Subtitle>) : SubtitleState()
        data class FailedSubDLSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingSubDLSubtitle : SubtitleState()
        data class SuccessDownloadingSubDLSubtitle(val subtitle: SubDL.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingSubDLSubtitle(val error: Exception, val subtitle: SubDL.Subtitle) : SubtitleState()

        // Adicionados para compatibilidade com Stremio no Fragment
        data class SuccessStremioSubtitles(val subtitles: List<StremioSubtitles.Subtitle>) : SubtitleState()
        data class FailedStremioSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingStremioSubtitle : SubtitleState()
        data class SuccessDownloadingStremioSubtitle(
            val uri: Uri, val label: String, val mimeType: String, val languageTag: String
        ) : SubtitleState()
        data class FailedDownloadingStremioSubtitle(val error: Exception) : SubtitleState()
    }
}
