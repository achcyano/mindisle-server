package me.hztcm.mindisle.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class UserEventItem(
    val eventName: String,
    val eventType: String,
    val dueAt: String,
    val persistent: Boolean = true,
    val payload: JsonObject
)

@Serializable
data class UserEventListResponse(
    val generatedAt: String,
    val items: List<UserEventItem>
)
