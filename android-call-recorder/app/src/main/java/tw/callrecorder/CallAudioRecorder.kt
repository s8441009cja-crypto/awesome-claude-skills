package tw.callrecorder

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import java.io.File

/**
 * 依序嘗試不同音源，第一個能成功 start() 的就採用；使用者也可在設定中為每個來源指定音源。
 *
 * - VOICE_CALL：直接錄通話音訊（雙方）。標準 Android 需系統權限，但不少廠商 ROM
 *   對啟用無障礙服務的 App 放行。
 * - VOICE_RECOGNITION / UNPROCESSED：不經回音消除的麥克風。許多機型在這個路徑下，
 *   聽筒的聲音仍收得到，所以不開擴音也能錄到對方。
 * - VOICE_COMMUNICATION / MIC：部分機型的替代選擇。
 *
 * 注意：某些手機上 VOICE_CALL 會「成功啟動但錄到靜音」，這時請在設定中手動換音源。
 */
class CallAudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    var usedSource: Int = -1
        private set

    fun start(output: File, source: CallSource): Boolean {
        val chosen = Settings.audioSource(context, source)
        val candidates = if (chosen != AUTO) listOf(chosen) + AUTO_ORDER.filter { it != chosen } else AUTO_ORDER
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

    companion object {
        private const val TAG = "CallAudioRecorder"
        const val AUTO = -1

        /**
         * 自動模式的嘗試順序。VOICE_CALL 也套用在 VoIP：部分廠商 ROM（如部分三星、小米機型）
         * 在 App 啟用無障礙服務時允許這個音源，可不開擴音就錄到雙方。
         */
        val AUTO_ORDER = listOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )

        /** 給設定畫面用的選項（與 Cube ACR 的「錄音音源」設定相同概念）。 */
        val OPTIONS = linkedMapOf(
            AUTO to "自動",
            MediaRecorder.AudioSource.VOICE_CALL to "Voice call",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "Voice recognition",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "Voice communication",
            MediaRecorder.AudioSource.MIC to "Mic",
            MediaRecorder.AudioSource.UNPROCESSED to "Unprocessed",
        )
    }
}
