package me.hztcm.mindisle.task.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.hztcm.mindisle.common.toIsoOffsetUtc
import me.hztcm.mindisle.common.utcNow
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.UiTaskStatus
import me.hztcm.mindisle.db.UiTaskType
import me.hztcm.mindisle.db.UiTasksTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.UiTaskItem
import me.hztcm.mindisle.model.UiTaskListResponse
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class UiTaskService {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listPending(userId: Long): UiTaskListResponse {
        return DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val rows = UiTasksTable.selectAll().where {
                (UiTasksTable.userId eq userRef) and (UiTasksTable.status eq UiTaskStatus.PENDING)
            }.orderBy(UiTasksTable.createdAt, SortOrder.DESC).limit(50).toList()
            UiTaskListResponse(items = rows.map { it.toItem() })
        }
    }

    suspend fun create(
        userId: Long,
        type: UiTaskType,
        title: String,
        payload: Map<String, String> = emptyMap(),
        source: String = "SYSTEM"
    ): Long {
        val now = utcNow()
        return DatabaseFactory.dbQuery {
            UiTasksTable.insert {
                it[UiTasksTable.userId] = EntityID(userId, UsersTable)
                it[taskType] = type
                it[UiTasksTable.title] = title
                it[status] = UiTaskStatus.PENDING
                it[payloadJson] = json.encodeToString(payload)
                it[taskSource] = source
                it[createdAt] = now
                it[updatedAt] = now
            }[UiTasksTable.id].value
        }
    }

    suspend fun markDone(userId: Long, taskId: Long) {
        DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            UiTasksTable.update({
                (UiTasksTable.id eq taskId) and (UiTasksTable.userId eq userRef)
            }) {
                it[status] = UiTaskStatus.DONE
                it[updatedAt] = utcNow()
            }
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toItem(): UiTaskItem {
        val payload = runCatching {
            json.decodeFromString<Map<String, String>>(this[UiTasksTable.payloadJson] ?: "{}")
        }.getOrDefault(emptyMap())
        return UiTaskItem(
            taskId = this[UiTasksTable.id].value,
            taskType = this[UiTasksTable.taskType].name,
            title = this[UiTasksTable.title],
            status = this[UiTasksTable.status].name,
            payload = payload,
            dueAt = this[UiTasksTable.dueAt]?.toIsoOffsetUtc(),
            createdAt = this[UiTasksTable.createdAt].toIsoOffsetUtc()
        )
    }
}
