package de.drehtuer.nextalarm.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.RawRes
import de.drehtuer.nextalarm.domain.SoundRef
import de.drehtuer.nextalarm.domain.SoundRejection
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlinx.coroutines.CoroutineDispatcher
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

val MEDIA_METADATA_AUDIO_PROBE = AudioProbe { file ->
    try {
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

    /** [fileName] is what the user knows the file by. */
    data class Rejected(val reason: SoundRejection, val fileName: String) : SoundImportResult
}

/** What the alarm settings need of the sound copies. */
interface SoundFiles {
    /** Takes a copy of the file the user picked, or says why it was not taken. */
    suspend fun import(uri: Uri): SoundImportResult

    /** Deletes the app's copy of a sound that is no longer selected. The user's original is not affected. */
    fun delete(sound: SoundRef)
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
    private val probe: AudioProbe = MEDIA_METADATA_AUDIO_PROBE,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SoundFiles {
    private val resolver = context.contentResolver
    private val directory = File(context.createDeviceProtectedStorageContext().filesDir, "sounds")

    override suspend fun import(uri: Uri): SoundImportResult = withContext(ioDispatcher) {
        val fileName = displayName(uri)
        if (resolver.getType(uri) !in SUPPORTED_SOUND_MIME_TYPES) {
            return@withContext SoundImportResult.Rejected(SoundRejection.UNSUPPORTED_TYPE, fileName)
        }
        directory.mkdirs()
        val sound = SoundRef.File.forCopy(UUID.randomUUID().toString(), fileName)
        val file = File(directory, sound.name)
        val rejection = when {
            !copy(uri, file) -> SoundRejection.UNREADABLE
            !probe.isPlayable(file) -> SoundRejection.NOT_PLAYABLE
            else -> null
        }
        if (rejection == null) {
            SoundImportResult.Imported(sound)
        } else {
            removeQuietly(file)
            SoundImportResult.Rejected(rejection, fileName)
        }
    }

    /** The picker's name for the file, or else the end of its address. A provider may refuse or fail to answer. */
    private fun displayName(uri: Uri): String {
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: RuntimeException) {
            null
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: UNNAMED_FILE
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

    override fun delete(sound: SoundRef) {
        storedFile(sound)?.let(::removeQuietly)
    }

    private fun storedFile(sound: SoundRef): File? =
        (sound as? SoundRef.File)?.let { File(directory, it.name) }?.takeIf { it.isFile }

    /** A copy that cannot be deleted only takes up space, so it must not get in the way of choosing a sound. */
    private fun removeQuietly(file: File) {
        try {
            file.toPath().deleteIfExists()
        } catch (_: IOException) {
            // Nothing else can be done about it here.
        }
    }

    private fun copy(uri: Uri, target: File): Boolean = try {
        val input = resolver.openInputStream(uri)
        input?.use { source -> target.outputStream().use { source.copyTo(it) } }
        input != null
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private companion object {
        const val UNNAMED_FILE = "file"
    }
}
