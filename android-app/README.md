# 港鐵終點提醒 Android APK

此 Android 封裝沿用 GitHub Pages 網頁介面。APK 前景的地圖定位由 `MainActivity` 直接用 Android `LocationManager` 訂閱 GPS／網路定位，再把座標送入網頁原有 `processLocation()` 路線邏輯；APK 不只依賴 WebView 的 `navigator.geolocation`。切到背景後，由 Android 原生 `location` 前景服務持續定位；偵測到與 MTR 網頁相同的站點座標 850 米範圍及已設定提示站時，透過 Android 通知發出提醒。行程、路線與提示點仍由既有網頁保存，座標表由 `mtr-network.js` 產生並內置於 APK。

## 建置

需要 JDK 17 和 Android SDK (API 35)。於本目錄執行：

```sh
./gradlew assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。Android 6+ 需允許精確／粗略定位；Android 13+ 需允許通知。開始行程後，系統會顯示持續的「背景行程追蹤中」通知，代表定位服務正在運行。

## 限制

此為 Android APK，不適用 iOS。系統會持續顯示前景服務通知並消耗電量；手機廠商的省電／強制停止、使用者撤銷定位或關閉 App，都可能中斷追蹤。地下站及 GPS 漂移可能影響到站判斷；請勿將它視為安全關鍵或保證準時的鬧鐘。實際鎖屏、背景、省電模式表現仍須以實機驗收。
