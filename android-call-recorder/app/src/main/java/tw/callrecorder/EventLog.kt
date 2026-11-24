package tw.callrecorder

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 診斷記錄：寫入 App 內部檔案，主畫面可直接查看，方便找出「為什麼沒錄」。 */
object EventLog {
    private const val MAX_LINES = 300
    private fun file(ctx: Context) = File(ctx.filesDir, "events.log")

    @Synchronized
    fun add(ctx: Context, msg: String, t: Throwable? = null) {
        Log.i("CallRecorder", msg, t)
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.TAIWAN).format(Date())
        val line = "$ts  $msg" + (t?.let { "  ⟶ ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        val f = file(ctx.applicationContext)
        val lines = (if (f.exists()) f.readLines() else emptyList()) + line
        f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
    }

    fun read(ctx: Context): String = file(ctx).takeIf { it.exists() }?.readText().orEmpty()
    fun clear(ctx: Context) { file(ctx).delete() }
}
