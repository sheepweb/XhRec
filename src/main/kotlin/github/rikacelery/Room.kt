package github.rikacelery

import github.rikacelery.utils.PathSingle
import github.rikacelery.utils.asLong
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

@Serializable
data class Room(val name: String, val id: Long, var quality: String, val lastSeen: String? = null)

suspend fun HttpClient.fetchRoomFromUrl(url: String, quality: String): Room {
    val roomHash = url.substringBefore("#").substringAfterLast("/").substringBefore("?")
    val str = proxiedClient.get("https://zh.xhamsterlive.com/api/front/v1/broadcasts/$roomHash").bodyAsText()
    val j = Json.Default.parseToJsonElement(str)
    return Room(j.PathSingle("item.username").jsonPrimitive.content, j.PathSingle("item.modelId").asLong(), quality)

    val html = Jsoup.parse(get(url).bodyAsText())
    val json = Json.Default.parseToJsonElement(
        html.select("script").first { it.data().contains("window.__PRELOADED_STATE__ = ") }.data()
            .removePrefix("window.__PRELOADED_STATE__ = ")
    )
    val roomId = json.PathSingle("viewCam.model.id").asLong()
    return Room(roomHash, roomId, quality)
}

