package tw.callrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 只負責顯示「錄音中」常駐通知（並提高程序優先權）。實際錄音在 RecorderController。
 * 從背景啟動可能被系統拒絕（Android 14+），此時改發一般通知，錄音照常進行。
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "錄音中"
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, build(this, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            EventLog.add(this, "前景服務被系統拒絕（不影響錄音）", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1001
        private const val EXTRA_TEXT = "text"

        fun showNotification(ctx: Context, text: String) {
            try {
                ctx.startForegroundService(Intent(ctx, RecordingService::class.java).putExtra(EXTRA_TEXT, text))
            } catch (e: Exception) {
                EventLog.add(ctx, "無法啟動前景服務，改用一般通知（不影響錄音）", e)
                try {
                    ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(ctx, text))
                } catch (_: Exception) { }
            }
        }

        fun hideNotification(ctx: Context) {
            ctx.stopService(Intent(ctx, RecordingService::class.java))
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        }

        private fun build(ctx: Context, text: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "錄音中", NotificationManager.IMPORTANCE_LOW))
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(ctx.getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .build()
        }
    }
}
