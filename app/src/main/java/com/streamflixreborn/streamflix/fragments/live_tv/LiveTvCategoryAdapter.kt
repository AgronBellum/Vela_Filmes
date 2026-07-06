package com.streamflixreborn.streamflix.fragments.live_tv

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.databinding.ItemLiveTvCategoryBinding

class LiveTvCategoryAdapter(
    private val onClick: (LiveTvRepository.Category) -> Unit,
) : ListAdapter<LiveTvRepository.Category, LiveTvCategoryAdapter.ViewHolder>(DiffCallback) {

    var selectedCategoryId: String? = null
        set(value) {
            val oldValue = field
            field = value
            currentList.indexOfFirst { it.id == oldValue }.takeIf { it >= 0 }?.let(::notifyItemChanged)
            currentList.indexOfFirst { it.id == value }.takeIf { it >= 0 }?.let(::notifyItemChanged)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemLiveTvCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), getItem(position).id == selectedCategoryId)
    }

    inner class ViewHolder(private val binding: ItemLiveTvCategoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(category: LiveTvRepository.Category, selected: Boolean) {
            binding.tvCategoryName.text = category.name
            binding.tvCategoryCount.visibility = android.view.View.GONE
            binding.root.isSelected = selected
            binding.root.setOnClickListener { onClick(category) }
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onClick(category)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<LiveTvRepository.Category>() {
        override fun areItemsTheSame(oldItem: LiveTvRepository.Category, newItem: LiveTvRepository.Category): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: LiveTvRepository.Category, newItem: LiveTvRepository.Category): Boolean =
            oldItem == newItem
    }
}

