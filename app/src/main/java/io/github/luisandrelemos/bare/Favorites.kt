package io.github.luisandrelemos.bare

import android.content.Context
import java.io.File

/**
 * The favorites row as chosen on the TV (hold OK on an app) or from the computer (`tv.py favorites`). Until the first
 * choice there is none, and the row follows the theme's "pinned" list or the streaming apps the TV has; after it, the
 * choice wins. Kept as a plain file beside the theme, one package per line, so `tv.py` can read and set it too.
 */
object Favorites {
    const val MAX = 8

    fun get(context: Context): List<String>? {
        val file = file(context)
        if (!file.isFile) return null
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun set(context: Context, packages: List<String>) {
        val file = file(context)
        file.parentFile?.mkdirs()
        val partial = File(file.path + ".part")
        partial.writeText(packages.distinct().take(MAX).joinToString("\n"))
        partial.renameTo(file)
    }

    private fun file(context: Context) = File(Theme.dir(context), ThemeProvider.FAVORITES)
}
