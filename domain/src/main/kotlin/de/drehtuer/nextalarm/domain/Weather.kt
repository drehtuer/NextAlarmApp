package de.drehtuer.nextalarm.domain

import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

// The weather forecast after the news. See docs/architecture.md#weather.

data class Coordinates(val latitude: Double, val longitude: Double)

/** Today's forecast for one place. [rainChance] is in percent, and empty if the service doesn't give one. */
data class Forecast(
    /** The WMO weather interpretation code, as Open-Meteo gives it. */
    val weatherCode: Int,
    val minTemperature: Double,
    val maxTemperature: Double,
    val rainChance: Int?,
)

/** A place a search found. */
data class WeatherPlace(
    val name: String,
    val region: String?,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
) {
    /** "Berlin, Land Berlin, Germany", which tells apart places of the same name. */
    val label: String
        get() = listOf(name, region, country).filterNot { it.isNullOrBlank() }.distinct().joinToString(", ")
}

/** The weather service, Open-Meteo. Both calls need the network. */
interface WeatherService {
    /** Empty if it can't be had. */
    suspend fun forecast(at: Coordinates): Forecast?

    /** The places of this name, best first. Empty if the search failed, which is not the same as finding nothing. */
    suspend fun search(name: String): List<WeatherPlace>?
}

/** Where the phone was last, roughly. Empty if it doesn't know, or may not say. */
fun interface LastLocation {
    fun coarse(): Coordinates?
}

/** A text to say, and the language tag to say it in. */
data class SpokenText(val text: String, val language: String)

/** Gets the forecast sentence in the background. The result cancels it, and is empty if there is none. */
fun interface WeatherFetcher {
    fun fetch(source: WeatherSource, onResult: (SpokenText?) -> Unit): () -> Unit
}

/** The languages the sentence comes in. Any other language gets the first, English. */
val WEATHER_LANGUAGES: List<String> = listOf("en", "de")

/** The language of the sentence: the chosen one, else the phone's, as far as there is a sentence in it. */
fun weatherLanguage(chosen: String?, phone: Locale): String {
    val wanted = (chosen?.let(Locale::forLanguageTag) ?: phone).language
    return wanted.takeIf { it in WEATHER_LANGUAGES } ?: WEATHER_LANGUAGES.first()
}

/** What the WMO code means, in words that fit into the sentence. */
fun weatherCondition(code: Int, language: String): String {
    val words = if (language == GERMAN) GERMAN_CONDITIONS else ENGLISH_CONDITIONS
    return words[code] ?: if (language == GERMAN) "wechselhaftes Wetter" else "changeable weather"
}

/**
 * "The weather in Berlin today: partly cloudy, 4 to 9 degrees, with a 60 percent chance of rain." [placeName] is empty for
 * the current location, which has no name: "The weather today: …".
 */
fun forecastSentence(placeName: String?, forecast: Forecast, language: String): String {
    val low = forecast.minTemperature.roundToInt()
    val high = forecast.maxTemperature.roundToInt()
    val condition = weatherCondition(forecast.weatherCode, language)
    val rain = forecast.rainChance
    return if (language == GERMAN) {
        val where = placeName?.let { " in $it" }.orEmpty()
        val temperature = if (low == high) "um $low Grad" else "$low bis $high Grad"
        val chance = rain?.let { ", Regenwahrscheinlichkeit $it Prozent" }.orEmpty()
        "Das Wetter$where heute: $condition, $temperature$chance."
    } else {
        val where = placeName?.let { " in $it" }.orEmpty()
        val temperature = if (low == high) "around $low degrees" else "$low to $high degrees"
        val chance = rain?.let { ", with ${englishArticle(it)} $it percent chance of rain" }.orEmpty()
        "The weather$where today: $condition, $temperature$chance."
    }
}

/**
 * The sentence for [source], or nothing if there is no forecast. The current location counts only if it is on, and known:
 * otherwise the forecast is for the chosen place.
 */
