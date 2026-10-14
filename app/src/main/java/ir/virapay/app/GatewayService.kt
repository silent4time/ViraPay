package ir.virapay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlin.concurrent.thread

class GatewayService : Service() {

    companion object {
        private const val CHANNEL_ID = "virapay_gateway"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } catch (e: Exception) {
            LogBus.append("❌ Cannot start foreground service: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (running) return START_STICKY

        // Settings come from prefs so a system restart (null intent) still works
        val prefs = getSharedPreferences("ViraPayPrefs", Context.MODE_PRIVATE)
        val apiId = prefs.getString("api_id", "")?.toIntOrNull()
        val apiHash = prefs.getString("api_hash", "") ?: ""
        val cards = prefs.getString("bank_cards", "") ?: ""
        val bots = prefs.getString("bot_usernames", "") ?: ""
        if (apiId == null || apiHash.isEmpty()) {
            LogBus.append("❌ Telegram credentials missing. / اطلاعات تلگرام ناقص است.")
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        val service = this
        thread {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                Python.getInstance().getModule("virapay_engine").callAttr(
                    "start_gateway_loop", service, filesDir.absolutePath, apiId, apiHash, cards, bots
                )
            } catch (e: Exception) {
                LogBus.append("❌ Service error: ${e.message}")
            } finally {
                running = false
                LogBus.append("🔴 Gateway stopped. / درگاه متوقف شد.")
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            if (Python.isStarted()) {
                Python.getInstance().getModule("virapay_engine").callAttr("stop_gateway")
            }
        } catch (e: Exception) {
            LogBus.append("Stop error: ${e.message}")
        }
        super.onDestroy()
    }

    // Called from Python (virapay_engine.py)
    fun appendLog(msg: String) = LogBus.append(msg)

    fun getSmsInboxJson(): String = SmsReader.readInboxJson(this)

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ViraPay Gateway", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.gateway_running))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
