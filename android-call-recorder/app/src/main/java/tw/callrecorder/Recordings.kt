package tw.callrecorder

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 一筆錄音（存在 MediaStore 公開資料夾）。 */
data class Recording(
    val uri: Uri,
    val name: String,
    val size: Long,
    val durationMs: Long,
    val dateTaken: Long,
) {
    /** 檔名格式：yyyyMMdd_HHmmss_來源_對象.m4a */
    private val parts get() = name.removeSuffix(".m4a").split("_", limit = 4)
    val source: CallSource? get() = parts.getOrNull(2)?.let { p -> CallSource.entries.firstOrNull { it.name == p } }
    val peer: String? get() = parts.getOrNull(3)
}

/**
 * 錄音檔管理。錄音中先寫到 App 內部暫存區，結束後才移到公開資料夾：
 * Android 12+：Recordings/通話錄音/　Android 10–11：Music/通話錄音/
 * 這樣檔案管理員、音樂 App 都看得到，且清單不會出現錄到一半的檔案。
 */
object Recordings {
    const val FOLDER = "通話錄音"

    val relativePath: String
        get() = (if (Build.VERSION.SDK_INT >= 31) Environment.DIRECTORY_RECORDINGS else Environment.DIRECTORY_MUSIC) + "/$FOLDER/"

    private val collection: Uri get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** 錄音中的暫存目錄（App 內部）。 */
    fun tempDir(ctx: Context): File = File(ctx.filesDir, "recording").apply { mkdirs() }

    /** 舊版存放位置（v0.3.x 以前）。 */
    private fun legacyDir(ctx: Context): File? = ctx.getExternalFilesDir(null)?.let { File(it, "Recordings") }

    /** 把錄好的檔案移到公開資料夾，成功後刪除暫存檔。 */
    fun publish(ctx: Context, file: File): Uri? {
        if (!file.exists() || file.length() == 0L) { file.delete(); return null }
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.DATE_TAKEN, file.lastModified())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(collection, values) ?: return null
        return try {
            cr.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            file.delete()
            uri
        } catch (e: Exception) {
            EventLog.add(ctx, "無法存到公開資料夾，檔案保留在暫存區：${file.name}", e)
            cr.delete(uri, null, null)
            null
        }
    }

    /** 啟動時：搬移舊版檔案，以及上次未完成（例如當機）的暫存檔。 */
    fun migrate(ctx: Context) {
        val leftovers = (legacyDir(ctx)?.listFiles()?.toList().orEmpty() + tempDir(ctx).listFiles()?.toList().orEmpty())
            .filter { it.extension == "m4a" && !RecorderController.isWriting(it) }
        if (leftovers.isEmpty()) return
        Thread {
            val moved = leftovers.count { publish(ctx, it) != null }
            EventLog.add(ctx, "已把 $moved 個錄音檔搬到 $relativePath")
        }.start()
    }

    fun list(ctx: Context): List<Recording> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
        )
        val out = mutableListOf<Recording>()
        ctx.contentResolver.query(
            collection, projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(relativePath),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val taken = c.getLong(4).takeIf { it > 0 } ?: (c.getLong(5) * 1000)
                out += Recording(ContentUris.withAppendedId(collection, c.getLong(0)), c.getString(1), c.getLong(2), c.getLong(3), taken)
            }
        }
        return out.sortedByDescending { it.name } // 檔名以時間開頭，排序最準
    }

    fun count(ctx: Context) = try { list(ctx).size } catch (_: Exception) { 0 }
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
