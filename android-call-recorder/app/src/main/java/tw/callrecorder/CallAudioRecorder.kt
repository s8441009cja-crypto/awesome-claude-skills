package tw.callrecorder

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import java.io.File

/**
 * 依序嘗試不同音源，第一個能成功 start() 的就採用。
 *
 * - VOICE_CALL：可同時錄到雙方聲音（上行＋下行），但一般 App 在 Android 9+ 沒有
 *   CAPTURE_AUDIO_OUTPUT 權限，只有 root／系統 App／部分廠商 ROM 才會成功。
 *   且只對「電信電話」有效，LINE 等 VoIP 不走這條路。
 * - VOICE_RECOGNITION：未經降噪處理的麥克風，搭配無障礙服務可在通話中同時收音，
 *   對方聲音需要靠喇叭（建議開擴音）傳回麥克風才錄得到。
 * - MIC：最後退路。
 */
class CallAudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    var usedSource: Int = -1
        private set

    fun start(output: File, source: CallSource): Boolean {
        val candidates = buildList {
            if (source == CallSource.PHONE) add(MediaRecorder.AudioSource.VOICE_CALL)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }
        for (audioSource in candidates) {
            val r = MediaRecorder(context)
            try {
                r.setAudioSource(audioSource)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(44_100)
                r.setAudioEncodingBitRate(96_000)
                r.setOutputFile(output.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                usedSource = audioSource
                Log.i(TAG, "錄音開始，音源=$audioSource，檔案=${output.name}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "音源 $audioSource 無法使用：${e.message}")
                r.release()
                output.delete()
            }
        }
        return false
    }

    fun stop() {
        recorder?.let {
            try { it.stop() } catch (e: RuntimeException) { Log.w(TAG, "stop 失敗（錄音太短？）", e) }
            it.release()
        }
        recorder = null
    }

    companion object { private const val TAG = "CallAudioRecorder" }
}
