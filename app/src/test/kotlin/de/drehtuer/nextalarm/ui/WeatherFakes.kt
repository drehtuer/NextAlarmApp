package de.drehtuer.nextalarm.ui

import de.drehtuer.nextalarm.domain.Coordinates
import de.drehtuer.nextalarm.domain.Forecast
import de.drehtuer.nextalarm.domain.WeatherPlace
import de.drehtuer.nextalarm.domain.WeatherService
import kotlinx.coroutines.CompletableDeferred

val BERLIN = WeatherPlace("Berlin", "Land Berlin", "Germany", 52.52, 13.41)
val HAMBURG = WeatherPlace("Hamburg", "Hamburg", "Germany", 53.55, 10.0)
val RAIN = Forecast(61, 4.0, 9.0, 80)

/** Answers at once, unless [gate] holds the answers back. */
class FakeWeatherService : WeatherService {
    var places: List<WeatherPlace>? = listOf(BERLIN, HAMBURG)
    var forecast: Forecast? = RAIN
    var gate: CompletableDeferred<Unit>? = null
    val searched = mutableListOf<String>()
    val forecastsAt = mutableListOf<Coordinates>()

    override suspend fun forecast(at: Coordinates): Forecast? {
        forecastsAt += at
        gate?.await()
        return forecast
    }

    override suspend fun search(name: String): List<WeatherPlace>? {
        searched += name
        gate?.await()
        return places
    }
}
