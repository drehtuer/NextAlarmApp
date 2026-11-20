package de.drehtuer.nextalarm.domain

/** The account a calendar is synced with, such as a DAVx⁵ account. */
data class CalendarAccount(
    val name: String,
    val type: String,
)

/** Asks the apps that sync calendars to fetch new events now. See docs/architecture.md#re-planning-triggers. */
interface CalendarSync {
    /** Only asks: the sync runs later, if at all, and its changes reach the planner through the calendar trigger. */
    fun requestSync(accounts: Set<CalendarAccount>)

    /**
     * The ones of [accounts] that the app may ask to sync. Android drops a request for any other account without a word,
     * until the user gives the app access to it.
     */
    fun accessible(accounts: Set<CalendarAccount>): Set<CalendarAccount>
}

/**
 * The account of [accounts] that the user still has to give access to before it can be asked to sync, or null if every
 * one is [accessible]. One at a time, in a fixed order, as the user is asked about each on its own.
 */
fun accountToGrant(accounts: Set<CalendarAccount>, accessible: Set<CalendarAccount>): CalendarAccount? =
    (accounts - accessible).minWithOrNull(compareBy(CalendarAccount::type, CalendarAccount::name))

/** Android's account type for calendars that live only on the phone, which nothing syncs. */
const val LOCAL_CALENDAR_ACCOUNT_TYPE = "LOCAL"

/**
 * The accounts of the chosen alarm calendars that are on the phone, each once. No other account is asked to sync.
 * Calendars without an account, and calendars that only live on the phone, are left out.
 */
fun syncAccounts(selected: Set<CalendarId>, calendars: List<DeviceCalendar>): Set<CalendarAccount> =
    chosenCalendars(selected, calendars)
        .filter { it.accountType != LOCAL_CALENDAR_ACCOUNT_TYPE }
        .mapNotNull { calendar ->
            val name = calendar.accountName ?: return@mapNotNull null
            val type = calendar.accountType ?: return@mapNotNull null
            CalendarAccount(name, type)
        }
        .toSet()
