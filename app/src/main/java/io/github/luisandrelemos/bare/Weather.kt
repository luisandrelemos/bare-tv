package io.github.luisandrelemos.bare

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/** Where to show the weather for, from the theme. */
data class Place(val city: String, val latitude: Double, val longitude: Double)

class Hour(val time: String, val temperature: Int, val code: Int, val day: Boolean)
class Day(val date: String, val code: Int, val max: Int, val min: Int, val rain: Int)
class Forecast(val now: Hour, val today: Day, val hours: List<Hour>, val days: List<Day>)

/**
 * The forecast from Open-Meteo: free, no account, no key, nothing sent but the place's coordinates. It is cached on
 * disk, so Home shows the last one at once and never waits for the network.
 */
object Weather {
    private const val REFRESH_MS = 30 * 60 * 1000L
    private const val TOO_OLD_MS = 6 * 60 * 60 * 1000L

    private fun cache(context: Context, place: Place) =
        File(context.cacheDir, String.format(Locale.ROOT, "weather-%.3f_%.3f.json", place.latitude, place.longitude))

    fun stale(context: Context, place: Place) =
        System.currentTimeMillis() - cache(context, place).lastModified() > REFRESH_MS

    /** The last forecast, or null when there is none yet or it's too old to mean anything. */
    fun cached(context: Context, place: Place): Forecast? {
        val file = cache(context, place)
        if (System.currentTimeMillis() - file.lastModified() > TOO_OLD_MS) return null
        return try { parse(file.readText()) } catch (e: Exception) { null }
    }

    /** Network: call it off the main thread. Returns whether a new forecast was saved. */
    fun fetch(context: Context, place: Place): Boolean = try {
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=4&forecast_hours=6")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        val body = try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        parse(body) // Only a forecast that parses replaces the cached one.
        val file = cache(context, place)
        val partial = File(file.path + ".part")
        partial.writeText(body)
        partial.renameTo(file)
    } catch (e: Exception) {
        false
    }

    private fun parse(body: String): Forecast {
        val json = JSONObject(body)
        val current = json.getJSONObject("current")
        val now = Hour(
            current.getString("time").takeLast(5),
            current.getDouble("temperature_2m").roundToInt(),
            current.getInt("weather_code"),
            current.optInt("is_day", 1) == 1,
        )
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temperatures = hourly.getJSONArray("temperature_2m")
        val codes = hourly.getJSONArray("weather_code")
        val days = hourly.getJSONArray("is_day")
        // The first hour is the current one, already shown as "now".
        val hours = (1 until times.length()).map {
            Hour(times.getString(it).takeLast(5), temperatures.getDouble(it).roundToInt(), codes.getInt(it), days.optInt(it, 1) == 1)
        }
        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val dailyCodes = daily.getJSONArray("weather_code")
        val highs = daily.getJSONArray("temperature_2m_max")
        val lows = daily.getJSONArray("temperature_2m_min")
        val rain = daily.optJSONArray("precipitation_probability_max")
        val forecastDays = (0 until dates.length()).map {
            Day(dates.getString(it), dailyCodes.getInt(it), highs.getDouble(it).roundToInt(), lows.getDouble(it).roundToInt(),
                rain?.optInt(it, -1) ?: -1)
        }
        return Forecast(now, forecastDays.first(), hours.take(5), forecastDays.drop(1).take(3))
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    fun icon(code: Int, day: Boolean) = when (code) {
        0, 1 -> if (day) R.drawable.wx_sun else R.drawable.wx_moon
        2 -> if (day) R.drawable.wx_partly else R.drawable.wx_cloud
        45, 48 -> R.drawable.wx_fog
        in 51..67, in 80..82 -> R.drawable.wx_rain
        in 71..77, 85, 86 -> R.drawable.wx_snow
        in 95..99 -> R.drawable.wx_storm
        else -> R.drawable.wx_cloud
    }

    fun description(code: Int) = when (code) {
        0 -> R.string.wx_clear
        1 -> R.string.wx_mostly_clear
        2 -> R.string.wx_partly
        45, 48 -> R.string.wx_fog
        in 51..57 -> R.string.wx_drizzle
        in 61..67 -> R.string.wx_rain
        in 80..82 -> R.string.wx_showers
        in 71..77, 85, 86 -> R.string.wx_snow
        in 95..99 -> R.string.wx_storm
        else -> R.string.wx_cloudy
    }
}
