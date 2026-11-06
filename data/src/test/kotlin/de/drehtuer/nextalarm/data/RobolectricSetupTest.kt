package de.drehtuer.nextalarm.data

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Confirms that Robolectric runs against the project's API level, with device-protected storage available. */
@RunWith(RobolectricTestRunner::class)
class RobolectricSetupTest {
    @Test
    fun runsOnTargetApiLevel() {
        assertEquals(37, Build.VERSION.SDK_INT)
    }

    @Test
    fun deviceProtectedStorageIsAvailable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context.createDeviceProtectedStorageContext().isDeviceProtectedStorage)
    }
}
