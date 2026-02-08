package github.rikacelery.utils

import github.rikacelery.Room
import github.rikacelery.rootLogger
import github.rikacelery.v2.exceptions.DeletedException
import github.rikacelery.v2.exceptions.RenameException
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val MODEL_RENAME_REGEX = "Model has new name: newName=(.*)".toRegex()

suspend fun HttpClient.fetchContentLength(segmentUrl: String): Long {
    val resp = head(segmentUrl)
    return resp.headers[HttpHeaders.ContentLength]!!.toLong()
}

/**
 * 处理 /api/front/v1/broadcasts API 的错误响应
 * @param roomName 房间名称（用于日志和异常）
 * @param description API 返回的错误描述
 * @throws RenameException 主播改名
 * @throws DeletedException 主播被删除或不存在
 */
fun handleBroadcastApiError(roomName: String, description: String?) {
    when {
        description == null -> {
            // 无法解析错误描述，忽略
        }
        MODEL_RENAME_REGEX.matches(description) -> {
            val newName = MODEL_RENAME_REGEX.find(description)!!.groupValues[1]
            rootLogger.info("[{}] model renamed to {}", roomName, newName)
            throw RenameException(newName)
        }
        description == "model already deleted" -> {
            rootLogger.info("[{}] model already deleted", roomName)
            throw DeletedException(roomName)
        }
        description.contains("not found", ignoreCase = true) -> {
            rootLogger.info("[{}] model not found: {}", roomName, description)
            throw DeletedException(roomName)
        }
    }
}

/**
 * 检查 API 响应是否是错误格式 {"title": "...", "description": "..."}
 * @param element JSON 响应
 * @param roomName 房间名称（用于日志和异常）
 * @throws RenameException 主播改名
 * @throws DeletedException 主播被删除或不存在
 * @return 如果是错误响应返回 description，否则返回 null
 */
fun checkBroadcastApiErrorResponse(element: JsonElement, roomName: String): String? {
    if (element.jsonObject.containsKey("title") && element.jsonObject.containsKey("description")) {
        val description = element.jsonObject["description"]?.jsonPrimitive?.content
        handleBroadcastApiError(roomName, description)
        return description
    }
    return null
}

suspend fun HttpClient.fetchRoomFromUrl(url: String, quality: String): Room {
    val roomHash = url.substringBefore("#").substringAfterLast("/").substringBefore("?")
    try {
        val response = get("https://xhamsterlive.com/api/front/v1/broadcasts/$roomHash") {
            expectSuccess = false
        }

        if (response.status == HttpStatusCode.NotFound) {
            val body = response.bodyAsText()
            val reason = runCatching {
                Json.Default.parseToJsonElement(body).String("description")
            }.getOrNull()

            handleBroadcastApiError(roomHash, reason)

            // 如果 handleBroadcastApiError 没有抛出异常，说明是未知的 404 错误
            rootLogger.error("[{}] failed to get room info: 404 - {}", roomHash, reason ?: body)
            throw ClientRequestException(response, body)
        }

        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            rootLogger.error("[{}] failed to get room info: {} - {}", roomHash, response.status, body)
            throw ClientRequestException(response, body)
        }

        val str = response.bodyAsText()
        val j = Json.Default.parseToJsonElement(str)

        // 检查是否是错误响应格式
        checkBroadcastApiErrorResponse(j, roomHash)

        return Room(j.PathSingle("item.username").asString(), j.PathSingle("item.modelId").asLong(), quality)
    } catch (e: DeletedException) {
        throw e
    } catch (e: RenameException) {
        // 递归调用获取新名称的房间信息
        return fetchRoomFromUrl("https://xhamsterlive.com/${e.newName}", quality)
    } catch (e: Exception) {
        rootLogger.error("[{}] failed to get room info", roomHash, e)
        throw e
    }
}
