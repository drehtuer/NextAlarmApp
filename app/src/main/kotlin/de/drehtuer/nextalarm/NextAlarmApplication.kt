package de.drehtuer.nextalarm

import android.app.Application
import androidx.work.Configuration
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import de.drehtuer.nextalarm.planning.NextAlarmWorkerFactory
import de.drehtuer.nextalarm.ringing.AlarmBroadcasts
import javax.inject.Inject

/** This also runs before the first unlock, so it must not lead to WorkManager. See docs/architecture.md#direct-boot. */
@HiltAndroidApp
class NextAlarmApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: NextAlarmWorkerFactory

    @Inject
    lateinit var alarmBroadcasts: Lazy<AlarmBroadcasts>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** At process start nothing is playing yet, and the persisted state says what should be. */
    override fun onCreate() {
        super.onCreate()
        alarmBroadcasts.get().sweep()
    }
}
