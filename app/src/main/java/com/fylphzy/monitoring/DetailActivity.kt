package com.fylphzy.monitoring

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fylphzy.monitoring.model.ApiResponse
import com.fylphzy.monitoring.model.Pantau
import com.fylphzy.monitoring.model.SimpleResponse
import com.fylphzy.monitoring.network.RetrofitClient
import com.google.android.material.button.MaterialButton
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class DetailActivity : AppCompatActivity() {

    companion object {
        private const val NOTIF_ID_BASE = 3_000
        private const val TAG = "DetailActivity"
        const val ACTION_CONF_CHANGED = "CONF_STATUS_CHANGED"
    }

    private lateinit var userText: TextView
    private lateinit var valueLatitude: TextView
    private lateinit var valueLongitude: TextView
    private lateinit var indicatorKonfirmasi: TextView
    private lateinit var lokasiBtn: TextView
    private lateinit var waBtn: TextView
    private lateinit var confirmBtn: MaterialButton
    private lateinit var cancelConfirm: TextView
    private lateinit var backBtn: TextView
    private lateinit var emrDescView: TextView

    private var id: Int = 0
    private var username: String = ""
    private var whatsapp: String = ""
    private var la: Double = 0.0
    private var lo: Double = 0.0
    private var confStatus: Int = 0

    private val handler = Handler(Looper.getMainLooper())
    private val refreshInterval = 3_000L
    private var isLoading = false

    private val detailRefreshRunnable = object : Runnable {
        override fun run() {
            loadDetail()
            handler.postDelayed(this, refreshInterval)
        }
    }

    private val confReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val u = intent?.getStringExtra("username") ?: return
            if (u != username) return
            val s = intent.getIntExtra("conf_status", Int.MIN_VALUE)
            if (s != Int.MIN_VALUE) {
                confStatus = s
                updateIndicator()
            } else {
                // fallback: re-load full detail if no explicit status provided
                loadDetail()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        id = intent.getIntExtra("id", 0)
        username = intent.getStringExtra("username") ?: ""
        whatsapp = intent.getStringExtra("whatsapp") ?: ""
        la = intent.getDoubleExtra("la", 0.0)
        lo = intent.getDoubleExtra("lo", 0.0)
        confStatus = intent.getIntExtra("conf_status", 0)
        val emrDescIntent = intent.getStringExtra("emr_desc")

        userText = findViewById(R.id.userText)
        valueLatitude = findViewById(R.id.valueLatitude)
        valueLongitude = findViewById(R.id.valueLongitude)
        indicatorKonfirmasi = findViewById(R.id.indicatorKonfirmasi)
        lokasiBtn = findViewById(R.id.lokasi)
        waBtn = findViewById(R.id.wa)
        confirmBtn = findViewById(R.id.confirmBtn)
        cancelConfirm = findViewById(R.id.cancelconfirm)
        backBtn = findViewById(R.id.backBtn)
        emrDescView = findViewById(R.id.emr_description)

        userText.text = username
        valueLatitude.text = la.toString()
        valueLongitude.text = lo.toString()
        emrDescView.text = emrDescIntent?.takeIf { it.isNotBlank() } ?: getString(R.string.deskripsi_darurat)
        updateIndicator()

        backBtn.setOnClickListener { finish() }

        lokasiBtn.setOnClickListener {
            val uriStr = "https://www.google.com/maps/search/?api=1&query=$la,$lo"
            startActivity(Intent(Intent.ACTION_VIEW, uriStr.toUri()))
        }

        waBtn.setOnClickListener {
            if (whatsapp.isNotBlank()) {
                val uri = "https://wa.me/$whatsapp".toUri()
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } else {
                Toast.makeText(this, "Nomor WhatsApp tidak tersedia", Toast.LENGTH_SHORT).show()
            }
        }

        confirmBtn.setOnClickListener { updateConfirmation(1) }
        cancelConfirm.setOnClickListener { updateConfirmation(0) }
    }

    private fun updateIndicator() {
        // drawable selector uses state_selected
        runOnUiThread {
            indicatorKonfirmasi.isSelected = (confStatus == 1)
            indicatorKonfirmasi.refreshDrawableState()
        }
    }

    private fun updateConfirmation(status: Int) {
        RetrofitClient.instance.updateConfStatus(username, status)
            .enqueue(object : Callback<SimpleResponse> {
                override fun onResponse(call: Call<SimpleResponse>, response: Response<SimpleResponse>) {
                    if (!response.isSuccessful) {
                        Toast.makeText(this@DetailActivity, "Gagal memperbarui status (server)", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val body = response.body()
                    if (body == null) {
                        Toast.makeText(this@DetailActivity, "Response invalid", Toast.LENGTH_SHORT).show()
                        return
                    }
                    // Terima "success" atau "ok"
                    if (body.status != "success" && body.status != "ok") {
                        Toast.makeText(this@DetailActivity, body.message, Toast.LENGTH_SHORT).show()
                        return
                    }

                    // atur status lokal
                    confStatus = status
                    updateIndicator()
                    setResult(RESULT_OK)
                    Toast.makeText(this@DetailActivity, body.message, Toast.LENGTH_SHORT).show()

                    // kirim broadcast internal supaya activity lain bisa langsung update
                    val i = Intent(ACTION_CONF_CHANGED)
                        .putExtra("username", username)
                        .putExtra("conf_status", confStatus)
                    LocalBroadcastManager.getInstance(this@DetailActivity).sendBroadcast(i)

                    // Hapus notifikasi terkait
                    val notifId = NOTIF_ID_BASE + id
                    getSystemService(NotificationManager::class.java)?.cancel(notifId)

                    // Hapus dari prefs supaya tidak muncul lagi
                    val prefs = getSharedPreferences("monitoring_service_prefs", MODE_PRIVATE)
                    val currentSet: MutableSet<String> =
                        prefs.getStringSet("active_notifications_set", emptySet())?.toMutableSet() ?: mutableSetOf()
                    currentSet.remove(notifId.toString())
                    prefs.edit {
                        putStringSet("active_notifications_set", currentSet)
                    }
                }

                override fun onFailure(call: Call<SimpleResponse>, t: Throwable) {
                    Log.e(TAG, "updateConfirmation onFailure: ${t.localizedMessage}")
                    Toast.makeText(this@DetailActivity, "Gagal koneksi ke server", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun loadDetail() {
        if (isLoading) return
        isLoading = true
        RetrofitClient.instance.getPantauDetail(username)
            .enqueue(object : Callback<ApiResponse> {
                override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                    isLoading = false
                    if (!response.isSuccessful) return
                    val body = response.body() ?: return
                    // terima "success" atau "ok"
                    if (body.status != "success" && body.status != "ok") return
                    val detail = body.data.firstOrNull()
                    if (detail != null) applyDetail(detail)
                }

                override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
                    isLoading = false
                    Log.e(TAG, "loadDetail onFailure: ${t.localizedMessage}")
                }
            })
    }

    private fun applyDetail(detail: Pantau) {
        la = detail.la
        lo = detail.lo
        confStatus = detail.confStatus
        valueLatitude.text = la.toString()
        valueLongitude.text = lo.toString()
        emrDescView.text = detail.emrDesc?.takeIf { it.isNotBlank() } ?: getString(R.string.deskripsi_darurat)
        updateIndicator()
    }

    private fun scheduleDataRefresh() {
        handler.postDelayed(detailRefreshRunnable, refreshInterval)
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(confReceiver, IntentFilter(ACTION_CONF_CHANGED))
    }

    override fun onResume() {
        super.onResume()
        // muat detail segera untuk sinkronisasi awal
        loadDetail()
        scheduleDataRefresh()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(detailRefreshRunnable)
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(confReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
