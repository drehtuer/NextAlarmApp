package de.drehtuer.nextalarm.ringing

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps the app alive while the wake-up automation plays. It is not sticky: an interrupted automation is not resumed,
 * see docs/architecture.md#recovery. The logic is in [WakeUpForeground].
 */
@AndroidEntryPoint
class WakeUpService : Service(), WakeUpForegroundHost {
    @Inject
    lateinit var foreground: WakeUpForeground

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground.attach(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foreground.detach(this)
        super.onDestroy()
    }

    override fun showForeground(id: Int, notification: Notification) {
        startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun finishWakeUp() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
