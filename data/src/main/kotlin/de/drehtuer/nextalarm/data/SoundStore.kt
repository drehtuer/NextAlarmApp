package de.drehtuer.nextalarm.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.RingtoneManager
import android.net.Uri
import androidx.annotation.RawRes
import de.drehtuer.nextalarm.domain.SoundRef
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the file picker offers. Android reports WAV and M4A files under more than one type. */
val SUPPORTED_SOUND_MIME_TYPES: Set<String> = setOf(
    "audio/mpeg",
    "audio/wav",
    "audio/x-wav",
    "audio/ogg",
    "audio/mp4",
    "audio/x-m4a",
)

/** Checks whether a file contains audio that Android can read. */
fun interface AudioProbe {
    fun isPlayable(file: File): Boolean
}

object MediaMetadataAudioProbe : AudioProbe {
    override fun isPlayable(file: File): Boolean = try {
        MediaMetadataRetriever().use {
            it.setDataSource(file.path)
            it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        }
    } catch (_: RuntimeException) {
        false
    }
}

sealed interface SoundImportResult {
    data class Imported(val sound: SoundRef.File) : SoundImportResult
    data class Rejected(val reason: Reason) : SoundImportResult

    enum class Reason { UNSUPPORTED_TYPE, UNREADABLE, NOT_PLAYABLE }
}

/** Where to play a sound from. */
sealed interface SoundSource {
    data class Stored(val file: File) : SoundSource
    data class System(val uri: Uri) : SoundSource
    data class Bundled(@param:RawRes val resourceId: Int) : SoundSource
}

/**
 * Keeps copies of the picked alarm sounds in device-protected storage, so that they can be played before the first
 * unlock and keep working if the original file is moved or deleted. Originals are never changed.
 */
class SoundStore(
    private val context: Context,
    private val probe: AudioProbe = MediaMetadataAudioProbe,
) {
    private val resolver = context.contentResolver
    private val directory = File(context.createDeviceProtectedStorageContext().filesDir, "sounds")

    suspend fun import(uri: Uri): SoundImportResult = withContext(Dispatchers.IO) {
        if (resolver.getType(uri) !in SUPPORTED_SOUND_MIME_TYPES) {
            return@withContext SoundImportResult.Rejected(SoundImportResult.Reason.UNSUPPORTED_TYPE)
        }
        directory.mkdirs()
        val file = File(directory, "sound-${UUID.randomUUID()}")
        val rejection = when {
            !copy(uri, file) -> SoundImportResult.Reason.UNREADABLE
            !probe.isPlayable(file) -> SoundImportResult.Reason.NOT_PLAYABLE
            else -> null
        }
        if (rejection == null) {
            SoundImportResult.Imported(SoundRef.File(file.name))
        } else {
            file.delete()
            SoundImportResult.Rejected(rejection)
        }
    }

    /**
     * What to try for this sound, in order: if one cannot be played, the next one is used. It always ends with the
     * bundled sound, because an alarm has to ring. The system's sound may be unavailable before the first unlock.
     */
    fun sources(sound: SoundRef): List<SoundSource> = listOfNotNull(
        storedFile(sound)?.let { SoundSource.Stored(it) },
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)?.let { SoundSource.System(it) },
        SoundSource.Bundled(R.raw.alarm_fallback),
    )

    /** Deletes the app's copy of a sound that is no longer selected. The user's original is not affected. */
    fun delete(sound: SoundRef) {
        (sound as? SoundRef.File)?.let { File(directory, it.name).delete() }
    }

    private fun storedFile(sound: SoundRef): File? =
        (sound as? SoundRef.File)?.let { File(directory, it.name) }?.takeIf { it.isFile }

    private fun copy(uri: Uri, target: File): Boolean = try {
        val input = resolver.openInputStream(uri)
        input?.use { source -> target.outputStream().use { source.copyTo(it) } }
        input != null
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
