package com.fylphzy.monitoring

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.fylphzy.monitoring.model.ApiResponse
import com.fylphzy.monitoring.model.Pantau
import com.fylphzy.monitoring.network.RetrofitClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * MonitoringService: polling server, menampilkan notifikasi event,
 * dan memastikan dedupe state sinkron dengan notifikasi aktif.
 */
class MonitoringService : Service() {

    companion object {
        private const val TAG = "MonitoringService"

        private const val CHANNEL_ID_SERVICE = "trc_monitor_service_channel"
        private const val CHANNEL_ID_EVENT = "trc_monitor_event_channel"

        // Foreground notification ID -> besar agar tidak tabrakan dengan user IDs
        private const val FOREGROUND_NOTIFICATION_ID = 999_999

        private const val PREFS_NAME = "monitoring_service_prefs"
        private const val PREF_KEY_ACTIVE_SET = "active_notifications_set"

        private const val DEDUPE_TTL_MS = 24L * 60L * 60L * 1000L
        private const val POLL_INTERVAL_MS = 10_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var isPolling = false

    // tanggal format server: "yyyy-MM-dd HH:mm:ss"
    private val dtf: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val zone = ZoneId.systemDefault()

    private val pollingRunnable = object : Runnable {
        override fun run() {
            fetchAndNotify()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        createNotificationChannels()
        startForegroundSafe()
        startPolling()
        Log.d(TAG, "Service created and polling started")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isPolling) startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        Log.d(TAG, "Service destroyed")
    }

    private fun startPolling() {
        if (isPolling) return
        isPolling = true
        handler.post(pollingRunnable)
        Log.d(TAG, "Polling scheduled every $POLL_INTERVAL_MS ms")
    }

    private fun stopPolling() {
        isPolling = false
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Polling stopped")
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)!!

        if (nm.getNotificationChannel(CHANNEL_ID_SERVICE) == null) {
            val chService = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "TRC Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Foreground service for TRC Monitoring" }
            nm.createNotificationChannel(chService)
            Log.d(TAG, "Service notification channel created")
        }

