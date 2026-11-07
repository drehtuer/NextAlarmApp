package de.drehtuer.nextalarm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.drehtuer.nextalarm.ringing.AlarmActivity
import de.drehtuer.nextalarm.ringing.RingingChanges
import de.drehtuer.nextalarm.ui.AppViewModel
import de.drehtuer.nextalarm.ui.NextAlarmApp
import de.drehtuer.nextalarm.ui.theme.NextAlarmTheme
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val resumeCount = mutableIntStateOf(0)
    private val diagnosticsRequests = mutableIntStateOf(0)
    private var alarmWatch: Job = Job()

    @Inject
    lateinit var ringing: RingingChanges

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A saved state means the activity was only recreated: the request was served, and the screens are restored.
        if (savedInstanceState == null) handleRequest(intent)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsState()
            NextAlarmTheme {
                NextAlarmApp(
                    state = state,
                    resumeCount = resumeCount.intValue,
                    onToggleCalendar = viewModel::setCalendarTicked,
                    onCompleteSetup = viewModel::completeSetup,
                    actions = viewModel,
                    diagnosticsRequests = diagnosticsRequests.intValue,
                )
            }
        }
    }

    /** A notification that opens the running app comes here. See `openDiagnosticsIntent`. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRequest(intent)
    }

    private fun handleRequest(intent: Intent) {
        if (intent.action == ACTION_OPEN_DIAGNOSTICS) diagnosticsRequests.intValue++
    }

    /**
     * Opening the app while an alarm rings leads to the alarm screen. If notifications are off, that is the only way to
     * stop the alarm: without them it has no screen of its own and no buttons.
     */
    override fun onStart() {
        super.onStart()
        alarmWatch = lifecycleScope.launch {
            ringing.alarms.collect { alarms ->
                if (alarms.isNotEmpty()) startActivity(Intent(this@MainActivity, AlarmActivity::class.java))
            }
        }
    }

    override fun onStop() {
        alarmWatch.cancel()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumeCount.intValue++
        viewModel.startWatching()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopWatching()
    }

    companion object {
        const val ACTION_OPEN_DIAGNOSTICS = "de.drehtuer.nextalarm.action.OPEN_DIAGNOSTICS"
    }
}
