package de.drehtuer.nextalarm.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import de.drehtuer.nextalarm.domain.CalendarAccount

/** What Android's account chooser says above the account, for "Sync now". */
const val ACCOUNT_ACCESS_TEXT = "Tap the account to let NextAlarm ask it to sync your alarm calendars."

/**
 * Android's account chooser, offering only [CalendarAccount]. Picking it makes the account visible to the app, which
 * Android needs before it lets the app ask the account to sync. The result tells whether the user picked it.
 */
class GrantAccountAccess : ActivityResultContract<CalendarAccount, Boolean>() {
    override fun createIntent(context: Context, input: CalendarAccount): Intent {
        val account = Account(input.name, input.type)
        return AccountManager.newChooseAccountIntent(
            account,
            listOf(account),
            arrayOf(input.type),
            ACCOUNT_ACCESS_TEXT,
            null,
            null,
            null,
        )
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
}
