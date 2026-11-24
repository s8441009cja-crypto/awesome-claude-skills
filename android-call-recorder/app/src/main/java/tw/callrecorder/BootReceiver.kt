package tw.callrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 開機後檢查無障礙服務是否有正常運作（MIUI 需開啟「自啟動」才收得到）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            EventLog.add(ctx, "手機開機")
            AccessibilityHealer.checkSoon(ctx, 15_000)
        }
    }
}
