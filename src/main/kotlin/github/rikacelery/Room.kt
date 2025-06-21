package github.rikacelery

import github.rikacelery.utils.Json
import github.rikacelery.utils.PathSingle
import github.rikacelery.utils.asLong
import io.github.nomisrev.JsonPath
import io.github.nomisrev.get
import io.github.nomisrev.select
import io.github.nomisrev.selectEvery
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.io.File
@Serializable
data class Room(val name: String, val id: Long, var quality: String, val lastSeen: String? = null)

suspend fun HttpClient.fetchRoomFromUrl(url: String, quality: String): Room {
    val roomHash = url.substringBefore("#").substringAfterLast("/").substringBefore("?")
    val html = Jsoup.parse(get(url).bodyAsText())
    val json = Json.Default.parseToJsonElement(
        html.select("script").first { it.data().contains("window.__PRELOADED_STATE__ = ") }.data()
            .removePrefix("window.__PRELOADED_STATE__ = ")
    )
    val roomId = json.PathSingle("viewCam.model.id").asLong()
//    File("${roomHash}.txt").writeText(json.toString())
//    println(json.toString())
//    System.exit(0)
    return Room(roomHash, roomId, quality)
}

