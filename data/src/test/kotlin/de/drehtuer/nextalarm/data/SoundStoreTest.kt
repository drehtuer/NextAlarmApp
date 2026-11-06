package de.drehtuer.nextalarm.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.data.SoundImportResult.Reason
import de.drehtuer.nextalarm.domain.SoundRef
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
class SoundStoreTest {
    /** Stands in for the document provider behind the file picker. */
    class FakeDocumentProvider : ContentProvider() {
        var type: String? = "audio/mpeg"
        var file: File? = null
        var denied = false

        override fun onCreate() = true
        override fun getType(uri: Uri): String? = type

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (denied) throw SecurityException("permission lost")
            val source = file ?: throw FileNotFoundException("gone")
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = Robolectric.buildContentProvider(FakeDocumentProvider::class.java).create("docs").get()
    private val uri = Uri.parse("content://docs/song")
    private val sounds = File(context.createDeviceProtectedStorageContext().filesDir, "sounds")
    private val bundled = SoundSource.Bundled(R.raw.alarm_fallback)
    private val systemAlarm = Uri.parse("content://media/internal/audio/media/37")

    private fun setSystemAlarm() {
        Settings.System.putString(context.contentResolver, Settings.System.ALARM_ALERT, systemAlarm.toString())
    }

    private fun store(playable: Boolean = true) = SoundStore(context) { playable }

    private fun picked(content: String = "audio data") =
        File.createTempFile("picked", null, context.cacheDir).apply { writeText(content) }

    private fun storedFiles() = sounds.listFiles().orEmpty().toList()

    @Test
    fun `a playable file is copied into device-protected storage`() = runBlocking {
        provider.file = picked("audio data")
        val result = assertIs<SoundImportResult.Imported>(store().import(uri))
        assertEquals("audio data", File(sounds, result.sound.name).readText())
    }

    @Test
    fun `every documented type is accepted`() = runBlocking {
        SUPPORTED_SOUND_MIME_TYPES.forEach { type ->
            provider.type = type
            provider.file = picked()
            assertIs<SoundImportResult.Imported>(store().import(uri), type)
        }
    }

    @Test
    fun `an unsupported type is rejected without copying`() = runBlocking {
        provider.type = "audio/flac"
        provider.file = picked()
        assertEquals(SoundImportResult.Rejected(Reason.UNSUPPORTED_TYPE), store().import(uri))
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `an unknown type is rejected`() = runBlocking {
        provider.type = null
        assertEquals(SoundImportResult.Rejected(Reason.UNSUPPORTED_TYPE), store().import(uri))
    }

    @Test
    fun `a file that cannot be opened is rejected and leaves nothing behind`() = runBlocking {
        provider.file = null
        assertEquals(SoundImportResult.Rejected(Reason.UNREADABLE), store().import(uri))
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `a file without read permission is rejected`() = runBlocking {
        provider.denied = true
        assertEquals(SoundImportResult.Rejected(Reason.UNREADABLE), store().import(uri))
    }

    @Test
    fun `a file that is not playable is rejected and leaves nothing behind`() = runBlocking {
        provider.file = picked()
        assertEquals(SoundImportResult.Rejected(Reason.NOT_PLAYABLE), store(playable = false).import(uri))
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `a picked sound is tried first, then the system default, then the bundled sound`() = runBlocking {
        setSystemAlarm()
        provider.file = picked()
        val result = assertIs<SoundImportResult.Imported>(store().import(uri))
        assertEquals(
            listOf(SoundSource.Stored(File(sounds, result.sound.name)), SoundSource.System(systemAlarm), bundled),
            store().sources(result.sound),
        )
    }

    @Test
    fun `the default sound is the system default, then the bundled sound`() {
        setSystemAlarm()
        assertEquals(listOf(SoundSource.System(systemAlarm), bundled), store().sources(SoundRef.SystemDefault))
    }

    @Test
    fun `a missing file falls through to the system default`() {
        setSystemAlarm()
        assertEquals(listOf(SoundSource.System(systemAlarm), bundled), store().sources(SoundRef.File("gone")))
    }

    @Test
    fun `without a system alarm sound the bundled sound still rings`() {
        assertEquals(listOf(bundled), store().sources(SoundRef.SystemDefault))
    }

    @Test
    fun `deleting a sound removes the app's copy and not the original`() = runBlocking {
        val original = picked()
        provider.file = original
        val sound = assertIs<SoundImportResult.Imported>(store().import(uri)).sound
        store().delete(sound)
        assertFalse(File(sounds, sound.name).exists())
        assertTrue(original.exists())
        assertEquals(listOf(bundled), store().sources(sound))
    }

    @Test
    fun `deleting the system default or a missing copy does nothing`() = runBlocking {
        provider.file = picked()
        val kept = assertIs<SoundImportResult.Imported>(store().import(uri)).sound
        store().delete(SoundRef.SystemDefault)
        store().delete(SoundRef.File("gone"))
        assertTrue(File(sounds, kept.name).exists())
    }

    @Test
    fun `the bundled sound is part of the build`() {
        context.resources.openRawResource(bundled.resourceId).use { assertTrue(it.read() != -1) }
    }

    @Test
    fun `importing a new sound keeps the earlier one and the original`() = runBlocking {
        val original = picked()
        provider.file = original
        val first = assertIs<SoundImportResult.Imported>(store().import(uri)).sound
        val second = assertIs<SoundImportResult.Imported>(store().import(uri)).sound
        assertTrue(File(sounds, first.name).exists())
        assertTrue(File(sounds, second.name).exists())
        assertTrue(original.exists())
    }

    @Test
    fun `a file that cannot be stored is rejected`() = runBlocking {
        provider.file = picked()
        // A file in place of the directory makes writing fail, as a full or broken storage would.
        sounds.parentFile?.mkdirs()
        sounds.writeText("in the way")
        assertEquals(Reason.UNREADABLE, assertIs<SoundImportResult.Rejected>(store().import(uri)).reason)
    }

    @Test
    fun `the store uses the real probe by default`() = runBlocking {
        provider.file = picked()
        assertEquals(SoundImportResult.Rejected(Reason.NOT_PLAYABLE), SoundStore(context).import(uri))
    }

    @Test
    fun `a stored source gives access to its file`() {
        val file = File("/a")
        assertEquals(file, SoundSource.Stored(file).file)
    }

    @Test
    fun `the probe accepts a file with audio`() {
        ShadowMediaMetadataRetriever.addMetadata(
            DataSource.toDataSource("/song"), MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes",
        )
        assertTrue(MediaMetadataAudioProbe.isPlayable(File("/song")))
    }

    @Test
    fun `the probe refuses a file without audio`() {
        assertFalse(MediaMetadataAudioProbe.isPlayable(File("/silent")))
    }

    @Test
    fun `the probe refuses a file that cannot be read`() {
        ShadowMediaMetadataRetriever.addException(DataSource.toDataSource("/broken"), IllegalArgumentException("bad"))
        assertFalse(MediaMetadataAudioProbe.isPlayable(File("/broken")))
    }
}
