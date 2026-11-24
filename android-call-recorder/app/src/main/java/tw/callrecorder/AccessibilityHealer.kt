package tw.callrecorder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import androidx.core.app.NotificationCompat

/**
 * 無障礙服務「已開啟但無法運作」的自動修復。
 *
 * MIUI 等系統清除背景或 App 當掉後，無障礙服務常停在「無法運作」，需手動關閉再開啟。
 * 若已用 adb 授予 WRITE_SECURE_SETTINGS，本 App 可自己把服務「關閉再開啟」；
 * 否則發通知提醒使用者，點一下直接進入無障礙設定。
 */
object AccessibilityHealer {

    private val handler = Handler(Looper.getMainLooper())
    private const val NOTIF_ID = 2001
    private const val CHANNEL = "a11y_health"

    const val ADB_GRANT_CMD = "adb shell pm grant tw.callrecorder android.permission.WRITE_SECURE_SETTINGS"

    fun canSelfHeal(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun component(ctx: Context) = ComponentName(ctx, CallAccessibilityService::class.java).flattenToString()

    private fun enabledList(ctx: Context): List<String> =
        SysSettings.Secure.getString(ctx.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.filter { it.isNotBlank() }.orEmpty()

    /** 延遲檢查：程序剛重啟時無障礙服務可能稍晚才連上，避免誤判。 */
    fun checkSoon(ctx: Context, delayMs: Long = 5_000) {
        val app = ctx.applicationContext
        handler.postDelayed({ check(app) }, delayMs)
    }

    fun check(ctx: Context) {
        if (CallAccessibilityService.instance != null) {
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
            return
        }
        if (canSelfHeal(ctx)) heal(ctx) else notifyUser(ctx)
    }

    private fun heal(ctx: Context) {
        val me = component(ctx)
        val others = enabledList(ctx).filterNot { it.equals(me, ignoreCase = true) }
        try {
            // 先移除再加回，系統會重新綁定服務
            SysSettings.Secure.putString(ctx.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            handler.postDelayed({
                try {
                    SysSettings.Secure.putString(ctx.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + me).joinToString(":"))
                    SysSettings.Secure.putInt(ctx.contentResolver, SysSettings.Secure.ACCESSIBILITY_ENABLED, 1)
                    EventLog.add(ctx, "無障礙服務未運作 → 已自動重新開啟")
                } catch (e: Exception) {
                    EventLog.add(ctx, "自動重新開啟無障礙服務失敗", e); notifyUser(ctx)
                }
            }, 800)
        } catch (e: Exception) {
            EventLog.add(ctx, "自動重新開啟無障礙服務失敗", e); notifyUser(ctx)
        }
    }

    private fun notifyUser(ctx: Context) {
        EventLog.add(ctx, "無障礙服務未運作，已發通知提醒重新開啟")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "服務狀態", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(
            ctx, 0,
            Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            nm.notify(NOTIF_ID, NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("通話錄音暫停運作")
                .setContentText("無障礙服務被系統停止，點此重新開啟")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } catch (_: SecurityException) { }
    }
}
