package io.github.luisandrelemos.bare

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.view.View

/**
 * The light behind Home, in the color of the app with focus.
 *
 * Its shape is painted once, in white, into a small bitmap stretched to the screen, and its color is a tint: a color
 * change animates one value, with nothing redrawn. A full-screen radial gradient per frame was too slow for TV GPUs.
 */
class Glow(context: Context) {

    private val light = BitmapDrawable(context.resources, shape()).apply { isFilterBitmap = true }

    /** The light, full screen. Home dims it as the page glides up. */
    val view: View = object : View(context) {
        override fun hasOverlappingRendering() = false
    }.apply { background = light }

    private var tint = Color.TRANSPARENT
    private var target = 0
    private var slide: ValueAnimator? = null

    fun show(color: Int) {
        if (color == target) return
        target = color
        slide?.cancel()
        // At first there is no light yet: it fades in from transparent.
        val from = if (tint == Color.TRANSPARENT) withAlpha(color, 0) else tint
        slide = ValueAnimator.ofArgb(from, color).apply {
            duration = SLIDE_MS
            addUpdateListener {
                tint = it.animatedValue as Int
                light.setTint(tint)
            }
            start()
        }
    }

    /** A wide pool of white light a little above the middle, fading to dark edges. */
    private fun shape(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = RadialGradient(WIDTH * 0.5f, HEIGHT * 0.36f, WIDTH * 0.62f,
                intArrayOf(withAlpha(Color.WHITE, 0x3A), withAlpha(Color.WHITE, 0x16), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        return bitmap
    }

    companion object {
        private const val SLIDE_MS = 700L
        private const val WIDTH = 192
        private const val HEIGHT = 108
        /** Behind apps whose art has no color of its own. */
        private const val NEUTRAL = 0xFF4A6FA5.toInt()

        /**
         * An app's color, from its art: only the colorful pixels count (a white or black background says nothing),
         * evened out to a calm brightness. Call it off the main thread.
         */
        fun colorOf(art: Bitmap): Int {
            val bitmap = Bitmap.createScaledBitmap(art, 16, 9, true)
            val hsv = FloatArray(3)
            var red = 0f
            var green = 0f
            var blue = 0f
            var weight = 0f
            for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) < 128) continue
                Color.colorToHSV(pixel, hsv)
                if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue
                val w = hsv[1] * hsv[1] * hsv[2]
                red += Color.red(pixel) * w
                green += Color.green(pixel) * w
                blue += Color.blue(pixel) * w
                weight += w
            }
            bitmap.recycle()
            if (weight == 0f) return NEUTRAL
            Color.colorToHSV(Color.rgb((red / weight).toInt(), (green / weight).toInt(), (blue / weight).toInt()), hsv)
            hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
            hsv[2] = 0.9f
            return Color.HSVToColor(hsv)
        }
    }
}