        if (nm.getNotificationChannel(CHANNEL_ID_EVENT) == null) {
            val chEvent = NotificationChannel(
                CHANNEL_ID_EVENT,
                "TRC Monitoring Events",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi event TRC Monitoring"
                enableLights(true)
                enableVibration(true)
            }
            nm.createNotificationChannel(chEvent)
            Log.d(TAG, "Event notification channel created")
        }
    }

    private fun startForegroundSafe() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Monitoring aktif")
            .setSmallIcon(R.drawable.ic_akurasi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            Log.d(TAG, "Service moved to foreground with id=$FOREGROUND_NOTIFICATION_ID")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground: ${e.localizedMessage}", e)
        }
    }

    /**
     * Fetch & decide notification actions.
     */
    private fun fetchAndNotify() {
        Log.d(TAG, "fetchAndNotify: polling server...")
        RetrofitClient.instance.getPantauList().enqueue(object : Callback<ApiResponse> {
            override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                if (!response.isSuccessful) {
                    Log.w(TAG, "Server returned error code=${response.code()}")
                    return
                }
                val body = response.body() ?: run {
                    Log.w(TAG, "Server returned empty body")
                    return
                }

                val candidates = if (body.recycle.isNotEmpty()) body.recycle else body.data
                Log.d(TAG, "Server returned ${candidates.size} raw items, notifyCandidates=${body.notify.size}")

                cleanupDedupeStore()

                for (item in candidates) {
                    try {
                        val id = item.id
                        val idStr = id.toString()

                        val emr = item.emr
                        val conf = item.confStatus
                        val emrTsStr = item.emrTimestamp
                        val emrRecent = isEmrRecent(emrTsStr)

                        if (emr == 0 && conf == 1) {
                            Log.d(TAG, "Item id=$id has emr=0 conf=1 -> clearing notification and mark")
                            removeNotificationForId(id)
                            continue
                        }

                        if (emr == 1) {
                            if (conf == 1) {
                                if (isAlreadyNotified(idStr)) {
                                    Log.d(TAG, "Item id=$id conf=1 but was notified earlier - removing notification")
                                    removeNotificationForId(id)
                                } else {
                                    Log.d(TAG, "Item id=$id conf=1 (no notif): nothing to post")
                                }
                                continue
                            }

                            if (conf == 0) {
                                if (!emrRecent) {
                                    if (isAlreadyNotified(idStr)) {
                                        Log.d(TAG, "Item id=$id emr outdated (>24h) -> removing stale notification")
                                        removeNotificationForId(id)
                                    } else {
                                        Log.d(TAG, "Item id=$id emr outdated (>24h) -> no action")
                                    }
                                    continue
                                }

                                if (!isAlreadyNotified(idStr)) {
                                    val title = "Laporan dari ${item.username}"
                                    val text = item.emrDesc ?: "Ada kejadian, cek detail."
                                    sendEventNotification(id, title, text, item)
                                    markAsNotified(idStr)
                                } else {
                                    Log.d(TAG, "Item id=$id alreadyNotified=true -> skip posting")
                                }
                                continue
                            }
                        }
                    } catch (ex: Exception) {
                        Log.e(TAG, "Error processing item ${item.id}: ${ex.localizedMessage}", ex)
                    }
                }
            }

            override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
                Log.e(TAG, "fetchAndNotify failure: ${t.localizedMessage}", t)
            }
        })
    }

    private fun sendEventNotification(notificationId: Int, title: String, text: String, pantau: Pantau? = null) {
        Log.d(TAG, "Attempt to send event notification id=$notificationId title=$title")

        if (!canPostNotifications()) {
            Log.w(TAG, "Cannot post notification: POST_NOTIFICATIONS denied")
            return
        }

        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra("id", pantau?.id)
            putExtra("username", pantau?.username)
            putExtra("whatsapp", pantau?.whatsapp)
            putExtra("la", pantau?.la)
            putExtra("lo", pantau?.lo)
            putExtra("conf_status", pantau?.confStatus)
            putExtra("emr_desc", pantau?.emrDesc)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = try {
            PendingIntent.getActivity(this, notificationId, intent, pendingIntentFlags)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create PendingIntent: ${e.localizedMessage}", e)
            null
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID_EVENT)
            .setSmallIcon(R.drawable.ic_akurasi)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        pi?.let { builder.setContentIntent(it) }

        try {
            NotificationManagerCompat.from(this).notify(notificationId, builder.build())
            Log.d(TAG, "Notification posted id=$notificationId")
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException posting notification: ${se.localizedMessage}", se)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification id=$notificationId: ${e.localizedMessage}", e)
        }
    }

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    // ---------- dedupe helpers with active-notification check ----------
    private fun isAlreadyNotified(idStr: String): Boolean {
        val raw = prefs.getStringSet(PREF_KEY_ACTIVE_SET, emptySet()) ?: emptySet()
        val prefEntryExists = raw.any { it.startsWith("$idStr:") }
        if (!prefEntryExists) return false

        // If prefs says it's notified but the notification is NOT active in the status bar,
        // remove the stale pref entry and return false so we can re-post.
        val id = idStr.toIntOrNull() ?: return true
        val active = isNotificationActive(id)
        if (!active) {
            val mutable = raw.toMutableSet()
            val removed = mutable.removeAll { it.startsWith("$idStr:") }
            if (removed) {
                prefs.edit { putStringSet(PREF_KEY_ACTIVE_SET, mutable) }
                Log.d(TAG, "Pref indicated id=$idStr was notified but no active notification found -> cleared pref entry")
            }
            return false
        }
        return true
    }

    private fun isNotificationActive(notificationId: Int): Boolean {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return false
            val active = nm.activeNotifications // requires API >= 23; project minSdk assumed >= 26/27
            for (s in active) {
                if (s.id == notificationId) return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query active notifications: ${e.localizedMessage}")
            // fallback: assume active to avoid repost storms
            return true
        }
        return false
    }

    private fun markAsNotified(idStr: String) {
        val raw = prefs.getStringSet(PREF_KEY_ACTIVE_SET, emptySet())!!.toMutableSet()
        val entry = "$idStr:${System.currentTimeMillis()}"
        raw.add(entry)
        prefs.edit { putStringSet(PREF_KEY_ACTIVE_SET, raw) }
        Log.d(TAG, "Marked as notified: $entry (total=${raw.size})")
    }

    private fun removeNotificationForId(id: Int) {
        try {
            NotificationManagerCompat.from(this).cancel(id)
            val raw = prefs.getStringSet(PREF_KEY_ACTIVE_SET, emptySet())!!.toMutableSet()
            val removed = raw.removeAll { it.startsWith("${id}:") }
            if (removed) {
                prefs.edit { putStringSet(PREF_KEY_ACTIVE_SET, raw) }
                Log.d(TAG, "Removed dedupe entry and cancelled notification for id=$id")
            } else {
                Log.d(TAG, "Cancelled notification for id=$id (no dedupe entry found)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing notification for id=$id: ${e.localizedMessage}", e)
        }
    }

    private fun cleanupDedupeStore() {
        val now = System.currentTimeMillis()
        val raw = prefs.getStringSet(PREF_KEY_ACTIVE_SET, emptySet()) ?: emptySet()
        if (raw.isEmpty()) return

        val filtered = raw.mapNotNull { item ->
            val parts = item.split(":")
            if (parts.size < 2) return@mapNotNull null
            val ts = parts[1].toLongOrNull() ?: return@mapNotNull null
            if (now - ts <= DEDUPE_TTL_MS) item else null
        }.toSet()

        if (filtered.size != raw.size) {
            prefs.edit { putStringSet(PREF_KEY_ACTIVE_SET, filtered) }
            Log.d(TAG, "Cleaned dedupe store: kept=${filtered.size} removed=${raw.size - filtered.size}")
        }
    }

    // ----- timestamp helper -----
    private fun isEmrRecent(emrTimestampStr: String?): Boolean {
        if (emrTimestampStr == null) return false
        return try {
            val dt = LocalDateTime.parse(emrTimestampStr, dtf)
            val tsMillis = dt.atZone(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()
            (now - tsMillis) <= DEDUPE_TTL_MS
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse emrTimestamp='$emrTimestampStr': ${e.localizedMessage}")
            false
        }
    }
}
