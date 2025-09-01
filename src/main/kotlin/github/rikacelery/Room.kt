package github.rikacelery

import github.rikacelery.utils.PathSingle
import github.rikacelery.utils.asLong
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration

@Serializable
data class Room(
    val name: String, val id: Long,
    //config
    var quality: String,
    //config
    var limit: Duration = Duration.INFINITE,
    val lastSeen: String? = null
)

suspend fun HttpClient.fetchRoomFromUrl(url: String, quality: String): Room {
    val roomHash = url.substringBefore("#").substringAfterLast("/").substringBefore("?")
    val str = get("https://xhamsterlive.com/api/front/v1/broadcasts/$roomHash").bodyAsText()
    val j = Json.Default.parseToJsonElement(str)
    return Room(j.PathSingle("item.username").jsonPrimitive.content, j.PathSingle("item.modelId").asLong(), quality)
}

