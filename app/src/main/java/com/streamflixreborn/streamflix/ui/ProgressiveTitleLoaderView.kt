package com.streamflixreborn.streamflix.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

class ProgressiveTitleLoaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val dimLogo = logoImage(0.22f)
    private val brightLogo = logoImage(1f)
    private val dimTitle = titleText(0.22f)
    private val brightTitle = titleText(1f)
    private var progress = 0f
    private var target: CustomTarget<Drawable>? = null

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_800L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            updateClip()
        }
    }

    init {
        isClickable = false
        isFocusable = false
        addView(dimLogo)
        addView(brightLogo)
        addView(dimTitle)
        addView(brightTitle)
        showTitleFallback()
        visibility = View.GONE
    }

    fun setTitle(title: String) {
        val value = title.trim().ifBlank { "VELA" }.uppercase()
        dimTitle.text = value
        brightTitle.text = value
    }

    fun setLogo(url: String?) {
        target?.let { Glide.with(this).clear(it) }
        target = null
        if (url.isNullOrBlank()) {
            showTitleFallback()
            return
        }

        target = object : CustomTarget<Drawable>() {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                dimLogo.setImageDrawable(resource.constantState?.newDrawable()?.mutate() ?: resource)
                brightLogo.setImageDrawable(resource.constantState?.newDrawable()?.mutate() ?: resource)
                dimLogo.visibility = View.VISIBLE
                brightLogo.visibility = View.VISIBLE
                dimTitle.visibility = View.GONE
                brightTitle.visibility = View.GONE
                updateClip()
            }

            override fun onLoadFailed(errorDrawable: Drawable?) = showTitleFallback()
            override fun onLoadCleared(placeholder: Drawable?) = Unit
        }.also { Glide.with(this).load(url).fitCenter().into(it) }
    }

    fun startLoading() {
        visibility = View.VISIBLE
        if (!animator.isStarted) animator.start()
    }

    fun stopLoading() {
        animator.cancel()
        progress = 0f
        visibility = View.GONE
        updateClip()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateClip()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        target?.let { Glide.with(context.applicationContext).clear(it) }
        target = null
        super.onDetachedFromWindow()
    }

    private fun updateClip() {
        val clip = Rect(0, 0, (width * progress).toInt(), height)
        brightLogo.clipBounds = clip
        brightTitle.clipBounds = clip
    }

    private fun showTitleFallback() {
        dimLogo.visibility = View.GONE
        brightLogo.visibility = View.GONE
        dimTitle.visibility = View.VISIBLE
        brightTitle.visibility = View.VISIBLE
        updateClip()
    }

    private fun logoImage(alphaValue: Float) = ImageView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        alpha = alphaValue
    }

    private fun titleText(alphaValue: Float) = TextView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 32f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(12f, 0f, 3f, Color.BLACK)
        alpha = alphaValue
        maxLines = 2
    }
}
