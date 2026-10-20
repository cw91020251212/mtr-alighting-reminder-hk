# Android APK 音效維護紀錄

> 給日後維護此專案的開發者或 AI：修改 Android 音效前，請先讀本文件及 `android-app/app/src/main/java/hk/mtr/reminder/MainActivity.kt`。不要只檢查 GitHub Pages 的瀏覽器版本；Android APK 使用 WebView，聲音路徑有平台差異。

## 已確認的故障現象

APK 中到站鬧聲及語音試聽按鈕均無聲。網頁版原本使用兩套瀏覽器 API：

- `playAlarmSound()`：以 Web Audio `AudioContext` 播放短音。
- `speakCantonese(text)`：以 Web Speech `speechSynthesis` 合成語音。

這些 API 在 Android WebView 上不一定可用、已解鎖或持續可播放；程式把錯誤 catch 後忽略時，介面仍可操作，但不會有聲。

此外，Android 通知的聲音由 Notification Channel 控制。先前的舊頻道若已被 Android 設成靜音，更新程式碼或再次建立同一個 channel ID 不會覆蓋使用者／系統已保存的設定。因此修正通知聲音時使用了新的 channel ID：`mtr_arrival_sound_v2`，並明確指定預設通知音及高重要性。Android 通知頻道建立後不可由 App 靜默改變使用者保存的聲音設定。

## 現行有效做法

### 1. 試聽與語音提醒使用 Android 原生 TextToSpeech

檔案：`android-app/app/src/main/java/hk/mtr/reminder/MainActivity.kt`

- `onCreate()` 初始化 Android `TextToSpeech`。
- 優先選擇 `yue-HK`（粵語／香港）；缺少時回退 `zh-HK`。
- `AndroidMtrAlarm.speak(text)` 由 JavaScript bridge 呼叫原生 TTS。
- `NATIVE_BRIDGE_SCRIPT` 在 WebView 載入 GitHub Pages 後，將 `speakCantonese()` 接到原生 `speak()`；因此網頁不必先部署新版本，APK 可替現有線上頁面補上 native 行為。
- `testVoice(text)` 先以 `ToneGenerator(AudioManager.STREAM_ALARM, ...)` 播一聲提示音，再用原生 TTS 朗讀測試句。設定頁的「試聽語音」按鈕由橋接腳本改接這個測試方法。
- Activity 結束時呼叫 `tts.stop()` 和 `tts.shutdown()`。

若修改網頁 ID 或試聽句，檢查 `NATIVE_BRIDGE_SCRIPT` 裡的 `document.getElementById('testVoice')`，保持和 `index.html` 一致。若修改 bridge method 名稱，要同步修改 Kotlin `@JavascriptInterface` 方法與注入的 JavaScript。

### 2. 到站鬧聲使用原生音訊

同一個 Kotlin 檔案中的 `AndroidMtrAlarm.playAlarm()` 使用 `ToneGenerator` 播放 Android 原生提示音；`NATIVE_BRIDGE_SCRIPT` 把網頁 `systemAlert()` 接到此方法，並保留震動。不要在 APK 上依賴 Web Audio 作為唯一鬧聲來源。

在背景沒有 WebView 可呼叫時，`MtrTrackingService` 仍會將到站事件交給 `AlarmReceiver` 發送系統通知。

### 3. 背景系統通知要設定有聲頻道

- `MainActivity.createChannels()` 建立 `mtr_arrival_sound_v2`，重要性為 `IMPORTANCE_HIGH`，明確指定 `RingtoneManager.TYPE_NOTIFICATION` 預設提示音及 vibration。
- `AlarmReceiver.kt` 發送通知時也設定 notification sound 與震動。
- 如果既有頻道曾被 Android 設成靜音，改 channel ID（例如新版本的 `_v3`）才會產生新頻道；同時在操作說明提醒使用者可能需要在 Android App 通知設定裡允許「落車提示」聲音。**不要每次改版都無條件改 ID**，只在舊 channel 確實設錯且需要重建時升版。

### 4. WebView 音訊設定與腳本時序

- `settings.mediaPlaybackRequiresUserGesture = false` 保留在 WebView 設定中。
- `onPageFinished()` 呼叫 `evaluateJavascript(NATIVE_BRIDGE_SCRIPT, null)`，確保線上頁面載入後才替換全域函式／按鈕處理器。
- `NATIVE_BRIDGE_SCRIPT` 需保留 `systemAlert`、`speakCantonese`、`testVoice` 的原生橋接；不可再用空函式覆蓋（例如 `window.systemAlert=function(){}`），否則按鈕或提醒表面可按、實際不發聲。
- 新增 `@JavascriptInterface` 方法時，只對受信任的 WebView 網域載入橋接。現有 `shouldOverrideUrlLoading` 限制為 `cw91020251212.github.io`，不要任意擴大到不受信任網域。

## 回歸測試清單

每次修改音訊或 WebView bridge，重新安裝 APK 後至少確認：

1. 開啟設定，按「試聽語音」：先聽到原生提示音，再聽到粵語測試句。
2. 按提示站旁的喇叭：應由 Android TTS 朗讀該站提示句。
3. 有聲鬧鐘模式到站：前景應有 Android 原生鬧聲及震動；背景應收到「落車提示」系統通知，且該頻道允許聲音。
4. 「淨係粵語」模式不應額外播鬧聲；「淨係響鬧」不應朗讀語音；「關閉」不應提示。
5. 若測試仍無聲，先檢查媒體／鬧鐘／通知音量、勿擾模式、Android App 通知頻道及系統是否有安裝 TTS 語音資料；記錄裝置型號與 Android 版本。沒有實機測試證據前，不要宣稱所有手機都已驗證。

## 建置與交付

```sh
cd android-app
./gradlew assembleDebug
```

APK 位於 `android-app/app/build/outputs/apk/debug/app-debug.apk`。完成後驗證編譯成功及 APK 簽章；debug APK 可側載，不等同 Play Store release。Android 原生程式碼修改需重新 build APK；如果改到要在線上網站生效的 `index.html`，另外還要合併／部署網站更新。

## 目前關鍵檔案

- `android-app/app/src/main/java/hk/mtr/reminder/MainActivity.kt`：原生 TextToSpeech、ToneGenerator、WebView bridge 與通知頻道。
- `android-app/app/src/main/java/hk/mtr/reminder/AlarmReceiver.kt`：背景到站通知及聲音／震動。
- `android-app/app/src/main/java/hk/mtr/reminder/MtrTrackingService.kt`：背景定位與觸發到站通知。
- `index.html`：網頁端的 `systemAlert()`、`speakCantonese()`、`testVoice` 原始行為。
