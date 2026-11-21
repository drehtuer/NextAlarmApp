package de.drehtuer.nextalarm.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import de.drehtuer.nextalarm.domain.PlaceSearch
import de.drehtuer.nextalarm.domain.WeatherDraft
import de.drehtuer.nextalarm.domain.WeatherModel
import de.drehtuer.nextalarm.domain.WeatherPreview
import de.drehtuer.nextalarm.domain.WeatherSource
import de.drehtuer.nextalarm.domain.buildWeather
import de.drehtuer.nextalarm.ui.screens.WeatherScreen
import de.drehtuer.nextalarm.ui.theme.NextAlarmTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h923dp")
class WeatherScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = RecordingActions()
    private var backs = 0
    private val location = mutableListOf<Boolean>()
    private val berlin = WeatherSource("Berlin", 52.52, 13.41)

    private fun show(model: WeatherModel, dark: Boolean = false) {
        rule.setContent {
            NextAlarmTheme(dark = dark) {
                WeatherScreen(model, actions, { backs++ }) { location += it }
            }
        }
    }

    private fun visible(text: String) = rule.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    @Test
    fun `an empty screen asks for a place, and offers nothing else yet`() {
        show(buildWeather(WeatherDraft()))
        rule.onNodeWithText("Weather").assertIsDisplayed()
        visible("After the news, NextAlarm can say today's weather forecast. It comes from Open-Meteo.")
        visible("Type the name of a town or city.")
        rule.onNodeWithText("Place").assertIsDisplayed()
        rule.onNodeWithText("Use the current location").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Show the forecast").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Turn off weather").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `typing reaches the actions, and a place found can be chosen`() {
        show(buildWeather(WeatherDraft(search = PlaceSearch.Found(listOf(BERLIN, HAMBURG)))))
        rule.onNode(hasSetTextAction()).performTextInput("Ham")
        rule.onNodeWithText("Hamburg, Germany").performScrollTo().performClick()
        assertEquals(listOf("weather query:Ham", "weather place:1"), actions.calls)
        visible("Choose one of these.")
    }

    @Test
    fun `a chosen place can use the current location, be previewed and be turned off`() {
        show(buildWeather(WeatherDraft(saved = berlin)))
        visible("Forecast for Berlin")
        rule.onNodeWithText("Another place").assertIsDisplayed()
        rule.onNodeWithText("Use the current location").performScrollTo().assertIsEnabled().assertIsOff().performClick()
        rule.onNodeWithText("Show the forecast").performScrollTo().performClick()
        rule.onNodeWithText("Turn off weather").performScrollTo().performClick()
        assertEquals(listOf(true), location)
        assertEquals(listOf("weather preview", "weather off"), actions.calls)
    }

    @Test
    fun `the current location that is on can be turned off`() {
        show(buildWeather(WeatherDraft(saved = berlin.copy(useCurrentLocation = true))), dark = true)
        rule.onNodeWithText("Use the current location").performScrollTo().assertIsOn().performClick()
        assertEquals(listOf(false), location)
    }

    @Test
    fun `the language is picked from its list`() {
        show(buildWeather(WeatherDraft(saved = berlin)))
        visible("Auto: the phone's language")
        rule.onNodeWithText("Language").performScrollTo().performClick()
        rule.onNodeWithText("German").performClick()
        assertEquals(listOf("weather language:de"), actions.calls)
    }

    @Test
    fun `the preview says what it found`() {
        show(buildWeather(WeatherDraft(saved = berlin, preview = WeatherPreview.Said("The weather in Berlin today: rain."))))
        visible("The weather in Berlin today: rain.")
    }

    @Test
    fun `the back arrow reaches the caller`() {
        show(buildWeather(WeatherDraft()))
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
