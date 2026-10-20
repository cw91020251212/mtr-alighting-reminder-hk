package hk.mtr.reminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import kotlin.math.*

class MtrTrackingService : Service(), LocationListener {
    private lateinit var locations: LocationManager
    private var journey = JSONObject()
    private val prefs by lazy { getSharedPreferences("mtr_background_tracker", MODE_PRIVATE) }
    private var lastNearest = ""
    private lateinit var stationCoords: JSONObject
    private lateinit var stationNames: JSONObject

    override fun onCreate() {
        super.onCreate()
        locations = getSystemService(LocationManager::class.java)
        stationCoords = JSONObject(assets.open("station-coords.json").bufferedReader().use { it.readText() })
        stationNames = JSONObject(assets.open("station-names.json").bufferedReader().use { it.readText() })
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopTracking(); return START_NOT_STICKY }
        val encoded = intent?.getStringExtra(EXTRA_JOURNEY)
        if (!encoded.isNullOrBlank()) {
            journey = runCatching { JSONObject(encoded) }.getOrElse { JSONObject() }
            val previous = runCatching { JSONObject(prefs.getString("journey", "{}") ?: "{}") }.getOrElse { JSONObject() }
            if (journey.optString("alarmSession").isNotEmpty() && journey.optString("alarmSession") == previous.optString("alarmSession")) {
                val merged = journey.optJSONArray("triggered") ?: org.json.JSONArray()
                val already = (0 until merged.length()).map { merged.optString(it) }.toMutableSet()
                val prior = previous.optJSONArray("triggered") ?: org.json.JSONArray()
                for (i in 0 until prior.length()) if (already.add(prior.optString(i))) merged.put(prior.optString(i))
                journey.put("triggered", merged)
            }
            prefs.edit().putString("journey", journey.toString()).apply()
        } else if (journey.length() == 0) {
            journey = runCatching { JSONObject(prefs.getString("journey", "{}") ?: "{}") }.getOrElse { JSONObject() }
        }
        if (!journey.optBoolean("active")) { stopTracking(); return START_NOT_STICKY }
        startForeground(ONGOING_ID, ongoingNotification())
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopTracking(); return START_NOT_STICKY
        }
        runCatching { locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 0f, this) }
        runCatching { locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 0f, this) }
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (!journey.optBoolean("active")) return
        val nearest = nearest(location.latitude, location.longitude) ?: return
        val isNear = nearest.second <= PROXIMITY_METERS
        if (!isNear) { lastNearest = ""; return }
        val station = nearest.first
        if (station == lastNearest) return
        lastNearest = station
        val route = journey.optJSONArray("path") ?: return
        val stationIndex = (0 until route.length()).firstOrNull { route.optString(it) == station } ?: -1
        val destination = journey.optString("destination")
        val destinationIndex = (0 until route.length()).lastOrNull { route.optString(it) == destination } ?: -1
        val hasProgress = journey.optBoolean("hasJourneyProgress")
        val terminal = station == destination
        val terminalPre = stationIndex >= 0 && destinationIndex == stationIndex + 1
        if (!terminal && !terminalPre && stationIndex == 0 && !hasProgress) { journey.put("hasJourneyProgress", true); prefs.edit().putString("journey", journey.toString()).apply() }
        val alerts = journey.optJSONArray("alerts") ?: return
        val triggered = journey.optJSONArray("triggered") ?: org.json.JSONArray()
        for (i in 0 until alerts.length()) {
            val alert = alerts.optJSONObject(i) ?: continue
            val id = alert.optString("id")
            if (alert.optString("station") != station || alert.optString("mode") == "off" || alert.optString("mode") == "speech" || contains(triggered, id)) continue
            val isPre = alert.optString("kind") == "destination-pre"
            val validPre = !isPre || (stationIndex >= 0 && destinationIndex == stationIndex + 1)
            val canTrigger = hasProgress || (!terminal && !terminalPre)
            if (!validPre || !canTrigger) continue
            triggered.put(id)
            journey.put("triggered", triggered)
            prefs.edit().putString("journey", journey.toString()).apply()
            showArrival(alert, station)
        }
    }

    private fun showArrival(alert: JSONObject, station: String) {
        val kind = alert.optString("kind")
        val name = stationNames.optString(alert.optString("station"), station)
        val title = when (kind) {
            "destination" -> "終點落車提示"
            "destination-pre" -> "準備落車"
            "transfer" -> "準備轉車"
            else -> "到達提示站"
        }
        val body = when (kind) {
            "destination" -> "已到達終點【${stationNames.optString(journey.optString("destination"), journey.optString("destination"))}】，請落車。"
            "destination-pre" -> "下一站係終點【${stationNames.optString(journey.optString("destination"), journey.optString("destination"))}】。"
            "transfer" -> "已到達【$name】，請按你計劃轉乘。"
            else -> "已到達【$name】。"
        }
        val id = alert.optString("id").hashCode().and(0x7fffffff).coerceAtLeast(1)
        val send = Intent(this, AlarmReceiver::class.java).putExtra("title", title).putExtra("body", body).putExtra("notificationId", id)
        sendBroadcast(send)
    }

    private fun nearest(lat: Double, lon: Double): Pair<String, Double>? {
        val coords = stationCoords
        var best: Pair<String, Double>? = null
        val keys = coords.keys()
        while (keys.hasNext()) {
            val code = keys.next(); val point = coords.optJSONArray(code) ?: continue
            val distance = distanceMeters(lat, lon, point.optDouble(0), point.optDouble(1))
            if (best == null || distance < best.second) best = code to distance
        }
        return best
    }

    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
        return 6371000.0 * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun contains(array: org.json.JSONArray, value: String) = (0 until array.length()).any { array.optString(it) == value }
    private fun ongoingNotification(): Notification = NotificationCompat.Builder(this, TRACK_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("港鐵行程追蹤中")
        .setContentText("背景定位已開啟，到達提示站時會通知你。")
        .setCategory(NotificationCompat.CATEGORY_SERVICE).setOngoing(true).build()

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(TRACK_CHANNEL, "背景行程追蹤", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(MainActivity.ALERT_CHANNEL, "落車提示", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        }
    }

    private fun stopTracking() {
        runCatching { locations.removeUpdates(this) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { runCatching { locations.removeUpdates(this) }; super.onDestroy() }

    companion object {
        const val ACTION_STOP = "hk.mtr.reminder.STOP"
        const val EXTRA_JOURNEY = "journey"
        const val TRACK_CHANNEL = "mtr_tracking"
        const val PROXIMITY_METERS = 850.0
        const val ONGOING_ID = 801
    }
}
