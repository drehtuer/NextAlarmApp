package de.drehtuer.nextalarm.ringing

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.drehtuer.nextalarm.domain.alarmScreenModel
import de.drehtuer.nextalarm.ui.screens.AlarmScreen
import de.drehtuer.nextalarm.ui.theme.NightTheme
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Screen 8, over the lock screen and also before the first unlock. The logic is in [AlarmScreenController]. */
@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {
    @Inject
    lateinit var controller: AlarmScreenController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller.start(lifecycleScope)
        lifecycleScope.launch {
            controller.finished.first { it }
            if (controller.wakeUpFollows.value) startActivity(Intent(this@AlarmActivity, WakeUpActivity::class.java))
            finish()
        }
        setContent {
            val ringing by controller.ringing.collectAsState()
            val now by controller.now.collectAsState()
            // Until the alarms are read, the black window background shows.
            if (ringing.isNotEmpty()) {
                NightTheme {
                    AlarmScreen(
                        alarmScreenModel(now, ZoneId.systemDefault(), ringing),
                        controller::snooze,
                        controller::dismiss,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        controller.onResume()
    }
}
