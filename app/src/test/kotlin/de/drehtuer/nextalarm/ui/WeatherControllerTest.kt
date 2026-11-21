package de.drehtuer.nextalarm.ui

import android.app.Application
import android.os.Looper
import de.drehtuer.nextalarm.domain.Coordinates
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.SettingsRepository
import de.drehtuer.nextalarm.domain.WEATHER_PREVIEW_FAILED
import de.drehtuer.nextalarm.domain.WeatherModel
import de.drehtuer.nextalarm.domain.WeatherSource
import de.drehtuer.nextalarm.domain.forecastSentence
import java.time.Duration
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

private val SEARCH_DELAY: Duration = Duration.ofSeconds(1)

/** Everything runs on the main thread of the test, whose looper only moves when the test says so. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WeatherControllerTest {
    private class FakeSettings(var stored: WeatherSource? = null) : SettingsRepository {
        override suspend fun load(): Settings = Settings.DEFAULT.copy(weather = stored)

        override suspend fun save(settings: Settings) {
            stored = settings.weather
        }
    }

    private val service = FakeWeatherService()
    private var here: Coordinates? = null
    private val settings = FakeSettings()
    private val stored = mutableListOf<WeatherSource?>()
    private var model: WeatherModel? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = WeatherController(
        scope,
        WeatherPorts(service, { here }) { Locale.UK },
        settings,
        store = {
            stored += it
            settings.stored = it
        },
        publish = { model = it },
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val shown: WeatherModel
        get() = assertNotNull(model)

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun search(query: String) {
        controller.setQuery(query)
        settle()
        shadowOf(Looper.getMainLooper()).idleFor(SEARCH_DELAY)
    }

    @Test
    fun `opening shows what is stored, and opening again while open changes nothing`() {
        settings.stored = WeatherSource("Berlin", 52.52, 13.41)
        controller.open()
        settle()
        assertEquals("Forecast for Berlin", shown.chosen)
        settings.stored = null
        controller.open()
        settle()
        assertEquals("Forecast for Berlin", shown.chosen)
        controller.close()
        controller.open()
        settle()
        assertNull(shown.chosen)
    }

    @Test
    fun `a search waits for the typing to pause, and lists what it finds`() {
        controller.setQuery("Ber")
        settle()
        assertEquals("Searching…", shown.helper)
        assertTrue(service.searched.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(SEARCH_DELAY)
        assertEquals(listOf("Ber"), service.searched)
        assertEquals(listOf("Berlin, Land Berlin, Germany", "Hamburg, Germany"), shown.places)
    }

    @Test
    fun `typing on replaces the search on its way, and too little to search for asks nobody`() {
        controller.setQuery("Be")
        settle()
        controller.setQuery("Berl")
        settle()
        shadowOf(Looper.getMainLooper()).idleFor(SEARCH_DELAY)
        assertEquals(listOf("Berl"), service.searched)
        search("B")
        search(" B ")
        assertEquals(listOf("Berl"), service.searched)
        assertEquals("Type the name of a town or city.", shown.helper)
        controller.setQuery(" B ")
        assertEquals(listOf("Berl"), service.searched, "the same text is no change")
    }

    @Test
    fun `a search that fails says so, and one that finds nothing too`() {
        service.places = null
        search("Berlin")
        assertTrue(shown.searchFailed)
        service.places = emptyList()
        search("Atlantis")
        assertEquals("No place of this name was found.", shown.helper)
        assertFalse(shown.searchFailed)
    }

    @Test
    fun `choosing a place stores it at once, and clears the search`() {
        search("Ham")
        controller.choose(1)
        settle()
        assertEquals(listOf<WeatherSource?>(WeatherSource("Hamburg", 53.55, 10.0)), stored)
        assertEquals("Forecast for Hamburg", shown.chosen)
        assertEquals("", shown.query)
        assertTrue(shown.places.isEmpty())
    }

    @Test
    fun `a choice that isn't on the list is no choice`() {
        controller.choose(0)
        search("Ham")
        controller.choose(5)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `the current location and the language change what is stored, and need a place`() {
        controller.setUseCurrentLocation(true)
        controller.setLanguage("de")
        assertTrue(stored.isEmpty())
        search("Ber")
        controller.choose(0)
        controller.setUseCurrentLocation(true)
        controller.setLanguage("de")
        controller.setLanguage("de")
        assertEquals(WeatherSource("Berlin", 52.52, 13.41, true, "de"), settings.stored)
        assertEquals(3, stored.size, "the same language twice is no change")
        assertTrue(shown.useCurrentLocation)
        assertEquals("German", shown.language.label)
    }

    @Test
    fun `the preview shows the forecast the next alarm would say`() {
        search("Ber")
        controller.choose(0)
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        controller.showPreview()
        settle()
        assertEquals("Getting the forecast…", shown.previewLine)
        gate.complete(Unit)
        settle()
        assertEquals(forecastSentence("Berlin", RAIN, "en"), shown.previewLine)
        assertEquals(listOf(Coordinates(52.52, 13.41)), service.forecastsAt)
    }

    @Test
    fun `a preview without a forecast says so, and a new choice drops the old preview`() {
        search("Ber")
        controller.choose(0)
        service.forecast = null
        controller.showPreview()
        settle()
        assertEquals(WEATHER_PREVIEW_FAILED, shown.previewLine)
        controller.setLanguage("de")
        assertNull(shown.previewLine)
    }

    @Test
    fun `there is no preview without a place`() {
        controller.showPreview()
        settle()
        assertNull(model)
        assertTrue(service.forecastsAt.isEmpty())
    }

    @Test
    fun `turning off stores no weather, and forgets everything`() {
        search("Ber")
        controller.choose(0)
        controller.turnOff()
        assertNull(settings.stored)
        assertEquals(listOf(null), stored.drop(1))
        assertNull(shown.chosen)
        assertFalse(shown.canTurnOff)
    }
}
