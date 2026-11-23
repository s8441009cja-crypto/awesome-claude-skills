package tw.callrecorder

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * 1. 啟用後，本 App 被系統視為無障礙服務，Android 10+ 允許在其他 App（電話、LINE…）
 *    占用麥克風時仍能「同時」收音，這是 VoIP 錄音能錄到聲音的關鍵。
 * 2. 常駐並監聽一般電話狀態（OFFHOOK 開始錄、IDLE 停止）。
 */
class CallAccessibilityService : AccessibilityService() {

    private var callback: TelephonyCallback? = null

    override fun onServiceConnected() {
        val tm = getSystemService(TelephonyManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleState(state)
            }
            tm.registerTelephonyCallback(ContextCompat.getMainExecutor(this), cb)
            callback = cb
        } else {
            @Suppress("DEPRECATION")
            tm.listen(legacyListener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    @Suppress("DEPRECATION")
    private val legacyListener = object : android.telephony.PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleState(state)
    }

    private fun handleState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> RecordingService.start(this, CallSource.PHONE, null)
            TelephonyManager.CALL_STATE_IDLE -> RecordingService.stop(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        val tm = getSystemService(TelephonyManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callback?.let { tm.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            tm.listen(legacyListener, android.telephony.PhoneStateListener.LISTEN_NONE)
        }
        super.onDestroy()
    }
}
