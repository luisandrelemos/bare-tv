package io.github.luisandrelemos.bare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * One app: a rounded card with its art, its name underneath, and a ring when focused. In the dock ([nameOnFocus])
 * the name only shows while the tile has focus. A small dot marks a [favorite] in All apps.
 */
@SuppressLint("ViewConstructor")
class Tile(
    context: Context,
    val app: App,
    private val theme: Theme,
    width: Int,
    private val nameOnFocus: Boolean = false,
    favorite: Boolean = false,
) : LinearLayout(context) {

    /** Called each time this tile gains focus. */
    var onFocus: (() -> Unit)? = null

    /** Holding OK. Unset, a long press is an ordinary press. */
    var onLongPress: (() -> Unit)? = null

    private val ring = GradientDrawable()
    private val label = TextView(context)
    private val density = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        isFocusable = true
        isClickable = true
        clipChildren = false

        val radius = theme.cornerRadius * density
        // Rounded by the art's shader, not by clipping: a rounded clip costs the GPU a mask per tile per frame.
        val card = FrameLayout(context).apply {
            background = LayerDrawable(arrayOf(
                GradientDrawable().apply { setColor(theme.tile); cornerRadius = radius },
                RoundedArt(app.art, radius),
            ))
            foreground = ring
        }
        ring.cornerRadius = radius
        ring.setColor(Color.TRANSPARENT)
        ring.setStroke((3 * density).toInt(), theme.accent)
        ring.alpha = 0
        if (favorite) {
            val size = (9 * density).toInt()
            card.addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.accent)
                    setStroke((1.5f * density).toInt(), 0x66000000)
                }
            }, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                topMargin = (8 * density).toInt()
                rightMargin = (8 * density).toInt()
            })
        }
        addView(card, LayoutParams(MATCH_PARENT, width * 9 / 16))

        label.text = app.label
        label.textSize = 14f
        label.setTextColor(theme.dim)
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.setPadding(0, (8 * density).toInt(), 0, 0)
        label.visibility = if (nameOnFocus || theme.labels) VISIBLE else GONE
        if (nameOnFocus) {
            label.alpha = 0f
            label.gravity = Gravity.CENTER_HORIZONTAL
        }
        addView(label, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setOnClickListener { open() }
        setOnLongClickListener { onLongPress?.let { it(); true } ?: false }
        setOnFocusChangeListener { _, focused -> showFocus(focused) }
    }

    /** Being moved in the dock: lifted, with how to move it where its name was. */
    fun setMoving(moving: Boolean) {
        label.text = if (moving) context.getString(R.string.move_hint) else app.label
        label.ellipsize = if (moving) null else TextUtils.TruncateAt.END
        animate().translationY(if (moving) -10 * density else 0f).setDuration(120).start()
    }

    private fun showFocus(focused: Boolean) {
        if (focused) onFocus?.invoke()
        val scale = if (focused) theme.focusScale else 1f
        animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        ring.alpha = if (focused) 255 else 0
        label.setTextColor(if (focused) theme.text else theme.dim)
        if (nameOnFocus) label.animate().alpha(if (focused) 1f else 0f).setDuration(120).start()
    }

    private fun open() {
        try {
            context.startActivity(app.intent)
        } catch (e: RuntimeException) { // Uninstalled meanwhile, or not allowed to start.
            Toast.makeText(context, context.getString(R.string.cannot_open, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    /** A bitmap filling its bounds with rounded corners, painted through a shader: one cheap draw, no clip. */
    private class RoundedArt(bitmap: Bitmap, private val radius: Float) : Drawable() {
        private val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = this@RoundedArt.shader }
        private val size = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        private val area = RectF()

        override fun onBoundsChange(bounds: Rect) {
            area.set(bounds)
            shader.setLocalMatrix(Matrix().apply { setRectToRect(size, area, Matrix.ScaleToFit.FILL) })
        }

        override fun draw(canvas: Canvas) = canvas.drawRoundRect(area, radius, radius, paint)

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Required by Drawable")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
