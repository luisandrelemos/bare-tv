package io.github.luisandrelemos.bare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONObject
import java.io.File

/**
 * Everything a theme can change. The defaults are Bare's own look; a theme.json pushed by tools/tv.py overrides any
 * subset of it. A missing, broken or wrongly typed value falls back to the default, so a theme can never break Home.
 */
data class Theme(
    val background: Int = Color.parseColor("#07090D"),
    val backgroundTop: Int = Color.parseColor("#1A2333"),
    val text: Int = Color.parseColor("#F2F4F7"),
    val dim: Int = Color.parseColor("#8E96A3"),
    val accent: Int = Color.parseColor("#FFFFFF"),
    val tile: Int = Color.parseColor("#1A1D22"),
    val cornerRadius: Float = 14f,
    val columns: Int = 5,
    val dockSize: Int = 6,
    val focusScale: Float = 1.08f,
    val clock: Boolean = true,
    val greeting: Boolean = true,
    val labels: Boolean = true,
    val glow: Boolean = true,
    val pinned: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    val name: String? = null,
    val weather: Place? = null,
    /** The buttons top right, in order: any of "inputs", "wifi", "settings". */
    val buttons: List<String> = BUTTONS,
    /** What's below the favorites, in order: any of "inputs", "weather", "apps" ("apps" is always kept). */
    val sections: List<String> = SECTIONS,
    /** A tile's name instead of the app's own. */
    val appLabels: Map<String, String> = emptyMap(),
    /** The TV's live TV app, when it isn't one Bare already knows. */
    val liveTvPackage: String? = null,
    /** Sent with an HDMI/AV input when nothing on the TV answers the standard way (some projectors). */
    val inputAction: String? = null,
    val keys: Keys = Keys(),
    val wallpaper: File? = null,
) {
    companion object {
        val BUTTONS = listOf("inputs", "wifi", "settings")
        val SECTIONS = listOf("inputs", "weather", "apps")

        fun dir(context: Context) = File(context.filesDir, "theme")

        /** Changes whenever a theme file is written or removed, so Home knows to reload. */
        fun stamp(context: Context): Long =
            ThemeProvider.FILES.sumOf { File(dir(context), it).lastModified() }

        fun load(context: Context): Theme {
            val dir = dir(context)
            val wallpaper = File(dir, ThemeProvider.WALLPAPER).takeIf { it.isFile }
            val json = try {
                JSONObject(File(dir, ThemeProvider.CONFIG).readText())
            } catch (e: Exception) {
                return Theme(wallpaper = wallpaper)
            }
            val d = Theme()
            val colors = json.optJSONObject("colors") ?: JSONObject()
            val background = colors.color("background", d.background)
            return Theme(
                background = background,
                // A theme that only sets "background" gets a flat background.
                backgroundTop = colors.color("background_top", if (colors.has("background")) background else d.backgroundTop),
                text = colors.color("text", d.text),
                dim = colors.color("dim", d.dim),
                accent = colors.color("accent", d.accent),
                tile = colors.color("tile", d.tile),
                cornerRadius = json.number("corner_radius", d.cornerRadius, 0f..48f),
                columns = json.number("columns", d.columns.toFloat(), 2f..8f).toInt(),
                dockSize = json.number("dock_size", d.dockSize.toFloat(), 1f..8f).toInt(),
                focusScale = json.number("focus_scale", d.focusScale, 1f..1.2f),
                clock = json.optBoolean("clock", d.clock),
                greeting = json.optBoolean("greeting", d.greeting),
                labels = json.optBoolean("labels", d.labels),
                glow = json.optBoolean("glow", d.glow),
                pinned = json.packages("pinned"),
                hidden = json.packages("hidden"),
                name = json.optString("name").trim().take(24).ifEmpty { null },
                weather = json.optJSONObject("weather")?.place(),
                buttons = json.choice("buttons", BUTTONS) ?: d.buttons,
                // All apps always stays: it is the one way to every app on the TV.
                sections = json.choice("sections", SECTIONS)?.let { if ("apps" in it) it else it + "apps" } ?: d.sections,
                appLabels = json.pairs("app_labels"),
                liveTvPackage = json.optString("live_tv_package").trim().ifEmpty { null },
                inputAction = json.optString("input_action").trim().ifEmpty { null },
                keys = Keys.from(json.optJSONObject("remote")),
                wallpaper = wallpaper,
            )
        }

        /** The wallpaper at no more than screen size: a 4K photo would otherwise take ~32 MB of a TV's little RAM. */
        fun decodeWallpaper(context: Context, file: File): Bitmap? = try {
            val screen = context.resources.displayMetrics
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= screen.widthPixels && bounds.outHeight / (sample * 2) >= screen.heightPixels) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            })
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }

        private fun JSONObject.color(key: String, fallback: Int) =
            try { Color.parseColor(getString(key)) } catch (e: Exception) { fallback }

        private fun JSONObject.number(key: String, fallback: Float, range: ClosedFloatingPointRange<Float>) =
            optDouble(key, Double.NaN).toFloat().takeIf { it in range } ?: fallback

        /** Only a place with real coordinates: without one, Bare makes no network request at all. */
        private fun JSONObject.place(): Place? {
            val latitude = optDouble("latitude", Double.NaN)
            val longitude = optDouble("longitude", Double.NaN)
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            return Place(optString("city").trim().take(32), latitude, longitude)
        }

        /** A list of names from [allowed], in the theme's order; unknown names are dropped. Null if not a list. */
        private fun JSONObject.choice(key: String, allowed: List<String>): List<String>? {
            val array = optJSONArray(key) ?: return null
            return (0 until array.length()).map { array.optString(it).trim().lowercase() }.filter { it in allowed }.distinct()
        }

        /** `{"com.example.app": "Name"}` */
        private fun JSONObject.pairs(key: String): Map<String, String> {
            val map = optJSONObject(key) ?: return emptyMap()
            return map.keys().asSequence().associateWith { map.optString(it).trim() }.filterValues { it.isNotEmpty() }
        }

        private fun JSONObject.packages(key: String): List<String> {
            val array = optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotEmpty() }
        }
    }
}
