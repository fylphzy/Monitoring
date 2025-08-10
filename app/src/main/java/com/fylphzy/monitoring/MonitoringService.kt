package com.fylphzy.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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

class MonitoringService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val checkInterval = 20000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        scheduleEmergencyCheck()
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "monitoring_service"
        val channel = NotificationChannel(
            channelId,
            getString(R.string.monitoring_service_title),
            NotificationManager.IMPORTANCE_LOW
        )
        val emergencyChannel = NotificationChannel(
            "emergency_channel",
            getString(R.string.emergency_notification_title),
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(emergencyChannel)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.monitoring_service_title))
            .setContentText(getString(R.string.monitoring_service_desc))
            .setSmallIcon(R.drawable.ic_location)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)
    }

    private fun scheduleEmergencyCheck() {
        handler.postDelayed({
            checkEmergencySignals()
            scheduleEmergencyCheck()
        }, checkInterval)
    }

    private fun checkEmergencySignals() {
        RetrofitClient.instance.getPantauList().enqueue(object : Callback<ApiResponse> {
            override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                if (response.isSuccessful) {
                    val allUsers = response.body()?.data ?: emptyList()
                    val manager = getSystemService(NotificationManager::class.java)
                    allUsers.forEach { user ->
                        if (user.emr == 1 && user.confStatus == 0) {
                            sendEmergencyNotification(user)
                        } else {
                            manager.cancel(user.id)
                        }
                    }
                }
            }
            override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
            }
        })
    }


    private fun sendEmergencyNotification(user: Pantau) {
        val notificationId = user.id
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra("id", user.id)
            putExtra("username", user.username)
            putExtra("whatsapp", user.whatsapp)
            putExtra("la", user.la)
            putExtra("lo", user.lo)
            putExtra("conf_status", user.confStatus)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusKonfirmasi = if (user.confStatus == 1) {
            getString(R.string.status_sudah)
        } else {
            getString(R.string.status_belum)
        }
        val baris1 = getString(R.string.user_status_format, user.username, user.id, statusKonfirmasi)
        val baris2 = getString(R.string.darurat)
        val notificationText = "$baris1\n$baris2"

        val notification: Notification = NotificationCompat.Builder(this, "emergency_channel")
            .setContentTitle(getString(R.string.emergency_notification_title))
            .setSmallIcon(R.drawable.ic_location)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }
}
