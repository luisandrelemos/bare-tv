package io.github.luisandrelemos.bare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
 * One app: a rounded card with its art and its name underneath. With focus it grows, glows in the app's own color and
 * gets a thin rim just outside its edge. In the [dock] the name is smaller, centered and only shown with focus, and
 * gives way to how to move the tile while it's being moved. A small dot marks a [favorite] in All apps.
 */
@SuppressLint("ViewConstructor")
class Tile(
    context: Context,
    val app: App,
    private val theme: Theme,
    width: Int,
    private val dock: Boolean = false,
    favorite: Boolean = false,
) : LinearLayout(context) {

    /** Called each time this tile gains focus. */
    var onFocus: (() -> Unit)? = null

    /** Holding OK. Unset, a long press is an ordinary press. */
    var onLongPress: (() -> Unit)? = null

    private val label = TextView(context)
    // Faded without an offscreen layer: the glow and the rim barely overlap.
    private val halo = object : View(context) {
        override fun hasOverlappingRendering() = false
    }
    private val density = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        isFocusable = true
        isClickable = true
        clipChildren = false

        val height = width * 9 / 16
        val radius = theme.cornerRadius * density
        val margin = (HALO_DP * density).toInt()
        val frame = FrameLayout(context).apply { clipChildren = false }

        halo.background = Halo(glowShape(width, height, margin, radius), app.color, margin.toFloat(), radius, density)
        halo.alpha = 0f
        frame.addView(halo, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            setMargins(-margin, -margin, -margin, -margin)
        })

        // Rounded by the art's shader, not by clipping: a rounded clip costs the GPU a mask per tile per frame.
        val card = FrameLayout(context).apply {
            background = LayerDrawable(arrayOf(
                GradientDrawable().apply { setColor(theme.tile); cornerRadius = radius },
                RoundedArt(app.art, radius),
            ))
        }
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
        frame.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(frame, LayoutParams(MATCH_PARENT, height))

        label.text = app.label
        label.textSize = if (dock) 13f else 14f
        label.setTextColor(theme.dim)
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.setPadding(0, (10 * density).toInt(), 0, 0)
        label.visibility = when {
            dock -> INVISIBLE
            theme.labels -> VISIBLE
            else -> GONE
        }
        if (dock) {
            label.gravity = Gravity.CENTER_HORIZONTAL
            label.letterSpacing = 0.02f
            label.setPadding(0, (14 * density).toInt(), 0, 0)
        }
        addView(label, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setOnClickListener { open() }
        setOnLongClickListener { onLongPress?.let { it(); true } ?: false }
        setOnFocusChangeListener { _, focused -> showFocus(focused) }
    }

    /** Being moved in the dock: lifted, with how to move it shown underneath. */
    fun setMoving(moving: Boolean) {
        label.text = if (moving) context.getString(R.string.move_hint) else app.label
        label.ellipsize = if (moving) null else TextUtils.TruncateAt.END
        if (dock) label.visibility = if (moving || (theme.labels && isFocused)) VISIBLE else INVISIBLE
        animate().translationY(if (moving) -10 * density else 0f).setDuration(120).start()
    }

    private fun showFocus(focused: Boolean) {
        if (focused) onFocus?.invoke()
        val scale = if (focused) theme.focusScale else 1f
        animate().scaleX(scale).scaleY(scale).setDuration(140).start()
        halo.animate().alpha(if (focused) 1f else 0f).setDuration(180).start()
        label.setTextColor(if (focused) theme.text else theme.dim)
        if (dock && theme.labels) label.visibility = if (focused) VISIBLE else INVISIBLE
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

    /**
     * The focused tile's light: a soft glow in the app's color all around the card, and a thin glass rim a few dp
     * outside its edge, brightest at the top. The glow is a blurred shape drawn once per tile size and tinted.
     */
    private class Halo(
        private val glow: Bitmap,
        color: Int,
        private val margin: Float,
        radius: Float,
        density: Float,
    ) : Drawable() {
        private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(withAlpha(color, 0x60), PorterDuff.Mode.SRC_IN)
        }
        private val gap = 4 * density
        private val rimRadius = radius + gap
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val rimArea = RectF()

        override fun onBoundsChange(bounds: Rect) {
            rimArea.set(bounds)
            rimArea.inset(margin - gap, margin - gap)
            rim.shader = LinearGradient(0f, rimArea.top, 0f, rimArea.bottom,
                withAlpha(Color.WHITE, 0xF0), withAlpha(Color.WHITE, 0x60), Shader.TileMode.CLAMP)
        }

        override fun draw(canvas: Canvas) {
            canvas.drawBitmap(glow, null, bounds, glowPaint)
            canvas.drawRoundRect(rimArea, rimRadius, rimRadius, rim)
        }

        override fun setAlpha(alpha: Int) {
            glowPaint.alpha = alpha
            rim.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Required by Drawable")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        /** How far the glow reaches past the card, in dp. */
        private const val HALO_DP = 22

        private val glowShapes = HashMap<String, Bitmap>()

        /**
         * The card's shape blurred outwards, in white, drawn once per tile size and shared by every tile of that size.
         * Drawn in software, where a blur is a one-off cost rather than a cost on every frame.
         */
        private fun glowShape(width: Int, height: Int, margin: Int, radius: Float): Bitmap =
            glowShapes.getOrPut("${width}x$height") {
                val bitmap = Bitmap.createBitmap(width + 2 * margin, height + 2 * margin, Bitmap.Config.ARGB_8888)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    maskFilter = BlurMaskFilter(margin * 0.45f, BlurMaskFilter.Blur.NORMAL)
                }
                // A little larger than the card, so the light starts at its edge rather than behind it.
                val card = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()).apply { inset(margin * 0.55f, margin * 0.55f) }
                Canvas(bitmap).drawRoundRect(card, radius + margin * 0.45f, radius + margin * 0.45f, paint)
                bitmap
            }
    }
}
