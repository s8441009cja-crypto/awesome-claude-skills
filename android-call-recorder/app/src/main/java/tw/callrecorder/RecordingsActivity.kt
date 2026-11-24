package tw.callrecorder

import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 錄音資料夾：依日期分組、可依來源篩選；點一下播放／暫停，長按分享或刪除。 */
class RecordingsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private var filter: CallSource? = null
    private var player: MediaPlayer? = null
    private var playing: Uri? = null
    private var pendingDelete: Recording? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "錄音資料夾"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }

        val filters = listOf<CallSource?>(null) + CallSource.entries
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, filters.map { it?.label ?: "全部來源" })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { filter = filters[pos]; render() }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        })
        summary = TextView(this).apply { setPadding(0, 8, 0, 8) }
        root.addView(summary)
        root.addView(TextView(this).apply {
            text = "📂 在檔案管理員中開啟"
            setPadding(0, 8, 0, 16)
            setTextColor(0xFF3F51B5.toInt())
            setOnClickListener { openInFileManager(this@RecordingsActivity) }
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        list.removeAllViews()
        val all = try { Recordings.list(this) } catch (e: Exception) {
            EventLog.add(this, "讀取錄音清單失敗", e); emptyList()
        }
        val shown = all.filter { filter == null || it.source == filter }
        summary.text = "共 ${shown.size} 個錄音｜點一下播放，長按分享或刪除"
        if (shown.isEmpty()) { list.addView(TextView(this).apply { text = "（沒有錄音）"; setPadding(0, 32, 0, 0) }); return }

        val dayFmt = SimpleDateFormat("yyyy/MM/dd（E）", Locale.TAIWAN)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.TAIWAN)
        shown.groupBy { dayFmt.format(Date(it.dateTaken)) }.forEach { (day, items) ->
            list.addView(TextView(this).apply {
                text = "$day　${items.size} 個"
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 28, 0, 8)
            })
            items.forEach { r -> list.addView(row(r, timeFmt)) }
        }
    }

    private fun row(r: Recording, timeFmt: SimpleDateFormat): View {
        val isPlaying = playing == r.uri
        val title = listOfNotNull(r.source?.label ?: "其他", r.peer).joinToString("・")
        val detail = "${timeFmt.format(Date(r.dateTaken))}　${DateUtils.formatElapsedTime(r.durationMs / 1000)}　${r.size / 1024} KB"
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 16, 8, 16)
            addView(TextView(context).apply { text = if (isPlaying) "⏸" else "▶"; textSize = 20f; setPadding(0, 0, 24, 0) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = title; textSize = 16f })
                addView(TextView(context).apply { text = detail; textSize = 12f; alpha = 0.7f })
            })
            setOnClickListener { togglePlay(r) }
            setOnLongClickListener { menu(r); true }
        }
    }

    private fun togglePlay(r: Recording) {
        val wasThis = playing == r.uri
        player?.release(); player = null; playing = null
        if (!wasThis) {
            try {
                player = MediaPlayer().apply {
                    setDataSource(this@RecordingsActivity, r.uri)
                    prepare(); start()
                    setOnCompletionListener { player?.release(); player = null; playing = null; render() }
                }
                playing = r.uri
            } catch (e: Exception) {
                EventLog.add(this, "無法播放 ${r.name}", e)
                Toast.makeText(this, "無法播放此檔案", Toast.LENGTH_SHORT).show()
            }
        }
        render()
    }

    private fun menu(r: Recording) {
        AlertDialog.Builder(this)
            .setTitle(r.name)
            .setItems(arrayOf("分享", "刪除")) { _, which ->
                when (which) {
                    0 -> startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, r.uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享錄音"))
                    1 -> AlertDialog.Builder(this).setMessage("確定刪除這個錄音？")
                        .setPositiveButton("刪除") { _, _ -> delete(r) }
                        .setNegativeButton("取消", null).show()
                }
            }.show()
    }

    private fun delete(r: Recording) {
        if (playing == r.uri) { player?.release(); player = null; playing = null }
        try {
            contentResolver.delete(r.uri, null, null)
            render()
        } catch (e: SecurityException) {
            // 重新安裝 App 後，舊檔案不再屬於本 App，需經使用者同意才能刪
            val sender = when {
                Build.VERSION.SDK_INT >= 30 -> MediaStore.createDeleteRequest(contentResolver, listOf(r.uri)).intentSender
                e is RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                else -> null
            }
            if (sender == null) { Toast.makeText(this, "無法刪除此檔案", Toast.LENGTH_SHORT).show(); return }
            pendingDelete = r
            @Suppress("DEPRECATION")
            startIntentSenderForResult(sender, REQ_DELETE, null, 0, 0, 0)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE) {
            // Android 11+ 的 createDeleteRequest 同意後即已刪除；Android 10 需再刪一次
            if (resultCode == Activity.RESULT_OK && Build.VERSION.SDK_INT < 30) {
                pendingDelete?.let { try { contentResolver.delete(it.uri, null, null) } catch (_: Exception) { } }
            }
            pendingDelete = null
            render()
        }
    }

    override fun onDestroy() { player?.release(); super.onDestroy() }

    companion object {
        private const val REQ_DELETE = 42

        /** 嘗試直接在系統「檔案」App 開啟錄音資料夾；不支援時提示路徑。 */
        fun openInFileManager(activity: Activity) {
            val docId = "primary:" + Recordings.relativePath.trimEnd('/')
            val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
            val attempts = listOf(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR),
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri),
            )
            for (i in attempts) {
                try { activity.startActivity(i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); return } catch (_: Exception) { }
            }
            Toast.makeText(activity, "請在檔案管理員開啟：內部儲存空間/${Recordings.relativePath}", Toast.LENGTH_LONG).show()
        }
    }
}
