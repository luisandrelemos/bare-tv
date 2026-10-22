package io.github.luisandrelemos.bare

import android.annotation.SuppressLint
import android.content.Context

/**
 * Three crashes within five minutes and Home starts in safe mode: the built-in look, no theme and no wallpaper.
 * With Google TV's own home disabled, Bare is the only way back to the TV's apps, so it must not crash-loop.
 */
object CrashGuard {
    private const val PREFS = "crash_guard"
    private const val KEY = "times"
    private const val WINDOW_MS = 5 * 60 * 1000L
    private const val LIMIT = 3

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Recorder) return
        Thread.setDefaultUncaughtExceptionHandler(Recorder(context.applicationContext, previous))
    }

    fun safeMode(context: Context) = recent(context).size >= LIMIT

    private fun recent(context: Context): List<Long> {
        val now = System.currentTimeMillis()
        return prefs(context).getString(KEY, "").orEmpty().split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { now - it < WINDOW_MS }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private class Recorder(val context: Context, val previous: Thread.UncaughtExceptionHandler?) :
        Thread.UncaughtExceptionHandler {
        @SuppressLint("ApplySharedPref") // commit, not apply: the process is about to die.
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                val times = recent(context) + System.currentTimeMillis()
                prefs(context).edit().putString(KEY, times.joinToString(",")).commit()
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
