package de.drehtuer.nextalarm.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import de.drehtuer.nextalarm.domain.PHONE_SOUNDS_TITLE
import de.drehtuer.nextalarm.domain.PhoneSoundPick
import de.drehtuer.nextalarm.domain.phoneSoundPick

/**
 * Android's own picker of alarm sounds, which lists the sounds that come with the phone. The file picker does not show
 * them. It has a "Default" entry and no "None". The input says whether the phone's default is the sound now, so that the
 * picker marks it.
 */
class PickPhoneSound : ActivityResultContract<Boolean, PhoneSoundPick>() {
    override fun createIntent(context: Context, input: Boolean): Intent {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, DEFAULT_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, PHONE_SOUNDS_TITLE)
        if (input) intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, DEFAULT_ALARM)
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): PhoneSoundPick {
        val picked = intent
            ?.takeIf { resultCode == Activity.RESULT_OK }
            ?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        return phoneSoundPick(picked?.toString(), DEFAULT_ALARM.toString())
    }

    companion object {
        /** What the picker gives back for its "Default" entry. */
        val DEFAULT_ALARM: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    }
}
