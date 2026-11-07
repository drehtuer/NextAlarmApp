package de.drehtuer.nextalarm.data

import android.content.Context
import android.content.SharedPreferences
import de.drehtuer.nextalarm.domain.SavedAlarmVolume

/**
 * Keeps the user's alarm volume in device-protected storage, so that it can be put back before the first unlock after a
 * reboot. It is not worth a table: it only exists while an alarm has the volume changed. Writing waits for the disk, as
 * the stream only changes after this.
 */
class DeviceSavedAlarmVolume(
    context: Context,
    fileName: String = "alarm_volume",
) : SavedAlarmVolume {
    private val preferences: SharedPreferences by lazy {
        context.createDeviceProtectedStorageContext().getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }

    override fun load(): Int? = if (preferences.contains(KEY)) preferences.getInt(KEY, 0) else null

    override fun save(level: Int) {
        check(preferences.edit().putInt(KEY, level).commit()) { "The alarm volume could not be stored" }
    }

    override fun clear() {
        check(preferences.edit().remove(KEY).commit()) { "The stored alarm volume could not be removed" }
    }

    private companion object {
        const val KEY = "previous_level"
    }
}
