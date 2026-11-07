package de.drehtuer.nextalarm

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import androidx.work.impl.WorkManagerImpl
import de.drehtuer.nextalarm.data.DataComponents
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.PlannerRecord
import de.drehtuer.nextalarm.domain.PlannerState
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.snapshotFor
import de.drehtuer.nextalarm.ringing.AlarmActivity
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** How the app that has been set up reacts to a notification that opens screen 7. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h923dp")
class MainActivityRequestsTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        // WorkManager keeps itself in static fields, which outlive the tests that set it up.
        listOf("sDelegatedInstance", "sDefaultInstance").forEach { name ->
            WorkManagerImpl::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
        }
        runBlocking { DataComponents(context).planner.save(PlannerRecord(PlannerState.Planned, 1)) }
    }

    private fun intent(action: String?) = Intent(context, MainActivity::class.java).setAction(action)

    private fun waitFor(text: String) {
        rule.waitUntil(WAIT_MILLIS) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForHome() {
        rule.waitUntil(WAIT_MILLIS) {
            rule.onAllNodes(hasContentDescription("Settings")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `an ordinary start opens home`() {
        ActivityScenario.launch<MainActivity>(intent(Intent.ACTION_MAIN)).use { waitForHome() }
    }

    @Test
    fun `a start from a notification opens diagnostics, and being recreated does not open them again`() {
        ActivityScenario.launch<MainActivity>(intent(MainActivity.ACTION_OPEN_DIAGNOSTICS)).use { scenario ->
            waitFor("Diagnostics & history")
            rule.onNodeWithContentDescription("Back").performClick()
            waitForHome()
            scenario.recreate()
            waitForHome()
            rule.onAllNodes(hasText("Diagnostics & history")).assertCountEquals(0)
        }
    }

    @Test
    fun `a running app opens diagnostics when a notification asks, and only then`() {
        ActivityScenario.launch<MainActivity>(intent(Intent.ACTION_MAIN)).use { scenario ->
            waitForHome()
            scenario.onActivity { deliver(it, intent(Intent.ACTION_VIEW)) }
            rule.waitForIdle()
            rule.onAllNodes(hasText("Diagnostics & history")).assertCountEquals(0)
            scenario.onActivity { deliver(it, intent(MainActivity.ACTION_OPEN_DIAGNOSTICS)) }
            waitFor("Diagnostics & history")
        }
    }

    @Test
    fun `opening the app while an alarm rings leads to the alarm screen`() {
        ActivityScenario.launch<MainActivity>(intent(Intent.ACTION_MAIN)).use { scenario ->
            waitForHome()
            scenario.onActivity { activity ->
                assertNull(shadowOf(activity).nextStartedActivity)
                activity.ringing.report(listOf(ringingAlarm()))
            }
            rule.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals(AlarmActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
                activity.ringing.report(emptyList())
            }
        }
    }

    @Test
    fun `coming back to the app while an alarm still rings leads there again, and not in the background`() {
        ActivityScenario.launch<MainActivity>(intent(Intent.ACTION_MAIN)).use { scenario ->
            waitForHome()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { activity ->
                activity.ringing.report(listOf(ringingAlarm()))
            }
            rule.waitForIdle()
            scenario.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            rule.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals(AlarmActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
                activity.ringing.report(emptyList())
            }
        }
    }

    @Test
    fun `opening the app puts back an alarm volume left raised, which Android may have ignored at boot`() {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 6, 0)
        DataComponents(context).savedVolume.save(2)
        ActivityScenario.launch<MainActivity>(intent(null)).use {
            rule.waitForIdle()
            assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
            assertNull(DataComponents(context).savedVolume.load())
        }
    }

    private fun ringingAlarm() = AlarmInstance(
        1,
        AlarmKey.forTestAlarm(1),
        Instant.EPOCH,
        AlarmState.Ringing,
        0,
        false,
        Settings.DEFAULT.snapshotFor("Gym"),
    )

    private fun deliver(activity: MainActivity, intent: Intent) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "onNewIntent",
            ReflectionHelpers.ClassParameter.from(Intent::class.java, intent),
        )
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}

/** The whole app on its real parts. There is no real calendar, and setup has not been done. */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `a new install starts with setup`() {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodes(hasText("Your calendar sets the alarms")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get started").assertIsDisplayed()
    }

    @Test
    fun `the activity stops watching in the background and can come back`() {
        composeRule.waitForIdle()
        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodes(hasText("Get started")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
