package tw.callrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 前景服務：負責實際錄音與檔案命名。 */
class RecordingService : Service() {

    private var recorder: CallAudioRecorder? = null
    private var pendingStart: Runnable? = null
    private var savedAudioMode: Int? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val source = CallSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: CallSource.PHONE.name)
                val peer = intent.getStringExtra(EXTRA_PEER)
                startRecording(source, peer)
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(source: CallSource, peer: String?) {
        if (recorder != null || pendingStart != null) return // 已在錄音（例如電話＋通知同時觸發）
        // startForegroundService 後須在 5 秒內呼叫 startForeground，所以先進前景再延遲錄音
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("正在錄製 ${source.label} 通話"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        val group = source.group
        val task = Runnable {
            pendingStart = null
            if (group == Group.PHONE && Settings.forceCommMode(this)) enterCommMode()
            val file = File(Recordings.dir(this), fileName(source, peer))
            val r = CallAudioRecorder()
            if (r.start(file, Settings.audioSource(this, group), Settings.gainDb(this, group))) {
                recorder = r
            } else {
                stopRecording()
            }
        }
        // 電話接通瞬間音訊路由還在切換，延遲幾秒再錄可避免對方聲音變小（同 Cube ACR）
        val delay = if (group == Group.PHONE) Settings.phoneDelayMs(this) else 0L
        pendingStart = task
        handler.postDelayed(task, delay)
    }

    private fun stopRecording() {
        pendingStart?.let { handler.removeCallbacks(it) }
        pendingStart = null
        recorder?.stop()
        recorder = null
        restoreAudioMode()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 強制通信模式：錄音期間把音訊模式設為 MODE_IN_COMMUNICATION，改善 Android 10+ 的電話錄音。 */
    private fun enterCommMode() {
        val am = getSystemService(AudioManager::class.java)
        savedAudioMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun restoreAudioMode() {
        savedAudioMode?.let { getSystemService(AudioManager::class.java).mode = it }
        savedAudioMode = null
    }

    override fun onDestroy() {
        pendingStart?.let { handler.removeCallbacks(it) }
        recorder?.stop()
        restoreAudioMode()
        super.onDestroy()
    }

    private fun fileName(source: CallSource, peer: String?): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(Date())
        val safePeer = peer?.replace(Regex("[^\\p{L}\\p{N}+_-]"), "_")?.take(40)
        return listOfNotNull(ts, source.name, safePeer).joinToString("_") + ".m4a"
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "錄音中", NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1001
        const val ACTION_START = "tw.callrecorder.START"
        const val ACTION_STOP = "tw.callrecorder.STOP"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_PEER = "peer"

        fun start(ctx: Context, source: CallSource, peer: String?) {
            if (!Settings.isEnabled(ctx, source)) return
            ctx.startForegroundService(
                Intent(ctx, RecordingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SOURCE, source.name)
                    .putExtra(EXTRA_PEER, peer),
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
