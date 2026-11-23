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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
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
        text("提示：不開擴音時，若對方聲音太小，請調高「清晰度提高」或換音源。")

        header("2. 自動錄音來源")
        CallSource.entries.forEach { s ->
            root.addView(Switch(this).apply {
                text = s.label
                isChecked = Settings.isEnabled(this@MainActivity, s)
                setOnCheckedChangeListener { _, on -> Settings.setEnabled(this@MainActivity, s, on) }
            })
        }

        header("3. 錄製設定")
        Group.entries.forEach { g ->
            subheader("${g.label}錄音")
            val keys = CallAudioRecorder.OPTIONS.keys.toList()
            text("${g.label}錄音音源")
            root.addView(Spinner(this).apply {
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, CallAudioRecorder.OPTIONS.values.toList())
                setSelection(keys.indexOf(Settings.audioSource(context, g)).coerceAtLeast(0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) =
                        Settings.setAudioSource(context, g, keys[pos])
                    override fun onNothingSelected(p: AdapterView<*>?) = Unit
                }
            })
            slider("${g.label}清晰度提高（軟體增益）", Settings.MAX_GAIN_DB, Settings.gainDb(this, g), { "+$it dB" }) {
                Settings.setGainDb(this, g, it)
            }
            if (g == Group.PHONE) {
                root.addView(Switch(this).apply {
                    text = "強制通信模式（若對方聲音變小請關閉）"
                    isChecked = Settings.forceCommMode(context)
                    setOnCheckedChangeListener { _, on -> Settings.setForceCommMode(context, on) }
                })
                slider("電話自動錄製延遲", Settings.MAX_DELAY_S, (Settings.phoneDelayMs(this) / 1000).toInt(), { "$it 秒" }) {
                    Settings.setPhoneDelayMs(this, it * 1000L)
                }
            }
        }

        header("4. 錄音檔")
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
    private fun subheader(t: String) = root.addView(TextView(this).apply { text = t; textSize = 17f; setPadding(0, 24, 0, 4) })
    private fun slider(label: String, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
        val tv = TextView(this).apply { text = "$label：${fmt(value)}" }
        root.addView(tv)
        root.addView(SeekBar(this).apply {
            this.max = max
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) { tv.text = "$label：${fmt(v)}"; onChange(v) }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        })
    }
    private fun text(t: String) = root.addView(TextView(this).apply { text = t })
    private fun step(label: String, done: Boolean, onClick: () -> Unit) =
        root.addView(Button(this).apply {
            text = (if (done) "✅ " else "⚠️ ") + label
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        })

    override fun onDestroy() { player?.release(); super.onDestroy() }
}
