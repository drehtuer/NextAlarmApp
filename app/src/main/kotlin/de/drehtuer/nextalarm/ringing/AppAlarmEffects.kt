package de.drehtuer.nextalarm.ringing

import de.drehtuer.nextalarm.domain.AlarmAction
import de.drehtuer.nextalarm.domain.AlarmEffects
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmRinger
import de.drehtuer.nextalarm.domain.SoundPreview
import de.drehtuer.nextalarm.domain.WakeUpAutomation
import de.drehtuer.nextalarm.planning.AppNotifications

/**
 * What the alarm state machine asks of Android. See the entry actions in docs/state_machines.md. An alarm that starts to
 * ring stops the [preview] of the sound on screen 5, after it has joined: from then on the preview can't start again.
 */
class AppAlarmEffects(
    private val ringer: AlarmRinger,
    private val notifications: AlarmNotifications,
    private val nextAlarm: AppNotifications,
    private val wakeUp: WakeUpAutomation,
    private val preview: SoundPreview,
) : AlarmEffects {
    override fun perform(instance: AlarmInstance, action: AlarmAction) {
        when (action) {
            AlarmAction.JoinAlarmService -> {
                // A snoozed alarm that rings again is no longer snoozed.
                notifications.cancelSnoozed(instance.id)
                ringer.join(instance)
                preview.stop()
            }
            AlarmAction.LeaveAlarmService -> leaveRinging(instance.id)
            AlarmAction.LeaveAllServices -> {
                leaveRinging(instance.id)
                wakeUp.leave(instance.id)
            }
            AlarmAction.StartAnnouncement -> wakeUp.startAnnouncement(instance)
            AlarmAction.StartNews -> wakeUp.startNews(instance)
            AlarmAction.PostSnoozeNotification -> notifications.showSnoozed(instance)
            AlarmAction.PostLateNotification -> notifications.showLate(instance)
            AlarmAction.PostMissedNotification -> notifications.showMissed(instance)
            AlarmAction.PostUnansweredNotification -> notifications.showUnanswered(instance)
            // The late, missed and unanswered ones stay: they report what happened, also after it ended.
            AlarmAction.RemoveNotifications -> removeAlarmNotifications(instance.id)
            // The engine handles these itself.
            AlarmAction.RegisterAlarm, AlarmAction.CancelAlarm, is AlarmAction.ReplaceSnapshot,
            is AlarmAction.SetAutoSnoozed, AlarmAction.IncrementSnoozeCount, is AlarmAction.RegisterSnoozeAlarm,
            -> Unit
        }
    }

    override fun showNextAlarm(alarm: AlarmInstance?) = nextAlarm.showNextAlarm(alarm)

    override fun ringing(): List<AlarmInstance> = ringer.ringing()

    private fun leaveRinging(instanceId: Long) {
        ringer.leave(instanceId)
        removeAlarmNotifications(instanceId)
    }

    private fun removeAlarmNotifications(instanceId: Long) {
        notifications.cancelRinging(instanceId)
        notifications.cancelSnoozed(instanceId)
    }
}
