package de.drehtuer.nextalarm.data

import android.accounts.Account
import android.content.ContentResolver
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import de.drehtuer.nextalarm.domain.CalendarAccount
import de.drehtuer.nextalarm.domain.CalendarSync

/**
 * Asks Android to sync the calendars of these accounts now, as a calendar app's refresh does. A manual request runs even
 * where the user has turned automatic sync off. The sync app, such as DAVx⁵, writes what it fetched into the calendar
 * provider, and that change starts a re-plan through the calendar trigger.
 */
class AndroidCalendarSync(
    private val request: (Account, String, Bundle) -> Unit = ContentResolver::requestSync,
) : CalendarSync {

    /** An account that refuses the request does not keep the others from being asked. */
    override fun requestSync(accounts: Set<CalendarAccount>) {
        accounts.forEach { account ->
            try {
                request(Account(account.name, account.type), CalendarContract.AUTHORITY, extras())
            } catch (failure: RuntimeException) {
                Log.w(TAG, "Could not ask for a sync of a calendar account", failure)
            }
        }
    }

    private fun extras() = Bundle().apply {
        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
    }

    private companion object {
        const val TAG = "NextAlarm"
    }
}
