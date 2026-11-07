package de.drehtuer.nextalarm.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.domain.PhoneSoundPick
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PhoneSoundPickerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val contract = PickPhoneSound()
    private val oxygen = Uri.parse("content://media/internal/audio/media/37")

    private fun picked(uri: Uri) = Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, uri)

    @Test
    fun `the picker lists alarm sounds with a default entry and without silence`() {
        val intent = contract.createIntent(context, false)
        assertEquals(RingtoneManager.ACTION_RINGTONE_PICKER, intent.action)
        assertEquals(RingtoneManager.TYPE_ALARM, intent.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1))
        assertFalse(intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true))
        assertEquals(true, intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false))
        assertEquals(
            PickPhoneSound.DEFAULT_ALARM,
            intent.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Uri::class.java),
        )
        assertEquals("Alarm sound", intent.getStringExtra(RingtoneManager.EXTRA_RINGTONE_TITLE))
    }

    @Test
    fun `the default entry is marked only while the default is used`() {
        assertEquals(
            PickPhoneSound.DEFAULT_ALARM,
            contract.createIntent(context, true).getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri::class.java),
        )
        assertFalse(contract.createIntent(context, false).hasExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI))
    }

    @Test
    fun `the default entry stands for the phone's default alarm sound`() {
        assertEquals(Settings.System.DEFAULT_ALARM_ALERT_URI, PickPhoneSound.DEFAULT_ALARM)
    }

    @Test
    fun `a chosen sound is read from the answer`() {
        assertEquals(PhoneSoundPick.Sound(oxygen.toString()), contract.parseResult(Activity.RESULT_OK, picked(oxygen)))
    }

    @Test
    fun `the default entry is read as the default`() {
        assertEquals(PhoneSoundPick.PhoneDefault, contract.parseResult(Activity.RESULT_OK, picked(PickPhoneSound.DEFAULT_ALARM)))
    }

    @Test
    fun `a cancel or an answer without a sound changes nothing`() {
        assertEquals(PhoneSoundPick.Cancelled, contract.parseResult(Activity.RESULT_CANCELED, picked(oxygen)))
        assertEquals(PhoneSoundPick.Cancelled, contract.parseResult(Activity.RESULT_CANCELED, null))
        assertEquals(PhoneSoundPick.Cancelled, contract.parseResult(Activity.RESULT_OK, null))
        assertEquals(PhoneSoundPick.Cancelled, contract.parseResult(Activity.RESULT_OK, Intent()))
    }
}
