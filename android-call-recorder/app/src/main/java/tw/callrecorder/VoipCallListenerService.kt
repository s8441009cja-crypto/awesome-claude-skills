package tw.callrecorder

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 透過「通話中」通知偵測 LINE / Messenger / Telegram / WhatsApp 的語音通話。
 *
 * 判斷規則：
 * 1. Android 12+ 若 App 使用 CallStyle，extras 會帶 android.callType：2 = 進行中。
 * 2. 否則退而求其次：category == CALL、常駐（ongoing）、且顯示計時器（代表已接通）。
 * 通知被移除 = 通話結束。
 */
class VoipCallListenerService : NotificationListenerService() {

    private val activeKeys = mutableSetOf<String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val source = CallSource.fromPackage(sbn.packageName) ?: return
        if (!isOngoingCall(sbn.notification)) return
        if (activeKeys.add(sbn.key)) {
            val peer = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            RecordingService.start(this, source, peer)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (activeKeys.remove(sbn.key) && activeKeys.isEmpty()) {
            RecordingService.stop(this)
        }
    }

    private fun isOngoingCall(n: Notification): Boolean {
        val extras = n.extras
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && extras.containsKey(EXTRA_CALL_TYPE)) {
            return extras.getInt(EXTRA_CALL_TYPE) == CALL_TYPE_ONGOING
        }
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val isCall = n.category == Notification.CATEGORY_CALL
        val hasTimer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        return isCall && ongoing && hasTimer
    }

    companion object {
        private const val EXTRA_CALL_TYPE = "android.callType"
        private const val CALL_TYPE_ONGOING = 2
    }
}
