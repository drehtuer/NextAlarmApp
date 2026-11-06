package de.drehtuer.nextalarm.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NextAlarmDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the database is created in device-protected storage`() = runBlocking {
        val database = NextAlarmDatabase.open(context)
        try {
            assertNull(database.settings().load())
        } finally {
            database.close()
        }
        val deviceContext = context.createDeviceProtectedStorageContext()
        assertTrue(deviceContext.getDatabasePath(NextAlarmDatabase.NAME).isFile)
    }
}
