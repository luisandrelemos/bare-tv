package io.github.luisandrelemos.bare

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.animation.PathInterpolator
import android.widget.ScrollView

/**
 * A ScrollView that never scrolls by itself. A plain one jumps as soon as focus moves; here the arrows only move
 * focus, and Home animates the page with [glideTo].
 */
@SuppressLint("ViewConstructor")
class Page(context: Context) : ScrollView(context) {

    private var glide: ValueAnimator? = null

    init {
        isVerticalScrollBarEnabled = false
        isFocusable = false
    }

    /** Slides to [y] (clamped to the content), from wherever it is now, even mid-slide. */
    fun glideTo(y: Int) {
        val content = getChildAt(0) ?: return
        if (height == 0) {
            post { glideTo(y) }
            return
        }
        val target = y.coerceIn(0, (content.height - height).coerceAtLeast(0))
        glide?.cancel()
        if (target == scrollY) return
        glide = ValueAnimator.ofInt(scrollY, target).apply {
            duration = GLIDE_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }

    /** The arrows move focus only (the window's own focus search finds the next item, on screen or not). */
    override fun executeKeyEvent(event: KeyEvent) = false

    /** Focus moving never scrolls: Home decides where the page goes. */
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?) = 0

    companion object {
        private const val GLIDE_MS = 450L
    }
}
