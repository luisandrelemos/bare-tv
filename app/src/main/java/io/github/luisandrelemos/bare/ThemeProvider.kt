package io.github.luisandrelemos.bare

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Lets tools/tv.py read and write the theme and the favorites in Bare's private storage:
 *
 *   content write --uri content://io.github.luisandrelemos.bare.theme/theme.json < /data/local/tmp/theme.json
 *   content read --uri content://io.github.luisandrelemos.bare.theme/favorites.txt
 *   content delete --uri content://io.github.luisandrelemos.bare.theme/wallpaper.jpg
 *
 * Some TVs lock adb out of Android/data, so this is the one route that works on every TV. Only adb's shell (and
 * root) may call it, and only for these few files: no other app can touch Bare's storage.
 */
class ThemeProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = themeFile(uri)
        file.parentFile?.mkdirs()
        // `content write` asks for "w", which doesn't truncate: a shorter new file would keep the old one's tail.
        val writing = "w" in mode && "a" !in mode
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(if (writing) "wt" else mode))
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (themeFile(uri).delete()) 1 else 0

    private fun themeFile(uri: Uri): File {
        val caller = Binder.getCallingUid()
        if (caller != SHELL_UID && caller != ROOT_UID) throw SecurityException("Only adb can change Bare's theme")
        val name = uri.pathSegments.singleOrNull()
        if (name !in FILES) throw FileNotFoundException("Not a file Bare shares: ${uri.path}")
        return File(Theme.dir(context!!), name!!)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun getType(uri: Uri): String? = null

    companion object {
        const val CONFIG = "theme.json"
        const val WALLPAPER = "wallpaper.jpg"
        const val FAVORITES = "favorites.txt"
        /** Every file here: a change to any of them makes Home reload (see [Theme.stamp]). */
        val FILES = listOf(CONFIG, WALLPAPER, FAVORITES)

        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0
    }
}
