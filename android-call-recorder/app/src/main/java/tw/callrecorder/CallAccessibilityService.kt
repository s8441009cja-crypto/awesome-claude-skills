package tw.callrecorder

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * 1. 啟用後，本 App 被系統視為無障礙服務：Android 10+ 允許在其他 App（電話、LINE…）
 *    占用麥克風時仍能「同時」收音，且程序會常駐，錄音直接在此程序內進行。
 * 2. 監聽一般電話狀態（OFFHOOK 開始錄、IDLE 停止）。
 */
class CallAccessibilityService : AccessibilityService() {

    private var callback: TelephonyCallback? = null
    private var lastState = TelephonyManager.CALL_STATE_IDLE

    @Suppress("DEPRECATION")
    private val legacyListener = object : android.telephony.PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleState(state)
    }

    override fun onServiceConnected() {
        instance = this
        EventLog.add(this, "無障礙服務已啟動")
        ensureListening()
        // 無障礙服務最常駐，由它負責確認通知存取（LINE 等偵測）有連上
        NotificationListenerHealer.checkSoon(this)
    }

    /** 權限可能在服務啟動後才授予，所以主畫面回到前景時會再呼叫一次。 */
    fun ensureListening() {
        if (callback != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            EventLog.add(this, "缺少「電話」權限，無法偵測一般電話")
            return
        }
        val tm = getSystemService(TelephonyManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleState(state)
                }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(this), cb)
                callback = cb
            } else {
                @Suppress("DEPRECATION")
                tm.listen(legacyListener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
                callback = object : TelephonyCallback() {}
            }
            EventLog.add(this, "開始監聽一般電話")
        } catch (e: Exception) {
            EventLog.add(this, "監聽電話狀態失敗", e)
        }
    }

    private fun handleState(state: Int) {
        if (state == lastState) return
        lastState = state
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                RecorderController.start(this, CallSource.PHONE, null)
                NotificationListenerHealer.check(this)
            }
            TelephonyManager.CALL_STATE_IDLE -> RecorderController.stop(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        EventLog.add(this, "無障礙服務已停止")
        val tm = getSystemService(TelephonyManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (callback as? TelephonyCallback.CallStateListener)?.let { tm.unregisterTelephonyCallback(callback!!) }
            } else {
                @Suppress("DEPRECATION")
                tm.listen(legacyListener, android.telephony.PhoneStateListener.LISTEN_NONE)
            }
        } catch (_: Exception) { }
        RecorderController.stop(this)
        super.onDestroy()
    }

    companion object {
        var instance: CallAccessibilityService? = null
            private set
    }
}
