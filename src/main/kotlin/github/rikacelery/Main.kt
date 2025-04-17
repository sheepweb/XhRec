package github.rikacelery

import github.rikacelery.utils.withRetryOrNull
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import okhttp3.ConnectionPool
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

val _clients = List(5) {
    HttpClient(OkHttp) {
        expectSuccess = true
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
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
                connectionPool(ConnectionPool(10, 20, TimeUnit.SECONDS))
                followSslRedirects(true)
                followRedirects(true)
            }
        }
    }

}
val client: HttpClient
    get() = _clients.random()
val proxiedClient = client.config {
    engine {
//        proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(InetAddress.getByAddress(byteArrayOf(10,0,2,1)),7890))
    }
}

data class LogInfo(val name: String, val size: String, val progress: Pair<Int, Int> = 0 to 0, val latency: Long = 0)

val logs = Hashtable<Long, LogInfo>()
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
val mt = MyTerminal()

suspend fun main() = supervisorScope {
    val logger = launch {
        while (isActive) {
            synchronized(logs) {
                mt.status(logs.toSortedMap())
            }
            delay(100)
        }
    }
    val jobFile = File("list.conf")
    val regex = "(#)?(https://)".toRegex()
    val recorders = channelFlow {
        if (!jobFile.exists())
            jobFile.writeText(BUILTIN.joinToString("\n"))
        for (line in jobFile.bufferedReader().lines().filter { it.isNotBlank() }) {
            send(line)
        }
    }.map { it: String ->
        mt.println(it)
        async {
            val split = it.split("q:")
            val url = split[0].trim()
            val q = if (split.size > 1) split[1].trim() else "720p"
            val room = withRetryOrNull(5, { it.message?.contains("404") == true }) {
                proxiedClient.fetchRoomFromUrl(url, q)
            } ?: return@async null
            mt.println(room)
            RoomRecorder(room, client)
        }
    }.toList().awaitAll().filterNotNull().toMutableList()
    val jobList = recorders.map { recorder ->
        mt.println("Start ${recorder.room.name}")
        recorder to launch {
            while (isActive) {
                if (recorder.isOpen())
                    recorder.start().join()
                delay(60_000)
            }
        }
    }.toList().toMutableList()
    mt.println("-".repeat(10) + "DONE" + "-".repeat(10))

    val running = AtomicBoolean(true)
    // REPL 主线程
    while (running.get()) {
        val line = try {
            mt.readLine()
        } catch (e: Exception) {
            running.set(false)
            break
        }
        val tokens = line.split(" ")
        when (tokens.firstOrNull()) {
            "add" -> tokens.getOrNull(1)?.let {
                val split = it.split("q:")
                val url = split[0].trim()
                val q = if (split.size > 1) split[1].trim() else "720p"
                val room = withRetryOrNull(5, { it.message?.contains("404") == true }) {
                    proxiedClient.fetchRoomFromUrl(url, q)
                } ?: return@let null
                mt.println(room)
                val recorder = RoomRecorder(room, client)
                recorders.add(recorder)
                mt.println("Start ${recorder.room.name}")
                jobList.add(
                    recorder to launch {
                        while (isActive) {
                            if (recorder.isOpen())
                                recorder.start().join()
                            delay(60_000)
                        }
                    })
                jobFile.appendText("\n$it")
            }

            "remove" -> tokens.getOrNull(1)?.let { input ->
                val job =
                    jobList.find { it.first.room.id.toString() == input || it.first.room.name == input } ?: return@let
                job.second.cancel()
                job.first.stop()
                mt.println("[${job.first.room.name}] removed")
                jobFile.writeText(jobFile.readText().lines().filterNot { it.contains(input) }.joinToString("\n"))
            }

            "stop" -> tokens.getOrNull(1)?.let { input ->
                val job =
                    jobList.find { it.first.room.id.toString() == input || it.first.room.name == input } ?: return@let
                job.second.cancel()
                job.first.stop()
                mt.println("[${job.first.room.name}] stopped")
            }

            "start" -> tokens.getOrNull(1)?.let { input ->
                val recorder =
                    recorders.find { it.room.id.toString() == input || it.room.name == input } ?: return@let
                jobList.add(
                    recorder to launch {
                        while (isActive) {
                            if (recorder.isOpen())
                                recorder.start().join()
                            delay(60_000)
                        }
                    })
                mt.println("[${recorder.room.name}] started")

            }

            "fatal" -> {
                exitProcess(1)
            }

            "list" -> {
                jobList.forEach { (recorder, job) ->
                    mt.println(recorder.room, if (job.isActive) "active" else "idle")
                }
            }

            "active" -> tokens.getOrNull(1)?.let { TaskManager.activateTask(it) }
            "deactive" -> tokens.getOrNull(1)?.let { TaskManager.deactivateTask(it) }
            "exit" -> running.set(false)
            else -> {
                mt.println("Unknown command. Try: add/remove/pause/resume/active/deactive/list/exit")
            }
        }
    }
    jobList.map { (rec, job) ->
        job.cancel()
        launch { rec.stop() }
    }.joinAll()

    logger.cancel()
    mt.println("all done")
    proxiedClient.close()
    _clients.forEach {
        it.close()
    }
    mt.terminal.close()
}

