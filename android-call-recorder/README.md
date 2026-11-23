# 通話錄音 App（Android）— 設計文件

支援自動錄音：**一般電話、LINE、Messenger、Telegram**（另附 WhatsApp）。

> ⚠️ 先講結論：Android 10 起，Google 從系統層封鎖一般 App 直接錄「通話音訊流」。
> 本設計採用目前沒有 root 也能用的做法：**無障礙服務＋麥克風收音**。
> 你的聲音一定錄得到；**對方的聲音要靠喇叭傳回麥克風**，所以強烈建議通話時開擴音。
> 若要「雙方都清楚、不開擴音」，只有 root 手機或手機廠商內建錄音（如三星、小米、ASUS 部分機型）能做到。

## 1. 架構

```
┌──────────────────────┐   OFFHOOK / IDLE    ┌──────────────────────┐
│ CallAccessibilityService ├──────────────────▶│                      │
│ ‧監聽電信電話狀態         │                    │   RecordingService   │
│ ‧取得「通話中同時收音」資格 │                    │   (前景服務,microphone)│──▶ Recordings/*.m4a
└──────────────────────┘                    │   CallAudioRecorder  │
┌──────────────────────┐  通話中通知出現/消失  │   音源自動退回        │
│ VoipCallListenerService  ├──────────────────▶│                      │
│ ‧讀 LINE/Messenger/TG 通知│                    └──────────────────────┘
└──────────────────────┘
          MainActivity：權限設定精靈、各來源開關、錄音清單（播放/分享/刪除）
```

| 檔案 | 職責 |
|---|---|
| `CallSource.kt` | 來源定義與套件名稱對照（LINE `jp.naver.line.android`、Messenger `com.facebook.orca`、Telegram `org.telegram.messenger` 等） |
| `CallAccessibilityService.kt` | 無障礙服務；監聽電話狀態並觸發錄音 |
| `VoipCallListenerService.kt` | 通知監聽；以「通話中」通知判斷 VoIP 通話開始/結束 |
| `RecordingService.kt` | 前景服務；建立檔名 `時間_來源_對象.m4a`，啟停錄音 |
| `CallAudioRecorder.kt` | MediaRecorder 包裝；依序嘗試 `VOICE_CALL → VOICE_RECOGNITION → MIC` |
| `Recordings.kt` | 檔案目錄與各來源開關設定 |
| `MainActivity.kt` | 主畫面 |

## 2. 如何偵測各種通話

| 來源 | 偵測方式 | 開始 | 結束 |
|---|---|---|---|
| 一般電話 | `TelephonyCallback` | `CALL_STATE_OFFHOOK` | `CALL_STATE_IDLE` |
| LINE / Messenger / Telegram / WhatsApp | `NotificationListenerService` | 該 App 貼出「通話中」通知：Android 12+ 看 `android.callType == 2`；否則看 `CATEGORY_CALL`＋常駐＋顯示計時器 | 通知被移除 |

VoIP App 沒有公開 API 告知「通話中」，讀它們的通話通知是最穩定、跨 App 通用的做法。若某版本 App 通知格式改變，只需調整 `isOngoingCall()`。

## 3. 為什麼錄音品質有限制（技術背景）

| 方式 | 一般 App 可用？ | 能錄到對方？ |
|---|---|---|
| `AudioSource.VOICE_CALL` | ❌ 需 `CAPTURE_AUDIO_OUTPUT`（系統權限），僅 root / 系統 App | ✅ 雙方清楚，僅限電信電話 |
| `AudioPlaybackCapture`（錄系統播放聲音） | ✅ | ❌ 系統明文排除 `USAGE_VOICE_COMMUNICATION`，LINE 等通話聲音抓不到 |
| 麥克風 + 無障礙服務（本設計） | ✅ | ⚠️ 需開擴音，靠麥克風收到喇叭聲 |

Android 10 的「音訊輸入共用」規則下，其他 App 正在通話占用麥克風時，一般 App 會收到靜音；**無障礙服務例外**，這就是本 App 必須啟用無障礙服務的原因。

### 進階：root 版本
在 root（Magisk）手機上把 App 安裝為 priv-app 並授予 `CAPTURE_AUDIO_OUTPUT`，`CallAudioRecorder` 會自動優先使用 `VOICE_CALL`，電信電話可雙方清楚錄音；VoIP 仍需走麥克風。

## 4. 使用者首次設定流程
1. 允許：麥克風、電話、通話紀錄、通知。
2. 開啟「無障礙 → 通話錄音」。
3. 開啟「通知存取 → 通話錄音」。
4. 允許「略過電池最佳化」（小米、OPPO、vivo 另需在系統設定允許「自動啟動」）。
5. 打一通電話或 LINE 測試，確認通知列出現「正在錄製」。

## 5. 建置
- Android Studio Ladybug 以上，JDK 17，`minSdk 29`（Android 10）、`targetSdk 35`。
- 以 Android Studio 開啟 `android-call-recorder/` 資料夾，Sync 後直接 Run。
- 錄音檔位置：`Android/data/tw.callrecorder/files/Recordings/`。

## 6. 法律與上架注意事項（台灣）
- **錄音合法性**：依《通訊保障及監察法》第 29 條第 3 款，**通話一方**為非不法目的而錄音不罰；但錄製**他人之間**的通話屬違法監聽。本 App 只應用於自己參與的通話。
- **個資**：錄音內容屬個人資料，若作為證據或對外提供，請注意《個人資料保護法》。建議通話開始時告知對方正在錄音。
- **Google Play**：自 2022 年 5 月起，Play 政策禁止以無障礙 API 實作通話錄音，此 App **無法上架 Play 商店**，請以 APK 自行安裝（側載）或企業內部發布。
- LINE、Meta、Telegram 的服務條款未授權第三方錄音，請自行評估。

## 7. 後續可擴充
- Room 資料庫存放通話紀錄（號碼/聯絡人名稱、時長）
- 錄音完成後自動上傳 Google Drive
- 語音轉文字逐字稿與摘要
- 依聯絡人設定白名單／黑名單
- 通話時自動提示「建議開啟擴音」懸浮按鈕
