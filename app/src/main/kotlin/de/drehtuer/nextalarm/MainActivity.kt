package de.drehtuer.nextalarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import dagger.hilt.android.AndroidEntryPoint
import de.drehtuer.nextalarm.ui.AppViewModel
import de.drehtuer.nextalarm.ui.NextAlarmApp
import de.drehtuer.nextalarm.ui.theme.NextAlarmTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val resumeCount = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsState()
            NextAlarmTheme {
                NextAlarmApp(
                    state = state,
                    resumeCount = resumeCount.intValue,
                    onSelectCalendar = viewModel::chooseCalendar,
                    onCompleteSetup = viewModel::completeSetup,
                )
            }
        }
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
}
