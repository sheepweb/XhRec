package github.rikacelery

import github.rikacelery.utils.withRetryOrNull
import github.rikacelery.v2.Decryptor
import github.rikacelery.v2.Metric
import github.rikacelery.v2.Scheduler
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.internal.synchronized
import kotlinx.serialization.Serializable
import okhttp3.ConnectionPool
import org.apache.commons.cli.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.max


val _clients = List(5) {
    HttpClient(OkHttp) {
        expectSuccess = true
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
        install(HttpRequestRetry) {
            retryOnException(maxRetries = 3, retryOnTimeout = true)
            constantDelay(300)
        }
        engine {
            config {
                connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))
                followSslRedirects(true)
                followRedirects(true)
            }
        }
    }
}
val client: HttpClient
    get() = _clients.random()
val proxiedClient = HttpClient(OkHttp) {
    expectSuccess = true
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
    install(HttpRequestRetry) {
        retryOnException(maxRetries = 3, retryOnTimeout = true)
        constantDelay(300)
    }
    engine {
        val proxyEnv = System.getenv("http_proxy") ?: System.getenv("HTTP_PROXY")
        if (proxyEnv != null) {
            val url = Url(proxyEnv)
            proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(url.host, url.port))
        }
        config {
            connectionPool(ConnectionPool(15, 10, TimeUnit.MINUTES))
            followSslRedirects(true)
            followRedirects(true)
        }
    }
}

@Serializable
data class LogInfo(
    val name: String,
    val msg: String = "",
    val size: String = "",
    val progress: Triple<Int, Int, Int> = Triple(0, 0, 0),
    val latency: Long = 0
)

val logs = Hashtable<Long, LogInfo>()
val BUILTIN = setOf(
    "#https://zh.xhamsterlive.com/lu_lisa",
)

fun extract(text: String, regex: Regex, default: String): String {
    return regex.find(text)?.groupValues?.get(1)?.ifBlank { default } ?: default
}

private suspend fun HttpClient.testFast(urls: List<String>): String? {
    urls.forEach { if (runCatching { head(it) }.getOrNull() != null) return it }
    return null
}

