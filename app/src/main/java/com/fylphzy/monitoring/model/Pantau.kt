package com.fylphzy.monitoring.model

import com.google.gson.annotations.SerializedName

data class Pantau(
    val id: Int,
    val username: String,
    val whatsapp: String,
    val la: Double,
    val lo: Double,
    val emr: Int,
    @SerializedName("emr_timestamp") val emrTimestamp: String,
    @SerializedName("conf_status") val confStatus: Int,
    @SerializedName("emr_desc") val emrDesc: String? = null
)
