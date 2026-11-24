package tw.callrecorder

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * 「通知存取」已開啟但服務沒連上的自動修復（MIUI 在 App 更新後常發生，導致 LINE 等偵測不到）。
 * 1. 先用官方 API requestRebind() 要求系統重新綁定。
 * 2. 若仍未連上且已用 adb 授予 WRITE_SECURE_SETTINGS，就把通知存取「關閉再開啟」。
 */
object NotificationListenerHealer {

    private val handler = Handler(Looper.getMainLooper())

    private fun component(ctx: Context) = ComponentName(ctx, VoipCallListenerService::class.java)

    private fun isGranted(ctx: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** 延遲檢查：服務剛啟動時可能稍晚才連上，避免誤判。 */
    fun checkSoon(ctx: Context, delayMs: Long = 5_000) {
        val app = ctx.applicationContext
        handler.postDelayed({ check(app) }, delayMs)
    }

    fun check(ctx: Context) {
        if (VoipCallListenerService.connected || !isGranted(ctx)) return
        EventLog.add(ctx, "通知存取已開啟但未連線 → 自動重新連線")
        rebind(ctx)
        handler.postDelayed({
            if (!VoipCallListenerService.connected && AccessibilityHealer.canSelfHeal(ctx)) toggle(ctx)
        }, 5_000)
    }

    fun rebind(ctx: Context) {
        try {
            NotificationListenerService.requestRebind(component(ctx))
        } catch (e: Exception) {
            EventLog.add(ctx, "requestRebind 失敗", e)
        }
    }

    private fun toggle(ctx: Context) {
        val key = "enabled_notification_listeners"
        val me = component(ctx).flattenToString()
        val list = SysSettings.Secure.getString(ctx.contentResolver, key)
            ?.split(':')?.filter { it.isNotBlank() && !it.equals(me, ignoreCase = true) }.orEmpty()
        try {
            SysSettings.Secure.putString(ctx.contentResolver, key, list.joinToString(":"))
            handler.postDelayed({
                try {
                    SysSettings.Secure.putString(ctx.contentResolver, key, (list + me).joinToString(":"))
                    EventLog.add(ctx, "已自動把通知存取關閉再開啟")
                } catch (e: Exception) { EventLog.add(ctx, "重新開啟通知存取失敗", e) }
            }, 800)
        } catch (e: Exception) {
            EventLog.add(ctx, "重新開啟通知存取失敗", e)
        }
    }
}
