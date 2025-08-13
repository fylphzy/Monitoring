package com.fylphzy.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.fylphzy.monitoring.model.ApiResponse
import com.fylphzy.monitoring.model.Pantau
import com.fylphzy.monitoring.network.RetrofitClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MonitoringService : Service() {

    companion object {
        private const val CHANNEL_ID_FOREGROUND = "monitoring_service_channel"
        private const val CHANNEL_ID_EMERGENCY = "emergency_channel"
        private const val PREFS_NAME = "monitoring_service_prefs"
        private const val PREF_KEY_ACTIVE = "active_notifications_set"
        private const val POLL_INTERVAL_MS = 5000L
        private const val FOREGROUND_NOTIFICATION_ID = 999_999
        private const val NOTIF_ID_BASE = 10_000
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private val activeNotifications = mutableSetOf<Int>()
    @Volatile private var foregroundStarted = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            checkEmergencySignals()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        loadActiveNotifications()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            startForegroundServiceNotification()
            foregroundStarted = true
        }
        handler.removeCallbacksAndMessages(null)
        handler.post(pollRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val foregroundChannel = NotificationChannel(
            CHANNEL_ID_FOREGROUND,
            "Monitoring Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Service berjalan untuk memantau sinyal emergency"
        }
        val emergencyChannel = NotificationChannel(
            CHANNEL_ID_EMERGENCY,
            "Emergency Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifikasi darurat"
        }
        manager.createNotificationChannel(foregroundChannel)
        manager.createNotificationChannel(emergencyChannel)
    }

    private fun startForegroundServiceNotification() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle(getString(R.string.monitoring_service_title))
            .setContentText(getString(R.string.monitoring_service_desc))
            .setSmallIcon(R.drawable.ic_location)
            .setOngoing(true)
            .build()
        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun checkEmergencySignals() {
        RetrofitClient.instance.getPantauList().enqueue(object : Callback<ApiResponse> {
            override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                if (!response.isSuccessful) return
                val allUsers = response.body()?.data ?: emptyList()
                val manager = getSystemService(NotificationManager::class.java) ?: return
                val serverIds = mutableSetOf<Int>()
                allUsers.forEach { user ->
                    val nid = user.id
                    serverIds.add(nid)

                    if (user.emr == 1 && user.confStatus == 0) {
                        synchronized(activeNotifications) {
                            if (!activeNotifications.contains(nid)) {
                                sendEmergencyNotification(user, manager)
                                activeNotifications.add(nid)
                                saveActiveNotifications()
                            }
                        }
                    } else {
                        synchronized(activeNotifications) {
                            if (activeNotifications.contains(nid)) {
                                manager.cancel(NOTIF_ID_BASE + nid)
                                activeNotifications.remove(nid)
                                saveActiveNotifications()
                            }
                        }
                    }
                }

                val toRemove = synchronized(activeNotifications) {
                    activeNotifications.filter { it !in serverIds }.toList()
                }
                if (toRemove.isNotEmpty()) {
                    toRemove.forEach { orphanId ->
                        manager.cancel(NOTIF_ID_BASE + orphanId)
                        synchronized(activeNotifications) { activeNotifications.remove(orphanId) }
                    }
                    saveActiveNotifications()
                }
            }

            override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
            }
        })
    }

    private fun sendEmergencyNotification(user: Pantau, manager: NotificationManager) {
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra("id", user.id)
            putExtra("username", user.username)
            putExtra("whatsapp", user.whatsapp)
            putExtra("la", user.la)
            putExtra("lo", user.lo)
            putExtra("conf_status", user.confStatus)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            user.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusKonfirmasi = if (user.confStatus == 1) getString(R.string.status_sudah) else getString(R.string.status_belum)
        val line1 = getString(R.string.user_status_format, user.username, user.id, statusKonfirmasi)
        val line2 = getString(R.string.darurat)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID_EMERGENCY)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle(getString(R.string.emergency_notification_title))
            .setContentText(line1)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$line1\n$line2"))
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)

        val notifId = NOTIF_ID_BASE + user.id
        manager.notify(notifId, builder.build())
    }

    private fun saveActiveNotifications() {
        val stringSet = synchronized(activeNotifications) {
            activeNotifications.map { it.toString() }.toSet()
        }
        prefs.edit {
            putStringSet(PREF_KEY_ACTIVE, stringSet)
        }
    }

    private fun loadActiveNotifications() {
        val set = prefs.getStringSet(PREF_KEY_ACTIVE, null) ?: return
        set.mapNotNull { it.toIntOrNull() }.forEach { activeNotifications.add(it) }
    }
}
