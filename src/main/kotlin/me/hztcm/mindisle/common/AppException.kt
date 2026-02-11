package me.hztcm.mindisle.common

import io.ktor.http.HttpStatusCode

class AppException(
    val code: Int,
    override val message: String,
    val status: HttpStatusCode
) : RuntimeException(message)
