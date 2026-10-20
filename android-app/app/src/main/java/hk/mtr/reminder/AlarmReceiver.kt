package hk.mtr.reminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val n = NotificationCompat.Builder(context, MainActivity.ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(intent.getStringExtra("title") ?: "港鐵落車提醒")
            .setContentText(intent.getStringExtra("body") ?: "已到達提示站，請準備落車。")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(sound, android.media.AudioManager.STREAM_NOTIFICATION).setVibrate(longArrayOf(250, 100, 250, 100, 500))
            .setAutoCancel(true).setContentIntent(open).build()
        context.getSystemService(NotificationManager::class.java).notify(intent.getIntExtra("notificationId", 1), n)
    }
}
