package me.hztcm.mindisle.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val code: Int = 0,
    val message: String = "OK",
    val data: T? = null
)
