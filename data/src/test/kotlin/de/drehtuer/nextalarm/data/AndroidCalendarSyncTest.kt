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
    private val otherDavx = CalendarAccount("family@cloud.example", "bitfire.at.davdroid")
    private val google = CalendarAccount("me@work.example", "com.google")

    private val noneVisible: (String) -> Array<Account> = { emptyArray() }

    private fun status(account: CalendarAccount) =
        ShadowContentResolver.getStatus(Account(account.name, account.type), CalendarContract.AUTHORITY)

    private fun visibleOf(vararg accounts: CalendarAccount): (String) -> Array<Account> = { type ->
        accounts.filter { it.type == type }.map { Account(it.name, it.type) }.toTypedArray()
    }

    @Test
    fun `each account is asked for a manual and expedited sync of its calendars`() {
        AndroidCalendarSync(noneVisible).requestSync(setOf(davx, google))

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
        AndroidCalendarSync(noneVisible) { account, _, _ -> asked += account }.requestSync(emptySet())
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `an account that refuses does not keep the others from being asked`() {
        val asked = mutableListOf<String>()
        val sync = AndroidCalendarSync(noneVisible) { account: Account, authority: String, _: Bundle ->
            assertEquals(CalendarContract.AUTHORITY, authority)
            if (account.name == davx.name) throw SecurityException("Not allowed")
            asked += account.name
        }
        sync.requestSync(setOf(davx, google))
        assertEquals(listOf(google.name), asked)
    }

    @Test
    fun `only the accounts the app can see are accessible`() {
        val sync = AndroidCalendarSync(visibleOf(davx, google))
        assertEquals(setOf(davx, google), sync.accessible(setOf(davx, otherDavx, google)))
    }

    @Test
    fun `an account the app cannot see is not accessible`() {
        assertEquals(emptySet(), AndroidCalendarSync(noneVisible).accessible(setOf(davx)))
    }

    @Test
    fun `a visible account that is not asked about is not returned`() {
        assertEquals(setOf(davx), AndroidCalendarSync(visibleOf(davx, otherDavx)).accessible(setOf(davx)))
    }

    @Test
    fun `an account of the same name but another type is not accessible`() {
        val sameNameOtherType = CalendarAccount(davx.name, "com.google")
        assertEquals(emptySet(), AndroidCalendarSync(visibleOf(davx)).accessible(setOf(sameNameOtherType)))
    }

    @Test
    fun `the accounts of each type are read once`() {
        val types = mutableListOf<String>()
        val sync = AndroidCalendarSync({ type -> types += type; emptyArray() })
        sync.accessible(setOf(davx, otherDavx, google))
        assertEquals(listOf("bitfire.at.davdroid", "com.google"), types.sorted())
    }

    @Test
    fun `a type whose accounts cannot be read does not keep the others from counting`() {
        val sync = AndroidCalendarSync({ type ->
            if (type == google.type) throw SecurityException("Not allowed")
            arrayOf(Account(davx.name, davx.type))
        })
        assertEquals(setOf(davx), sync.accessible(setOf(davx, google)))
    }

    @Test
    fun `no accounts are no accessible accounts`() {
        assertEquals(emptySet(), AndroidCalendarSync(visibleOf(davx)).accessible(emptySet()))
    }
}
