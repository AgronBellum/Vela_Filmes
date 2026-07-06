package com.streamflixreborn.streamflix.fragments.live_tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.FragmentLiveTvPlayerTvBinding
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

class LiveTvPlayerTvFragment : Fragment() {
    private var _binding: FragmentLiveTvPlayerTvBinding? = null
    private val binding get() = _binding!!

    private var player: ExoPlayer? = null
    private var reconnectAttempts = 0
    private val reconnectRunnable = Runnable { prepareLiveStream(isRetry = true) }
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideInfoOverlay() }
    private val numberInputRunnable = Runnable { commitNumberInput() }
    private val epgProgressRunnable = Runnable {
        updateEpgProgress(currentChannel?.schedule)
        scheduleEpgProgressTick()
    }
    private var numberInputBuffer = ""

    private lateinit var categoryAdapter: LiveTvCategoryAdapter
    private lateinit var channelAdapter: LiveTvChannelAdapter
    private var categories: List<LiveTvRepository.Category> = emptyList()
    private var currentChannel: LiveTvRepository.Channel? = null
    private var displayedCategoryId: String? = null
    private var epgJob: Job? = null
    private var categoryEpgJob: Job? = null
    private val epgTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale("pt", "BR"))
        .withZone(LiveTvRepository.brazilZone)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveTvPlayerTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentChannel = initialChannelFromArgs()
        setupSidebar()
        setupRemoteKeys()
        setupTouchControls()
        setupBackHandling()
        updateHeader(currentChannel)
        binding.btnLivePlayerRetry.setOnClickListener { prepareLiveStream() }
        hideSidebar()
        showInfoOverlayTemporarily()
        initializePlayer()
        prepareLiveStream()
        currentChannel?.let(::loadChannelEpg)
        loadSidebarData()
    }

    override fun onResume() {
        super.onResume()
        WindowInsetsControllerCompat(requireActivity().window, requireActivity().window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
        player?.play()
        binding.livePlayerRoot.requestFocus()
    }

    override fun onPause() {
        player?.pause()
        super.onPause()
    }

    override fun onDestroyView() {
        epgJob?.cancel()
        categoryEpgJob?.cancel()
        overlayHandler.removeCallbacks(hideOverlayRunnable)
        overlayHandler.removeCallbacks(reconnectRunnable)
        overlayHandler.removeCallbacks(numberInputRunnable)
        overlayHandler.removeCallbacks(epgProgressRunnable)
        binding.pvLivePlayer.player = null
        player?.release()
        player = null
        _binding = null
        super.onDestroyView()
    }

    private fun initialChannelFromArgs(): LiveTvRepository.Channel = LiveTvRepository.Channel(
        id = requireArguments().getString("channelId").orEmpty(),
        name = requireArguments().getString("channelName").orEmpty(),
        categoryId = "",
        categoryName = "TV ao vivo",
        streamUrl = requireArguments().getString("streamUrl").orEmpty(),
        headers = parseHeaders(requireArguments().getString("headers").orEmpty()),
        number = requireArguments().getInt("channelNumber", 0),
        schedule = LiveTvRepository.Schedule(
            current = requireArguments().getString("nowTitle")?.let {
                LiveTvRepository.Program(it, null, java.time.Instant.now(), null)
            },
            next = requireArguments().getString("nextTitle")?.let {
                LiveTvRepository.Program(it, null, java.time.Instant.now(), null)
            },
        )
    )

    private fun setupSidebar() {
        categoryAdapter = LiveTvCategoryAdapter(::selectCategory).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        channelAdapter = LiveTvChannelAdapter({ channel -> playChannel(channel, closeSidebar = true) }, ::loadChannelEpg).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

        binding.vgvLivePlayerCategories.apply {
            adapter = categoryAdapter
            setItemSpacing(resources.getDimensionPixelSize(R.dimen.live_tv_category_spacing))
        }
        binding.vgvLivePlayerChannels.apply {
            adapter = channelAdapter
            setItemSpacing(resources.getDimensionPixelSize(R.dimen.live_tv_channel_spacing))
        }
    }

    private fun setupRemoteKeys() {
        val keyListener = View.OnKeyListener { _, keyCode, event ->
            handleRemoteKey(keyCode, event)
        }
        binding.livePlayerRoot.setOnKeyListener(keyListener)
        binding.livePlayerSidebar.setOnKeyListener(keyListener)
        binding.vgvLivePlayerCategories.setOnKeyListener(keyListener)
        binding.vgvLivePlayerChannels.setOnKeyListener(keyListener)
    }


    private fun setupTouchControls() {
        binding.livePlayerRoot.setOnClickListener {
            if (binding.livePlayerSidebar.visibility == View.VISIBLE) {
                hideSidebar()
            } else {
                showSidebar()
            }
        }
        binding.livePlayerScrim.setOnClickListener { hideSidebar() }
    }
    private fun setupBackHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.livePlayerSidebar.visibility == View.VISIBLE) {
                        hideSidebar()
                    } else {
                        findNavController().navigateUp()
                    }
                }
            },
        )
    }

    private fun handleRemoteKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        digitForKeyCode(keyCode)?.let { digit ->
            handleNumberInput(digit)
            return true
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when {
                    binding.livePlayerSidebar.visibility == View.VISIBLE -> binding.vgvLivePlayerCategories.requestFocus()
                    binding.livePlayerHeader.visibility == View.VISIBLE -> hideInfoOverlay()
                    else -> showSidebar()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.livePlayerSidebar.visibility == View.VISIBLE) {
                    hideSidebar()
                } else {
                    showInfoOverlayTemporarily(3_000L)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (binding.livePlayerSidebar.visibility != View.VISIBLE) {
                    playAdjacentChannel(-1)
                    true
                } else false
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.livePlayerSidebar.visibility != View.VISIBLE) {
                    playAdjacentChannel(1)
                    true
                } else false
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (binding.livePlayerSidebar.visibility == View.VISIBLE) {
                    hideSidebar()
                    true
                } else {
                    findNavController().navigateUp()
                    true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                showInfoOverlayTemporarily()
                false
            }
            else -> false
        }
    }

    private fun digitForKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> 0
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> 4
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> 5
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> 6
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> 7
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> 8
        KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> 9
        else -> null
    }

    private fun handleNumberInput(digit: Int) {
        numberInputBuffer = (numberInputBuffer + digit).takeLast(maxChannelDigits().coerceAtLeast(2))
        binding.tvLivePlayerNumberInput.text = numberInputBuffer
        binding.tvLivePlayerNumberInput.visibility = View.VISIBLE
        showInfoOverlayTemporarily()
        overlayHandler.removeCallbacks(numberInputRunnable)
        if (numberInputBuffer.length >= maxChannelDigits().coerceAtLeast(2)) {
            commitNumberInput()
        } else {
            overlayHandler.postDelayed(numberInputRunnable, 900)
        }
    }

    private fun commitNumberInput() {
        val typedNumber = numberInputBuffer.toIntOrNull()
        numberInputBuffer = ""
        binding.tvLivePlayerNumberInput.visibility = View.GONE
        overlayHandler.removeCallbacks(numberInputRunnable)
        val channel = typedNumber?.let { number -> allChannels().firstOrNull { it.number == number } } ?: return
        playChannel(channel, closeSidebar = binding.livePlayerSidebar.visibility != View.VISIBLE)
    }

    private fun maxChannelDigits(): Int = allChannels().maxOfOrNull { it.number }?.toString()?.length ?: 2

    private fun allChannels(): List<LiveTvRepository.Channel> = categories.flatMap { it.channels }

    private fun loadSidebarData() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { LiveTvRepository.loadCategories() }
                .onSuccess { loaded ->
                    categories = loaded
                    val loadedCurrent = loaded.flatMap { it.channels }.firstOrNull { it.id == currentChannel?.id }
                    if (loadedCurrent != null) {
                        loadedCurrent.schedule = currentChannel?.schedule ?: loadedCurrent.schedule
                        currentChannel = loadedCurrent
                    }
                    categoryAdapter.submitList(loaded)
                    val selected = loaded.firstOrNull { category ->
                        category.channels.any { it.id == currentChannel?.id }
                    } ?: loaded.firstOrNull()
                    selected?.let { selectCategory(it) }
                    currentChannel?.let(::syncSidebarSelection)
                }
        }
    }

    private fun selectCategory(category: LiveTvRepository.Category) {
        displayedCategoryId = category.id
        categoryAdapter.selectedCategoryId = category.id
        binding.tvLivePlayerSidebarTitle.text = category.name
        channelAdapter.selectedChannelId = currentChannel?.id
        channelAdapter.submitList(category.channels) {
            val index = category.channels.indexOfFirst { it.id == currentChannel?.id }.takeIf { it >= 0 } ?: 0
            binding.vgvLivePlayerChannels.scrollToPosition(index)
            channelAdapter.selectedChannelId = currentChannel?.id
        }
        preloadCategoryEpg(category.channels)
    }

    private fun syncSidebarSelection(channel: LiveTvRepository.Channel) {
        channelAdapter.selectedChannelId = channel.id
        val category = categories.firstOrNull { candidate -> candidate.channels.any { it.id == channel.id } } ?: return
        categoryAdapter.selectedCategoryId = category.id
        if (displayedCategoryId != category.id) {
            displayedCategoryId = category.id
            binding.tvLivePlayerSidebarTitle.text = category.name
            channelAdapter.submitList(category.channels) {
                val index = category.channels.indexOfFirst { it.id == channel.id }.takeIf { it >= 0 } ?: 0
                binding.vgvLivePlayerChannels.scrollToPosition(index)
                channelAdapter.selectedChannelId = channel.id
            }
            preloadCategoryEpg(category.channels)
        } else {
            val index = category.channels.indexOfFirst { it.id == channel.id }.takeIf { it >= 0 } ?: return
            binding.vgvLivePlayerChannels.post { binding.vgvLivePlayerChannels.scrollToPosition(index) }
        }
    }

    private fun preloadCategoryEpg(channels: List<LiveTvRepository.Channel>) {
        categoryEpgJob?.cancel()
        categoryEpgJob = viewLifecycleOwner.lifecycleScope.launch {
            channels.forEach { channel ->
                if (channel.schedule.current == null && channel.schedule.next == null) {
                    val schedule = runCatching { LiveTvRepository.loadSchedule(channel) }.getOrNull() ?: return@forEach
                    channel.schedule = schedule
                    channelAdapter.updateSchedule(channel.id, schedule)
                    if (currentChannel?.id == channel.id) {
                        currentChannel = channel
                        updateHeader(channel)
                    }
                }
            }
        }
    }

    private fun playAdjacentChannel(delta: Int) {
        val channelList = categories.firstOrNull { category ->
            category.channels.any { it.id == currentChannel?.id }
        }?.channels ?: categories.flatMap { it.channels }
        if (channelList.isEmpty()) return

        val currentIndex = channelList.indexOfFirst { it.id == currentChannel?.id }.takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + delta).floorMod(channelList.size)
        playChannel(channelList[nextIndex], closeSidebar = false)
    }

    private fun Int.floorMod(size: Int): Int = ((this % size) + size) % size

    private fun playChannel(channel: LiveTvRepository.Channel, closeSidebar: Boolean = true) {
        currentChannel = channel
        reconnectAttempts = 0
        updateHeader(channel)
        syncSidebarSelection(channel)
        if (closeSidebar) hideSidebar()
        showInfoOverlayTemporarily()
        player?.release()
        player = null
        initializePlayer()
        prepareLiveStream()
        loadChannelEpg(channel)
    }

    private fun loadChannelEpg(channel: LiveTvRepository.Channel) {
        updateHeader(channel)
        epgJob?.cancel()
        epgJob = viewLifecycleOwner.lifecycleScope.launch {
            val schedule = LiveTvRepository.loadSchedule(channel)
            channel.schedule = schedule
            channelAdapter.updateSchedule(channel.id, schedule)
            if (currentChannel?.id == channel.id) {
                currentChannel = channel
                updateHeader(channel)
            }
        }
    }

    private fun showSidebar() {
        binding.livePlayerScrim.visibility = View.VISIBLE
        binding.livePlayerSidebar.visibility = View.VISIBLE
        binding.livePlayerHeader.visibility = View.VISIBLE
        overlayHandler.removeCallbacks(hideOverlayRunnable)
        if (categories.isEmpty()) loadSidebarData() else currentChannel?.let(::syncSidebarSelection)
        binding.vgvLivePlayerChannels.post { binding.vgvLivePlayerChannels.requestFocus() }
    }

    private fun hideSidebar() {
        binding.livePlayerScrim.visibility = View.GONE
        binding.livePlayerSidebar.visibility = View.GONE
        binding.livePlayerRoot.requestFocus()
        showInfoOverlayTemporarily()
    }

    private fun updateHeader(channel: LiveTvRepository.Channel?) {
        val schedule = channel?.schedule
        binding.tvLivePlayerChannel.text = channel?.let { selected ->
            selected.number.takeIf { it > 0 }?.let { number -> "${"%02d".format(number)} - ${selected.name}" } ?: selected.name
        } ?: getString(R.string.main_menu_live_tv)
        binding.tvLivePlayerNow.text = schedule?.current?.title ?: "Programacao carregando"
        binding.tvLivePlayerNext.text = buildNextProgramText(schedule)
        updateEpgProgress(schedule)
        scheduleEpgProgressTick()
    }

    private fun buildNextProgramText(schedule: LiveTvRepository.Schedule?): String {
        val remaining = schedule?.current?.end?.let { end ->
            val minutes = Duration.between(Instant.now(), end).toMinutes().coerceAtLeast(0)
            when {
                minutes >= 60 -> "Faltam ${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}"
                minutes > 1 -> "Faltam $minutes min"
                minutes == 1L -> "Falta 1 min"
                else -> "Terminando agora"
            }
        }
        val next = schedule?.next?.title?.let { getString(R.string.live_tv_player_next, it) }
        return listOfNotNull(remaining, next).joinToString(" - ")
    }

    private fun updateEpgProgress(schedule: LiveTvRepository.Schedule?) {
        val current = schedule?.current
        val start = current?.start
        val end = current?.end
        if (start == null || end == null || !end.isAfter(start)) {
            binding.livePlayerEpgProgressGroup.visibility = View.GONE
            binding.pbLivePlayerEpgProgress.progress = 0
            return
        }

        val now = Instant.now()
        val total = Duration.between(start, end).toMillis().coerceAtLeast(1L)
        val elapsed = Duration.between(start, now).toMillis().coerceIn(0L, total)
        binding.livePlayerEpgProgressGroup.visibility = View.VISIBLE
        binding.tvLivePlayerStartTime.text = epgTimeFormatter.format(start)
        binding.tvLivePlayerEndTime.text = epgTimeFormatter.format(end)
        binding.pbLivePlayerEpgProgress.progress = ((elapsed * binding.pbLivePlayerEpgProgress.max) / total).toInt()
    }

    private fun scheduleEpgProgressTick() {
        overlayHandler.removeCallbacks(epgProgressRunnable)
        if (currentChannel?.schedule?.current?.end == null) return
        overlayHandler.postDelayed(epgProgressRunnable, 30_000L)
    }

    private fun initializePlayer() {
        val dataSourceFactory = buildDataSourceFactory()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 35_000, 1_000, 2_500)
            .build()

        player = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()
            .also { exoPlayer ->
                binding.pvLivePlayer.player = exoPlayer
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) binding.groupLivePlayerError.visibility = View.GONE
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        showError(error.message ?: getString(R.string.live_tv_player_error))
                    }
                })
            }
    }

    private fun prepareLiveStream(isRetry: Boolean = false) {
        if (!isRetry) reconnectAttempts = 0
        overlayHandler.removeCallbacks(reconnectRunnable)
        binding.groupLivePlayerError.visibility = View.GONE

        val channel = currentChannel ?: return
        val mediaItem = MediaItem.Builder()
            .setUri(channel.streamUrl)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(channel.name).build())
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(10_000)
                    .setMinOffsetMs(3_000)
                    .setMaxOffsetMs(35_000)
                    .build()
            )
            .build()

        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(mediaItem, true)
            seekToDefaultPosition()
            prepare()
            playWhenReady = true
        }
    }

    private fun buildDataSourceFactory(): DataSource.Factory {
        val headers = currentChannel?.headers.orEmpty().toMutableMap().apply {
            putIfAbsent("User-Agent", NetworkClient.USER_AGENT)
            putIfAbsent("Accept", "*/*")
            putIfAbsent("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.7")
        }
        return OkHttpDataSource.Factory(LiveTvRepository.httpClient)
            .setDefaultRequestProperties(headers)
    }

    private fun parseHeaders(headersJson: String): Map<String, String> {
        if (headersJson.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(headersJson)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.optString(key)
                    if (value.isNotBlank()) put(key, value)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun showInfoOverlayTemporarily(durationMs: Long = 4_500L) {
        binding.livePlayerHeader.visibility = View.VISIBLE
        overlayHandler.removeCallbacks(hideOverlayRunnable)
        overlayHandler.postDelayed(hideOverlayRunnable, durationMs)
    }

    private fun hideInfoOverlay() {
        if (binding.livePlayerSidebar.visibility == View.VISIBLE) return
        binding.livePlayerHeader.visibility = View.GONE
        overlayHandler.removeCallbacks(hideOverlayRunnable)
    }

    private fun scheduleReconnect(): Boolean {
        if (reconnectAttempts >= 5) return false
        reconnectAttempts += 1
        val delayMs = (2_000L * reconnectAttempts).coerceAtMost(12_000L)
        overlayHandler.postDelayed(reconnectRunnable, delayMs)
        return true
    }

    private fun showError(message: String) {
        val willRetry = scheduleReconnect()
        binding.groupLivePlayerError.visibility = View.VISIBLE
        binding.tvLivePlayerError.text = if (willRetry) "Reconectando ao canal..." else message
        showInfoOverlayTemporarily()
    }
}










