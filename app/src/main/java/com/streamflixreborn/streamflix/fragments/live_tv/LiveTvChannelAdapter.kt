package com.streamflixreborn.streamflix.fragments.live_tv

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.databinding.ItemLiveTvChannelBinding
import java.time.format.DateTimeFormatter
import java.util.Locale

class LiveTvChannelAdapter(
    private val onClick: (LiveTvRepository.Channel) -> Unit,
    private val onFocus: (LiveTvRepository.Channel) -> Unit,
) : ListAdapter<LiveTvRepository.Channel, LiveTvChannelAdapter.ViewHolder>(DiffCallback) {

    var selectedChannelId: String? = null
        set(value) {
            if (field == value) return
            val oldIndex = currentList.indexOfFirst { it.id == field }
            field = value
            val newIndex = currentList.indexOfFirst { it.id == value }
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
            if (newIndex >= 0) notifyItemChanged(newIndex)
        }

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale("pt", "BR"))
        .withZone(LiveTvRepository.brazilZone)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemLiveTvChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun updateSchedule(channelId: String, schedule: LiveTvRepository.Schedule) {
        val index = currentList.indexOfFirst { it.id == channelId }
        if (index == -1) return
        currentList[index].schedule = schedule
        notifyItemChanged(index)
    }

    inner class ViewHolder(private val binding: ItemLiveTvChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(channel: LiveTvRepository.Channel) {
            binding.tvChannelNumber.text = channel.number.takeIf { it > 0 }?.let { "%02d".format(it) } ?: "--"
            binding.tvChannelName.text = channel.name
            binding.tvChannelCategory.text = channel.categoryName
            binding.tvChannelNow.text = channel.schedule.current?.title ?: "Programacao carregando"
            binding.tvChannelNext.text = channel.schedule.next?.let { "Proximo: ${timeFormatter.format(it.start)} - ${it.title}" } ?: ""
            binding.root.isSelected = channel.id == selectedChannelId
            binding.root.setOnClickListener { onClick(channel) }
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocus(channel)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<LiveTvRepository.Channel>() {
        override fun areItemsTheSame(oldItem: LiveTvRepository.Channel, newItem: LiveTvRepository.Channel): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: LiveTvRepository.Channel, newItem: LiveTvRepository.Channel): Boolean =
            oldItem == newItem
    }
}


