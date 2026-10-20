package hk.mtr.reminder

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var pendingJourney: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        createChannels()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setGeolocationEnabled(true)
            settings.userAgentString += " MTRReminderAndroid/1.0"
            addJavascriptInterface(AndroidMtrAlarm(), "AndroidMtrAlarm")
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                    callback.invoke(origin, origin == WEB_ORIGIN, false)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return request.url.host != WEB_HOST
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (url.startsWith(WEB_ORIGIN)) view.evaluateJavascript(NATIVE_BRIDGE_SCRIPT, null)
                }
            }
            loadUrl("$WEB_ORIGIN/mtr-alighting-reminder-hk/?android=1")
        }
        setContentView(web)
        requestRuntimePermissions()
    }

    private fun requestRuntimePermissions() {
        val missing = buildList {
            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            pendingJourney?.let { syncJourney(it) }
            pendingJourney = null
        }
    }

    private fun syncJourney(json: String) {
        val journey = runCatching { JSONObject(json) }.getOrNull() ?: return
        val intent = Intent(this, MtrTrackingService::class.java)
        if (!journey.optBoolean("active")) {
            intent.action = MtrTrackingService.ACTION_STOP
            startService(intent)
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingJourney = json
            requestRuntimePermissions()
            return
        }
        intent.putExtra(MtrTrackingService.EXTRA_JOURNEY, json)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(MtrTrackingService.TRACK_CHANNEL, "背景行程追蹤", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "落車提示", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    inner class AndroidMtrAlarm {
        @JavascriptInterface fun syncJourney(json: String) { runOnUiThread { this@MainActivity.syncJourney(json) } }
    }

    companion object {
        const val ALERT_CHANNEL = "mtr_arrival"
        const val REQUEST_PERMISSIONS = 40
        const val WEB_HOST = "cw91020251212.github.io"
        const val WEB_ORIGIN = "https://$WEB_HOST"
        const val NATIVE_BRIDGE_SCRIPT = """
            (function(){
              var key='mtr-smart-alert-web-v4';
              if(window.saveJourney && window.saveJourney.toString().indexOf('AndroidMtrAlarm')<0){
                var original=window.saveJourney;
                window.saveJourney=function(){var result=original.apply(this,arguments);try{var state=localStorage.getItem(key);if(state)AndroidMtrAlarm.syncJourney(state)}catch(e){}return result};
              }
              if(window.systemAlert)window.systemAlert=function(){};
              try{var saved=localStorage.getItem(key);if(saved)AndroidMtrAlarm.syncJourney(saved)}catch(e){}
            })();
        """
    }
}
