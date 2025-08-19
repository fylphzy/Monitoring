package com.fylphzy.monitoring.model

data class ApiResponse(
    val status: String,
    val data: List<Pantau> = emptyList(),
    val recycle: List<Pantau> = emptyList(),
    val notify: List<Pantau> = emptyList()
)
