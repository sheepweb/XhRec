package github.rikacelery.utils

import github.rikacelery.Room
import github.rikacelery.rootLogger
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

suspend fun HttpClient.fetchContentLength(segmentUrl: String): Long {
    val resp = head(segmentUrl)
    return resp.headers[HttpHeaders.ContentLength]!!.toLong()
}


suspend fun HttpClient.fetchRoomFromUrl(url: String, quality: String): Room {
    try {
        val roomHash = url.substringBefore("#").substringAfterLast("/").substringBefore("?")
        val str = get("https://xhamsterlive.com/api/front/v1/broadcasts/$roomHash").bodyAsText()
        val j = Json.Default.parseToJsonElement(str)
        return Room(j.PathSingle("item.username").asString(), j.PathSingle("item.modelId").asLong(), quality)
    } catch (e: Exception) {
        rootLogger.error("failed to get room info", e)
        throw e
    }
}
