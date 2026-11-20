package de.drehtuer.nextalarm.ui

import android.accounts.Account
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.domain.CalendarAccount
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The extras are those of Android's `ChooseTypeAndAccountActivity`, which `AccountManager.newChooseAccountIntent` fills. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountAccessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val contract = GrantAccountAccess()
    private val davx = CalendarAccount("me@cloud.example", "bitfire.at.davdroid")
    private val account = Account(davx.name, davx.type)

    @Test
    fun `the chooser offers only that account, already selected`() {
        val intent = contract.createIntent(context, davx)
        @Suppress("DEPRECATION")
        assertEquals(arrayListOf(account), intent.getParcelableArrayListExtra<Account>("allowableAccounts"))
        assertEquals(listOf(davx.type), intent.getStringArrayExtra("allowableAccountTypes")?.toList())
        @Suppress("DEPRECATION")
        assertEquals(account, intent.getParcelableExtra("selectedAccount"))
    }

    @Test
    fun `the chooser says why it is shown`() {
        val intent = contract.createIntent(context, davx)
        assertEquals(ACCOUNT_ACCESS_TEXT, intent.getStringExtra("descriptionTextOverride"))
    }

    @Test
    fun `a pick grants the access`() {
        assertTrue(contract.parseResult(Activity.RESULT_OK, Intent()))
    }

    @Test
    fun `leaving the chooser grants nothing`() {
        assertFalse(contract.parseResult(Activity.RESULT_CANCELED, null))
    }
}
