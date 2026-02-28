package me.hztcm.mindisle.user.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import me.hztcm.mindisle.DEBUG
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.model.ApiResponse
import me.hztcm.mindisle.model.DeleteAccountDebugRequest
import me.hztcm.mindisle.model.UpsertBasicProfileRequest
import me.hztcm.mindisle.model.UpsertProfileRequest
import me.hztcm.mindisle.security.UserPrincipal
import me.hztcm.mindisle.user.service.UserManagementService
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.io.readByteArray

private const val AVATAR_FILE_FIELD = "file"
private const val AVATAR_MAX_UPLOAD_BYTES = 5 * 1024 * 1024
private const val AVATAR_CACHE_CONTROL = "private, max-age=300"

fun Route.registerUserRoutes(service: UserManagementService) {
    authenticate("auth-jwt") {
        route("/users/me") {
            get {
                val data = service.getProfile(call.requireUserId())
                call.respond(ApiResponse(data = data))
            }

            put("/profile") {
                val request = call.receive<UpsertProfileRequest>()
                val data = service.upsertProfile(call.requireUserId(), request)
                call.respond(ApiResponse(data = data))
            }

            get("/basic-profile") {
                val data = service.getBasicProfile(call.requireUserId())
                call.respond(ApiResponse(data = data))
            }

            put("/basic-profile") {
                val request = call.receive<UpsertBasicProfileRequest>()
                val data = service.upsertBasicProfile(call.requireUserId(), request)
                call.respond(ApiResponse(data = data))
            }

            put("/avatar") {
                val userId = call.requireUserId()
                val multipart = call.receiveMultipart()
                var bytes: ByteArray? = null

                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            if (bytes == null && part.name == AVATAR_FILE_FIELD) {
                                bytes = readPartBytesLimited(part, AVATAR_MAX_UPLOAD_BYTES)
                            }
                        }

                        else -> Unit
                    }
                    part.dispose()
                }

                val fileBytes = bytes ?: throw AppException(
                    code = ErrorCodes.INVALID_REQUEST,
                    message = "Multipart field '$AVATAR_FILE_FIELD' is required",
                    status = HttpStatusCode.BadRequest
                )
                val data = service.upsertAvatar(userId, fileBytes)
                call.respond(ApiResponse(data = data))
            }

            get("/avatar") {
                val userId = call.requireUserId()
                val avatar = service.getAvatarBinary(userId)
                val etag = "\"${avatar.sha256}\""
                val lastModified = avatar.updatedAt
                    .atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME)

                call.response.header(HttpHeaders.ETag, etag)
                call.response.header(HttpHeaders.CacheControl, AVATAR_CACHE_CONTROL)
                call.response.header(HttpHeaders.LastModified, lastModified)

                if (matchesIfNoneMatch(call.request.headers[HttpHeaders.IfNoneMatch], etag)) {
                    call.response.status(HttpStatusCode.NotModified)
                    return@get
                }

                call.respondBytes(
                    bytes = avatar.bytes,
                    contentType = ContentType.parse(avatar.contentType),
                    status = HttpStatusCode.OK
                )
            }
        }
    }

    if (DEBUG) {
        route("/users") {
            delete {
                val request = call.receive<DeleteAccountDebugRequest>()
                service.deleteAccountByPhoneForDebug(request.phone)
                call.respond(ApiResponse<Unit>(message = "Account deleted"))
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.requireUserId(): Long {
    return principal<UserPrincipal>()?.userId ?: throw AppException(
        code = ErrorCodes.UNAUTHORIZED,
        message = "Unauthorized",
        status = HttpStatusCode.Unauthorized
    )
}

private suspend fun readPartBytesLimited(part: PartData.FileItem, maxBytes: Int): ByteArray {
    val bytes = part.provider().readRemaining(maxBytes.toLong() + 1).readByteArray()
    if (bytes.size > maxBytes) {
        throw AppException(
            code = ErrorCodes.INVALID_REQUEST,
            message = "Avatar file exceeds $maxBytes bytes",
            status = HttpStatusCode.BadRequest
        )
    }
    return bytes
}

private fun matchesIfNoneMatch(headerValue: String?, currentEtag: String): Boolean {
    if (headerValue == null) {
        return false
    }
    val currentBare = currentEtag.removePrefix("\"").removeSuffix("\"")
    return headerValue
        .split(',')
        .map { it.trim() }
        .any { tag ->
            val normalized = tag.removePrefix("W/").trim()
            normalized == "*" || normalized == currentEtag || normalized == currentBare
        }
}
