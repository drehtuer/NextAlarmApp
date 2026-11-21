package de.drehtuer.nextalarm.data

import de.drehtuer.nextalarm.domain.Coordinates
import de.drehtuer.nextalarm.domain.FeedDownload
import de.drehtuer.nextalarm.domain.FeedDownloader
import de.drehtuer.nextalarm.domain.Forecast
import de.drehtuer.nextalarm.domain.WeatherPlace
import de.drehtuer.nextalarm.domain.WeatherService
import de.drehtuer.nextalarm.domain.openMeteoForecastAddress
import de.drehtuer.nextalarm.domain.openMeteoSearchAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Open-Meteo's forecast and place search. Both need no key and no account, which keeps the app free of proprietary
 * services. The answers are small, and anything larger than [MAX_ANSWER_BYTES] is not one of them.
 */
class OpenMeteoWeatherService(
    private val downloader: FeedDownloader = HttpFeedDownloader(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WeatherService {
    override suspend fun forecast(at: Coordinates): Forecast? =
        withContext(ioDispatcher) { answer(openMeteoForecastAddress(at))?.let(::parseForecast) }

    override suspend fun search(name: String): List<WeatherPlace>? =
        withContext(ioDispatcher) { answer(openMeteoSearchAddress(name))?.let(::parsePlaces) }

    private fun answer(address: String): String? =
        (downloader.download(address, MAX_ANSWER_BYTES) as? FeedDownload.Bytes)?.bytes?.toString(Charsets.UTF_8)

    companion object {
        const val MAX_ANSWER_BYTES = 64L * 1024
    }
}

/** Today's forecast, the first day of the answer. Empty if the answer is not one, or lacks what the sentence needs. */
internal fun parseForecast(json: String): Forecast? = try {
    val daily = JSONObject(json).getJSONObject("daily")
    val code = daily.getJSONArray("weather_code").firstNumber()
    val max = daily.getJSONArray("temperature_2m_max").firstNumber()
    val min = daily.getJSONArray("temperature_2m_min").firstNumber()
    val rain = daily.optJSONArray("precipitation_probability_max")?.firstNumber()
    if (code == null || max == null || min == null) null else Forecast(code.toInt(), min, max, rain?.toInt())
} catch (_: JSONException) {
    null
}

/** The places found. An answer without results found nothing; one that is no answer at all is a failure. */
internal fun parsePlaces(json: String): List<WeatherPlace>? = try {
    val results = JSONObject(json).optJSONArray("results") ?: JSONArray()
    (0 until results.length()).mapNotNull { index -> place(results.getJSONObject(index)) }
} catch (_: JSONException) {
    null
}

private fun place(json: JSONObject): WeatherPlace? {
    val name = json.optString("name").trim()
    val latitude = json.optDouble("latitude")
    val longitude = json.optDouble("longitude")
    val onEarth = latitude in -90.0..90.0 && longitude in -180.0..180.0
    if (name.isEmpty() || !onEarth) return null
    return WeatherPlace(name, json.text("admin1"), json.text("country"), latitude, longitude)
}

private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).trim().ifEmpty { null }

/** A missing value is `null` in Open-Meteo's arrays. */
private fun JSONArray.firstNumber(): Double? =
    if (length() == 0 || isNull(0)) null else optDouble(0).takeUnless { it.isNaN() }
