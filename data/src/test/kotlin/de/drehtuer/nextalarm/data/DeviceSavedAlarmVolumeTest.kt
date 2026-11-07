package de.drehtuer.nextalarm.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceSavedAlarmVolumeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val saved = DeviceSavedAlarmVolume(context)

    /** Preferences whose every write fails, as with a full disk. */
    private val full = object : ContextWrapper(context) {
        override fun createDeviceProtectedStorageContext(): Context = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                failingPreferences(context.getSharedPreferences(name, mode))
        }
    }

    private fun failingPreferences(real: SharedPreferences): SharedPreferences {
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { self, method, _ ->
            if (method.name == "commit") false else self
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, arguments ->
            if (method.name == "edit") editor else method.invoke(real, *arguments.orEmpty())
        } as SharedPreferences
    }

    @Test
    fun `nothing is stored at first`() {
        assertNull(saved.load())
    }

    @Test
    fun `a stored volume can be read and forgotten`() {
        saved.save(4)
        assertEquals(4, saved.load())
        saved.clear()
        assertNull(saved.load())
    }

    @Test
    fun `a volume of zero counts as stored`() {
        saved.save(0)
        assertEquals(0, saved.load())
    }

    @Test
    fun `a new instance, as after a crash, finds what was stored`() {
        saved.save(4)
        assertEquals(4, DeviceSavedAlarmVolume(context).load())
    }

    @Test
    fun `it is in device-protected storage, so that it can be read before the first unlock`() {
        saved.save(4)
        val preferences = context.createDeviceProtectedStorageContext().getSharedPreferences("alarm_volume", Context.MODE_PRIVATE)
        assertEquals(4, preferences.getInt("previous_level", -1))
    }

    @Test
    fun `a file of its own keeps tests apart from the real one`() {
        DeviceSavedAlarmVolume(context, "another").save(4)
        assertNull(saved.load())
    }

    @Test
    fun `a volume that cannot be written is an error, so that the caller knows`() {
        val failing = DeviceSavedAlarmVolume(full)
        assertFailsWith<IllegalStateException> { failing.save(4) }
        assertFailsWith<IllegalStateException> { failing.clear() }
    }
}
