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

suspend fun HttpClient.fetchContentLength(segmentUrl: String): Long {
    val resp = head(segmentUrl)
    return resp.headers[HttpHeaders.ContentLength]!!.toLong()
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

            when {
                reason?.matches("Model has new name: newName=(.*)".toRegex()) == true -> {
                    val newName = "Model has new name: newName=(.*)".toRegex().find(reason)!!.groupValues[1]
                    rootLogger.info("[{}] model renamed to {}, auto-updating", roomHash, newName)
                    // 递归调用获取新名称的房间信息
                    return fetchRoomFromUrl("https://xhamsterlive.com/$newName", quality)
                }
                reason == "model already deleted" -> {
                    rootLogger.warn("[{}] model already deleted", roomHash)
                    throw DeletedException(roomHash)
                }
                reason?.contains("Entity") == true && reason.contains("not found") -> {
                    rootLogger.warn("[{}] model not found: {}", roomHash, reason)
                    throw DeletedException(roomHash)
                }
                else -> {
                    rootLogger.error("[{}] failed to get room info: 404 - {}", roomHash, reason ?: body)
                    throw ClientRequestException(response, body)
                }
            }
        }

        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            rootLogger.error("[{}] failed to get room info: {} - {}", roomHash, response.status, body)
            throw ClientRequestException(response, body)
        }

        val str = response.bodyAsText()
        val j = Json.Default.parseToJsonElement(str)
        return Room(j.PathSingle("item.username").asString(), j.PathSingle("item.modelId").asLong(), quality)
    } catch (e: RenameException) {
        throw e
    } catch (e: DeletedException) {
        throw e
    } catch (e: Exception) {
        rootLogger.error("[{}] failed to get room info", roomHash, e)
        throw e
    }
}
