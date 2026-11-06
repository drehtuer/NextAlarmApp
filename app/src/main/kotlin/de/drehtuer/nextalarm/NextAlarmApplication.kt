package de.drehtuer.nextalarm

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import de.drehtuer.nextalarm.planning.NextAlarmWorkerFactory
import javax.inject.Inject

@HiltAndroidApp
class NextAlarmApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: NextAlarmWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