@OptIn(InternalCoroutinesApi::class)
suspend fun main(vararg args: String) = supervisorScope {


    val parser: CommandLineParser = DefaultParser()
    val options: Options = Options()
    options.addOption("f", "file", true, "Room List File")
    options.addOption("o", "output", true, "Output Dir")
    options.addOption("t", "tmp", true, "Temp Dir")
//    options.addOption("s", "server", false, "Server Mode")
    options.addOption("p", "port", true, "Server Port [default:8090]")

    val commandLine: CommandLine = try {
        parser.parse(options, args)
    } catch (e: ParseException) {
        val formatter = HelpFormatter()
        formatter.printHelp("CommandLineParameters", options)
        return@supervisorScope
    }
    if (commandLine.hasOption("h")) {
        val formatter = HelpFormatter()
        formatter.printHelp("CommandLineParameters", options)
    }

    val jobFile = File(commandLine.getOptionValue("f", "list.conf"))
    val regex = "([#;])? *(https://(?:zh.)?xhamsterlive.com/\\S+)(?: (.+))?".toRegex()
    val rooms = channelFlow {
        if (!jobFile.exists())
            jobFile.writeText(BUILTIN.joinToString("\n"))
        for (line in jobFile.bufferedReader().lines().filter { it.isNotBlank() }) {
            send(line)
        }
    }.map { it: String ->
        val match = regex.find(it) ?: return@map null
        val active = match.groupValues[1].isBlank()
        val url = match.groupValues[2]
        val q = extract(match.groupValues[3], "q:(\\S+)".toRegex(), "720p")
        println("${if (active) "[+]" else "[X]"} $q $url")
        async {
            val room = withRetryOrNull(5, { it.message?.contains("404") == true }) {
                proxiedClient.fetchRoomFromUrl(url, q)
            } ?: run {
                println("failed " + url)
                return@async null
            }
            println(room)
            room to active
        }
    }.toList().filterNotNull().awaitAll().filterNotNull().toMutableList()

    val scheduler =
        Scheduler(commandLine.getOptionValue("o", "out"), commandLine.getOptionValue("t", "tmp")) { scheduler ->
            synchronized(jobFile) {
                jobFile.writeText(scheduler.sessions.keys.joinToString("\n") {
                    "${if (it.listen) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
                })
            }
        }
    rooms.forEach {
        File("/screenshot/${it.first.name}").mkdir()
        scheduler.add(it.first, it.second)
    }
    println("-".repeat(10) + "DONE" + "-".repeat(10))
    scheduler.start(false)

    // 开启截图协程
//fixme: 修复截图功能

//    val sc = launch(Dispatchers.IO + CoroutineExceptionHandler { coroutineContext, throwable ->
//        println(throwable.stackTraceToString())
//    }) {
//        delay(10000)
//        while (true) {
//            scheduler.sessions.filter { it.key.room.quality != "model already deleted" }.map {
//                async {
////                    println("[INFO] screenshot ${it.key.room.name}:${it.key.room.id}")
//                    File("/screenshot/${it.key.room.name}").mkdir()
//                    val url =
//                        proxiedClient.testFast(
//                            listOf(
//                                "https://b-hls-04.doppiocdn.live/hls/${it.key.room.id}/${it.key.room.id}_160p.m3u8",
//                                "https://b-hls-04.doppiocdn.live/hls/${it.key.room.id}/${it.key.room.id}_240p.m3u8",
//                                "https://b-hls-04.doppiocdn.live/hls/${it.key.room.id}/${it.key.room.id}_560p.m3u8",
//                                "https://b-hls-04.doppiocdn.live/hls/${it.key.room.id}/${it.key.room.id}_720p.m3u8",
//                                "https://b-hls-04.doppiocdn.live/hls/${it.key.room.id}/${it.key.room.id}_720p60.m3u8",
//                                "https://b-hls-04.doppiocdn.live/hls/${it.key.room.id}/${it.key.room.id}.m3u8",
//                            )
//                        )
//                    if (url == null) {
//                        return@async
//                    }
//                    runCatching {
//                        val builder = ProcessBuilder(
//                            "ffmpeg",
//                            "-hide_banner",
//                            "-v",
//                            "error",
//                            "-i",
//                            url,
//                            "-vf",
//                            "scale=480:-1",
//                            "-vframes",
//                            "1",
//                            "-q:v",
//                            "2",
//                            "/screenshot/${it.key.room.name}/%d.jpg".format(System.currentTimeMillis() / 1000)
//                        )
//                        val proxyEnv = System.getenv("http_proxy") ?: System.getenv("HTTP_PROXY")
//                        if (proxyEnv != null) {
//                            builder.environment()["http_proxy"] = proxyEnv
//                        }
//                        val p = builder.start()
//                        if (p.waitFor() != 0) {
//                            println("[ERROR] screenshot exited ${p.exitValue()}")
//                        }
//                        val readText = p.errorStream.bufferedReader().readText()
//                        if (readText.isNotBlank()) {
//                            println(readText)
//                        }
//                    }.onFailure { it ->
//                        println(it.stackTraceToString())
//                    }
//                }
//            }.awaitAll()
//            delay(5 * 60_000)
//        }
//    }
    // web server
    var engine: ApplicationEngine? = null
    engine = embeddedServer(
        io.ktor.server.cio.CIO,
        port = commandLine.getOptionValue("p", "8090").toInt(),
        host = "0.0.0.0"
    ) {
        install(ContentNegotiation) {
            json()
        }
        install(CORS) {
            anyHost() // @TODO: Don't do this in production if possible. Try to limit it.
        }
        routing {
            get("/add") {
                val active = call.request.queryParameters["active"].toBoolean()
                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Room slug not provided.")
                    return@get
                }
                val url = "https://zh.xhamsterlive.com/${slug.substringAfterLast("/").substringBefore("#")}"
                val q = call.request.queryParameters["quality"] ?: "720p"
                println("${if (active) "[+]" else "[X]"} $q $slug")
                val room = withRetryOrNull(5, { it.message?.contains("404") == true }) {
                    proxiedClient.fetchRoomFromUrl(url, q)
                }
                if (room == null) {
                    call.respond(HttpStatusCode.InternalServerError, "Failed to get room info.")
                    return@get
                }
                if (scheduler.sessions.keys.any { it.room.name == room.name }) {
                    println("Exist ${room.name}")
                    call.respond(HttpStatusCode.InternalServerError, "Exist ${room.name}.")
                    return@get
                }
                println(room)
                scheduler.add(room, active)
                kotlin.synchronized(jobFile) {
                    jobFile.writeText(scheduler.sessions.keys.joinToString("\n") {
                        "${if (it.listen) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
                    })
                }
                call.respond("OK")
            }
            get("/remove") {
                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Room slug not provided.")
                    return@get
                }
                scheduler.remove(slug)
                kotlin.synchronized(jobFile) {
                    jobFile.writeText(scheduler.sessions.keys.joinToString("\n") {
                        "${if (it.listen) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
                    })
                }
                call.respond("OK")
            }
            get("/start") {
                scheduler.start(false)
                call.respond(HttpStatusCode.OK, "OK.")

            }
            get("/break") {
                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Room slug not provided.")
                    return@get
                }
                scheduler.stopRecorder(slug)
                call.respond("OK.")
            }
            get("/stop") {
                scheduler.job?.cancel()
                scheduler.job?.join()
                call.respond(HttpStatusCode.OK, "OK.")
            }
            get("/activate") {
                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Room slug not provided.")
                    return@get
                }
                scheduler.active(slug)
                kotlin.synchronized(jobFile) {
                    jobFile.writeText(scheduler.sessions.keys.joinToString("\n") {
                        "${if (it.listen) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
                    })
                }
                call.respond("OK")
            }
            get("/quality") {
                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Room slug not provided.")
                    return@get
                }
                val q = call.request.queryParameters["quality"]
                if (q == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Quality not provided.")
                    return@get
                }
                val room = scheduler.sessions.keys.find { it.room.name.equals(slug, true) }?.room
                if (room == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Room $slug not found.")
                    return@get
                }
                room.quality = q
                kotlin.synchronized(jobFile) {
                    jobFile.writeText(scheduler.sessions.keys.joinToString("\n") {
                        "${if (it.listen) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
                    })
                }
                call.respond(room)
            }
            get("/deactivate") {

                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Room slug not provided.")
                    return@get
                }
                scheduler.deactivate(slug)
                kotlin.synchronized(jobFile) {
                    jobFile.writeText(scheduler.sessions.keys.joinToString("\n") {
                        "${if (it.listen) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
                    })
                }
                call.respond("OK")
            }
            get("/list") {
//                println(
//                    "streaming recorder job room room_id quality"
//                )
                val list = scheduler.sessions.map { (state, session) ->
                    async {
                        listOf(
                            if (session.isOpen) "[*]" else "[ ]",
                            if (state.listen) "listening" else "         ",
                            if (session.isActive) "recording" else "         ",
                            state.room.name,
                            state.room.id.toString(),
                            state.room.quality,
                        )
                    }
                }.awaitAll().also {
//                    // column
//                    it.associateWith { it.map(String::length) }.let {
//                        val maxLen = it.values.reduce { acc, ints ->
//                            acc.zip(ints).map { max(it.first, it.second) }
//                        }
//                        it.keys.map { strings ->
//                            strings.zip(maxLen).map { pair ->
//                                pair.first.padEnd(pair.second)
//                            }
//                        }
//                    }.sortedBy { it[0] + it[1] + it[3] }.forEach {
//                        println(it.joinToString(" "))
//                    }
                }

                call.respond(list)
            }
            get("/status") {
                call.respond(scheduler.sessions.filter { it.value.isActive }
                    .map { it.key.room.name to it.value.status() }.toMap())
            }
            get("/metrics") {
                call.respond(Metric.prometheus())
            }
            get("/recorders") {
                synchronized(scheduler.sessions) {
                    runBlocking {
                        call.respond(scheduler.sessions.map { it.key.room })
                    }
                }
            }
            get("/stop-server") {
//                sc.cancel()
                scheduler.stop()

                call.respond("OK")
                engine?.stop(1000)
            }
        }
    }
        .start(true)

    println("all done")
    proxiedClient.close()
    _clients.forEach {
        it.close()
    }
    return@supervisorScope
}

