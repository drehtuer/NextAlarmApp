package de.drehtuer.nextalarm.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.DeviceCalendar
import de.drehtuer.nextalarm.domain.DiagnosticsModel
import de.drehtuer.nextalarm.domain.HomeDay
import de.drehtuer.nextalarm.domain.HomeModel
import de.drehtuer.nextalarm.domain.HomeRow
import de.drehtuer.nextalarm.domain.LOG_TITLES_NOTE
import de.drehtuer.nextalarm.domain.NEXT_CHECK_TEXT
import de.drehtuer.nextalarm.domain.NewsDraft
import de.drehtuer.nextalarm.domain.NewsTestProgress
import de.drehtuer.nextalarm.domain.OutcomeKind
import de.drehtuer.nextalarm.domain.PastAlarmRow
import de.drehtuer.nextalarm.domain.StatusCard
import de.drehtuer.nextalarm.domain.StatusKind
import de.drehtuer.nextalarm.domain.buildNews
import de.drehtuer.nextalarm.ui.screens.CalendarList
import de.drehtuer.nextalarm.ui.screens.CalendarScreen
import de.drehtuer.nextalarm.ui.screens.DiagnosticsScreen
import de.drehtuer.nextalarm.ui.screens.HomeScreen
import de.drehtuer.nextalarm.ui.screens.NewsScreen
import de.drehtuer.nextalarm.ui.theme.NextAlarmTheme
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val TEXT_MARGIN = 24.dp
private val COLOURED = DeviceCalendar(CalendarId(1), "Coloured", "me@cloud.example", "bitfire.at.davdroid", 0xFF00639B.toInt())
private val PLAIN = DeviceCalendar(CalendarId(2), "Plain", "me@cloud.example", "bitfire.at.davdroid", null)
private const val DAVX_HEADER = "DAVx⁵ · me@cloud.example"

/** Native graphics, because the baselines come from real fonts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h923dp")
class ScreenAlignmentTest {
    @get:Rule
    val rule = createComposeRule()

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /** At this density a pixel is a dp. */
    private fun baseline(text: String): Dp {
        val node = rule.onNodeWithText(text)
        return node.getBoundsInRoot().top + node.layout().firstBaseline.dp
    }

    private fun assertSameBaseline(first: String, second: String) {
        val difference = abs((baseline(first) - baseline(second)).value)
        assertTrue(difference <= 1f, "$first and $second are $difference dp apart")
    }

    /** Of the text itself: in the merged tree, this would be the row it is in. */
    private fun left(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot().left

    private fun diagnostics() {
        val row = PastAlarmRow(1, "Thu 07:00", "Gym", "Dismissed", OutcomeKind.DISMISSED)
        val model = DiagnosticsModel(
            StatusCard(StatusKind.PLANNED, "Alarms are planned", "Body"),
            "NextAlarm",
            NEXT_CHECK_TEXT,
            "Allowed",
            pastAlarms = listOf(row),
            logFileName = "log.txt",
        )
        rule.setContent { NextAlarmTheme(dark = false) { DiagnosticsScreen(model, false, null, {}, {}, {}) } }
    }

    @Test
    fun `the time and the title of a row on home share a baseline, also above a chip`() {
        val model = HomeModel(
            null,
            listOf(
                HomeDay(
                    "Today",
                    listOf(HomeRow("06:00", "Flight", null), HomeRow("07:00", "Gym", "Snoozed until 07:10")),
                ),
            ),
            null,
            null,
            null,
        )
        rule.setContent { NextAlarmTheme(dark = false) { HomeScreen(model, {}, {}) } }
        assertSameBaseline("06:00", "Flight")
        assertSameBaseline("07:00", "Gym")
    }

    @Test
    fun `the time and the title of a past alarm share a baseline`() {
        diagnostics()
        assertSameBaseline("Thu 07:00", "Gym")
    }

    @Test
    fun `the time of a past alarm has tabular figures`() {
        diagnostics()
        assertEquals("tnum", rule.onNodeWithText("Thu 07:00").layout().layoutInput.style.fontFeatureSettings)
    }

    @Test
    fun `the text of diagnostics is at the margin for text`() {
        diagnostics()
        rule.onNodeWithText("Past alarms").assertLeftPositionInRootIsEqualTo(TEXT_MARGIN)
        rule.onNodeWithText(LOG_TITLES_NOTE).assertLeftPositionInRootIsEqualTo(TEXT_MARGIN)
    }

    @Test
    fun `the progress line of a news test is at the margin for text`() {
        val text = "Getting the news · tagesschau.de"
        val model = buildNews(NewsDraft("https://www.tagesschau.de/feed"), NewsTestProgress.Getting("https://www.tagesschau.de/feed"), Instant.EPOCH, ZoneOffset.UTC)
        rule.setContent { NextAlarmTheme(dark = false) { NewsScreen(model, RecordingActions()) {} } }
        rule.onNodeWithText(text).assertLeftPositionInRootIsEqualTo(TEXT_MARGIN)
    }

    @Test
    fun `the text of screen 4 is at the margin for text`() {
        rule.setContent { NextAlarmTheme(dark = false) { CalendarScreen(listOf(COLOURED), COLOURED.id, {}, {}) } }
        rule.onNodeWithText("Every timed event in this calendar rings at its start time. No other calendar is read.")
            .assertLeftPositionInRootIsEqualTo(TEXT_MARGIN)
        rule.onNodeWithText(DAVX_HEADER).assertLeftPositionInRootIsEqualTo(TEXT_MARGIN)
    }

    @Test
    fun `the list alone adds no margin, as onboarding has its own`() {
        rule.setContent { NextAlarmTheme(dark = false) { CalendarList(listOf(COLOURED), COLOURED.id, {}) } }
        rule.onNodeWithText(DAVX_HEADER).assertLeftPositionInRootIsEqualTo(0.dp)
    }

    @Test
    fun `calendar names line up, with or without a colour`() {
        rule.setContent { NextAlarmTheme(dark = false) { CalendarScreen(listOf(COLOURED, PLAIN), COLOURED.id, {}, {}) } }
        assertEquals(left("Coloured"), left("Plain"))
    }
}
