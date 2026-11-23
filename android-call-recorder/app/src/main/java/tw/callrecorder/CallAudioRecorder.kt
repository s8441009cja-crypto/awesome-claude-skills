package tw.callrecorder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 「軟體」錄音器（對應 Cube ACR 的 "Voice recognition (software)"）：
 * AudioRecord 取得 PCM → 軟體增益放大 → MediaCodec 編成 AAC → MediaMuxer 存成 .m4a。
 *
 * 不開擴音時，對方聲音是從聽筒漏進麥克風，音量很小；
 * VOICE_RECOGNITION 音源不做回音消除，所以收得到，再靠軟體增益放大到聽得清楚。
 */
class CallAudioRecorder {

    @Volatile private var running = false
    private var worker: Thread? = null
    var usedSource: Int = -1
        private set

    /** @param preferredSource 使用者選的音源；失敗時依 AUTO_ORDER 往下試。 */
    fun start(output: File, preferredSource: Int, gainDb: Int): Boolean {
        val candidates = (listOf(preferredSource) + AUTO_ORDER).filter { it != AUTO }.distinct()
        for (src in candidates) {
            val record = open(src) ?: continue
            usedSource = src
            running = true
            worker = Thread({ encodeLoop(record, output, gainDb) }, "call-recorder").apply { start() }
            Log.i(TAG, "錄音開始：音源=${OPTIONS[src]}，增益=${gainDb}dB，檔案=${output.name}")
            return true
        }
        return false
    }

    fun stop() {
        running = false
        worker?.join(3_000)
        worker = null
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO 已於 MainActivity 申請
    private fun open(source: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        } catch (e: Exception) {
            Log.w(TAG, "音源 ${OPTIONS[source]} 無法建立：${e.message}")
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release(); return null
        }
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            record.release(); return null
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release(); return null
        }
        return record
    }

    private fun encodeLoop(record: AudioRecord, output: File, gainDb: Int) {
        val gain = 10.0.pow(gainDb / 20.0).toFloat()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        var samplesWritten = 0L
        val pcm = ShortArray(2048)
        var eosQueued = false

        try {
            while (true) {
                // 1) 餵 PCM 給編碼器
                if (!eosQueued) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!.order(ByteOrder.nativeOrder())
                        buf.clear()
                        val pts = samplesWritten * 1_000_000L / SAMPLE_RATE
                        if (!running) {
                            codec.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        } else {
                            val want = minOf(pcm.size, buf.capacity() / 2)
                            val n = record.read(pcm, 0, want)
                            if (n > 0) {
                                for (i in 0 until n) {
                                    val v = (pcm[i] * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                    buf.putShort(v.toShort())
                                }
                                codec.queueInputBuffer(inIdx, 0, n * 2, pts, 0)
                                samplesWritten += n
                            } else {
                                codec.queueInputBuffer(inIdx, 0, 0, pts, 0)
                            }
                        }
                    }
                }
                // 2) 取出 AAC 寫入檔案
                var outIdx = codec.dequeueOutputBuffer(info, 0)
                while (outIdx != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (outIdx >= 0) {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (muxerStarted && !isConfig && info.size > 0) {
                            muxer.writeSampleData(track, codec.getOutputBuffer(outIdx)!!, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "錄音中斷", e)
        } finally {
            record.stop(); record.release()
            codec.stop(); codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
            if (samplesWritten == 0L) output.delete()
        }
    }

    companion object {
        private const val TAG = "CallAudioRecorder"
        private const val SAMPLE_RATE = 44_100
        const val AUTO = -1

        val AUTO_ORDER = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )

        /** 設定畫面的音源選項（與 Cube ACR 的「錄音音源」同概念）。 */
        val OPTIONS = linkedMapOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "Voice recognition (software)",
            MediaRecorder.AudioSource.VOICE_CALL to "Voice call",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "Voice communication",
            MediaRecorder.AudioSource.MIC to "Mic",
            MediaRecorder.AudioSource.UNPROCESSED to "Unprocessed",
            AUTO to "自動",
        )
    }
}
