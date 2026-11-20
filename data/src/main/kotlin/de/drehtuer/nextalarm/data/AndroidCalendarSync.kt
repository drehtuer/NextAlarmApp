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
 *
 * Android drops a request without a word unless the app can see the account and the sync app. The manifest makes sync
 * apps visible; an account becomes visible once the user picks it in Android's account chooser.
 *
 * [visible] gives the accounts of a type that the app can see, as `AccountManager.getAccountsByType` does.
 */
class AndroidCalendarSync(
    private val visible: (type: String) -> Array<Account>,
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

    /** Asks once per account type. A type whose accounts can't be read counts as having none the app can see. */
    override fun accessible(accounts: Set<CalendarAccount>): Set<CalendarAccount> =
        accounts.groupBy(CalendarAccount::type).flatMap { (type, wanted) ->
            val names = try {
                visible(type).mapTo(HashSet(), Account::name)
            } catch (failure: RuntimeException) {
                Log.w(TAG, "Could not read the calendar accounts the app can see", failure)
                emptySet()
            }
            wanted.filter { it.name in names }
        }.toSet()

    private fun extras() = Bundle().apply {
        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
    }

    private companion object {
        const val TAG = "NextAlarm"
    }
}
