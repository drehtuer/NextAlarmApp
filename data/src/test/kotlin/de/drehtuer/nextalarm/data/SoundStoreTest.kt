package de.drehtuer.nextalarm.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.domain.SoundRejection
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
    /** Stands in for the document provider behind the file picker, and for the media store with the phone's sounds. */
    class FakeDocumentProvider : ContentProvider() {
        var type: String? = "audio/mpeg"
        var file: File? = null
        var denied = false
        var names: List<String?> = emptyList()

        /** What the media store calls a sound. A document provider has no titles. */
        var titles: List<String?> = emptyList()
        var nameFailure: RuntimeException? = null

        override fun onCreate() = true
        override fun getType(uri: Uri): String? = type

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (denied) throw SecurityException("permission lost")
            val source = file ?: throw FileNotFoundException("gone")
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
            nameFailure?.let { throw it }
            val column = p?.single() ?: OpenableColumns.DISPLAY_NAME
            val values = if (column == MediaStore.MediaColumns.TITLE) titles else names
            if (values.isEmpty() && !answersEmpty) return null
            return MatrixCursor(arrayOf(column)).apply { values.forEach { addRow(arrayOf(it)) } }
        }

        var answersEmpty = false

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

    private fun store(playable: Boolean = true) = SoundStore(context, probe = { playable })

    private fun picked(content: String = "audio data") =
        File.createTempFile("picked", null, context.cacheDir).apply { writeText(content) }

    private fun storedFiles() = sounds.listFiles().orEmpty().toList()

    @Test
    fun `an imported sound knows the name the picker gave its file`() = runBlocking {
        provider.names = listOf("morning.mp3")
        provider.file = picked()
        val sound = assertIs<SoundImportResult.Imported>(store().import(uri)).sound
        assertEquals("morning.mp3", sound.displayName)
        assertTrue(File(sounds, sound.name).isFile)
    }

    @Test
    fun `a rejection names the file the way the picker does`() = runBlocking {
        provider.names = listOf("Notes.pdf")
        provider.type = "application/pdf"
        assertEquals(SoundImportResult.Rejected(SoundRejection.UNSUPPORTED_TYPE, "Notes.pdf"), store().import(uri))
    }

    @Test
    fun `a name the provider does not give comes from the address`() = runBlocking {
        provider.type = null
        provider.answersEmpty = true
        val documents = Uri.parse("content://docs/document/primary%3AMusic%2Fmorning.mp3")
        assertEquals("morning.mp3", assertIs<SoundImportResult.Rejected>(store().import(documents)).fileName)
        provider.names = listOf(null)
        assertEquals("song", assertIs<SoundImportResult.Rejected>(store().import(uri)).fileName)
    }

    @Test
    fun `a provider that fails to give a name does not stop the import`() = runBlocking {
        provider.nameFailure = SecurityException("no")
        provider.file = picked()
        val sound = assertIs<SoundImportResult.Imported>(store().import(uri)).sound
        assertEquals("song", sound.displayName)
    }

    @Test
    fun `an address without a path still gives a name`() = runBlocking {
        provider.type = null
        assertEquals("file", assertIs<SoundImportResult.Rejected>(store().import(Uri.parse("content://docs"))).fileName)
    }

    @Test
    fun `a name cannot lead the copy out of its directory`() = runBlocking {
        provider.names = listOf("../../evil.mp3")
        provider.file = picked()
        val sound = assertIs<SoundImportResult.Imported>(store().import(uri)).sound
        assertEquals(sounds.canonicalFile, File(sounds, sound.name).canonicalFile.parentFile)
    }

    @Test
    fun `a phone sound is named by its title, and copied like a file`() = runBlocking {
        provider.type = "audio/ogg"
        provider.names = listOf("Oxygen.ogg")
        provider.titles = listOf("Oxygen")
        provider.file = picked("ogg data")
        val sound = assertIs<SoundImportResult.Imported>(store().importPhoneSound(uri)).sound
        assertEquals("Oxygen", sound.displayName)
        assertEquals("ogg data", File(sounds, sound.name).readText())
    }

    @Test
    fun `a phone sound without a title is named the way a file is`() = runBlocking {
        provider.type = "audio/ogg"
        provider.names = listOf("Oxygen.ogg")
        provider.file = picked()
        assertEquals("Oxygen.ogg", assertIs<SoundImportResult.Imported>(store().importPhoneSound(uri)).sound.displayName)
        provider.titles = listOf(" ")
        assertEquals("Oxygen.ogg", assertIs<SoundImportResult.Imported>(store().importPhoneSound(uri)).sound.displayName)
        provider.titles = listOf(null)
        assertEquals("Oxygen.ogg", assertIs<SoundImportResult.Imported>(store().importPhoneSound(uri)).sound.displayName)
    }

    @Test
    fun `a media store that fails to give a title does not stop the import`() = runBlocking {
        provider.type = "audio/ogg"
        provider.nameFailure = SecurityException("no")
        provider.file = picked()
        assertEquals("song", assertIs<SoundImportResult.Imported>(store().importPhoneSound(uri)).sound.displayName)
    }

    @Test
    fun `a phone sound is checked like a file, and a rejection names its title`() = runBlocking {
        provider.titles = listOf("Oxygen")
        provider.type = "audio/flac"
        assertEquals(SoundImportResult.Rejected(SoundRejection.UNSUPPORTED_TYPE, "Oxygen"), store().importPhoneSound(uri))
        provider.type = "audio/ogg"
        provider.denied = true
        assertEquals(SoundImportResult.Rejected(SoundRejection.UNREADABLE, "Oxygen"), store().importPhoneSound(uri))
        provider.denied = false
        provider.file = picked()
        assertEquals(
            SoundImportResult.Rejected(SoundRejection.NOT_PLAYABLE, "Oxygen"),
            store(playable = false).importPhoneSound(uri),
        )
        assertTrue(storedFiles().isEmpty())
    }

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
        assertEquals(SoundImportResult.Rejected(SoundRejection.UNSUPPORTED_TYPE, "song"), store().import(uri))
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `an unknown type is rejected`() = runBlocking {
        provider.type = null
        assertEquals(SoundImportResult.Rejected(SoundRejection.UNSUPPORTED_TYPE, "song"), store().import(uri))
    }

    @Test
    fun `a file that cannot be opened is rejected and leaves nothing behind`() = runBlocking {
        provider.file = null
        assertEquals(SoundImportResult.Rejected(SoundRejection.UNREADABLE, "song"), store().import(uri))
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `a file without read permission is rejected`() = runBlocking {
        provider.denied = true
        assertEquals(SoundImportResult.Rejected(SoundRejection.UNREADABLE, "song"), store().import(uri))
    }

    @Test
    fun `a file that is not playable is rejected and leaves nothing behind`() = runBlocking {
        provider.file = picked()
        assertEquals(SoundImportResult.Rejected(SoundRejection.NOT_PLAYABLE, "song"), store(playable = false).import(uri))
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
    fun `the bundled sound can be had on its own, and ends the list`() {
        assertEquals(bundled, store().bundled)
        assertEquals(store().bundled, store().sources(SoundRef.SystemDefault).last())
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
        assertEquals(SoundRejection.UNREADABLE, assertIs<SoundImportResult.Rejected>(store().import(uri)).reason)
    }

    @Test
    fun `the store uses the real probe by default`() = runBlocking {
        provider.file = picked()
        assertEquals(SoundImportResult.Rejected(SoundRejection.NOT_PLAYABLE, "song"), SoundStore(context).import(uri))
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
        assertTrue(MEDIA_METADATA_AUDIO_PROBE.isPlayable(File("/song")))
    }

    @Test
    fun `the probe refuses a file without audio`() {
        assertFalse(MEDIA_METADATA_AUDIO_PROBE.isPlayable(File("/silent")))
    }

    @Test
    fun `the probe refuses a file that cannot be read`() {
        ShadowMediaMetadataRetriever.addException(DataSource.toDataSource("/broken"), IllegalArgumentException("bad"))
        assertFalse(MEDIA_METADATA_AUDIO_PROBE.isPlayable(File("/broken")))
    }
}
