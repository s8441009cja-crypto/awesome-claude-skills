package tw.callrecorder

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 錄音控制中心。錄音直接在本 App 的程序內執行（由無障礙服務維持常駐），
 * 不依賴「麥克風前景服務」— Android 14+ 禁止 App 從背景啟動麥克風前景服務，
 * 那正是先前版本通話時不會自動錄音的原因。前景服務只用來顯示「錄音中」通知，失敗也不影響錄音。
 */
object RecorderController {

    private val handler = Handler(Looper.getMainLooper())
    private var recorder: CallAudioRecorder? = null
    private var pending: Runnable? = null
    private var savedAudioMode: Int? = null
    private var current: CallSource? = null
    private var currentFile: File? = null

    fun isWriting(f: File) = f == currentFile

    val isRecording get() = recorder != null

    fun start(ctx: Context, source: CallSource, peer: String?, delayOverrideMs: Long? = null) {
        val app = ctx.applicationContext
        if (!Settings.isEnabled(app, source)) {
            EventLog.add(app, "${source.label} 通話：此來源已關閉自動錄音，略過"); return
        }
        if (recorder != null || pending != null) {
            EventLog.add(app, "${source.label} 通話：已在錄音中（${current?.label}），略過"); return
        }
        current = source
        val group = source.group
        val delay = delayOverrideMs ?: if (group == Group.PHONE) Settings.phoneDelayMs(app) else 0L
        EventLog.add(app, "偵測到 ${source.label} 通話${peer?.let { "（$it）" } ?: ""}，${delay / 1000.0} 秒後開始錄音")
        RecordingService.showNotification(app, "正在錄製 ${source.label} 通話")

        val task = Runnable {
            pending = null
            if (group == Group.PHONE && Settings.forceCommMode(app)) enterCommMode(app)
            val file = File(Recordings.tempDir(app), fileName(source, peer))
            val r = CallAudioRecorder()
            val src = Settings.audioSource(app, group)
            if (r.start(file, src, Settings.gainDb(app, group))) {
                recorder = r
                currentFile = file
                EventLog.add(app, "錄音開始：音源=${CallAudioRecorder.OPTIONS[r.usedSource]}，增益=+${Settings.gainDb(app, group)}dB，檔案=${file.name}")
            } else {
                EventLog.add(app, "錄音失敗：所有音源都無法開啟（麥克風權限？無障礙服務是否開啟？）")
                stop(app)
            }
        }
        pending = task
        handler.postDelayed(task, delay)
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        pending?.let { handler.removeCallbacks(it); EventLog.add(app, "通話在開始錄音前就結束，取消") }
        pending = null
        val file = currentFile
        recorder?.let { r ->
            currentFile = null
            Thread {
                r.stop()
                val size = (file?.length() ?: 0) / 1024
                val uri = file?.let { Recordings.publish(app, it) }
                EventLog.add(app, "錄音結束：${file?.name ?: "（無檔案）"}，$size KB" + if (uri != null) "，已存到 ${Recordings.relativePath}" else "")
            }.start()
        }
        recorder = null
        current = null
        restoreAudioMode(app)
        RecordingService.hideNotification(app)
    }

    private fun enterCommMode(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        savedAudioMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun restoreAudioMode(ctx: Context) {
        savedAudioMode?.let { ctx.getSystemService(AudioManager::class.java).mode = it }
        savedAudioMode = null
    }

    private fun fileName(source: CallSource, peer: String?): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(Date())
        val safePeer = peer?.replace(Regex("[^\\p{L}\\p{N}+_-]"), "_")?.take(40)
        return listOfNotNull(ts, source.name, safePeer).joinToString("_") + ".m4a"
    }
}
