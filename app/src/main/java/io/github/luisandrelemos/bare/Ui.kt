package io.github.luisandrelemos.bare

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

fun Context.label(value: String, sp: Float, color: Int, family: String = "sans-serif") = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    typeface = Typeface.create(family, Typeface.NORMAL)
}

/** A one-color icon, tinted to [color]. */
fun Context.icon(resource: Int, color: Int) = ImageView(this).apply {
    setImageResource(resource)
    setColorFilter(color, PorterDuff.Mode.SRC_IN)
}

/** An icon drawn in its own colors (the weather's sun is yellow, its rain blue): never tinted. */
fun Context.colorIcon(resource: Int) = ImageView(this).apply { setImageResource(resource) }

fun linearParams(width: Int, height: Int, weight: Float = 0f) = LinearLayout.LayoutParams(width, height, weight)

/** The buttons, inputs and weather card: a thin border around an almost clear inside. */
class Outline(context: Context, private val theme: Theme, radius: Int) {
    private val width = maxOf(1, context.dp(1))

    val drawable = GradientDrawable().apply {
        cornerRadius = context.dp(radius).toFloat()
        setColor(withAlpha(theme.text, CLEAR))
        setStroke(width, withAlpha(theme.text, BORDER))
    }

    /** Focus that fills it with the text color (buttons, inputs). */
    fun solid(focused: Boolean) {
        drawable.setColor(if (focused) theme.text else withAlpha(theme.text, CLEAR))
    }

    /** Focus that only brightens the border, for a box whose content keeps its colors (the weather card). */
    fun lit(focused: Boolean) {
        drawable.setStroke(width, withAlpha(theme.text, if (focused) 0xD0 else BORDER))
    }

    private companion object {
        const val BORDER = 0x40
        const val CLEAR = 0x08
    }
}

/** Focus on an outline: it turns solid, its icon and text take the background's color, and it grows a little. */
fun View.showOutlineFocus(focused: Boolean, outline: Outline, theme: Theme, scale: Float, icon: ImageView, name: TextView) {
    outline.solid(focused)
    icon.setColorFilter(if (focused) theme.background else theme.text, PorterDuff.Mode.SRC_IN)
    name.setTextColor(if (focused) theme.background else theme.text)
    val size = if (focused) scale else 1f
    animate().scaleX(size).scaleY(size).setDuration(120).start()
}

val ViewGroup.children: List<View> get() = (0 until childCount).map { getChildAt(it) }

/** Left from a row's first item and Right from its last stay put, instead of jumping to another row. */
fun LinearLayout.pinEnds() {
    for (child in children) {
        child.nextFocusLeftId = View.NO_ID
        child.nextFocusRightId = View.NO_ID
    }
    val first = getChildAt(0) ?: return
    val last = getChildAt(childCount - 1)
    for (end in setOf(first, last)) if (end.id == View.NO_ID) end.id = View.generateViewId()
    first.nextFocusLeftId = first.id
    last.nextFocusRightId = last.id
}

/**
 * A column that fades without an offscreen layer: each child is drawn with the alpha instead of the whole group being
 * rendered into a buffer first. Its children barely overlap, so it looks the same and costs the GPU nothing extra.
 */
class Section(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
    }

    override fun hasOverlappingRendering() = false
}

/** The same, for a frame. */
class Faded(context: Context) : FrameLayout(context) {
    override fun hasOverlappingRendering() = false
}
