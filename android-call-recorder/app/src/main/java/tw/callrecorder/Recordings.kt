package tw.callrecorder

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/** 錄音檔存放於 App 專屬外部目錄：Android/data/tw.callrecorder/files/Recordings */
object Recordings {
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "Recordings").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.extension == "m4a" }?.sortedByDescending { it.lastModified() }.orEmpty()
}

/** 錄音設定分「電話」與「VoIP」兩組，與 Cube ACR 的錄製設定對應。 */
enum class Group(val label: String) { PHONE("電話"), VOIP("VoIP") }

val CallSource.group get() = if (this == CallSource.PHONE) Group.PHONE else Group.VOIP

object Settings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // 各來源是否自動錄音
    fun isEnabled(ctx: Context, s: CallSource) = prefs(ctx).getBoolean(s.name, true)
    fun setEnabled(ctx: Context, s: CallSource, on: Boolean) = prefs(ctx).edit().putBoolean(s.name, on).apply()

    // 錄音音源（預設 Voice recognition）
    fun audioSource(ctx: Context, g: Group) =
        prefs(ctx).getInt("src_${g.name}", MediaRecorder.AudioSource.VOICE_RECOGNITION)
    fun setAudioSource(ctx: Context, g: Group, v: Int) = prefs(ctx).edit().putInt("src_${g.name}", v).apply()

    // 清晰度提高（軟體增益 0–24 dB，預設 12 dB ≈ 4 倍）
    const val MAX_GAIN_DB = 24
    fun gainDb(ctx: Context, g: Group) = prefs(ctx).getInt("gain_${g.name}", 12)
    fun setGainDb(ctx: Context, g: Group, v: Int) = prefs(ctx).edit().putInt("gain_${g.name}", v).apply()

    // 強制通信模式（僅電話）
    fun forceCommMode(ctx: Context) = prefs(ctx).getBoolean("force_comm", true)
    fun setForceCommMode(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("force_comm", on).apply()

    // 電話自動錄製延遲（毫秒，預設 4 秒）
    const val MAX_DELAY_S = 10
    fun phoneDelayMs(ctx: Context) = prefs(ctx).getLong("phone_delay", 4_000L)
    fun setPhoneDelayMs(ctx: Context, v: Long) = prefs(ctx).edit().putLong("phone_delay", v).apply()
}
