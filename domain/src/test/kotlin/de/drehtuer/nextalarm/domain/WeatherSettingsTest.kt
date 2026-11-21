package de.drehtuer.nextalarm.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherSettingsTest {
    private val berlin = WeatherPlace("Berlin", "Land Berlin", "Germany", 52.52, 13.41)

    @Test
    fun `the helper says what to do, what is going on and what was found`() {
        assertEquals("Type the name of a town or city.", placeHelper(PlaceSearch.None))
        assertEquals("Searching…", placeHelper(PlaceSearch.Searching))
        assertEquals("Choose one of these.", placeHelper(PlaceSearch.Found(listOf(berlin))))
        assertEquals("No place of this name was found.", placeHelper(PlaceSearch.Found(emptyList())))
        assertEquals("The search didn't work. Check the phone's connection.", placeHelper(PlaceSearch.Failed))
    }

    @Test
    fun `the languages are auto, then those with a sentence, by name`() {
        val picker = weatherLanguagePicker(null)
        assertEquals("Auto: the phone's language", picker.label)
        assertEquals(listOf(null, "en", "de"), picker.options.map { it.value })
        assertEquals(listOf("Auto: the phone's language", "English", "German"), picker.options.map { it.label })
        assertEquals("German", weatherLanguagePicker("de").label)
        assertEquals("Auto: the phone's language", weatherLanguagePicker("fr").label)
    }

    @Test
    fun `the row on screen 5 is off, the place, or the current location with the place to fall back on`() {
        assertEquals("Off", weatherLabel(null))
        assertEquals("Berlin", weatherLabel(WEATHER))
        assertEquals("Current location, else Berlin", weatherLabel(WEATHER.copy(useCurrentLocation = true)))
    }

    @Test
    fun `an empty screen asks for a place, and allows nothing else`() {
        val model = buildWeather(WeatherDraft())
        assertEquals("", model.query)
        assertEquals("Type the name of a town or city.", model.helper)
        assertFalse(model.searchFailed)
        assertTrue(model.places.isEmpty())
        assertNull(model.chosen)
        assertFalse(model.canUseCurrentLocation)
        assertFalse(model.canPreview)
        assertFalse(model.canTurnOff)
        assertNull(model.previewLine)
    }

    @Test
    fun `a chosen place can be previewed, used with the current location, and turned off`() {
        val model = buildWeather(WeatherDraft(saved = WEATHER.copy(useCurrentLocation = true, language = "de")))
        assertEquals("Forecast for Berlin", model.chosen)
        assertTrue(model.useCurrentLocation)
        assertTrue(model.canUseCurrentLocation)
        assertEquals("German", model.language.label)
        assertTrue(model.canPreview)
        assertTrue(model.canTurnOff)
    }

    @Test
    fun `places found are listed by their labels, and a failed search marks the field`() {
        assertEquals(listOf("Berlin, Land Berlin, Germany"), buildWeather(WeatherDraft(search = PlaceSearch.Found(listOf(berlin)))).places)
        assertTrue(buildWeather(WeatherDraft(search = PlaceSearch.Failed)).searchFailed)
    }

    @Test
    fun `the preview line says what is going on, and what the forecast is`() {
        val getting = buildWeather(WeatherDraft(saved = WEATHER, preview = WeatherPreview.Getting))
        assertEquals("Getting the forecast…", getting.previewLine)
        assertTrue(getting.previewing)
        assertFalse(getting.canPreview)
        assertEquals("Sunny.", buildWeather(WeatherDraft(saved = WEATHER, preview = WeatherPreview.Said("Sunny."))).previewLine)
        assertEquals(WEATHER_PREVIEW_FAILED, buildWeather(WeatherDraft(saved = WEATHER, preview = WeatherPreview.Failed)).previewLine)
        assertEquals(2, MIN_PLACE_QUERY)
    }
}
