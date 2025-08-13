package com.fylphzy.monitoring

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.fylphzy.monitoring.model.ApiResponse
import com.fylphzy.monitoring.model.Pantau
import com.fylphzy.monitoring.network.RetrofitClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class DetailActivity : AppCompatActivity() {

    companion object {
        // samakan dengan MonitoringService.NOTIF_ID_BASE
        private const val NOTIF_ID_BASE = 10_000
    }

    private lateinit var userText: TextView
    private lateinit var valueLatitude: TextView
    private lateinit var valueLongitude: TextView
    private lateinit var indicatorKonfirmasi: TextView
    private lateinit var lokasiBtn: TextView
    private lateinit var waBtn: TextView
    private lateinit var confirmBtn: TextView
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
    private val refreshInterval = 30_000L
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        // ambil intent
        id = intent.getIntExtra("id", 0)
        username = intent.getStringExtra("username") ?: ""
        whatsapp = intent.getStringExtra("whatsapp") ?: ""
        la = intent.getDoubleExtra("la", 0.0)
        lo = intent.getDoubleExtra("lo", 0.0)
        confStatus = intent.getIntExtra("conf_status", 0)
        val emrDescIntent = intent.getStringExtra("emr_desc")

        // view binding via findViewById (sesuaikan id dengan layout Anda)
        userText = findViewById(R.id.userText)
        valueLatitude = findViewById(R.id.valueLatitude)
        valueLongitude = findViewById(R.id.valueLongitude)
        indicatorKonfirmasi = findViewById(R.id.indicatorKonfirmasi)
        lokasiBtn = findViewById(R.id.lokasi)
        waBtn = findViewById(R.id.wa)
        confirmBtn = findViewById(R.id.confirmBtn)
        cancelConfirm = findViewById(R.id.cancelconfirm)
        backBtn = findViewById(R.id.backBtn)
        emrDescView = findViewById(R.id.emr_description) // pastikan id ini ada

        // isi awal
        userText.text = username
        valueLatitude.text = la.toString()
        valueLongitude.text = lo.toString()
        emrDescView.text = emrDescIntent?.takeIf { it.isNotBlank() } ?: getString(R.string.deskripsi_darurat)
        updateIndicator()

        backBtn.setOnClickListener { finish() }

        lokasiBtn.setOnClickListener {
            // jika Anda punya URL lokasi dinamis, ganti string di bawah
            val uri = "https://kir.my.id/trc/".toUri()
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }

        waBtn.setOnClickListener {
            val uri = "https://wa.me/$whatsapp".toUri()
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }

        confirmBtn.setOnClickListener { updateConfirmation(1) }
        cancelConfirm.setOnClickListener { updateConfirmation(0) }
    }

    private fun updateIndicator() {
        indicatorKonfirmasi.isSelected = (confStatus == 1)
    }

    private fun updateConfirmation(status: Int) {
        RetrofitClient.instance.updateConfStatus(username, status)
            .enqueue(object : Callback<Map<String, String>> {
                override fun onResponse(call: Call<Map<String, String>>, response: Response<Map<String, String>>) {
                    if (response.isSuccessful) {
                        confStatus = status
                        updateIndicator()
                        setResult(RESULT_OK)
                        val msg = response.body()?.get("message") ?: "Status diperbarui"
                        Toast.makeText(this@DetailActivity, msg, Toast.LENGTH_SHORT).show()

                        if (status == 1) {
                            // batalkan notifikasi yang relevan
                            val manager = getSystemService(NotificationManager::class.java)
                            manager?.cancel(NOTIF_ID_BASE + id)
                        }
                    } else {
                        Toast.makeText(this@DetailActivity, "Gagal memperbarui status (server)", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: Call<Map<String, String>>, t: Throwable) {
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
                    val detail = response.body()?.data?.firstOrNull()
                    if (detail != null) applyDetail(detail)
                }

                override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
                    isLoading = false
                }
            })
    }

    private fun applyDetail(detail: Pantau) {
        la = detail.la
        lo = detail.lo
        confStatus = detail.confStatus
        valueLatitude.text = la.toString()
        valueLongitude.text = lo.toString()
        // tampilkan emr_desc jika tersedia
        val desc = try {
            // model mungkin sudah punya emrDesc; gunakan reflection-safe access
            detail.javaClass.getDeclaredField("emrDesc").let { f ->
                f.isAccessible = true
                val v = f.get(detail) as? String
                v
            }
        } catch (_: Exception) {
            // fallback jika field tidak ada atau akses gagal
            null
        }
        emrDescView.text = desc?.takeIf { it.isNotBlank() } ?: getString(R.string.deskripsi_darurat)
        updateIndicator()
    }

    private fun scheduleDataRefresh() {
        handler.postDelayed({
            loadDetail()
            scheduleDataRefresh()
        }, refreshInterval)
    }

    override fun onResume() {
        super.onResume()
        scheduleDataRefresh()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }
}
