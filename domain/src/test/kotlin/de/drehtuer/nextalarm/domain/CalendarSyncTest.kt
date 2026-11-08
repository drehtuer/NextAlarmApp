package de.drehtuer.nextalarm.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarSyncTest {
    private val alarms = DeviceCalendar(CalendarId(1), "NextAlarm", "me@cloud.example", "bitfire.at.davdroid", null)
    private val special = DeviceCalendar(CalendarId(2), "Special cases", "me@cloud.example", "bitfire.at.davdroid", null)
    private val work = DeviceCalendar(CalendarId(3), "Work", "me@work.example", "com.google", null)
    private val local = DeviceCalendar(CalendarId(4), "On the phone", "Phone", LOCAL_CALENDAR_ACCOUNT_TYPE, null)
    private val noAccount = DeviceCalendar(CalendarId(5), "No account", null, null, null)
    private val noName = DeviceCalendar(CalendarId(6), "No name", null, "bitfire.at.davdroid", null)
    private val noType = DeviceCalendar(CalendarId(7), "No type", "me@cloud.example", null, null)
    private val all = listOf(alarms, special, work, local, noAccount, noName, noType)

    private val davx = CalendarAccount("me@cloud.example", "bitfire.at.davdroid")

    private fun ids(vararg calendars: DeviceCalendar) = calendars.map { it.id }.toSet()

    @Test
    fun `the account of a chosen calendar is synced`() {
        assertEquals(setOf(davx), syncAccounts(ids(alarms), all))
    }

    @Test
    fun `an account with several chosen calendars is synced once`() {
        assertEquals(setOf(davx), syncAccounts(ids(alarms, special), all))
    }

    @Test
    fun `the account of a calendar that is not chosen is not synced`() {
        assertEquals(setOf(davx), syncAccounts(ids(alarms), all))
        assertEquals(setOf(CalendarAccount("me@work.example", "com.google")), syncAccounts(ids(work), all))
    }

    @Test
    fun `a calendar that only lives on the phone is not synced`() {
        assertEquals(emptySet(), syncAccounts(ids(local), all))
    }

    @Test
    fun `a calendar without a full account is not synced`() {
        assertEquals(emptySet(), syncAccounts(ids(noAccount, noName, noType), all))
    }

    @Test
    fun `a chosen calendar that is no longer on the phone is not synced`() {
        assertEquals(emptySet(), syncAccounts(setOf(CalendarId(99)), all))
    }

    @Test
    fun `nothing chosen syncs nothing`() {
        assertEquals(emptySet(), syncAccounts(emptySet(), all))
    }
}
