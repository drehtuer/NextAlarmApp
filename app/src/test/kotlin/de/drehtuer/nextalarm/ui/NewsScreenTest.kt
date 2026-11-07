package de.drehtuer.nextalarm.ui

import android.app.Application
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import de.drehtuer.nextalarm.domain.FeedFailure
import de.drehtuer.nextalarm.domain.NewsCheck
import de.drehtuer.nextalarm.domain.NewsDraft
import de.drehtuer.nextalarm.domain.NewsModel
import de.drehtuer.nextalarm.domain.NewsTestProgress
import de.drehtuer.nextalarm.domain.WakeUpPlaying
import de.drehtuer.nextalarm.domain.buildNews
import de.drehtuer.nextalarm.ui.screens.NewsScreen
import de.drehtuer.nextalarm.ui.theme.NextAlarmTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val ADDRESS = "https://www.tagesschau.de/feed"
private val NOW: Instant = Instant.parse("2026-11-06T19:00:00Z")

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h923dp")
class NewsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = RecordingActions()
    private var backs = 0

    private val field = hasSetTextAction()
    private val inError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)

    private fun model(draft: NewsDraft = NewsDraft(), test: NewsTestProgress? = null) =
        buildNews(draft, test, NOW, ZoneOffset.UTC)

    private fun show(model: NewsModel, dark: Boolean = false) {
        rule.setContent {
            NextAlarmTheme(dark = dark) { NewsScreen(model, actions) { backs++ } }
        }
    }

    private fun assertShown(vararg texts: String) = texts.forEach {
        rule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an empty screen shows its fixed wording, with testing disabled`() {
        show(model())
        rule.onNodeWithText("News").assertIsDisplayed()
        assertShown(
            "After you dismiss an alarm, NextAlarm can read an RSS feed aloud, or play the latest episode of a podcast.",
            "Paste the address of an RSS or podcast feed.",
            "Maximum duration",
            "Playback stops after this",
            "15 min",
        )
        rule.onNodeWithText("Feed address").assertIsDisplayed()
        rule.onNodeWithText("Test playback").assertIsNotEnabled()
        rule.onNodeWithText("Turn off news").assertIsNotEnabled()
    }

    @Test
    fun `a placeholder shows in the field that has the focus`() {
        show(model())
        rule.onNode(field).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("https://…").assertIsDisplayed()
    }

    @Test
    fun `typing reaches the actions`() {
        show(model())
        rule.onNode(field).performTextInput(ADDRESS)
        assertEquals(listOf("address:$ADDRESS"), actions.calls)
    }

    @Test
    fun `an address can be tested and turned off`() {
        show(model(NewsDraft(ADDRESS)))
        rule.onNodeWithText(ADDRESS).assertIsDisplayed()
        rule.onNodeWithText("Test playback").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("Turn off news").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("toggleTest", "turnOff"), actions.calls)
    }

    @Test
    fun `an RSS feed is described in a card`() {
        show(model(NewsDraft(ADDRESS, check = NewsCheck.Found(RSS_FEED))))
        assertShown("RSS feed found.", "RSS feed · read aloud", "tagesschau.de", "2 headlines")
    }

    @Test
    fun `a podcast is described in a card, in the dark theme too`() {
        show(model(NewsDraft(ADDRESS, check = NewsCheck.Found(PODCAST_FEED))), dark = true)
        assertShown("Podcast found.", "Podcast · latest episode plays", "Der Tag", "Deutschlandfunk · 18 min")
    }

    @Test
    fun `a podcast that says nothing about itself has no detail line`() {
        val feed = PODCAST_FEED.copy(author = null, latestEpisode = PODCAST_FEED.latestEpisode?.copy(duration = null))
        show(model(NewsDraft(ADDRESS, check = NewsCheck.Found(feed))))
        assertShown("Der Tag")
    }

    @Test
    fun `the stepper reaches the actions`() {
        show(model())
        rule.onNodeWithContentDescription("Longer").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Shorter").performScrollTo().performClick()
        assertEquals(listOf("lengthen", "shorten"), actions.calls)
    }

    @Test
    fun `the stepper ends at its limits`() {
        show(model(NewsDraft(minutes = 60)))
        rule.onNodeWithText("60 min").assertIsDisplayed()
        rule.onNodeWithContentDescription("Longer").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Shorter").assertIsEnabled()
    }

    @Test
    fun `the back arrow reaches the caller`() {
        show(model())
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `a test that plays can be stopped, and shows how far it has got`() {
        val playing = WakeUpPlaying.PlayingPodcast(
            "Der Tag",
            "Deutschlandfunk",
            "Friday",
            true,
            Duration.ofSeconds(42),
            Duration.ofMinutes(18),
            Duration.ofMinutes(15),
        )
        show(model(NewsDraft(ADDRESS), NewsTestProgress.Playing(playing)))
        assertShown("Playing “Der Tag” · 0:42 of 15:00")
        rule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(42f / 900, 0f..1f))).assertIsDisplayed()
        rule.onNodeWithText("Test playback").assertDoesNotExist()
        rule.onNodeWithText("Stop test").performScrollTo().performClick()
        assertEquals(listOf("toggleTest"), actions.calls)
        rule.onNodeWithContentDescription("Longer").assertIsNotEnabled()
    }

    @Test
    fun `while there is no telling, the bar just moves`() {
        show(model(NewsDraft(ADDRESS), NewsTestProgress.Getting(ADDRESS)))
        assertShown("Getting the news · tagesschau.de")
        rule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun `a failed test marks the field and says why`() {
        show(model(NewsDraft(ADDRESS, check = NewsCheck.Failed(FeedFailure.Http(404, "Not Found")))))
        rule.onNode(inError and field).assertIsDisplayed()
        assertShown(
            "This address didn't return a feed.",
            "Test failed.",
            "The server answered “404 Not Found”. Check the address, then try again. Until it works, alarms skip the news.",
        )
    }

    @Test
    fun `a field without a failure isn't marked`() {
        show(model(NewsDraft(ADDRESS)))
        rule.onNode(inError and field).assertDoesNotExist()
    }
}
