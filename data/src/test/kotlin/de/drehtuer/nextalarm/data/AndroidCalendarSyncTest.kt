package de.drehtuer.nextalarm.data

import android.accounts.Account
import android.content.ContentResolver
import android.os.Bundle
import android.provider.CalendarContract
import de.drehtuer.nextalarm.domain.CalendarAccount
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class AndroidCalendarSyncTest {
    private val davx = CalendarAccount("me@cloud.example", "bitfire.at.davdroid")
    private val google = CalendarAccount("me@work.example", "com.google")

    private fun status(account: CalendarAccount) =
        ShadowContentResolver.getStatus(Account(account.name, account.type), CalendarContract.AUTHORITY)

    @Test
    fun `each account is asked for a manual and expedited sync of its calendars`() {
        AndroidCalendarSync().requestSync(setOf(davx, google))

        listOf(davx, google).forEach { account ->
            val status = status(account)
            assertEquals(1, status.syncRequests)
            assertTrue(status.syncExtras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL))
            assertTrue(status.syncExtras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED))
        }
    }

    @Test
    fun `no accounts asks nothing`() {
        val asked = mutableListOf<Account>()
        AndroidCalendarSync { account, _, _ -> asked += account }.requestSync(emptySet())
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `an account that refuses does not keep the others from being asked`() {
        val asked = mutableListOf<String>()
        val sync = AndroidCalendarSync { account: Account, authority: String, _: Bundle ->
            assertEquals(CalendarContract.AUTHORITY, authority)
            if (account.name == davx.name) throw SecurityException("Not allowed")
            asked += account.name
        }
        sync.requestSync(setOf(davx, google))
        assertEquals(listOf(google.name), asked)
    }
}
