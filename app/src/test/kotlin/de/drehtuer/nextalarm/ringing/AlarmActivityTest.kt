package de.drehtuer.nextalarm.ringing

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import de.drehtuer.nextalarm.data.DataComponents
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.snapshotFor
import java.time.Instant
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** The activity on the real app: the screen shows what the database says is ringing, and acts on it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h923dp")
class AlarmActivityTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val data = DataComponents(context)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // WorkManager keeps itself in static fields, which outlive the tests that set it up.
        listOf("sDelegatedInstance", "sDefaultInstance").forEach { name ->
            WorkManagerImpl::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
        }
    }

    private fun ringingAlarm(title: String): AlarmInstance = runBlocking {
        val at = Instant.now()
        val created = data.alarms.create(AlarmKey.forEvent(1, at), at, Settings.DEFAULT.snapshotFor(title))
        created.copy(state = AlarmState.Ringing).also { data.alarms.update(it) }
    }

    private fun state(id: Long) = runBlocking { data.alarms.find(id)?.state }

    @Test
    fun `the night screens stay in portrait, so that no button can be cut off`() {
        listOf(AlarmActivity::class.java, WakeUpActivity::class.java).forEach { activity ->
            val info = context.packageManager.getActivityInfo(ComponentName(context, activity), 0)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, info.screenOrientation, activity.name)
        }
    }

    @Test
    fun `the screen shows the ringing alarm, and Snooze acts on it`() {
        val alarm = ringingAlarm("Flight to Hamburg")
        ActivityScenario.launch(AlarmActivity::class.java).use {
            rule.waitUntil(WAIT_MILLIS) { rule.onAllNodes(hasText("Snooze")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Flight to Hamburg").assertIsDisplayed()
            rule.onNodeWithText("Snooze").performClick()
            rule.waitUntil(WAIT_MILLIS) { state(alarm.id) == AlarmState.Snoozed }
        }
    }

    @Test
    fun `Dismiss ends the alarm and closes the screen`() {
        val alarm = ringingAlarm("Flight to Hamburg")
        ActivityScenario.launch(AlarmActivity::class.java).use { scenario ->
            rule.waitUntil(WAIT_MILLIS) { rule.onAllNodes(hasText("Dismiss")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Dismiss").performClick()
            rule.waitUntil(WAIT_MILLIS) { state(alarm.id) == AlarmState.Done }
            rule.waitUntil(WAIT_MILLIS) { scenario.isClosing() }
        }
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
