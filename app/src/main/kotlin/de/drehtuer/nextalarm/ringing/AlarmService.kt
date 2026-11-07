package de.drehtuer.nextalarm.ringing

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps the app alive while alarms ring. It is sticky: after a crash during ringing Android restarts the process, whose
 * recovery sweep rings the alarm again. See docs/architecture.md#recovery. The logic is in [AlarmForeground].
 */
@AndroidEntryPoint
class AlarmService : Service(), AlarmForegroundHost {
    @Inject
    lateinit var foreground: AlarmForeground

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground.attach(this)
        return START_STICKY
    }

    override fun onDestroy() {
        foreground.detach(this)
        super.onDestroy()
    }

    override fun showForeground(id: Int, notification: Notification) {
        startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
    }

    override fun finishRinging() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
