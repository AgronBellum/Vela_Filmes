package com.streamflixreborn.streamflix.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.utils.CatalogFilter
import com.streamflixreborn.streamflix.utils.CatalogFolder

class CatalogFolderAdapter(
    private val isTv: Boolean,
    private val onClick: (CatalogFilter) -> Unit,
    private val onPreviewNeeded: (CatalogFilter) -> Unit,
) : ListAdapter<CatalogFolder, CatalogFolderAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        fun Int.dp() = (this * context.resources.displayMetrics.density).toInt()
        val image = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(25, 25, 25))
        }
        val shade = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 90.dp(), Gravity.BOTTOM)
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(245, 0, 0, 0), Color.TRANSPARENT)
            )
        }
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = if (isTv) 17f else 15f
            setPadding(12.dp(), 8.dp(), 12.dp(), 6.dp())
            maxLines = 2
        }
        val count = TextView(context).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(12.dp(), 0, 12.dp(), 10.dp())
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            addView(title)
            addView(count)
        }
        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (isTv) 250.dp() else 220.dp())
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = 8.dp().toFloat()
                setColor(Color.rgb(25, 25, 25))
            }
            clipToOutline = true
            addView(image)
            addView(shade)
            addView(labels)
        }
        return Holder(root, image, title, count, onClick, onPreviewNeeded)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position), isTv)

    class Holder(
        private val root: FrameLayout,
        private val image: ImageView,
        private val title: TextView,
        private val count: TextView,
        private val onClick: (CatalogFilter) -> Unit,
        private val onPreviewNeeded: (CatalogFilter) -> Unit,
    ) : RecyclerView.ViewHolder(root) {
        fun bind(folder: CatalogFolder, isTv: Boolean) {
            title.text = folder.filter.title
            count.text = folder.totalResults?.let { "$it títulos" }.orEmpty()
            Glide.with(image).load(folder.poster).centerCrop().into(image)
            root.setOnClickListener { onClick(folder.filter) }
            root.setOnFocusChangeListener { _, focused ->
                val scale = if (focused && isTv) 1.06f else 1f
                root.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                root.alpha = if (focused) 1f else .92f
                val accentValue = TypedValue()
                val accent = if (root.context.theme.resolveAttribute(android.R.attr.colorAccent, accentValue, true)) {
                    accentValue.data
                } else {
                    Color.rgb(124, 92, 255)
                }
                root.foreground = if (focused) {
                    GradientDrawable().apply {
                        cornerRadius = 8f * root.resources.displayMetrics.density
                        setColor(Color.TRANSPARENT)
                        setStroke((3f * root.resources.displayMetrics.density).toInt(), accent)
                    }
                } else null
            }
            if (!folder.previewRequested) onPreviewNeeded(folder.filter)
        }
    }

    private object Diff : DiffUtil.ItemCallback<CatalogFolder>() {
        override fun areItemsTheSame(oldItem: CatalogFolder, newItem: CatalogFolder) = oldItem.filter.id == newItem.filter.id
        override fun areContentsTheSame(oldItem: CatalogFolder, newItem: CatalogFolder) = oldItem == newItem
    }
}
