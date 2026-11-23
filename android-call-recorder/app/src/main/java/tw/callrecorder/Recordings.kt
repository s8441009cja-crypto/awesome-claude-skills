package tw.callrecorder

import android.content.Context
import java.io.File

/** 錄音檔存放於 App 專屬外部目錄：Android/data/tw.callrecorder/files/Recordings */
object Recordings {
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "Recordings").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.extension == "m4a" }?.sortedByDescending { it.lastModified() }.orEmpty()
}

/** 各來源是否自動錄音的開關。 */
object Settings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun isEnabled(ctx: Context, s: CallSource) = prefs(ctx).getBoolean(s.name, true)
    fun setEnabled(ctx: Context, s: CallSource, on: Boolean) = prefs(ctx).edit().putBoolean(s.name, on).apply()
}
