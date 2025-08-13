package com.fylphzy.monitoring

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
        private const val LOCATION_PERMS_CODE = 2001
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PantauAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val refreshInterval = 5000L
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
            moveTaskToBack(true)
            finishAffinity()
        }

        checkNotificationPermission()
        ensureLocationPermissions()

        val svcIntent = Intent(this, MonitoringService::class.java)
        try {
            ContextCompat.startForegroundService(this, svcIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal memulai monitoring service: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
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

    private fun ensureLocationPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), LOCATION_PERMS_CODE)
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), LOCATION_PERMS_CODE)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQ_NOTIF) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode == LOCATION_PERMS_CODE) {
            val fineIdx = permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)
            val coarseIdx = permissions.indexOf(Manifest.permission.ACCESS_COARSE_LOCATION)
            val fineGranted = if (fineIdx >= 0) grantResults.getOrNull(fineIdx) == PackageManager.PERMISSION_GRANTED else true
            val coarseGranted = if (coarseIdx >= 0) grantResults.getOrNull(coarseIdx) == PackageManager.PERMISSION_GRANTED else true

            if (!fineGranted && !coarseGranted) {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadPantauList() {
        if (isLoading) return
        isLoading = true
        RetrofitClient.instance.getPantauList().enqueue(object : Callback<ApiResponse> {
            override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                isLoading = false
                if (response.isSuccessful) {
                    val allUsers = response.body()?.data ?: emptyList()
                    val emergencyUsers = allUsers.filter { it.emr == 1 }
                    adapter.updateData(emergencyUsers)
                } else {
                    Toast.makeText(this@MainActivity, "Failed to load data (Server error)", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
                isLoading = false
                Toast.makeText(this@MainActivity, "Failed to load data", Toast.LENGTH_SHORT).show()
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
