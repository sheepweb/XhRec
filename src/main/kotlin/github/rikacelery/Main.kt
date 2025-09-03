package github.rikacelery

import github.rikacelery.utils.withRetryOrNull
import github.rikacelery.v2.Metric
import github.rikacelery.v2.PostProcessor
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
import okhttp3.ConnectionPool
import org.apache.commons.cli.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds


val _clients = List(1) {
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
            println("Using http proxy $proxyEnv")
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


val BUILTIN = setOf(
    "# https://zh.xhamsterlive.com/ChangeToYourModel q:1080p limit:120",
)

private fun extract(text: String, regex: Regex, default: String): String {
    return regex.find(text)?.groupValues?.get(1)?.ifBlank { default } ?: default
}


@OptIn(InternalCoroutinesApi::class)
fun main(vararg args: String): Unit = runBlocking {
    if ((System.getenv("http_proxy") ?: System.getenv("HTTP_PROXY")) != null) {
        println("Testing proxy")
        runCatching {
            proxiedClient.get(("https://xhamsterlive.com/")) {
                expectSuccess = false
            }
        }.onFailure {
            println("Proxy test failed. $it")
        }
    }
    val parser: CommandLineParser = DefaultParser()
    val options = Options()
    options.addOption("post", true, "Post Processor Config File (default: postprocessor.json)")
    options.addOption("f", "file", true, "Room List File")
    options.addOption("o", "output", true, "Output Dir")
    options.addOption("t", "tmp", true, "Temp Dir")
//    options.addOption("s", "server", false, "Server Mode")
    options.addOption("p", "port", true, "Server Port [default:8090]")

    val commandLine: CommandLine = try {
        parser.parse(options, args)
    } catch (_: ParseException) {
        val formatter = HelpFormatter()
        formatter.printHelp("CommandLineParameters", options)
        return@runBlocking
    }
    if (commandLine.hasOption("h")) {
        val formatter = HelpFormatter()
        formatter.printHelp("CommandLineParameters", options)
    }
    PostProcessor.loadConfig(File(commandLine.getOptionValue("post", "postprocessor.json")))
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
        val limit = extract(match.groupValues[3], "limit:(\\d+)".toRegex(), "0")
        println("${if (active) "[+]" else "[X]"} $q $limit $url")
        async {
            val room = withRetryOrNull(5, { it.message?.contains("404") == true }) {
                proxiedClient.fetchRoomFromUrl(url, q)
            } ?: run {
                println("failed " + url)
                return@async null
            }
            if (limit.toLong() > 0) {
                room.limit = limit.toLong().seconds
            }
            println(room)
            room to active
        }
    }.toList().filterNotNull().awaitAll().filterNotNull().toMutableList()

    val scheduler =
        Scheduler(commandLine.getOptionValue("o", "out"), commandLine.getOptionValue("t", "tmp")) { scheduler ->
            saveJobFile(jobFile, scheduler)
        }
    rooms.forEach {
        File("screenshot/${it.first.name}").mkdir()
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
            get("/") {
                call.respondText(this::class.java.getResource("/index.html")!!.readText(), ContentType.Text.Html)
            }
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
                saveJobFile(jobFile, scheduler)
            }
            get("/remove") {
                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Room slug not provided.")
                    return@get
                }
                scheduler.remove(slug)
                saveJobFile(jobFile, scheduler)
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
                saveJobFile(jobFile, scheduler)
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
                saveJobFile(jobFile, scheduler)
                call.respond(room)
            }
            get("/deactivate") {

                val slug = call.request.queryParameters["slug"]
                if (slug == null) {
                    call.respond(HttpStatusCode.NotAcceptable, "Room slug not provided.")
                    return@get
                }
                scheduler.deactivate(slug)
                saveJobFile(jobFile, scheduler)
                call.respond("OK")
            }
            get("/list") {
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
                }.awaitAll()
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
                engine?.stop()
            }
        }
    }
    println("Starting server ...")
    engine.start(true)
    println("Server Stopped, waiting post processors exit...")
    withContext(NonCancellable) {
        scheduler.stop()
    }
    // Suppress waring
    println("all done")
    proxiedClient.close()
    _clients.forEach {
        it.close()
    }
    return@runBlocking
}

@OptIn(InternalCoroutinesApi::class)
private fun saveJobFile(jobFile: File, scheduler: Scheduler) {
    synchronized(jobFile) {
        jobFile.writeText(scheduler.sessions.keys.joinToString("\n") {
            "${if (it.listen) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}" + (if (!it.room.limit.isFinite()) "limit:${it.room.limit.inWholeSeconds}" else "")
        })
    }
}

