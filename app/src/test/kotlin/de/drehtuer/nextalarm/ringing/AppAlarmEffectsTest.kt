package de.drehtuer.nextalarm.ringing

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import de.drehtuer.nextalarm.domain.AlarmAction
import de.drehtuer.nextalarm.domain.AlarmEffects
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmRinger
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.WakeUpAutomation
import de.drehtuer.nextalarm.domain.snapshotFor
import de.drehtuer.nextalarm.planning.AppNotifications
import de.drehtuer.nextalarm.planning.MutexEngineLock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppAlarmEffectsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = AlarmNotifications(context)
    private val ringer = AlarmRinger(FakeSound(), FakeVibration(), FakeTimers(), FakeWakeLock(), {}, {})
    private val started = mutableListOf<String>()
    private val wakeUp = object : WakeUpAutomation {
        override fun startAnnouncement(alarm: AlarmInstance) {
            started += "announcement:${alarm.id}"
        }

        override fun startNews(alarm: AlarmInstance) {
            started += "news:${alarm.id}"
        }
    }
    private val effects = AppAlarmEffects(ringer, notifications, AppNotifications(context), wakeUp)

    private fun posted(id: Int) = shadowOf(manager).getNotification(id)

    @Test
    fun `an alarm joins the ringing ones, and is no longer snoozed`() {
        notifications.showSnoozed(alarm(id = 1))
        effects.perform(alarm(id = 1), AlarmAction.JoinAlarmService)
        assertEquals(listOf(1L), ringer.ringing().map { it.id })
        assertNull(posted(AlarmNotifications.snoozedId(1)))
    }

    @Test
    fun `an alarm that leaves stops ringing and has no alarm notification left`() {
        listOf(AlarmAction.LeaveAlarmService, AlarmAction.LeaveAllServices).forEach { action ->
            effects.perform(alarm(id = 1), AlarmAction.JoinAlarmService)
            notifications.showRinging(alarm(id = 1))
            notifications.showSnoozed(alarm(id = 1))
            effects.perform(alarm(id = 1), action)
            assertTrue(ringer.ringing().isEmpty())
            assertNull(posted(AlarmNotifications.ringingId(1)))
            assertNull(posted(AlarmNotifications.snoozedId(1)))
        }
    }

    @Test
    fun `the wake-up automation is started for the alarm`() {
        effects.perform(alarm(id = 1), AlarmAction.StartAnnouncement)
        effects.perform(alarm(id = 2), AlarmAction.StartNews)
        assertEquals(listOf("announcement:1", "news:2"), started)
    }

    @Test
    fun `each report gets its notification`() {
        effects.perform(alarm(id = 1), AlarmAction.PostSnoozeNotification)
        effects.perform(alarm(id = 2, lateBy = Duration.ofMinutes(7)), AlarmAction.PostLateNotification)
        effects.perform(alarm(id = 3), AlarmAction.PostMissedNotification)
        effects.perform(alarm(id = 4), AlarmAction.PostUnansweredNotification)
        assertNotNull(posted(AlarmNotifications.snoozedId(1)))
        assertNotNull(posted(AlarmNotifications.lateId(2)))
        assertNotNull(posted(AlarmNotifications.missedId(3)))
        assertNotNull(posted(AlarmNotifications.unansweredId(4)))
    }

    @Test
    fun `removing notifications keeps the reports of what happened`() {
        notifications.showRinging(alarm(id = 1))
        notifications.showSnoozed(alarm(id = 1))
        notifications.showLate(alarm(id = 1))
        effects.perform(alarm(id = 1), AlarmAction.RemoveNotifications)
        assertNull(posted(AlarmNotifications.ringingId(1)))
        assertNull(posted(AlarmNotifications.snoozedId(1)))
        assertNotNull(posted(AlarmNotifications.lateId(1)))
    }

    @Test
    fun `what the engine handles itself is no concern here`() {
        listOf(
            AlarmAction.RegisterAlarm,
            AlarmAction.CancelAlarm,
            AlarmAction.ReplaceSnapshot(Settings.DEFAULT.snapshotFor(null)),
            AlarmAction.SetAutoSnoozed(true),
            AlarmAction.IncrementSnoozeCount,
            AlarmAction.RegisterSnoozeAlarm(10),
        ).forEach { effects.perform(alarm(id = 1), it) }
        assertTrue(ringer.ringing().isEmpty())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `the next alarm goes to its notification`() {
        effects.showNextAlarm(alarm(id = 1, state = AlarmState.Scheduled))
        assertNotNull(posted(AppNotifications.NEXT_ALARM_ID))
        effects.showNextAlarm(null)
        assertNull(posted(AppNotifications.NEXT_ALARM_ID))
    }
}

/** With the real lock: a wake-up automation that reports back from inside the engine's call would never finish. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SkippedWakeUpTest {
    private object NoEffects : AlarmEffects {
        override fun perform(instance: AlarmInstance, action: AlarmAction) = Unit
        override fun showNextAlarm(alarm: AlarmInstance?) = Unit
    }

    private val alarms = FakeAlarms(alarm(id = 1, state = AlarmState.Announcing), alarm(id = 2, state = AlarmState.NewsPlaying))
    private val engine = AlarmEngine(alarms, FakeScheduler(), NoEffects, { AT }, MutexEngineLock())
    private val wakeUp = SkippedWakeUp(alarmScope(Dispatchers.Default), Lazy { engine })

    @Test
    fun `the announcement ends straight away`() {
        wakeUp.startAnnouncement(alarm(id = 1))
        awaitUntil { alarms.stored[1]?.state == AlarmState.Done }
    }

    @Test
    fun `the news ends straight away`() {
        wakeUp.startNews(alarm(id = 2))
        awaitUntil { alarms.stored[2]?.state == AlarmState.Done }
    }

    @Test
    fun `an effect can start it while the engine holds its lock`(): Unit = runBlocking {
        val store = FakeAlarms(alarm(id = 1, snapshot = Settings.DEFAULT.copy(speakReason = true).snapshotFor("Gym")))
        lateinit var withEffect: AlarmEngine
        val effects = object : AlarmEffects {
            private val skipped = SkippedWakeUp(alarmScope(Dispatchers.Default), Lazy { withEffect })

            override fun perform(instance: AlarmInstance, action: AlarmAction) {
                if (action == AlarmAction.StartAnnouncement) skipped.startAnnouncement(instance)
            }

            override fun showNextAlarm(alarm: AlarmInstance?) = Unit
        }
        withEffect = AlarmEngine(store, FakeScheduler(), effects, { AT }, MutexEngineLock())
        withEffect.handle(1, de.drehtuer.nextalarm.domain.AlarmEvent.Dismiss)
        awaitUntil { store.stored[1]?.state == AlarmState.Done }
    }
}