suspend fun spokenForecast(source: WeatherSource, service: WeatherService, location: LastLocation, phone: Locale): SpokenText? {
    val here = if (source.useCurrentLocation) location.coarse() else null
    val forecast = service.forecast(here ?: Coordinates(source.latitude, source.longitude)) ?: return null
    val language = weatherLanguage(source.language, phone)
    return SpokenText(forecastSentence(if (here == null) source.placeName else null, forecast, language), language)
}

/** The weather a place found by a search stands for, with the settings of [current] that are not about the place. */
fun weatherSourceFor(place: WeatherPlace, current: WeatherSource?): WeatherSource = WeatherSource(
    placeName = place.name,
    latitude = place.latitude,
    longitude = place.longitude,
    useCurrentLocation = current?.useCurrentLocation ?: false,
    language = current?.language,
)

/** Today's forecast at [at], in the place's own time zone, with what the sentence needs. */
fun openMeteoForecastAddress(at: Coordinates): String =
    "https://api.open-meteo.com/v1/forecast?latitude=${coordinate(at.latitude)}&longitude=${coordinate(at.longitude)}" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
        "&timezone=auto&forecast_days=1"

/** The places of this name, with English names, as the rest of the app is in English. */
fun openMeteoSearchAddress(name: String): String =
    "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name.trim(), Charsets.UTF_8)}" +
        "&count=$SEARCH_RESULTS&language=en&format=json"

/** "an 80 percent chance", "a 60 percent chance": the numbers from 0 to 100 that are said with a vowel first. */
internal fun englishArticle(number: Int): String =
    if (number == 8 || number == 11 || number == 18 || number in 80..89) "an" else "a"

/** Four decimals are about 10 m, which is more than any forecast needs. A dot, whatever the phone's language. */
private fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.4f", value)

private const val SEARCH_RESULTS = 5
private const val GERMAN = "de"

private val ENGLISH_CONDITIONS = mapOf(
    0 to "clear sky",
    1 to "mainly clear",
    2 to "partly cloudy",
    3 to "overcast",
    45 to "fog",
    48 to "fog",
    51 to "light drizzle",
    53 to "drizzle",
    55 to "heavy drizzle",
    56 to "freezing drizzle",
    57 to "freezing drizzle",
    61 to "light rain",
    63 to "rain",
    65 to "heavy rain",
    66 to "freezing rain",
    67 to "freezing rain",
    71 to "light snow",
    73 to "snow",
    75 to "heavy snow",
    77 to "snow grains",
    80 to "light showers",
    81 to "showers",
    82 to "heavy showers",
    85 to "light snow showers",
    86 to "heavy snow showers",
    95 to "thunderstorms",
    96 to "thunderstorms with hail",
    99 to "thunderstorms with hail",
)

private val GERMAN_CONDITIONS = mapOf(
    0 to "klarer Himmel",
    1 to "überwiegend klar",
    2 to "teilweise bewölkt",
    3 to "bedeckt",
    45 to "Nebel",
    48 to "Nebel",
    51 to "leichter Nieselregen",
    53 to "Nieselregen",
    55 to "starker Nieselregen",
    56 to "gefrierender Nieselregen",
    57 to "gefrierender Nieselregen",
    61 to "leichter Regen",
    63 to "Regen",
    65 to "starker Regen",
    66 to "gefrierender Regen",
    67 to "gefrierender Regen",
    71 to "leichter Schneefall",
    73 to "Schneefall",
    75 to "starker Schneefall",
    77 to "Schneegriesel",
    80 to "leichte Regenschauer",
    81 to "Regenschauer",
    82 to "starke Regenschauer",
    85 to "leichte Schneeschauer",
    86 to "starke Schneeschauer",
    95 to "Gewitter",
    96 to "Gewitter mit Hagel",
    99 to "Gewitter mit Hagel",
)
