package tw.callrecorder

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 透過「通話中」通知偵測 LINE / Messenger / Telegram / WhatsApp 的語音通話。
 *
 * 判斷規則：
 * 1. Android 12+ 若 App 使用 CallStyle，extras 帶 android.callType：1 來電、2 進行中、3 篩選。
 * 2. 否則：category == CALL 且常駐（ongoing），並且（顯示計時器 或 沒有全螢幕來電畫面）。
 * 通知被移除 = 通話結束。每則通話通知都會寫入診斷記錄，方便調整規則。
 */
class VoipCallListenerService : NotificationListenerService() {

    private val activeKeys = mutableSetOf<String>()

    override fun onListenerConnected() {
        EventLog.add(this, "通知存取已連線（可偵測 LINE / Messenger / Telegram）")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val source = CallSource.fromPackage(sbn.packageName) ?: return
        val n = sbn.notification
        val looksLikeCall = n.category == Notification.CATEGORY_CALL || n.extras.containsKey(EXTRA_CALL_TYPE)
        if (!looksLikeCall) return

        val ongoing = isOngoingCall(n)
        if (sbn.key !in activeKeys) {
            EventLog.add(this, "${source.label} 通話通知：${describe(n)} → ${if (ongoing) "通話中" else "非通話中（略過）"}")
        }
        if (ongoing && activeKeys.add(sbn.key)) {
            val peer = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            RecorderController.start(this, source, peer)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (activeKeys.remove(sbn.key)) {
            EventLog.add(this, "${CallSource.fromPackage(sbn.packageName)?.label} 通話通知已移除 → 通話結束")
            if (activeKeys.isEmpty()) RecorderController.stop(this)
        }
    }

    private fun isOngoingCall(n: Notification): Boolean {
        val extras = n.extras
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && extras.containsKey(EXTRA_CALL_TYPE)) {
            return extras.getInt(EXTRA_CALL_TYPE) == CALL_TYPE_ONGOING
        }
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val hasTimer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        return n.category == Notification.CATEGORY_CALL && ongoing && (hasTimer || n.fullScreenIntent == null)
    }

    private fun describe(n: Notification): String {
        val e = n.extras
        return "callType=${if (e.containsKey(EXTRA_CALL_TYPE)) e.getInt(EXTRA_CALL_TYPE) else "無"}, " +
            "ongoing=${n.flags and Notification.FLAG_ONGOING_EVENT != 0}, " +
            "計時器=${e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)}, " +
            "全螢幕=${n.fullScreenIntent != null}, 標題=${e.getCharSequence(Notification.EXTRA_TITLE)}"
    }

    companion object {
        private const val EXTRA_CALL_TYPE = "android.callType"
        private const val CALL_TYPE_ONGOING = 2
    }
}
