package de.drehtuer.nextalarm.ui

import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import de.drehtuer.nextalarm.domain.AlarmVolume
import de.drehtuer.nextalarm.ringing.AlarmNotifications
import java.io.IOException

/** What the alarm settings show about the phone's own sound. */
interface PhoneAudio {
    fun alarmVolume(): AlarmVolume

    /** Empty if the phone has no default alarm sound, or does not say what it is called. */
    fun defaultSoundName(): String?
}

/** The phone's alarm stream, and the sound set for it. Only reads: the system's alarm volume is never changed. */
class AndroidPhoneAudio(private val context: Context) : PhoneAudio {
    private val audio = context.getSystemService(AudioManager::class.java)

    override fun alarmVolume() = AlarmVolume(
        audio.getStreamVolume(AudioManager.STREAM_ALARM),
        audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
    )

    override fun defaultSoundName(): String? {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM) ?: return null
        return uri.getQueryParameter(TITLE_PARAMETER) ?: queryTitle(uri)
    }

    /** Only for a label, so a provider that refuses or fails only means that no name is shown. */
    private fun queryTitle(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.TITLE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: RuntimeException) {
        null
    }

    private companion object {
        /** Android adds the title to the address of a sound it stores as the default. */
        const val TITLE_PARAMETER = "title"
    }
}

/** Writes the document the user chose for the exported log. */
fun interface LogDocuments {
    /** False if it could not be written. */
    fun write(uri: Uri, text: String): Boolean
}

class ContentResolverLogDocuments(private val resolver: ContentResolver) : LogDocuments {
    override fun write(uri: Uri, text: String): Boolean = try {
        val output = resolver.openOutputStream(uri, TRUNCATE)
        output?.use { it.write(text.toByteArray()) }
        output != null
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private companion object {
        /** A document chosen again must not keep the end of what it held. */
        const val TRUNCATE = "wt"
    }
}

/** Whether a ringing alarm can fill the screen, which the user can turn off in the system settings. */
fun canUseFullScreenIntent(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

/**
 * Whether a ringing alarm can show anything. The user can turn off all notifications of the app, or only the channel of
 * the ringing alarm. A channel that does not exist yet is created when it is first needed, and is not off.
 */
fun alarmNotificationsAllowed(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = manager.getNotificationChannel(AlarmNotifications.ALARM_CHANNEL)
    return manager.areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE
}
