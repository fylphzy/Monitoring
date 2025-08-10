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
import com.fylphzy.monitoring.model.ApiResponse
import com.fylphzy.monitoring.model.Pantau
import com.fylphzy.monitoring.network.RetrofitClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import androidx.core.content.edit

class MonitoringService : Service() {

    companion object {
        private const val CHANNEL_ID_FOREGROUND = "monitoring_service_channel"
        private const val CHANNEL_ID_EMERGENCY = "emergency_channel"
        private const val PREFS_NAME = "monitoring_service_prefs"
        private const val PREF_KEY_ACTIVE = "active_notifications"
        private const val POLL_INTERVAL_MS = 5000L // 5 detik
        private const val FOREGROUND_NOTIFICATION_ID = 999_999
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private val activeNotifications = mutableSetOf<Int>()
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
        startForegroundServiceNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        val manager = getSystemService(NotificationManager::class.java)
        // Foreground channel (low importance)
        val foregroundChannel = NotificationChannel(
            CHANNEL_ID_FOREGROUND,
            "Monitoring Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Service berjalan untuk memantau sinyal emergency"
        }
        // Emergency channel (high importance / heads-up)
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
                val manager = getSystemService(NotificationManager::class.java)

                // Track ids seen on this poll. If notification was removed from active set externally,
                // we still handle according to server state.
                val serverIds = mutableSetOf<Int>()

                allUsers.forEach { user ->
                    val nid = user.id
                    serverIds.add(nid)

                    if (user.emr == 1 && user.confStatus == 0) {
                        // EMERGENCY and not confirmed -> ensure notification exists
                        if (!activeNotifications.contains(nid)) {
                            sendEmergencyNotification(user)
                            activeNotifications.add(nid)
                            saveActiveNotifications()
                        } else {
                            // already active -> do nothing (no resend)
                        }
                    } else {
                        // either not emergency or already confirmed -> remove if exists
                        if (activeNotifications.contains(nid)) {
                            manager.cancel(nid)
                            activeNotifications.remove(nid)
                            saveActiveNotifications()
                        }
                    }
                }

                // Optional cleanup: if activeNotifications contains ids no longer on server,
                // cancel them to avoid stale notifications.
                val toRemove = activeNotifications.filter { it !in serverIds }
                toRemove.forEach { orphanId ->
                    manager.cancel(orphanId)
                    activeNotifications.remove(orphanId)
                }
                if (toRemove.isNotEmpty()) saveActiveNotifications()
            }

            override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
                // network failure -> do nothing this cycle
            }
        })
    }

    private fun sendEmergencyNotification(user: Pantau) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // Intent opens DetailActivity with full payload
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra("id", user.id)
            putExtra("username", user.username)
            putExtra("whatsapp", user.whatsapp)
            putExtra("la", user.la)
            putExtra("lo", user.lo)
            putExtra("conf_status", user.confStatus)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            user.id, // requestCode unique per user.id
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
            .setAutoCancel(false) // do not auto-cancel, remove only when confirmed
            .setOngoing(true)      // keep visible until explicitly removed
            .setPriority(NotificationCompat.PRIORITY_MAX)

        notificationManager.notify(user.id, builder.build())
    }

    // --- Persistence helpers for activeNotifications set ---
    private fun saveActiveNotifications() {
        prefs.edit {
            putString(PREF_KEY_ACTIVE, activeNotifications.joinToString(","))
        }
    }

    private fun loadActiveNotifications() {
        val csv = prefs.getString(PREF_KEY_ACTIVE, "") ?: ""
        if (csv.isBlank()) return
        csv.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .forEach { activeNotifications.add(it) }
    }
}
