package tw.callrecorder

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

/** 簡易主畫面：權限設定精靈＋來源開關＋錄音清單（播放／分享／刪除）。 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        setContentView(ScrollView(this).apply { addView(root) })
        requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun render() {
        root.removeAllViews()
        header("1. 必要設定")
        step("無障礙服務（通話中同時收音／偵測電話）", isAccessibilityOn()) {
            startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        step("通知存取（偵測 LINE / Messenger / Telegram 通話）", isNotificationListenerOn()) {
            startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        step("略過電池最佳化（避免背景被殺）", isIgnoringBattery()) {
            startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        text("提示：LINE 等網路通話建議開啟擴音，才能清楚錄到對方聲音。")

        header("2. 自動錄音來源")
        CallSource.entries.forEach { s ->
            root.addView(Switch(this).apply {
                text = s.label
                isChecked = Settings.isEnabled(this@MainActivity, s)
                setOnCheckedChangeListener { _, on -> Settings.setEnabled(this@MainActivity, s, on) }
            })
        }

        header("3. 錄音檔")
        val files = Recordings.list(this)
        if (files.isEmpty()) text("尚無錄音")
        files.forEach { f ->
            text("${f.nameWithoutExtension}\n${f.length() / 1024} KB")
            root.addView(LinearLayout(this).apply {
                addView(Button(context).apply { text = "播放"; setOnClickListener { play(f) } })
                addView(Button(context).apply { text = "分享"; setOnClickListener { share(f) } })
                addView(Button(context).apply { text = "刪除"; setOnClickListener { f.delete(); render() } })
            })
        }
    }

    private fun play(f: java.io.File) {
        player?.release()
        player = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start() }
    }

    private fun share(f: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享錄音"))
    }

    private fun isAccessibilityOn(): Boolean {
        val enabled = SysSettings.Secure.getString(contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(ComponentName(this, CallAccessibilityService::class.java).flattenToString())
    }

    private fun isNotificationListenerOn() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun isIgnoringBattery() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun header(t: String) = root.addView(TextView(this).apply { text = t; textSize = 20f; setPadding(0, 32, 0, 8) })
    private fun text(t: String) = root.addView(TextView(this).apply { text = t })
    private fun step(label: String, done: Boolean, onClick: () -> Unit) =
        root.addView(Button(this).apply {
            text = (if (done) "✅ " else "⚠️ ") + label
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        })

    override fun onDestroy() { player?.release(); super.onDestroy() }
}
