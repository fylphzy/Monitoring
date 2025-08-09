package com.fylphzy.monitoring.network

import com.fylphzy.monitoring.model.ApiResponse
import retrofit2.Call
import retrofit2.http.*

interface ApiService {

    // Ambil semua user dengan status emergency
    @GET("read.php")
    fun getPantauList(): Call<ApiResponse>

    // Update status konfirmasi
    // sebelumnya: fun updateConfStatus(...): Call<Void>
    @FormUrlEncoded
    @POST("update.php")
    fun updateConfStatus(
        @Field("username") username: String,
        @Field("conf_status") confStatus: Int
    ): Call<Map<String, String>>

}
