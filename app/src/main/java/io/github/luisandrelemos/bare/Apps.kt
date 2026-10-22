package io.github.luisandrelemos.bare

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable

/**
 * A TV app as a tile shows it: its name, how to open it, its [art] (the banner, or the icon centered on a clear
 * card) drawn once at tile size, and the [color] the background glows in when it has focus.
 */
class App(
    val packageName: String,
    val label: String,
    val intent: Intent,
    val art: Bitmap,
    val color: Int,
)

object Apps {
    /**
     * Every app with a TV entry point, except Bare itself, by name. Banners are drawn once at the tile's size and the
     * originals released, so no large banner is scaled down on every frame. Call it off the main thread.
     */
    @Suppress("DEPRECATION")
    fun load(context: Context, artWidth: Int, artHeight: Int, labels: Map<String, String> = emptyMap()): List<App> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info ->
                val banner = info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm)
                val art = if (banner != null) cover(banner, artWidth, artHeight) else centered(info.loadIcon(pm), artWidth, artHeight)
                App(
                    packageName = info.packageName,
                    label = labels[info.packageName] ?: info.loadLabel(pm).toString(),
                    intent = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setClassName(info.packageName, info.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                    art = art,
                    color = Glow.colorOf(art),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** The banner filling the tile, cropped at the edges if its shape differs. */
    private fun cover(drawable: Drawable, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val scale = maxOf(width.toFloat() / drawable.intrinsicWidth.coerceAtLeast(1), height.toFloat() / drawable.intrinsicHeight.coerceAtLeast(1))
        val w = (drawable.intrinsicWidth * scale).toInt().coerceAtLeast(width)
        val h = (drawable.intrinsicHeight * scale).toInt().coerceAtLeast(height)
        drawable.setBounds((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** An app without a banner: its icon, half the tile's height, in the middle of a clear card. */
    private fun centered(icon: Drawable, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val size = height / 2
        icon.setBounds((width - size) / 2, (height - size) / 2, (width + size) / 2, (height + size) / 2)
        icon.draw(Canvas(bitmap))
        return bitmap
    }

    /** Streaming apps that make a sensible dock when the theme doesn't pin any, in this order. */
    private val DEFAULT_DOCK = listOf(
        "com.netflix.ninja", "com.google.android.youtube.tv", "org.smarttube.stable", "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus", "com.wbd.stream", "com.apple.atve.androidtv.appletv", "com.stremio.one", "com.nuvio.tv",
        "com.plexapp.android", "org.xbmc.kodi", "com.spotify.tv.android",
    )

    /** The theme's pinned apps first, in its order, then the rest by name; hidden apps never show. */
    fun arrange(apps: List<App>, theme: Theme): List<App> {
        val visible = apps.filter { it.packageName !in theme.hidden }
        val byPackage = visible.associateBy { it.packageName }
        val pinned = theme.pinned.distinct().mapNotNull { byPackage[it] }
        return pinned + (visible - pinned.toSet())
    }

    /**
     * The dock row: the favorites chosen on the TV if there are any (even none: an empty dock is a choice), else the
     * theme's pinned apps, else the streaming apps this TV has, up to the theme's dock size.
     */
    fun dock(apps: List<App>, theme: Theme, favorites: List<String>?): List<App> {
        val byPackage = apps.filter { it.packageName !in theme.hidden }.associateBy { it.packageName }
        if (favorites != null) return favorites.mapNotNull { byPackage[it] }.take(Favorites.MAX)
        val wanted = theme.pinned.ifEmpty { DEFAULT_DOCK }
        val dock = wanted.distinct().mapNotNull { byPackage[it] }.take(theme.dockSize)
        return dock.ifEmpty { byPackage.values.take(theme.dockSize) }
    }
}
