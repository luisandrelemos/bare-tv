package io.github.luisandrelemos.bare

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Locale

/** The forecast below the fold: now on the left, then the next hours, then the next days, each column centered. */
class WeatherCard(private val context: Context, private val theme: Theme) {

    private val glass = context.glass(theme, 20)

    val view = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        background = glass
        isFocusable = true
        setPadding(context.dp(32), context.dp(24), context.dp(32), context.dp(24))
    }

    fun showFocus(focused: Boolean) {
        glass.setStroke(context.dp(if (focused) 2 else 1), withAlpha(theme.text, if (focused) 0xB0 else 0x30))
    }

    fun show(forecast: Forecast?) {
        view.removeAllViews()
        if (forecast == null) {
            view.addView(context.label(context.getString(R.string.weather_loading), 16f, theme.dim))
            return
        }
        view.addView(now(forecast), linearParams(context.dp(170), WRAP_CONTENT))
        view.addView(divider())
        val hours = LinearLayout(context).apply { isBaselineAligned = false }
        for (hour in forecast.hours) {
            val temperature = context.label("${hour.temperature}°", 17f, theme.text)
            hours.addView(column(hour.time.take(2) + "h", Weather.icon(hour.code, hour.day), temperature), linearParams(0, WRAP_CONTENT, 1f))
        }
        view.addView(hours, linearParams(0, WRAP_CONTENT, 1f))
        view.addView(divider())
        forecast.days.forEachIndexed { i, day ->
            val name = if (i == 0) context.getString(R.string.tomorrow) else weekday(day.date)
            val range = LinearLayout(context).apply {
                addView(context.label("${day.max}°", 17f, theme.text))
                addView(context.label("${day.min}°", 17f, theme.dim), linearParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(10) })
            }
            view.addView(column(name, Weather.icon(day.code, true), range), linearParams(context.dp(92), WRAP_CONTENT))
        }
    }

    private fun now(forecast: Forecast) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(context.colorIcon(Weather.icon(forecast.now.code, forecast.now.day)), linearParams(context.dp(44), context.dp(44)))
            addView(context.label("${forecast.now.temperature}°", 48f, theme.text).apply {
                typeface = resources.getFont(R.font.clock)
                includeFontPadding = false
            }, linearParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(12) })
        })
        addView(context.label(context.getString(Weather.description(forecast.now.code)), 16f, theme.text),
            linearParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = context.dp(6) })
        addView(context.label(context.getString(R.string.wx_range, forecast.today.max, forecast.today.min), 14f, theme.dim))
        if (forecast.today.rain >= 0) addView(context.label(context.getString(R.string.wx_rain_chance, forecast.today.rain), 14f, theme.dim))
    }

    /** A label, an icon and a value, each centered on the same axis. */
    private fun column(top: String, icon: Int, bottom: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(context.label(top, 14f, theme.dim), linearParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(context.colorIcon(icon), linearParams(context.dp(30), context.dp(30)).apply {
            topMargin = context.dp(10)
            bottomMargin = context.dp(10)
        })
        addView(bottom, linearParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(withAlpha(theme.text, 0x22))
        layoutParams = linearParams(context.dp(1), context.dp(96)).apply {
            leftMargin = context.dp(20)
            rightMargin = context.dp(20)
        }
    }

    /** "2026-10-24" as "Sáb", in the TV's language. */
    private fun weekday(date: String): String = try {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(date)!!
        SimpleDateFormat("EEE", Locale.getDefault()).format(day).trimEnd('.').replaceFirstChar { it.titlecase(Locale.getDefault()) }
    } catch (e: Exception) {
        date
    }
}
