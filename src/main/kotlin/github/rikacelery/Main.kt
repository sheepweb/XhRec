package github.rikacelery

import github.rikacelery.utils.withRetryOrNull
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
import kotlinx.serialization.Serializable
import okhttp3.ConnectionPool
import org.apache.commons.cli.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.max


val _clients = List(10) {
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
            retryOnException(maxRetries = 5, retryOnTimeout = true)
            constantDelay(100)
        }
        engine {
            config {
                connectionPool(ConnectionPool(3, 120, TimeUnit.SECONDS))
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
        val url = Url(System.getenv("HTTP_PROXY")?:"http://localhost:7890")
        proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(url.host,url.port))
        config {
            connectionPool(ConnectionPool(3, 120, TimeUnit.SECONDS))
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
    val regex = "(#)?(https://(?:zh.)?xhamsterlive.com/\\S+)(?: (.+))?".toRegex()
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

    val scheduler = Scheduler(commandLine.getOptionValue("o", "out"), commandLine.getOptionValue("t", "tmp"))
    rooms.forEach {
        scheduler.add(it.first, it.second)
    }
    println("-".repeat(10) + "DONE" + "-".repeat(10))

    // 开启截图协程
    val sc = launch(Dispatchers.IO) {
        while (currentCoroutineContext().isActive) {
            scheduler.sessions.filterValues { it.testAndConfigure() }.map {
                async {
                    File("screenshot/${it.key.room.name}").mkdirs()
                    val builder = ProcessBuilder(
                        "ffmpeg",
                        "-hide_banner",
                        "-v",
                        "error",
                        "-i",
                        "https://b-hls-16.doppiocdn.live/hls/${it.key.room.id}/${it.key.room.id}.m3u8",
                        "-vf",
                        "scale=480:-1",
                        "-vframes",
                        "1",
                        "-q:v",
                        "2",
                        "screenshot/${it.key.room.name}/%d.jpg".format(System.currentTimeMillis() / 1000)
                    )

                    runCatching {
                        val p = builder.start()
                        p
                    }.onFailure {
                        it.printStackTrace()
                    }
                }
            }.awaitAll().map {
                launch {
                    it.getOrNull()?.waitFor(10, TimeUnit.SECONDS)
                    it.getOrNull()?.destroyForcibly()
                }
            }.joinAll()
            delay(3 * 60_000)
        }
    }
    scheduler.start(false)
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
                jobFile.writeText(scheduler.sessions.keys.joinToString("\n") {
                    "${if (it.listen) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
                })
                call.respond("OK")
            }
            get("/remove") {
                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Room slug not provided.")
                    return@get
                }
                scheduler.remove(slug)
                jobFile.writeText(scheduler.sessions.keys.joinToString("\n") {
                    "${if (it.listen) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
                })
                call.respond("OK")
            }
            get("/start") {
                scheduler.start(false)
                call.respond(HttpStatusCode.OK, "OK.")

            }
            get("/break"){
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
                jobFile.writeText(scheduler.sessions.keys.joinToString("\n") {
                    "${if (it.listen) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
                })
                call.respond("OK")

            }
            get("/deactivate") {

                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Room slug not provided.")
                    return@get
                }
                scheduler.deactivate(slug)
                jobFile.writeText(scheduler.sessions.keys.joinToString("\n") {
                    "${if (it.listen) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
                })
                call.respond("OK")
            }
            get("/list") {
                println(
                    "streaming recorder job room room_id quality"
                )
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
                    // column
                    it.associateWith { it.map(String::length) }.let {
                        val maxLen = it.values.reduce { acc, ints ->
                            acc.zip(ints).map { max(it.first, it.second) }
                        }
                        it.keys.map { strings ->
                            strings.zip(maxLen).map { pair ->
                                pair.first.padEnd(pair.second)
                            }
                        }
                    }.sortedBy { it[0] + it[1] + it[3] }.forEach {
                        println(it.joinToString(" "))
                    }
                }

                call.respond(list)
            }
            get("/status") {
                call.respond(scheduler.sessions.filter { it.value.isActive }
                    .map { it.key.room.name to it.value.status() }.toMap())
            }
            get("/recorders") {
                synchronized(scheduler.sessions) {
                    runBlocking {
                        call.respond(scheduler.sessions.map { it.key.room })
                    }
                }
            }
            get("/stop-server") {
                sc.cancel()
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

