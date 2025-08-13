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
import com.fylphzy.monitoring.network.RetrofitClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class DetailActivity : AppCompatActivity() {

    private lateinit var userText: TextView
    private lateinit var valueLatitude: TextView
    private lateinit var valueLongitude: TextView
    private lateinit var indicatorKonfirmasi: TextView
    private lateinit var lokasiBtn: TextView
    private lateinit var waBtn: TextView
    private lateinit var confirmBtn: TextView
    private lateinit var cancelConfirm: TextView
    private lateinit var backBtn: TextView

    private var id: Int = 0
    private var username: String = ""
    private var whatsapp: String = ""
    private var la: Double = 0.0
    private var lo: Double = 0.0
    private var confStatus: Int = 0

    private val handler = Handler(Looper.getMainLooper())
    private val refreshInterval = 30000L
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        id = intent.getIntExtra("id", 0)
        username = intent.getStringExtra("username") ?: ""
        whatsapp = intent.getStringExtra("whatsapp") ?: ""
        la = intent.getDoubleExtra("la", 0.0)
        lo = intent.getDoubleExtra("lo", 0.0)
        confStatus = intent.getIntExtra("conf_status", 0)

        userText = findViewById(R.id.userText)
        valueLatitude = findViewById(R.id.valueLatitude)
        valueLongitude = findViewById(R.id.valueLongitude)
        indicatorKonfirmasi = findViewById(R.id.indicatorKonfirmasi)
        lokasiBtn = findViewById(R.id.lokasi)
        waBtn = findViewById(R.id.wa)
        confirmBtn = findViewById(R.id.confirmBtn)
        cancelConfirm = findViewById(R.id.cancelconfirm)
        backBtn = findViewById(R.id.backBtn)

        userText.text = username
        valueLatitude.text = la.toString()
        valueLongitude.text = lo.toString()
        updateIndicator()

        backBtn.setOnClickListener { finish() }

        lokasiBtn.setOnClickListener {
            val uri = "https://kir.my.id/trc/".toUri()
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }

        waBtn.setOnClickListener {
            val uri = "https://wa.me/$whatsapp".toUri()
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }

        confirmBtn.setOnClickListener {
            updateConfirmation(1)
        }

        cancelConfirm.setOnClickListener {
            updateConfirmation(0)
        }
    }

    private fun updateIndicator() {
        indicatorKonfirmasi.isSelected = (confStatus == 1)
    }

    private fun updateConfirmation(status: Int) {
        RetrofitClient.instance.updateConfStatus(username, status)
            .enqueue(object : Callback<Map<String, String>> {
                override fun onResponse(
                    call: Call<Map<String, String>>,
                    response: Response<Map<String, String>>
                ) {
                    if (response.isSuccessful) {
                        confStatus = status
                        updateIndicator()
                        setResult(RESULT_OK)
                        val msg = response.body()?.get("message") ?: "Status diperbarui"
                        Toast.makeText(this@DetailActivity, msg, Toast.LENGTH_SHORT).show()

                        if (status == 1) {
                            val manager = getSystemService(NotificationManager::class.java)
                            manager.cancel(id)
                        }
                    } else {
                        Toast.makeText(
                            this@DetailActivity,
                            "Gagal memperbarui status (server)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(call: Call<Map<String, String>>, t: Throwable) {
                    Toast.makeText(
                        this@DetailActivity,
                        "Gagal koneksi ke server",
                        Toast.LENGTH_SHORT
                    ).show()
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
                    if (response.isSuccessful) {
                        val detail = response.body()?.data?.firstOrNull()
                        if (detail != null) {
                            la = detail.la
                            lo = detail.lo
                            confStatus = detail.confStatus
                            valueLatitude.text = la.toString()
                            valueLongitude.text = lo.toString()
                            updateIndicator()
                        }
                    }
                }

                override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
                    isLoading = false
                }
            })
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
