package io.github.luisandrelemos.bare

import android.content.Context
import android.graphics.Typeface
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextClock
import java.util.Calendar
import java.util.Locale

/** Top left of Home, stacked: the greeting with the time of day's icon, a large rounded clock, the date, the weather. */
class Header(private val context: Context, private val theme: Theme) {

    val view = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val greetingIcon = context.colorIcon(R.drawable.wx_sun)
    private val greeting = context.label("", 13f, theme.dim, "sans-serif-medium").apply {
        letterSpacing = 0.24f
        isAllCaps = true
    }
    private val weather = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }

    init {
        if (theme.greeting) {
            view.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(greetingIcon, linearParams(context.dp(18), context.dp(18)))
                addView(greeting, linearParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(6) })
            })
        }
        if (theme.clock) {
            view.addView(clock(), linearParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = context.dp(14) })
            view.addView(date(), linearParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = context.dp(10) })
        }
        if (theme.weather != null) {
            view.addView(weather, linearParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = context.dp(8) })
        }
        updateGreeting()
    }

    /** Good morning, afternoon or evening, with a rising sun, a sun or the moon. Call it every minute. */
    fun updateGreeting() {
        val (words, icon) = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> R.string.good_morning to R.drawable.wx_sunrise
            in 12..19 -> R.string.good_afternoon to R.drawable.wx_sun
            else -> R.string.good_evening to R.drawable.wx_moon
        }
        val hello = context.getString(words)
        greetingIcon.setImageResource(icon)
        greeting.text = theme.name?.let { context.getString(R.string.greeting_name, hello, it) } ?: hello
    }

    /** "7° Cloudy · Porto" under the date; hidden until there is a forecast. */
    fun showWeather(forecast: Forecast?, place: Place) {
        weather.removeAllViews()
        weather.visibility = if (forecast == null) View.GONE else View.VISIBLE
        if (forecast == null) return
        weather.addView(context.colorIcon(Weather.icon(forecast.now.code, forecast.now.day)), linearParams(context.dp(26), context.dp(26)))
        weather.addView(context.label("${forecast.now.temperature}°", 18f, theme.text, "sans-serif-medium"),
            linearParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(10) })
        weather.addView(context.label(context.getString(Weather.description(forecast.now.code)), 18f, theme.text, "sans-serif-light").apply {
            alpha = 0.85f
        }, linearParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(8) })
        if (place.city.isNotEmpty()) weather.addView(context.label("  ·  ${place.city}", 18f, theme.dim, "sans-serif-light"))
    }

    private fun clock() = TextClock(context).apply {
        format12Hour = "h:mm"
        format24Hour = "HH:mm"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 116f)
        setTextColor(theme.text)
        // Nunito Black cut down to the digits and the colon, its line box fitted to them (see LICENSE). Tabular
        // digits, so the clock never shifts as the minutes change.
        typeface = resources.getFont(R.font.clock)
        fontFeatureSettings = "tnum"
        includeFontPadding = false
        letterSpacing = -0.01f
    }

    private fun date() = TextClock(context).apply {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        format12Hour = pattern
        format24Hour = pattern
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setTextColor(theme.text)
        alpha = 0.9f
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
}
