package github.rikacelery

import github.rikacelery.utils.bytesToHumanReadable
import github.rikacelery.utils.withRetry
import github.rikacelery.utils.withRetryOrNull
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cache.*
import io.ktor.client.plugins.cache.storage.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.ConnectionPool
import java.io.File
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

val _clients = List(2) {
    HttpClient(OkHttp) {
        expectSuccess = true
        install(HttpCache) {
            this.isShared = true
            this.publicStorage(
                FileStorage(File("./cache"))
            )
            this.privateStorage(
                FileStorage(File("./cache_private"))
            )
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 5_000
            requestTimeoutMillis = 50_000
        }
        install(DefaultRequest) {
            headers {
                append(
                    HttpHeaders.Accept,
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
                )
                append(HttpHeaders.AcceptLanguage, "en,zh-CN;q=0.9,zh;q=0.8")
                append(HttpHeaders.Connection, "keep-alive")
            }
        }
        engine {
            config {
                connectionPool(ConnectionPool(30, 60, TimeUnit.SECONDS))
                followSslRedirects(true)
                followRedirects(true)
            }
        }
    }

}
val client: HttpClient
    get() = _clients.random()
val proxiedClient = client
val logs = Hashtable<Long, String>()
val BUILTIN = setOf(
    "https://zh.xhamsterlive.com/lu_lisa",
    "https://zh.xhamsterlive.com/skinnyandcute",
    "https://zh.xhamsterlive.com/DanieleAida",
    "https://zh.xhamsterlive.com/sili_silva",
    "https://zh.xhamsterlive.com/LilyRyan",
    "https://zh.xhamsterlive.com/sua_hong",
    "https://zh.xhamsterlive.com/tiya_tin",
    "https://zh.xhamsterlive.com/ranran_ch",
    "https://zh.xhamsterlive.com/Yui-Ch",
    "https://zh.xhamsterlive.com/sayaka_xo",
    "https://zh.xhamsterlive.com/koreanmagic",
    "https://zh.xhamsterlive.com/Teentatalla",
    "https://zh.xhamsterlive.com/MillaBelle",
    "https://zh.xhamsterlive.com/lucille_evans",
    "https://zh.xhamsterlive.com/Cutie_Annie",
    "https://zh.xhamsterlive.com/sweety_tifanny",
    "https://zh.xhamsterlive.com/jelli_jenni",
    "https://zh.xhamsterlive.com/Teentatalla",
    "https://zh.xhamsterlive.com/Dakota_Blare",
    "https://zh.xhamsterlive.com/_BABBE_Hii",
    "https://zh.xhamsterlive.com/babyxgiirl",
    "https://zh.xhamsterlive.com/Britney_Major",
    "https://zh.xhamsterlive.com/wife170-",
    "https://zh.xhamsterlive.com/Berrysweet_68",
    "https://zh.xhamsterlive.com/xReix",
    "https://zh.xhamsterlive.com/Page_Lewis",
    "https://zh.xhamsterlive.com/NataIyaa",
    "https://zh.xhamsterlive.com/nyakotan",
    "https://zh.xhamsterlive.com/Sime_Naughty",
    "https://zh.xhamsterlive.com/LanaLee",
    "https://zh.xhamsterlive.com/ladyelegant",
    "https://zh.xhamsterlive.com/Lolly_Red",
    "https://zh.xhamsterlive.com/MiracleSandy",
    "https://zh.xhamsterlive.com/AlineDenls",
    "https://zh.xhamsterlive.com/Violamartini",
    "https://zh.xhamsterlive.com/xmayka",
    "https://zh.xhamsterlive.com/Mia-Tsuki-",
    "https://zh.xhamsterlive.com/Julia-Sweet",
    "https://zh.xhamsterlive.com/Asian2021",
    "https://zh.xhamsterlive.com/qxyqxy",
    "https://zh.xhamsterlive.com/ElleDiane",
    "https://zh.xhamsterlive.com/NuoMi-11",
    "https://zh.xhamsterlive.com/_Math_",
    "https://zh.xhamsterlive.com/makoto_mai",
    "https://zh.xhamsterlive.com/choi_ara",
    "https://zh.xhamsterlive.com/jane_sui",
    "https://zh.xhamsterlive.com/Julia-Sweet",
    "https://zh.xhamsterlive.com/saki-a-jp",
    "https://zh.xhamsterlive.com/LanaLee",
    "https://zh.xhamsterlive.com/Misa_Sakurai",
    "https://zh.xhamsterlive.com/Sunshine-YueYue",
    "https://zh.xhamsterlive.com/Bad_Barbies",
    "https://zh.xhamsterlive.com/creampussyy",
    "https://zh.xhamsterlive.com/Top_Skinny",
    "https://zh.xhamsterlive.com/seraphine23",
    "https://zh.xhamsterlive.com/Monica_Lane_",
    "https://zh.xhamsterlive.com/choi_ara",
)

suspend fun main() = supervisorScope {
    val logger = launch {
        while (isActive) {
            synchronized(logs) {
                print(buildString {
                    append("\r")
                    logs.toSortedMap().forEach { (t, u) ->
                        append("[$u], ")
                    }
                })
            }
            delay(1000)
        }
    }
    val rooms = channelFlow {
        val file = File("list.txt")
        if (!file.exists())
            file.writeText(BUILTIN.joinToString("\n"))
        for (line in file.bufferedReader().lines().filter { !it.startsWith("#") && it.isNotBlank() }) {
            send(line)
        }
    }.map { it: String ->
        async {
            println(it)
            val split = it.split("q:")
            val url = split[0].trim()
            val q = if (split.size > 1) split[1].trim() else "720p"
            val room = withRetryOrNull(5, { it.message?.contains("404") == true }) {
                proxiedClient.fetchRoomFromUrl(url, q)
            } ?: return@async null

            val writer = Writer(room.name)
            println(room)
            RoomRecorder(room, client).apply {
                onRecordingStarted = { room ->
                    writer.init()
                    synchronized(logs) {
                        if (!logs.containsKey(room.id)) {
                            println("\n+[${room.id}-https://zh.xhamsterlive.com/${room.name}]")
                        }
                    }
                }
                onRecordingStopped = { room ->
                    synchronized(logs) {
                        if (logs.containsKey(room.id)) {
                            println("\n-[${room.id}-https://zh.xhamsterlive.com/${room.name}]")
                        }
                        logs.remove(room.id)
                    }
                    writer.done()
                    println("\nfinished [${room.id}-https://zh.xhamsterlive.com/${room.name}]")

                }
                var counter = 0
                onLiveSegmentDownloaded = { bytes, total ->
                    writer.append(bytes)
                    logs[room.id] = room.name + " " + bytesToHumanReadable(total)+"-\\|/"[counter++]
                    if (counter==3) counter = 0
                }
            }
        }
    }.toList().awaitAll().filterNotNull()
    val jobList = rooms.map { recorder ->
        println("Start ${recorder.room.name}")
        recorder to launch {
            while (isActive) {
                if (recorder.isOpen())
                    recorder.start().join()
                delay(60_000)
            }
        }
    }.toList()
    println("-".repeat(10) + "DONE" + "-".repeat(10))

    readln()
    logger.cancel()
    jobList.map { (rec, job) ->
        job.cancel()
        launch { rec.stop() }
    }.joinAll()

    println("all done")
    _clients.forEach {
        it.close()
    }
}

