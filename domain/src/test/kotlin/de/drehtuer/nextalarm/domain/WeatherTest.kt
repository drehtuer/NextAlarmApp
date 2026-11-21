package de.drehtuer.nextalarm.domain

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WeatherTest {
    private val forecast = Forecast(weatherCode = 2, minTemperature = 3.6, maxTemperature = 9.4, rainChance = 60)

    private class FakeService(var answer: Forecast?) : WeatherService {
        val asked = mutableListOf<Coordinates>()

        override suspend fun forecast(at: Coordinates): Forecast? {
            asked += at
            return answer
        }

        override suspend fun search(name: String): List<WeatherPlace>? = error("not used")
    }

    @Test
    fun `the sentence names the place, the sky, the temperatures and the rain, in English`() {
        assertEquals(
            "The weather in Berlin today: partly cloudy, 4 to 9 degrees, with a 60 percent chance of rain.",
            forecastSentence("Berlin", forecast, "en"),
        )
    }

    @Test
    fun `the article fits the number, as it is said`() {
        assertEquals(listOf(8, 11, 18) + (80..89), (0..100).filter { englishArticle(it) == "an" })
        assertTrue(forecastSentence("Berlin", forecast.copy(rainChance = 80), "en").endsWith("with an 80 percent chance of rain."))
    }

    @Test
    fun `the sentence comes in German too`() {
        assertEquals(
            "Das Wetter in Berlin heute: teilweise bewölkt, 4 bis 9 Grad, Regenwahrscheinlichkeit 60 Prozent.",
            forecastSentence("Berlin", forecast, "de"),
        )
    }

    @Test
    fun `the current location has no name, one temperature is said once, and no chance of rain is left out`() {
        val even = Forecast(0, -2.4, -1.6, null)
        assertEquals("The weather today: clear sky, around -2 degrees.", forecastSentence(null, even, "en"))
        assertEquals("Das Wetter heute: klarer Himmel, um -2 Grad.", forecastSentence(null, even, "de"))
    }

    @Test
    fun `every WMO code has words, in both languages, and an unknown one too`() {
        val codes = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
        codes.forEach { code ->
            assertFalse(weatherCondition(code, "en") == weatherCondition(-1, "en"), "en $code")
            assertFalse(weatherCondition(code, "de") == weatherCondition(-1, "de"), "de $code")
        }
        assertEquals("changeable weather", weatherCondition(42, "en"))
        assertEquals("wechselhaftes Wetter", weatherCondition(42, "de"))
        assertEquals("thunderstorms with hail", weatherCondition(99, "en"))
        assertEquals("Schneegriesel", weatherCondition(77, "de"))
    }

    @Test
    fun `the language is the chosen one, else the phone's, and English where there is no sentence`() {
        assertEquals("de", weatherLanguage("de-AT", Locale.US))
        assertEquals("en", weatherLanguage("en", Locale.GERMANY))
        assertEquals("de", weatherLanguage(null, Locale.GERMANY))
        assertEquals("en", weatherLanguage(null, Locale.FRANCE))
        assertEquals("en", weatherLanguage("fr", Locale.GERMANY))
        assertEquals(listOf("en", "de"), WEATHER_LANGUAGES)
    }

    @Test
    fun `the forecast is for the chosen place, by name`() = runBlocking {
        val service = FakeService(forecast)
        val spoken = spokenForecast(WEATHER, service, { Coordinates(1.0, 2.0) }, Locale.UK)
        assertEquals(listOf(Coordinates(52.52, 13.41)), service.asked)
        assertEquals(SpokenText(forecastSentence("Berlin", forecast, "en"), "en"), spoken)
    }

    @Test
    fun `with the current location on, the forecast is for where the phone is, without a name`() = runBlocking {
        val service = FakeService(forecast)
        val spoken = spokenForecast(WEATHER.copy(useCurrentLocation = true, language = "de"), service, { Coordinates(1.0, 2.0) }, Locale.UK)
        assertEquals(listOf(Coordinates(1.0, 2.0)), service.asked)
        assertEquals(SpokenText(forecastSentence(null, forecast, "de"), "de"), spoken)
    }

    @Test
    fun `a current location that isn't known falls back to the chosen place`() = runBlocking {
        val service = FakeService(forecast)
        val spoken = spokenForecast(WEATHER.copy(useCurrentLocation = true), service, { null }, Locale.UK)
        assertEquals(listOf(Coordinates(52.52, 13.41)), service.asked)
        assertTrue(spoken!!.text.contains("Berlin"))
    }

    @Test
    fun `no forecast means nothing to say`() = runBlocking {
        assertNull(spokenForecast(WEATHER, FakeService(null), { null }, Locale.UK))
    }

    @Test
    fun `a place found by a search keeps the other weather settings`() {
        val place = WeatherPlace("Hamburg", "Hamburg", "Germany", 53.55, 10.0)
        assertEquals(WeatherSource("Hamburg", 53.55, 10.0), weatherSourceFor(place, null))
        val current = WEATHER.copy(useCurrentLocation = true, language = "de")
        assertEquals(WeatherSource("Hamburg", 53.55, 10.0, true, "de"), weatherSourceFor(place, current))
    }

    @Test
    fun `a place is labelled by what tells it apart, without repeating itself`() {
        assertEquals("Hamburg, Germany", WeatherPlace("Hamburg", "Hamburg", "Germany", 0.0, 0.0).label)
        assertEquals("Springfield, Illinois, United States", WeatherPlace("Springfield", "Illinois", "United States", 0.0, 0.0).label)
        assertEquals("Nowhere", WeatherPlace("Nowhere", null, " ", 0.0, 0.0).label)
    }

    @Test
    fun `the addresses ask Open-Meteo for today, with a dot in the numbers whatever the phone's language`() {
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(
                "https://api.open-meteo.com/v1/forecast?latitude=52.5200&longitude=-13.4100" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=1",
                openMeteoForecastAddress(Coordinates(52.52, -13.41)),
            )
        } finally {
            Locale.setDefault(default)
        }
        assertEquals(
            "https://geocoding-api.open-meteo.com/v1/search?name=S%C3%A3o+Paulo&count=5&language=en&format=json",
            openMeteoSearchAddress(" São Paulo "),
        )
    }

    @Test
    fun `a weather source is somewhere on Earth`() {
        assertFailsWith<IllegalArgumentException> { WEATHER.copy(latitude = 91.0) }
        assertFailsWith<IllegalArgumentException> { WEATHER.copy(longitude = -181.0) }
        assertFalse(WEATHER.useCurrentLocation)
        assertNull(WEATHER.language)
    }

    @Test
    fun `the weather alone makes a news step, and is part of the snapshot`() {
        assertTrue(snapshot(news = null).copy(weather = WEATHER).hasNewsStep)
        assertTrue(snapshot(news = NEWS).hasNewsStep)
        assertFalse(snapshot(news = null).hasNewsStep)
        assertEquals(WEATHER, Settings.DEFAULT.copy(weather = WEATHER).snapshotFor("Gym").weather)
        assertNull(Settings.DEFAULT.weather)
    }
}
