package de.drehtuer.nextalarm

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether Android's account chooser makes a sync account, such as DAVx⁵'s, visible to NextAlarm, which "Sync now" needs:
 * since Android 13, a sync request for an account the app can't see is dropped without a word.
 *
 * It needs someone at the phone to pick the account, so it only runs when asked for, with the account type:
 * `-e accountType bitfire.at.davdroid`. It waits up to two minutes for the pick.
 */
@RunWith(AndroidJUnit4::class)
class AccountAccessDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val accounts = AccountManager.get(context)

    @Test
    fun theAccountChooserMakesTheAccountVisible() {
        val type = InstrumentationRegistry.getArguments().getString("accountType")
        assumeTrue("Only runs with -e accountType <type>", type != null)

        val before = accounts.getAccountsByType(type)
        println("Visible before the chooser: ${before.map(Account::name)}")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val intent = AccountManager.newChooseAccountIntent(
                    null, null, arrayOf(type), "Device test: pick the account to sync", null, null, null,
                )
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, REQUEST)
            }
            repeat(WAIT_STEPS) {
                if (accounts.getAccountsByType(type).isNotEmpty()) return@use
                Thread.sleep(POLL_MILLIS)
            }
        }

        val after = accounts.getAccountsByType(type)
        println("Visible after the chooser: ${after.map(Account::name)}")
        assertTrue("No account of type $type became visible", after.isNotEmpty())

        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        after.forEach { ContentResolver.requestSync(it, CalendarContract.AUTHORITY, extras) }
    }

    private companion object {
        const val REQUEST = 1
        const val WAIT_STEPS = 240
        const val POLL_MILLIS = 500L
    }
}
