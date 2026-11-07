package de.drehtuer.nextalarm.data

import android.app.AlarmManager
import android.content.Context
import de.drehtuer.nextalarm.data.db.NextAlarmDatabase
import de.drehtuer.nextalarm.domain.AlarmRepository
import de.drehtuer.nextalarm.domain.AlarmScheduler
import de.drehtuer.nextalarm.domain.CalendarRepository
import de.drehtuer.nextalarm.domain.FeedSource
import de.drehtuer.nextalarm.domain.PlannerRepository
import de.drehtuer.nextalarm.domain.SettingsRepository

/** Everything `:data` offers, behind the interfaces of `:domain`. The database opens on first use. */
class DataComponents(private val context: Context) {
    private val database by lazy { NextAlarmDatabase.open(context) }

    val settings: SettingsRepository by lazy { RoomSettingsRepository(database.settings()) }
    val planner: PlannerRepository by lazy { RoomPlannerRepository(database.planner()) }
    val alarms: AlarmRepository by lazy { RoomAlarmRepository(database.alarms()) }
    val calendars: CalendarRepository by lazy { AndroidCalendarRepository(context.contentResolver) }
    val sounds: SoundStore by lazy { SoundStore(context) }

    /** Fetches and parses feeds, for the wake-up automation and the news screen. */
    val feeds: FeedSource by lazy { HttpFeedSource() }

    fun scheduler(intents: AlarmIntents): AlarmScheduler =
        AndroidAlarmScheduler(context.getSystemService(AlarmManager::class.java), intents)
}
