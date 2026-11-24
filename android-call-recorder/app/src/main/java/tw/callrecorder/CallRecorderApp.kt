package tw.callrecorder

import android.app.Application

/** 攔截未處理的例外寫進診斷記錄，找出讓無障礙服務停止運作的當機原因。 */
class CallRecorderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                EventLog.add(this, "程式當機（${t.name}）：${e.stackTrace.take(3).joinToString(" ← ")}", e)
            } catch (_: Throwable) { }
            previous?.uncaughtException(t, e)
        }
    }
}
