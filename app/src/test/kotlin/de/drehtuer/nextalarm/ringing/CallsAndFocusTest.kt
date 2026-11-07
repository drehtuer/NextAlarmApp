package de.drehtuer.nextalarm.ringing

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CallsAndFocusTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val shadowAudio = shadowOf(audio)

    @Test
    fun `a phone call and an internet call count as calls`() {
        assertTrue(isCallMode(AudioManager.MODE_IN_CALL))
        assertTrue(isCallMode(AudioManager.MODE_IN_COMMUNICATION))
    }

    @Test
    fun `a phone that rings, normal use and anything unknown are no calls, so that the alarm sounds`() {
        assertFalse(isCallMode(AudioManager.MODE_NORMAL))
        assertFalse(isCallMode(AudioManager.MODE_RINGTONE))
        assertFalse(isCallMode(AudioManager.MODE_CALL_SCREENING))
        assertFalse(isCallMode(AudioManager.MODE_INVALID))
        assertFalse(isCallMode(AudioManager.MODE_CURRENT))
    }

    @Test
    fun `it asks the audio mode`() {
        val calls = AndroidPhoneCalls(context)
        assertFalse(calls.active())
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        assertTrue(calls.active())
        audio.mode = AudioManager.MODE_NORMAL
        assertFalse(calls.active())
    }

    @Test
    fun `watching can be started and stopped`() {
        val heard = mutableListOf<Boolean>()
        val stop = AndroidPhoneCalls(context).watch { heard += it }
        audio.mode = AudioManager.MODE_IN_CALL
        shadowOf(Looper.getMainLooper()).idle()
        stop()
        assertTrue(heard.all { it })
    }

    private fun lastRequest() = assertNotNull(shadowAudio.lastAudioFocusRequest)

    @Test
    fun `the alarm takes the focus for a moment, as an alarm`() {
        assertTrue(alarmAudioFocus(context).request {})
        val request = lastRequest().audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, request.focusGain)
        assertEquals(AudioAttributes.USAGE_ALARM, request.audioAttributes.usage)
    }

    @Test
    fun `the wake-up audio takes the focus as media`() {
        assertTrue(mediaAudioFocus(context).request {})
        val request = lastRequest().audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, request.focusGain)
        assertEquals(AudioAttributes.USAGE_MEDIA, request.audioAttributes.usage)
    }

    @Test
    fun `a request that isn't granted says so`() {
        shadowAudio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertFalse(alarmAudioFocus(context).request {})
    }

    @Test
    fun `only losing it for good is told, not for a moment`() {
        var lost = 0
        mediaAudioFocus(context).request { lost++ }
        val listener = lastRequest().listener
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0, lost)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, lost)
    }

    @Test
    fun `abandoning gives up the request it made, and without one there is nothing to give up`() {
        val focus = alarmAudioFocus(context)
        focus.abandon()
        assertEquals(null, shadowAudio.lastAbandonedAudioFocusRequest)
        focus.request {}
        val request = lastRequest().audioFocusRequest
        focus.abandon()
        assertEquals(request, shadowAudio.lastAbandonedAudioFocusRequest)
    }
}
