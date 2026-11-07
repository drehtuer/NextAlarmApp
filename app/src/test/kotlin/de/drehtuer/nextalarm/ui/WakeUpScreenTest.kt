package de.drehtuer.nextalarm.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.drehtuer.nextalarm.domain.NowPlayingModel
import de.drehtuer.nextalarm.domain.PlayingKind
import de.drehtuer.nextalarm.domain.WakeUpScreenModel
import de.drehtuer.nextalarm.ui.screens.WakeUpScreen
import de.drehtuer.nextalarm.ui.theme.NextAlarmTypography
import de.drehtuer.nextalarm.ui.theme.NightTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val SPEECH = NowPlayingModel(PlayingKind.SPEECH, "Speaking the title", "Gym", "0:03", null, null)
private val RSS = NowPlayingModel(PlayingKind.NEWS, "Reading the news · tagesschau.de", "Headline 3 of 12", "1:48", "up to 15:00", 0.12f)
private val PODCAST = NowPlayingModel(PlayingKind.PODCAST, "Podcast · Deutschlandfunk", "Der Tag, 6 November", "4:12", "18:30", 0.23f)
private val GETTING = NowPlayingModel(PlayingKind.NEWS, "Getting the news", "tagesschau.de", null, null, null)
private val LONG_TITLE = "Flight to Hamburg, leave at 6:15 and do not forget the passport. ".repeat(40).trim()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h923dp")
class WakeUpScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(
        playing: NowPlayingModel? = SPEECH,
        titles: List<String?> = listOf("Gym"),
        onStop: () -> Unit = {},
    ) {
        rule.setContent {
            NightTheme {
                WakeUpScreen(WakeUpScreenModel("Good morning · Saturday 7 November", "06:06", titles, playing), onStop)
            }
        }
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun style(value: String) = rule.onNodeWithText(value, useUnmergedTree = true).layout().layoutInput.style

    private fun progressBar(current: Float?) = SemanticsMatcher("a progress bar at $current") { node ->
        val info = node.config.getOrElseNullable(SemanticsProperties.ProgressBarRangeInfo) { null }
        info != null && (current == null || info.current == current)
    }

    @Test
    fun `it has everything the design asks for`() {
        show(titles = listOf("Flight"))
        listOf(
            "Good morning · Saturday 7 November",
            "06:06",
            "Flight",
            "Speaking the title",
            "Gym",
            "0:03",
            "Unlocking the phone won't stop it.",
            "Stop",
        ).forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `the type follows the design`() {
        show(playing = PODCAST, titles = listOf("Flight"))
        assertEquals(NextAlarmTypography.displaySmall.fontSize, style("06:06").fontSize)
        assertEquals(NextAlarmTypography.headlineSmall.fontSize, style("Flight").fontSize)
        assertEquals(NextAlarmTypography.titleMedium.fontSize, style("Der Tag, 6 November").fontSize)
        assertEquals(FontWeight.Medium, style("Der Tag, 6 November").fontWeight)
        assertEquals("tnum", style("4:12").fontFeatureSettings)
        assertEquals("tnum", style("18:30").fontFeatureSettings)
    }

    @Test
    fun `several titles are all there`() {
        show(titles = listOf("One", "Two", "Three"))
        listOf("One", "Two", "Three").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `an event without a title says so`() {
        show(titles = listOf(null))
        assertEquals(FontStyle.Italic, style("Alarm (event has no title)").fontStyle)
    }

    @Test
    fun `reading shows the headline and the times`() {
        show(playing = RSS)
        listOf("Reading the news · tagesschau.de", "Headline 3 of 12", "1:48", "up to 15:00").forEach {
            rule.onNodeWithText(it).assertIsDisplayed()
        }
        rule.onNode(progressBar(0.12f)).assertExists()
    }

    @Test
    fun `a podcast shows the episode and the times`() {
        show(playing = PODCAST)
        listOf("Podcast · Deutschlandfunk", "Der Tag, 6 November", "4:12", "18:30").forEach {
            rule.onNodeWithText(it).assertIsDisplayed()
        }
        rule.onNode(progressBar(0.23f)).assertExists()
    }

    @Test
    fun `speech has a bar that only shows that something happens, and no end time`() {
        show(playing = SPEECH)
        rule.onNode(progressBar(null)).assertExists()
    }

    @Test
    fun `waiting for the feed shows no times`() {
        show(playing = GETTING)
        rule.onNodeWithText("Getting the news").assertIsDisplayed()
        rule.onNode(progressBar(null)).assertExists()
    }

    @Test
    fun `a time without an end shows the time alone, and the other way round`() {
        show(playing = PODCAST.copy(elapsed = null))
        rule.onNodeWithText("18:30").assertIsDisplayed()
    }

    @Test
    fun `between two steps there is no card`() {
        show(playing = null)
        rule.onNodeWithText("Stop").assertIsDisplayed()
        rule.onNode(progressBar(null)).assertDoesNotExist()
    }

    @Test
    fun `Stop is the filled pill of Dismiss, and acts`() {
        var stops = 0
        show(onStop = { stops++ })
        rule.onNodeWithText("Stop").assertHeightIsAtLeast(80.dp)
        rule.onNodeWithText("Stop").performClick()
        assertEquals(1, stops)
    }

    @Test
    fun `a very long title scrolls, and Stop stays on screen`() {
        show(titles = listOf(LONG_TITLE))
        rule.onNode(hasScrollAction()).assertExists()
        val stop = rule.onNodeWithText("Stop").assertIsDisplayed().getBoundsInRoot()
        assertTrue(stop.bottom <= rule.onRoot().getBoundsInRoot().bottom)
    }

    @Test
    fun `the background is black, whatever the system setting`() {
        show(playing = null)
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(Color(0xFF000000), pixels[pixels.width / 2, 2])
    }

    @Test
    fun `the card and the button have the night colours`() {
        var surface = Color.Unspecified
        var primary = Color.Unspecified
        rule.setContent {
            NightTheme {
                surface = MaterialTheme.colorScheme.surfaceContainer
                primary = MaterialTheme.colorScheme.primary
                WakeUpScreen(WakeUpScreenModel("g", "06:06", listOf("Gym"), PODCAST)) {}
            }
        }
        rule.waitForIdle()
        assertEquals(Color(0xFF1A1A1A), surface)
        assertEquals(Color(0xFF99CBFF), primary)
    }
}
