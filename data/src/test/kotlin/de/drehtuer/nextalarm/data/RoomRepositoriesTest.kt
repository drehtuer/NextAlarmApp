package de.drehtuer.nextalarm.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.data.db.NextAlarmDatabase
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.PlannerRecord
import de.drehtuer.nextalarm.domain.PlannerState
import de.drehtuer.nextalarm.domain.PlanningFailure
import de.drehtuer.nextalarm.domain.PlanningRun
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.SoundRef
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomRepositoriesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, NextAlarmDatabase::class.java).build()
    private val alarms = RoomAlarmRepository(database.alarms())
    private val settings = RoomSettingsRepository(database.settings())
    private val planner = RoomPlannerRepository(database.planner())

    private val time = Instant.parse("2026-11-07T05:30:00Z")
    private val key = AlarmKey.forEvent(1, time)

    @After
    fun tearDown() = database.close()

    @Test
    fun `settings default until saved`() = runBlocking {
        assertEquals(Settings.DEFAULT, settings.load())
    }

    @Test
    fun `settings keep every field`() = runBlocking {
        val saved = Settings(
            alarmCalendar = CalendarId(9),
            sound = SoundRef.File("sound-1"),
            vibrate = false,
            volumeRampSeconds = 12,
            maxVolume = 0.5f,
            ringTimeoutMinutes = 4,
            snoozeMinutes = 8,
            speakReason = false,
            news = NEWS,
        )
        settings.save(saved)
        assertEquals(saved, settings.load())
    }

    @Test
    fun `saving settings again replaces them`() = runBlocking {
        settings.save(Settings.DEFAULT.copy(vibrate = false))
        settings.save(Settings.DEFAULT)
        assertEquals(Settings.DEFAULT, settings.load())
    }

    @Test
    fun `the planner starts unconfigured until saved`() = runBlocking {
        assertEquals(PlannerRecord.INITIAL, planner.load())
    }

    @Test
    fun `the planner keeps its state and generation, and saving replaces them`() = runBlocking {
        planner.save(PlannerRecord(PlannerState.Planning, 3))
        assertEquals(PlannerRecord(PlannerState.Planning, 3), planner.load())
        planner.save(PlannerRecord(PlannerState.FailSafe, 4))
        assertEquals(PlannerRecord(PlannerState.FailSafe, 4), planner.load())
    }

    @Test
    fun `the planner keeps the details of a successful and a failed run`() = runBlocking {
        val succeeded = PlannerRecord(
            state = PlannerState.Planned,
            generation = 2,
            consecutiveFailures = 0,
            lastRun = PlanningRun(time, Duration.ofMillis(400), alarmCount = 4, failure = null),
        )
        planner.save(succeeded)
        assertEquals(succeeded, planner.load())

        val failed = PlannerRecord(
            state = PlannerState.FailSafe,
            generation = 3,
            consecutiveFailures = 2,
            lastRun = PlanningRun(time, Duration.ofMillis(10), alarmCount = null, failure = PlanningFailure.NO_PERMISSION),
        )
        planner.save(failed)
        assertEquals(failed, planner.load())
    }

    @Test
    fun `a new alarm is scheduled, with an id and every field kept`() = runBlocking {
        val created = alarms.create(key, time, SNAPSHOT)
        assertNotEquals(0, created.id)
        assertEquals(AlarmState.Scheduled, created.state)
        assertEquals(0, created.snoozeCount)
        assertEquals(false, created.autoSnoozed)
        assertEquals(created, alarms.find(created.id))
    }

    @Test
    fun `an alarm without reason, sound file and news keeps those empty`() = runBlocking {
        val created = alarms.create(key, time, MINIMAL_SNAPSHOT)
        assertEquals(MINIMAL_SNAPSHOT, alarms.find(created.id)?.snapshot)
    }

    @Test
    fun `an unknown alarm is not found`() = runBlocking {
        assertNull(alarms.find(404))
    }

    @Test
    fun `an update changes the stored alarm`() = runBlocking {
        val changed = alarms.create(key, time, SNAPSHOT).copy(
            state = AlarmState.Snoozed,
            snoozeCount = 2,
            autoSnoozed = true,
            triggerAt = time.plusSeconds(600),
            snapshot = MINIMAL_SNAPSHOT,
            lateBy = Duration.ofMinutes(7),
        )
        alarms.update(changed)
        assertEquals(changed, alarms.find(changed.id))
    }

    @Test
    fun `a new alarm has not been late`() = runBlocking {
        assertNull(alarms.find(alarms.create(key, time, SNAPSHOT).id)?.lateBy)
    }

    @Test
    fun `alarms are listed by trigger time`() = runBlocking {
        val late = alarms.create(AlarmKey.forEvent(1, time), time.plusSeconds(60), SNAPSHOT)
        val early = alarms.create(AlarmKey.forEvent(2, time), time, SNAPSHOT)
        assertEquals(listOf(early.id, late.id), alarms.all().map { it.id })
    }

    @Test
    fun `no alarms give an empty list`() = runBlocking {
        assertTrue(alarms.all().isEmpty())
    }

    @Test
    fun `a restored event can have a second alarm with the same key`() = runBlocking {
        val first = alarms.create(key, time, SNAPSHOT)
        alarms.update(first.copy(state = AlarmState.Cancelled))
        val second = alarms.create(key, time, SNAPSHOT)
        assertEquals(setOf(first.id, second.id), alarms.all().map { it.id }.toSet())
    }
}
