package com.fylphzy.monitoring

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fylphzy.monitoring.adapter.PantauAdapter
import com.fylphzy.monitoring.model.ApiResponse
import com.fylphzy.monitoring.model.Pantau
import com.fylphzy.monitoring.network.RetrofitClient
import com.google.android.material.button.MaterialButton
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERM_REQ_NOTIF = 1001
        private const val TAG = "MainActivity"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PantauAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val refreshInterval = 3000L
    private var isLoading = false

    private val detailLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                loadPantauList()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PantauAdapter(this, emptyList())
        recyclerView.adapter = adapter

        val logoutBtn = findViewById<MaterialButton>(R.id.logoutBtn)
        logoutBtn.setOnClickListener {
            // clear saved notification set supaya tidak meninggalkan stale state (pakai KTX edit)
            getSharedPreferences("monitoring_service_prefs", MODE_PRIVATE).edit {
                remove("active_notifications_set")
            }

            moveTaskToBack(true)
            finishAffinity()
        }

        checkNotificationPermission()
        // ensureLocationPermissions()  <-- DIHAPUS karena lokasi tidak digunakan

        val svcIntent = Intent(this, MonitoringService::class.java)
        try {
            ContextCompat.startForegroundService(this, svcIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal memulai monitoring service: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "startForegroundService error", e)
        }

        loadPantauList()
        scheduleDataRefresh()
    }

    private fun checkNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERM_REQ_NOTIF)
            }
        }
    }

    // onRequestPermissionsResult hanya tangani PERM_REQ_NOTIF sekarang
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQ_NOTIF) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
            }
            return
        }
    }

    private fun loadPantauList() {
        if (isLoading) return
        isLoading = true
        RetrofitClient.instance.getPantauList().enqueue(object : Callback<ApiResponse> {
            override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                isLoading = false
                if (!response.isSuccessful) {
                    Toast.makeText(this@MainActivity, "Failed to load data (Server error ${response.code()})", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "response not successful code=${response.code()} body=${response.errorBody()?.string()}")
                    return
                }

                val body = response.body()
                Log.d(TAG, "loadPantauList body=$body")
                if (body == null) {
                    Toast.makeText(this@MainActivity, "Response invalid", Toast.LENGTH_SHORT).show()
                    return
                }

                // terima status "ok" atau "success"
                if (body.status != "success" && body.status != "ok") {
                    Toast.makeText(this@MainActivity, "${body.status}: tidak ada data", Toast.LENGTH_SHORT).show()
                    return
                }

                // Prioritaskan recycle (server-side filter emr==1 & within24)
                val listToShow: List<Pantau> = if (body.recycle.isNotEmpty()) {
                    body.recycle
                } else {
                    body.data.filter { it.emr == 1 }
                }

                Log.d(TAG, "loadPantauList: showing ${listToShow.size} items (recycle=${body.recycle.size})")
                adapter.updateData(listToShow)
            }

            override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
                isLoading = false
                Toast.makeText(this@MainActivity, "Failed to load data", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "loadPantauList failure", t)
            }
        })
    }

    private fun scheduleDataRefresh() {
        handler.postDelayed({
            loadPantauList()
            scheduleDataRefresh()
        }, refreshInterval)
    }

    fun openDetail(pantau: Pantau) {
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra("id", pantau.id)
            putExtra("username", pantau.username)
            putExtra("whatsapp", pantau.whatsapp)
            putExtra("la", pantau.la)
            putExtra("lo", pantau.lo)
            putExtra("conf_status", pantau.confStatus)
            putExtra("emr_desc", pantau.emrDesc)
        }
        detailLauncher.launch(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
