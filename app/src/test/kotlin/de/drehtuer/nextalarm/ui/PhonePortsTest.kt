package de.drehtuer.nextalarm.ui

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.domain.AlarmVolume
import de.drehtuer.nextalarm.ringing.AlarmNotifications
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PhonePortsTest {
    /** Stands in for the media store, which knows what a sound is called. */
    class FakeSoundTitles : ContentProvider() {
        var titles: List<String?> = emptyList()
        var failure: RuntimeException? = null
        var answers = true

        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null

        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
            failure?.let { throw it }
            if (!answers) return null
            return MatrixCursor(arrayOf(MediaStore.MediaColumns.TITLE)).apply { titles.forEach { addRow(arrayOf(it)) } }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = AndroidPhoneAudio(context)
    private val titles = Robolectric.buildContentProvider(FakeSoundTitles::class.java).create("titles").get()
    private val document = Uri.parse("content://documents/log")
    private val documents = ContentResolverLogDocuments(context.contentResolver)

    private fun setDefaultSound(uri: String) {
        Settings.System.putString(context.contentResolver, Settings.System.ALARM_ALERT, uri)
    }

    private fun failing(failure: Exception) = object : OutputStream() {
        override fun write(b: Int) = throw failure
    }

    @Test
    fun `the alarm volume is read from the alarm stream`() {
        val manager = context.getSystemService(AudioManager::class.java)
        manager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, 4, 0)
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        assertEquals(AlarmVolume(1, max), audio.alarmVolume())
    }

    @Test
    fun `without a default sound there is no name`() {
        assertNull(audio.defaultSoundName())
    }

    @Test
    fun `the name in the sound's address is used`() {
        setDefaultSound("content://titles/37?title=Bright%20Morning&canonical=1")
        assertEquals("Bright Morning", audio.defaultSoundName())
    }

    @Test
    fun `otherwise the provider is asked`() {
        titles.titles = listOf("Bright Morning")
        setDefaultSound("content://titles/37")
        assertEquals("Bright Morning", audio.defaultSoundName())
    }

    @Test
    fun `a provider without an answer gives no name`() {
        setDefaultSound("content://titles/37")
        assertNull(audio.defaultSoundName())
        titles.answers = false
        assertNull(audio.defaultSoundName())
        titles.answers = true
        titles.titles = listOf(null)
        assertNull(audio.defaultSoundName())
    }

    @Test
    fun `a provider that refuses gives no name`() {
        setDefaultSound("content://titles/37")
        titles.failure = SecurityException("no")
        assertNull(audio.defaultSoundName())
    }

    @Test
    fun `whether full-screen alarms are allowed comes from the notification manager`() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertEquals(manager.canUseFullScreenIntent(), canUseFullScreenIntent(context))
    }

    @Test
    fun `the log is written to the document`() {
        val output = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(document, output)
        assertTrue(documents.write(document, "the log"))
        assertEquals("the log", output.toString())
    }

    @Test
    fun `a document that fails to take the log says so`() {
        shadowOf(context.contentResolver).registerOutputStream(document, failing(IOException("full")))
        assertFalse(documents.write(document, "the log"))
    }

    @Test
    fun `a document that may no longer be written says so`() {
        shadowOf(context.contentResolver).registerOutputStream(document, failing(SecurityException("revoked")))
        assertFalse(documents.write(document, "the log"))
    }

    @Test
    fun `alarms can show something while notifications are on, also before their channel exists`() {
        assertTrue(alarmNotificationsAllowed(context))
    }

    @Test
    fun `alarms cannot show anything while the app's notifications are off`() {
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertFalse(alarmNotificationsAllowed(context))
    }

    @Test
    fun `alarms cannot show anything while their channel is off, but another channel does not matter`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("other", "Other", NotificationManager.IMPORTANCE_NONE))
        assertTrue(alarmNotificationsAllowed(context))
        manager.createNotificationChannel(
            NotificationChannel(AlarmNotifications.ALARM_CHANNEL, "Alarm", NotificationManager.IMPORTANCE_NONE),
        )
        assertFalse(alarmNotificationsAllowed(context))
    }
}
