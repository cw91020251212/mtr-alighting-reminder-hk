package hk.mtr.reminder

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingJourney: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        createChannels()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val cantonese = tts?.setLanguage(Locale.forLanguageTag("yue-HK"))
                if (cantonese == TextToSpeech.LANG_MISSING_DATA || cantonese == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.forLanguageTag("zh-HK"))
                }
                ttsReady = true
            }
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setGeolocationEnabled(true)
            settings.mediaPlaybackRequiresUserGesture = false
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
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audio = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "落車提示（有聲）", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setSound(sound, audio)
            })
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun speak(text: String) {
        if (ttsReady && text.isNotBlank()) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mtr-voice-${System.currentTimeMillis()}")
    }

    private fun playTestTone() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 700)
        }
    }

    inner class AndroidMtrAlarm {
        @JavascriptInterface fun syncJourney(json: String) { runOnUiThread { this@MainActivity.syncJourney(json) } }
        @JavascriptInterface fun playAlarm() {
            runOnUiThread {
                runCatching {
                    val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 900)
                    Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 1100)
                }
            }
        }
        @JavascriptInterface fun speak(text: String) { runOnUiThread { this@MainActivity.speak(text) } }
        @JavascriptInterface fun testVoice(text: String) {
            runOnUiThread {
                playTestTone()
                Handler(Looper.getMainLooper()).postDelayed({ speak(text) }, 550)
            }
        }
    }

    companion object {
        const val ALERT_CHANNEL = "mtr_arrival_sound_v2"
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
              if(window.systemAlert && window.AndroidMtrAlarm){
                window.systemAlert=function(){AndroidMtrAlarm.playAlarm();try{navigator.vibrate&&navigator.vibrate([250,100,250,100,500])}catch(e){}};
              }
              if(window.speakCantonese && window.AndroidMtrAlarm){
                window.speakCantonese=function(text){AndroidMtrAlarm.speak(text)};
                var test=document.getElementById('testVoice');
                if(test)test.onclick=function(){AndroidMtrAlarm.testVoice('港鐵終點提醒，粵語語音測試成功。')};
              }
              try{var saved=localStorage.getItem(key);if(saved)AndroidMtrAlarm.syncJourney(saved)}catch(e){}
            })();
        """
    }
}
