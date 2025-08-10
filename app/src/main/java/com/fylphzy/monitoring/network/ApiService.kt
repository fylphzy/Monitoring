package com.fylphzy.monitoring.network

import com.fylphzy.monitoring.model.ApiResponse
import retrofit2.Call
import retrofit2.http.*
import retrofit2.http.GET
import retrofit2.http.Query

interface ApiService {

    @GET("read.php")
    fun getPantauList(): Call<ApiResponse>

    @GET("read.php")
    fun getPantauDetail(@Query("username") username: String): Call<ApiResponse>

    @FormUrlEncoded
    @POST("update.php")
    fun updateConfStatus(
        @Field("username") username: String,
        @Field("conf_status") confStatus: Int
    ): Call<Map<String, String>>
}
