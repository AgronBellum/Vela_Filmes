package com.streamflixreborn.streamflix.fragments.live_tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.FragmentLiveTvTvBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale

class LiveTvTvFragment : Fragment() {
    private var _binding: FragmentLiveTvTvBinding? = null
    private val binding get() = _binding!!

    private lateinit var categoryAdapter: LiveTvCategoryAdapter
    private lateinit var channelAdapter: LiveTvChannelAdapter
    private var categories: List<LiveTvRepository.Category> = emptyList()
    private var epgJob: Job? = null
    private var categoryEpgJob: Job? = null

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale("pt", "BR"))
        .withZone(LiveTvRepository.brazilZone)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveTvTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupLists()
        loadChannels()
    }

    override fun onDestroyView() {
        epgJob?.cancel()
        categoryEpgJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    private fun setupLists() {
        categoryAdapter = LiveTvCategoryAdapter(::selectCategory)
        channelAdapter = LiveTvChannelAdapter(::openChannel, ::showChannelDetails)

        binding.hgvLiveTvCategories.apply {
            adapter = categoryAdapter
            setItemSpacing(resources.getDimensionPixelSize(R.dimen.live_tv_category_spacing))
        }

        binding.vgvLiveTvChannels.apply {
            adapter = channelAdapter
            setItemSpacing(resources.getDimensionPixelSize(R.dimen.live_tv_channel_spacing))
        }

        binding.btnLiveTvRetry.setOnClickListener { loadChannels() }
    }

    private fun loadChannels() {
        binding.groupLiveTvError.visibility = View.GONE
        binding.pbLiveTvLoading.visibility = View.VISIBLE
        binding.tvLiveTvStatus.text = getString(R.string.live_tv_loading)

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { LiveTvRepository.loadCategories() }
                .onSuccess { result ->
                    categories = result
                    binding.pbLiveTvLoading.visibility = View.GONE
                    binding.tvLiveTvStatus.text = if (result.isEmpty()) getString(R.string.live_tv_empty) else ""
                    categoryAdapter.submitList(result)
                    result.firstOrNull()?.let { firstCategory ->
                        selectCategory(firstCategory)
                        binding.hgvLiveTvCategories.post { binding.hgvLiveTvCategories.requestFocus() }
                    }
                }
                .onFailure { error ->
                    binding.pbLiveTvLoading.visibility = View.GONE
                    binding.groupLiveTvError.visibility = View.VISIBLE
                    binding.tvLiveTvStatus.text = error.message ?: getString(R.string.live_tv_error)
                }
        }
    }

    private fun selectCategory(category: LiveTvRepository.Category) {
        if (categoryAdapter.selectedCategoryId == category.id && channelAdapter.itemCount > 0) return
        categoryAdapter.selectedCategoryId = category.id
        channelAdapter.submitList(category.channels) {
            binding.vgvLiveTvChannels.scrollToPosition(0)
        }
        binding.tvLiveTvCategoryTitle.text = category.name
        binding.tvLiveTvCategorySubtitle.text = resources.getQuantityString(
            R.plurals.live_tv_channel_count,
            category.channels.size,
            category.channels.size,
        )
        category.channels.firstOrNull()?.let(::showChannelDetails)
        preloadCategoryEpg(category.channels)
    }

    private fun preloadCategoryEpg(channels: List<LiveTvRepository.Channel>) {
        categoryEpgJob?.cancel()
        categoryEpgJob = viewLifecycleOwner.lifecycleScope.launch {
            channels.forEach { channel ->
                if (channel.schedule.current == null && channel.schedule.next == null) {
                    val schedule = runCatching { LiveTvRepository.loadSchedule(channel) }.getOrNull() ?: return@forEach
                    channel.schedule = schedule
                    channelAdapter.updateSchedule(channel.id, schedule)
                }
            }
        }
    }

    private fun showChannelDetails(channel: LiveTvRepository.Channel) {
        binding.tvLiveTvSelectedChannel.text = channel.name
        binding.tvLiveTvSelectedCategory.text = channel.categoryName
        updateDetails(channel, channel.schedule)

        epgJob?.cancel()
        epgJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.pbLiveTvEpg.visibility = View.VISIBLE
            val schedule = LiveTvRepository.loadSchedule(channel)
            channel.schedule = schedule
            channelAdapter.updateSchedule(channel.id, schedule)
            updateDetails(channel, schedule)
            binding.pbLiveTvEpg.visibility = View.GONE
        }
    }

    private fun updateDetails(channel: LiveTvRepository.Channel, schedule: LiveTvRepository.Schedule) {
        val current = schedule.current
        val next = schedule.next

        binding.tvLiveTvNowTitle.text = current?.title ?: getString(R.string.live_tv_now_unknown)
        binding.tvLiveTvNowTime.text = current?.let { program ->
            val endText = program.end?.let { timeFormatter.format(it) } ?: "--:--"
            "${timeFormatter.format(program.start)} - $endText"
        } ?: "--:--"

        binding.tvLiveTvNextTitle.text = next?.title ?: getString(R.string.live_tv_next_unknown)
        binding.tvLiveTvNextTime.text = next?.let { timeFormatter.format(it.start) } ?: "--:--"

        val end = current?.end
        val start = current?.start
        if (start != null && end != null) {
            val total = Duration.between(start, end).toMillis().coerceAtLeast(1L)
            val elapsed = Duration.between(start, java.time.Instant.now()).toMillis().coerceIn(0L, total)
            binding.pbLiveTvProgress.progress = ((elapsed * 100) / total).toInt()
        } else {
            binding.pbLiveTvProgress.progress = 0
        }

        binding.btnLiveTvWatch.setOnClickListener { openChannel(channel) }
    }

    private fun openChannel(channel: LiveTvRepository.Channel) {
        if (channel.streamUrl.isBlank()) {
            Toast.makeText(requireContext(), R.string.live_tv_missing_stream, Toast.LENGTH_SHORT).show()
            return
        }
        val headersJson = JSONObject(channel.headers).toString()
        findNavController().navigate(
            R.id.live_tv_player,
            Bundle().apply {
                putString("channelId", channel.id)
                putString("channelName", channel.name)
                putString("streamUrl", channel.streamUrl)
                putString("headers", headersJson)
                putString("nowTitle", channel.schedule.current?.title)
                putString("nextTitle", channel.schedule.next?.title)
                putInt("channelNumber", channel.number)
            }
        )
    }
}




