package de.drehtuer.nextalarm.data

import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.domain.Coordinates
import de.drehtuer.nextalarm.domain.FeedDownload
import de.drehtuer.nextalarm.domain.FeedDownloader
import de.drehtuer.nextalarm.domain.FeedFailure
import de.drehtuer.nextalarm.domain.Forecast
import de.drehtuer.nextalarm.domain.WeatherPlace
import de.drehtuer.nextalarm.domain.openMeteoForecastAddress
import de.drehtuer.nextalarm.domain.openMeteoSearchAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** On Robolectric, for Android's own `org.json`. No network is involved. */
@RunWith(RobolectricTestRunner::class)
class OpenMeteoWeatherServiceTest {
    private class FakeDownloader(var answer: FeedDownload) : FeedDownloader {
        val asked = mutableListOf<Pair<String, Long>>()

        override fun download(url: String, maxBytes: Long): FeedDownload {
            asked += url to maxBytes
            return answer
        }
    }

    private fun bytes(json: String) = FeedDownload.Bytes(json.toByteArray(), listOf("https://api.open-meteo.com"))

    private val forecastJson = """
        {"latitude":52.52,"longitude":13.42,"daily":{"time":["2026-11-21"],"weather_code":[61],
        "temperature_2m_max":[9.4],"temperature_2m_min":[3.6],"precipitation_probability_max":[80]}}
    """.trimIndent()

    private val searchJson = """
        {"results":[
          {"id":1,"name":"Berlin","latitude":52.52437,"longitude":13.41053,"country":"Germany","admin1":"Land Berlin"},
          {"id":2,"name":"Berlin","latitude":44.46867,"longitude":-71.18508,"country":"United States","admin1":"New Hampshire"},
          {"id":3,"name":"","latitude":1.0,"longitude":1.0},
          {"id":4,"name":"Nowhere","latitude":100.0,"longitude":1.0},
          {"id":5,"name":"Atlantis","latitude":1.0,"longitude":2.0,"admin1":null}
        ]}
    """.trimIndent()

    @Test
    fun `a forecast is asked for at its address, with a small limit, and read`() = runBlocking {
        val downloader = FakeDownloader(bytes(forecastJson))
        val service = OpenMeteoWeatherService(downloader, Dispatchers.Unconfined)
        val at = Coordinates(52.52, 13.41)
        assertEquals(Forecast(61, 3.6, 9.4, 80), service.forecast(at))
        assertEquals(listOf(openMeteoForecastAddress(at) to OpenMeteoWeatherService.MAX_ANSWER_BYTES), downloader.asked)
    }

    @Test
    fun `a forecast that can't be downloaded is none`() = runBlocking {
        val service = OpenMeteoWeatherService(FakeDownloader(FeedDownload.Failed(FeedFailure.Unreachable)), Dispatchers.Unconfined)
        assertNull(service.forecast(Coordinates(0.0, 0.0)))
    }

    @Test
    fun `a forecast without a chance of rain still counts, and one without temperatures does not`() {
        val noRain = """{"daily":{"weather_code":[0],"temperature_2m_max":[1],"temperature_2m_min":[-1]}}"""
        assertEquals(Forecast(0, -1.0, 1.0, null), parseForecast(noRain))
        val nullRain = """{"daily":{"weather_code":[0],"temperature_2m_max":[1],"temperature_2m_min":[-1],"precipitation_probability_max":[null]}}"""
        assertEquals(Forecast(0, -1.0, 1.0, null), parseForecast(nullRain))
        assertNull(parseForecast("""{"daily":{"weather_code":[0],"temperature_2m_max":[null],"temperature_2m_min":[1]}}"""))
        assertNull(parseForecast("""{"daily":{"weather_code":[],"temperature_2m_max":[1],"temperature_2m_min":[1]}}"""))
        assertNull(parseForecast("""{"daily":{"weather_code":[0],"temperature_2m_max":[1],"temperature_2m_min":["x"]}}"""))
    }

    @Test
    fun `an answer that is no forecast is none`() {
        assertNull(parseForecast("""{"error":true,"reason":"Latitude must be in range"}"""))
        assertNull(parseForecast("not json"))
    }

    @Test
    fun `a search finds the places on Earth that have a name, with what tells them apart`() = runBlocking {
        val downloader = FakeDownloader(bytes(searchJson))
        val places = OpenMeteoWeatherService(downloader, Dispatchers.Unconfined).search("Berlin")
        assertEquals(
            listOf(
                WeatherPlace("Berlin", "Land Berlin", "Germany", 52.52437, 13.41053),
                WeatherPlace("Berlin", "New Hampshire", "United States", 44.46867, -71.18508),
                WeatherPlace("Atlantis", null, null, 1.0, 2.0),
            ),
            places,
        )
        assertEquals(openMeteoSearchAddress("Berlin"), downloader.asked.single().first)
    }

    @Test
    fun `a search that finds nothing is an empty list, and one that fails is none`() = runBlocking {
        assertEquals(emptyList(), parsePlaces("""{"generationtime_ms":0.5}"""))
        assertNull(parsePlaces("""{"results":[1]}"""))
        assertNull(parsePlaces("<html>"))
        val failing = OpenMeteoWeatherService(FakeDownloader(FeedDownload.Failed(FeedFailure.TooLarge)), Dispatchers.Unconfined)
        assertNull(failing.search("Berlin"))
    }

    @Test
    fun `the data components offer the service`() {
        assertIs<OpenMeteoWeatherService>(DataComponents(ApplicationProvider.getApplicationContext()).weather)
    }
}
