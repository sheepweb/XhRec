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
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

data class Room(val name: String, val id: Long, var quality: String)

suspend fun HttpClient.fetchRoomFromUrl(url: String, quality: String): Room {
    val roomHash = url.substringBefore("#").substringAfterLast("/").substringBefore("?")
    val html = Jsoup.parse(get(url).bodyAsText())
//    "window.__PRELOADED_STATE__ = ()"
    val json = Json.Default.parseToJsonElement(
        html.select("script").first { it.data().contains("window.__PRELOADED_STATE__ = ") }.data()
            .removePrefix("window.__PRELOADED_STATE__ = ")
    )
    val roomId = json.PathSingle("viewCam.model.id").asLong()
//    println(json.toString())
//    System.exit(0)
    return Room(roomHash, roomId, quality)
}

