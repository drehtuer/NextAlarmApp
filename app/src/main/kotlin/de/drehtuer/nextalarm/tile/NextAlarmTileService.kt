package de.drehtuer.nextalarm.tile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.components.SingletonComponent
import de.drehtuer.nextalarm.data.DataComponents
import de.drehtuer.nextalarm.domain.AlarmRepository
import de.drehtuer.nextalarm.domain.NextAlarmTile
import de.drehtuer.nextalarm.domain.nextAlarmTile
import de.drehtuer.nextalarm.planning.openAppIntent
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "NextAlarm"

/** Tells the tile that the next alarm may have changed. This is called on the alarm path, so it must not throw. */
fun interface TileRefresh {
    fun request()
}

/**
 * Only asks the system to bind the tile: no WorkManager and no storage, so it works before the first unlock, when the
 * tile itself can't start yet. The tile is of no importance to an alarm, so a failure here ends here.
 */
class AndroidTileRefresh(
    private val context: Context,
    private val ask: (Context, ComponentName) -> Unit = TileService::requestListeningState,
) : TileRefresh {
    override fun request() {
        try {
            ask(context, ComponentName(context, NextAlarmTileService::class.java))
        } catch (failure: Exception) {
            Log.w(TAG, "Could not ask for a refresh of the tile", failure)
        }
    }
}

/**
 * Reads what the tile has to show. The tile runs in the process that rings the alarms, so a failure here must not reach
 * the main thread. [start] and [stop] are called on [dispatcher]'s thread, and only the latest [start] still counts.
 */
class NextAlarmTileReader(
    private val alarms: AlarmRepository,
    dispatcher: CoroutineDispatcher,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var reading: Job? = null

    fun start(show: (NextAlarmTile) -> Unit) {
        stop()
        reading = scope.launch {
            try {
                show(nextAlarmTile(alarms.all(), now(), zone()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // The tile keeps what it showed: "None in 48 hours" would claim something nobody knows.
                Log.w(TAG, "Could not refresh the tile", failure)
            }
        }
    }

    fun stop() {
        reading?.cancel()
        reading = null
    }
}

/** Puts [model] on the tile, if there is one, and sends it to the system. */
fun showOnTile(tile: Tile?, model: NextAlarmTile) {
    if (tile == null) return
    tile.label = model.label
    tile.subtitle = model.subtitle
    tile.state = if (model.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    tile.updateTile()
}

/** The quick settings tile. It is read-only: tapping it opens the app. See docs/architecture.md#quick-settings-tile. */
@AndroidEntryPoint
class NextAlarmTileService : TileService() {
    @Inject
    lateinit var reader: NextAlarmTileReader

    override fun onStartListening() {
        super.onStartListening()
        reader.start { showOnTile(qsTile, it) }
    }

    override fun onStopListening() {
        reader.stop()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        startActivityAndCollapse(openAppIntent(this))
    }

    override fun onDestroy() {
        reader.stop()
        super.onDestroy()
    }
}

/** Only for the tile. It needs nothing but the alarms, so it stays clear of WorkManager. */
@Module
@InstallIn(SingletonComponent::class)
object TileModule {
    /** The database does its own work off the main thread, and the tile has to be touched on it. */
    @Provides
    fun reader(data: DataComponents) = NextAlarmTileReader(data.alarms, Dispatchers.Main.immediate)
}
