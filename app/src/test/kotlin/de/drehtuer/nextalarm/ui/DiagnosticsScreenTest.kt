package de.drehtuer.nextalarm.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.drehtuer.nextalarm.domain.BannerText
import de.drehtuer.nextalarm.domain.DiagnosticsModel
import de.drehtuer.nextalarm.domain.LOG_FAILED_NOTE
import de.drehtuer.nextalarm.domain.LOG_TITLES_NOTE
import de.drehtuer.nextalarm.domain.NEXT_CHECK_TEXT
import de.drehtuer.nextalarm.domain.OutcomeKind
import de.drehtuer.nextalarm.domain.PastAlarmRow
import de.drehtuer.nextalarm.domain.StatusCard
import de.drehtuer.nextalarm.domain.StatusKind
import de.drehtuer.nextalarm.domain.TEST_ALARM_NOTE
import de.drehtuer.nextalarm.ui.screens.DiagnosticsScreen
import de.drehtuer.nextalarm.ui.theme.NextAlarmTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h923dp")
class DiagnosticsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val planned = StatusCard(StatusKind.PLANNED, "Alarms are planned", "Last check today at 19:58.")

    private fun model(status: StatusCard = planned, rows: List<PastAlarmRow> = emptyList()) = DiagnosticsModel(
        status = status,
        calendar = "NextAlarm · DAVx⁵",
        nextCheck = NEXT_CHECK_TEXT,
        fullScreen = "Allowed",
        pastAlarms = rows,
        logFileName = "log.txt",
    )

    private fun show(model: DiagnosticsModel = model(), pending: Boolean = false, note: String? = null) {
        rule.setContent {
            NextAlarmTheme(dark = false) {
                DiagnosticsScreen(model, pending, note, { calls += "back" }, { calls += "test" }, { calls += "export" })
            }
        }
    }

    private fun visible(text: String) = rule.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    @Test
    fun `it shows the warning and the fact while notifications are off`() {
        show(
            model().copy(
                notificationsOff = BannerText("Notifications are off", "Open NextAlarm to stop it."),
                notifications = "Off: alarms can't show their screen or their buttons",
            ),
        )
        visible("Notifications are off")
        visible("Notifications")
        visible("Off: alarms can't show their screen or their buttons")
    }

    @Test
    fun `the status, the facts and the buttons are there`() {
        show()
        rule.onNodeWithText("Diagnostics & history").assertIsDisplayed()
        listOf(
            "Alarms are planned",
            "Last check today at 19:58.",
            "Calendar",
            "NextAlarm · DAVx⁵",
            "Next check",
            "When the calendar changes, and at least once a day",
            "Full-screen alarms",
            "Allowed",
            "Ring a test alarm",
            "Export log",
            LOG_TITLES_NOTE,
            "Past alarms",
        ).forEach(::visible)
    }

    @Test
    fun `the top bar goes back`() {
        show()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("back"), calls)
    }

    @Test
    fun `every planner state has its card`() {
        val shown = mutableStateOf(StatusKind.entries.first())
        rule.setContent {
            NextAlarmTheme(dark = true) {
                DiagnosticsScreen(model(StatusCard(shown.value, "Title ${shown.value.name}", "Body")), false, null, {}, {}, {})
            }
        }
        StatusKind.entries.forEach { kind ->
            shown.value = kind
            rule.waitForIdle()
            visible("Title ${kind.name}")
        }
    }

    @Test
    fun `a running check shows its progress, and others do not`() {
        val hasProgress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        val status = mutableStateOf(planned)
        rule.setContent {
            NextAlarmTheme(dark = false) { DiagnosticsScreen(model(status.value), false, null, {}, {}, {}) }
        }
        rule.onNode(hasProgress).assertDoesNotExist()
        status.value = StatusCard(StatusKind.PLANNING, "Checking the calendar…", "Alarms already planned stay in place.")
        rule.waitForIdle()
        rule.onNode(hasProgress).assertExists()
    }

    @Test
    fun `a test alarm can be asked for, and until then nothing is announced`() {
        show()
        rule.onNodeWithText(TEST_ALARM_NOTE).assertDoesNotExist()
        rule.onNodeWithText("Ring a test alarm").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("test"), calls)
    }

    @Test
    fun `a pending test alarm cannot be asked for again, and says what comes`() {
        show(pending = true)
        visible(TEST_ALARM_NOTE)
        rule.onNodeWithText("Ring a test alarm").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `the log can be exported, and says how it went`() {
        show(note = LOG_FAILED_NOTE)
        visible(LOG_FAILED_NOTE)
        visible(LOG_TITLES_NOTE)
        rule.onNodeWithText("Export log").performScrollTo().performClick()
        assertEquals(listOf("export"), calls)
    }

    @Test
    fun `without past alarms it says so`() {
        show()
        visible("No alarms have ended yet.")
    }

    @Test
    fun `every outcome has its row, and an event without a title says so`() {
        val rows = OutcomeKind.entries.mapIndexed { index, kind ->
            PastAlarmRow(index.toLong(), "Thu 07:0$index", if (index == 0) null else "Title $index", "Outcome ${kind.name}", kind)
        }
        show(model(rows = rows))
        rule.onNodeWithText("No alarms have ended yet.").assertDoesNotExist()
        visible("No title")
        rows.forEach { row ->
            visible(row.time)
            visible(row.outcome)
            row.title?.let(::visible)
        }
    }
}
