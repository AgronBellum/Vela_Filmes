package com.streamflixreborn.streamflix.adapters

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.utils.CatalogFilter
import com.streamflixreborn.streamflix.utils.dp

class CatalogMenuAdapter(
    private val onClick: (CatalogFilter) -> Unit,
) : RecyclerView.Adapter<CatalogMenuAdapter.Holder>() {
    private var items = emptyList<CatalogFilter>()
    private var selectedId: String? = null

    init { setHasStableIds(true) }

    fun submitList(value: List<CatalogFilter>, selected: String?) {
        if (items == value && selectedId == selected) return
        if (items == value) {
            select(selected.orEmpty())
            return
        }
        items = value
        selectedId = selected
        notifyDataSetChanged()
    }

    fun select(id: String) {
        val old = items.indexOfFirst { it.id == selectedId }
        val new = items.indexOfFirst { it.id == id }
        selectedId = id
        if (old >= 0) notifyItemChanged(old)
        if (new >= 0) notifyItemChanged(new)
    }

    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val text = TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(context), 11.dp(context), 14.dp(context), 11.dp(context))
            textSize = 15f
            isFocusable = true
            isClickable = true
        }
        return Holder(text)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val selected = item.id == selectedId
        holder.text.apply {
            text = item.title
            isFocusable = !item.section
            isClickable = !item.section
            textSize = if (item.section) 11f else 15f
            setTypeface(typeface, if (item.section || selected) Typeface.BOLD else Typeface.NORMAL)
            alpha = if (item.section) .75f else 1f
            setOnClickListener { if (!item.section) onClick(item) }
            fun applyFocusStyle(focused: Boolean) {
                val accent = resolveAccentColor(this)
                val fill = when {
                    focused -> accent
                    selected -> Color.argb(145, Color.red(accent), Color.green(accent), Color.blue(accent))
                    else -> Color.TRANSPARENT
                }
                background = GradientDrawable().apply {
                    cornerRadius = 9.dp(context).toFloat()
                    setColor(fill)
                    if (focused) setStroke(2.dp(context), Color.WHITE)
                }
                setTextColor(
                    when {
                        item.section -> Color.GRAY
                        focused || selected -> Color.WHITE
                        else -> Color.LTGRAY
                    }
                )
                animate().scaleX(if (focused) 1.03f else 1f)
                    .scaleY(if (focused) 1.03f else 1f)
                    .setDuration(100L)
                    .start()
            }
            applyFocusStyle(hasFocus())
            setOnFocusChangeListener { _, focused -> applyFocusStyle(focused) }
        }
    }

    private fun resolveAccentColor(view: TextView): Int {
        val value = TypedValue()
        return if (view.context.theme.resolveAttribute(android.R.attr.colorAccent, value, true)) {
            value.data
        } else {
            Color.rgb(124, 92, 255)
        }
    }

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
}
