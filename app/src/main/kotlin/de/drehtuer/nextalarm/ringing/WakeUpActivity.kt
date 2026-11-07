package de.drehtuer.nextalarm.ringing

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmRepository
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.WakeUpStatus
import de.drehtuer.nextalarm.domain.wakeUpScreenModel
import de.drehtuer.nextalarm.ui.screens.WakeUpScreen
import de.drehtuer.nextalarm.ui.theme.NightTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Tells whoever is interested that the wake-up automation changed. */
class WakeUpChanges {
    private val mutable = MutableStateFlow(WakeUpStatus.IDLE)
    val status: StateFlow<WakeUpStatus> = mutable.asStateFlow()

    fun report(status: WakeUpStatus) {
        mutable.value = status
    }
}

/**
 * The logic of the wake-up screen. It ends when no alarm is left in the automation, according to the stored state:
 * a dismissed alarm is stored before the automation hears of it, so the automation alone could seem to be over when this
 * screen comes up. It asks once a second as well, for the clock and how far a podcast has got.
 */
class WakeUpScreenController(
    private val alarms: AlarmRepository,
    private val engine: AlarmEngine,
    private val currentStatus: () -> WakeUpStatus,
    private val changes: WakeUpChanges,
    private val foreground: WakeUpForeground,
    /** Stop outlives the screen, which closes as soon as it has taken effect. */
    private val appScope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
    private val clockPeriod: Duration = Duration.ofSeconds(1),
) {
    private val mutableStatus = MutableStateFlow(WakeUpStatus.IDLE)
    private val mutableNow = MutableStateFlow(clock())
    private val mutableFinished = MutableStateFlow(false)

    val status: StateFlow<WakeUpStatus> = mutableStatus.asStateFlow()
    val now: StateFlow<Instant> = mutableNow.asStateFlow()
    val finished: StateFlow<Boolean> = mutableFinished.asStateFlow()

    /** Keeps the state current for as long as [scope] lives. */
    fun start(scope: CoroutineScope) {
        scope.launch { changes.status.collect { reload() } }
        scope.launch {
            while (true) {
                mutableNow.value = clock()
                reload()
                delay(clockPeriod.toMillis())
            }
        }
    }

    fun onResume() = foreground.sync()

    fun stop() {
        appScope.launch {
            engine.stopWakeUp()
            reload()
        }
    }

    private suspend fun reload() {
        val stored = alarms.all().any { it.state == AlarmState.Announcing || it.state == AlarmState.NewsPlaying }
        val status = currentStatus()
        mutableStatus.value = status
        mutableFinished.value = !stored && !status.running
    }
}

/** Screen 9, over the lock screen and also before the first unlock. The logic is in [WakeUpScreenController]. */
@AndroidEntryPoint
class WakeUpActivity : ComponentActivity() {
    @Inject
    lateinit var controller: WakeUpScreenController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Between two utterances nothing plays, and the volume keys should still mean the media volume.
        volumeControlStream = AudioManager.STREAM_MUSIC
        controller.start(lifecycleScope)
        lifecycleScope.launch {
            controller.finished.first { it }
            finish()
        }
        setContent {
            val status by controller.status.collectAsState()
            val now by controller.now.collectAsState()
            // Until the automation has told what it is doing, the black window background shows.
            if (status.running) {
                NightTheme {
                    WakeUpScreen(wakeUpScreenModel(now, ZoneId.systemDefault(), status), controller::stop)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        controller.onResume()
    }
}
