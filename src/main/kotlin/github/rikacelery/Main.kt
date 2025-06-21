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
            retryOnException(maxRetries = 5, retryOnTimeout = true)
            constantDelay(100)
        }
        engine {
            config {
//                connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
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
        proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(7890))
        config {
//            connectionPool(ConnectionPool(1, 1, TimeUnit.SECONDS))
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

fun extract(text: String, regex: Regex, default: String): String {
    return regex.find(text)?.groupValues?.get(1)?.ifBlank { default } ?: default
}

suspend fun main(vararg args: String) = supervisorScope {
//    val sess =
//        Session(proxiedClient.fetchRoomFromUrl("https://zh.xhamsterlive.com/zhizhi0000", "720p"), client, proxiedClient)
//    launch { sess.start() }
//    readln()
//    sess.stop()
//    client.close()
//    proxiedClient.close()
//    _clients.forEach { it.close() }
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
        val formatter: HelpFormatter = HelpFormatter()
        formatter.printHelp("CommandLineParameters", options)
        return@supervisorScope
    }
    if (commandLine.hasOption("h")) {
        val formatter: HelpFormatter = HelpFormatter()
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
                    it.getOrNull()?.waitFor(30, TimeUnit.SECONDS)
                    it.getOrNull()?.destroyForcibly()
                }
            }.joinAll()
            delay(3 * 60_000)
        }
    }
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
                val q = call.request.queryParameters["slug"] ?: "720p"
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
                call.respond(HttpStatusCode.InternalServerError, "Not implement.")

            }
            get("/stop") {
                call.respond(HttpStatusCode.InternalServerError, "Not implement.")
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
                            if (session.testAndConfigure()) "[*]" else "[ ]",
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
//            get("/recorders") {
//                synchronized(recorders) {
//                    runBlocking {
//                        call.respond(recorders.map { it.room })
//                    }
//                }
//            }
            get("/stop-server") {
                scheduler.stop()
                call.respond("OK")
                engine?.stop(1000)
            }
        }
    }
        .start(wait = false)

    scheduler.start()
    println("all done")
    proxiedClient.close()
    _clients.forEach {
        it.close()
    }
    return@supervisorScope
//    val recorders = channelFlow {
//        if (!jobFile.exists())
//            jobFile.writeText(BUILTIN.joinToString("\n"))
//        for (line in jobFile.bufferedReader().lines().filter { it.isNotBlank() }) {
//            send(line)
//        }
//    }.map { it: String ->
//        val match = regex.find(it) ?: return@map null
//        val active = match.groupValues[1].isBlank()
//        val url = match.groupValues[2]
//        val q = extract(match.groupValues[3], "q:(\\S+)".toRegex(), "720p")
//        println("${if (active) "[+]" else "[X]"} $q $url")
//        async {
//            val room = withRetryOrNull(5, { it.message?.contains("404") == true }) {
//                proxiedClient.fetchRoomFromUrl(url, q)
//            } ?: run {
//                println("failed " + url)
//                return@async null
//            }
//            println(room)
//            RoomRecorder(
//                room,
//                client,
//                commandLine.getOptionValue("o", "/mnt/download/_Crawler/Video/R18/rec/raw"),
//                commandLine.getOptionValue("t", "./"),
//            ).apply {
//                this.active = active
//            }
//        }
//    }.toList().filterNotNull().awaitAll().filterNotNull().toMutableList()
//
//    val jobList = recorders.map { recorder ->
//        println("Start ${recorder.room.name}")
//        recorder to launch {
//            while (isActive) {
//                if (recorder.isOpen() && recorder.active)
//                    recorder.start().join()
//                delay(60_000)
//            }
//        }
//    }.toList().toMutableList()
//    // 开启截图协程
//    val sc = launch {
//        while (isActive) {
//            recorders.filter(RoomRecorder::isOpenCached).map {
//                launch {
//                    File("screenshot/${it.room.name}").mkdirs()
//                    val builder = ProcessBuilder(
//                        "ffmpeg",
//                        "-i",
//                        "https://b-hls-16.doppiocdn.live/hls/${it.room.id}/${it.room.id}.m3u8",
//                        "-vf",
//                        "scale=1280:-1",
//                        "-vframes",
//                        "1",
//                        "-q:v",
//                        "2",
//                        "screenshot/${it.room.name}/%d.jpg".format(System.currentTimeMillis() / 1000)
//                    )
//
//                    runCatching {
//                        val p = builder.start()
//                        if (p.waitFor() != 0) {
//                            builder.start().waitFor()
//                        }
//                    }.onFailure {
//                        it.printStackTrace()
//                    }
//                }
//            }
//            delay(3 * 60_000)
//        }
//    }
//    println("-".repeat(10) + "DONE" + "-".repeat(10))
//
//    val running = AtomicBoolean(true)
//
//    // web server
//    val server = if (terminal == null || commandLine.hasOption("s")) launch {
//        var engine: ApplicationEngine? = null
//        engine = embeddedServer(
//            io.ktor.server.cio.CIO,
//            port = commandLine.getOptionValue("p", "8090").toInt(),
//            host = "0.0.0.0"
//        ) {
//            install(ContentNegotiation) {
//                json()
//            }
//            install(CORS) {
//                anyHost() // @TODO: Don't do this in production if possible. Try to limit it.
//            }
//            routing {
//                get("/add") {
//                    val active = call.request.queryParameters["active"].toBoolean()
//                    val slug = call.request.queryParameters["slug"]
//                    if (slug == null) {
//                        call.respond(HttpStatusCode.NotAcceptable, "Room slug not provided.")
//                        return@get
//                    }
//                    val url = "https://zh.xhamsterlive.com/${slug.substringAfterLast("/").substringBefore("#")}"
//                    val q = call.request.queryParameters["slug"] ?: "720p"
//                    println("${if (active) "[+]" else "[X]"} $q $slug")
//                    val room = withRetryOrNull(5, { it.message?.contains("404") == true }) {
//                        proxiedClient.fetchRoomFromUrl(url, q)
//                    }
//                    if (room == null) {
//                        call.respond(HttpStatusCode.InternalServerError, "Failed to get room info.")
//                        return@get
//                    }
//                    if (recorders.any { it.room.name == room.name }) {
//                        println("Exist ${room.name}")
//                        call.respond(HttpStatusCode.InternalServerError, "Exist ${room.name}.")
//                        return@get
//                    }
//                    println(room)
//                    val recorder = RoomRecorder(
//                        room,
//                        client,
//                        commandLine.getOptionValue("o", "/mnt/download/_Crawler/Video/R18/rec/raw"),
//                        commandLine.getOptionValue("t", "./"),
//                    )
//                    recorder.active = active
//                    recorders.add(recorder)
//                    println("Start ${recorder.room.name}")
//                    jobList.add(
//                        recorder to launch {
//                            while (isActive) {
//                                if (recorder.isOpen() && recorder.active)
//                                    recorder.start().join()
//                                delay(60_000)
//                            }
//                        })
//
//                    jobFile.writeText(recorders.joinToString("\n") {
//                        "${if (it.active) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
//                    })
//                    call.respond("OK")
//                }
//                get("/remove") {
//                    val slug = call.request.queryParameters["slug"]
//                    if (slug == null) {
//                        call.respond(HttpStatusCode.NotAcceptable, "Room slug not provided.")
//                        return@get
//                    }
//                    val job =
//                        jobList.find { it.first.room.id.toString() == slug || it.first.room.name == slug }
//                    if (job == null) {
//                        call.respond(HttpStatusCode.NotAcceptable, "Job not found.")
//                        return@get
//                    }
//                    recorders.removeIf { slug.contains(it.room.name) }
//                    job.second.cancel()
//                    job.first.stop()
//                    println("[${job.first.room.name}] removed")
//
//                    jobFile.writeText(recorders.joinToString("\n") {
//                        "${if (it.active) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
//                    })
//                    call.respond("OK")
//                }
//                get("/start") {
//                    call.respond(HttpStatusCode.InternalServerError, "Not implement.")
//
//                }
//                get("/stop") {
//                    call.respond(HttpStatusCode.InternalServerError, "Not implement.")
//                }
//                get("/activate") {
//                    val slug = call.request.queryParameters["slug"]
//                    if (slug == null) {
//                        call.respond(HttpStatusCode.NotAcceptable, "Room slug not provided.")
//                        return@get
//                    }
//                    val recorder =
//                        recorders.find { it.room.id.toString() == slug || it.room.name == slug }
//                    if (recorder == null) {
//                        call.respond(HttpStatusCode.NotAcceptable, "Recorder not found.")
//                        return@get
//                    }
//                    recorder.active = true
//                    println("[${recorder.room.name}] active")
//                    jobFile.writeText(recorders.joinToString("\n") {
//                        "${if (it.active) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
//                    })
//                    call.respond("OK")
//
//                }
//                get("/deactivate") {
//
//                    val slug = call.request.queryParameters["slug"]
//                    if (slug == null) {
//                        call.respond(HttpStatusCode.NotAcceptable, "Room slug not provided.")
//                        return@get
//                    }
//                    val recorder =
//                        recorders.find { it.room.id.toString() == slug || it.room.name == slug }
//
//                    if (recorder == null) {
//                        call.respond(HttpStatusCode.NotAcceptable, "Recorder not found.")
//                        return@get
//                    }
//                    recorder.active = false
//                    println("[${recorder.room.name}] deactivate")
//                    jobFile.writeText(recorders.joinToString("\n") {
//                        "${if (it.active) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
//                    })
//                    call.respond("OK")
//                }
//                get("/list") {
//                    println(
//                        "streaming recorder job room room_id quality"
//                    )
//                    val list = jobList.map { (recorder, job) ->
//                        async {
//                            listOf(
//                                if (recorder.isOpen()) "[*]" else "[ ]",
//                                if (recorder.active) "active" else "idle  ",
//                                if (job.isActive) "listening" else "X stopped",
//                                recorder.room.name,
//                                recorder.room.id.toString(),
//                                recorder.room.quality,
//                            )
//                        }
//                    }.awaitAll().also {
//                        // column
//                        it.associateWith { it.map(String::length) }.let {
//                            val maxLen = it.values.reduce { acc, ints ->
//                                acc.zip(ints).map { max(it.first, it.second) }
//                            }
//                            it.keys.map { strings ->
//                                strings.zip(maxLen).map { pair ->
//                                    pair.first.padEnd(pair.second)
//                                }
//                            }
//                        }.sortedBy { it[0] + it[1] + it[3] }.forEach {
//                            println(it.joinToString(" "))
//                        }
//                    }
//
//                    call.respond(list)
//                }
//                get("/status") {
//                    synchronized(logs) {
//                        runBlocking {
//                            call.respond(logs.toMap())
//                        }
//                    }
//                }
//                get("/recorders") {
//                    synchronized(recorders) {
//                        runBlocking {
//                            call.respond(recorders.map { it.room })
//                        }
//                    }
//                }
//                get("/stop-server") {
//                    running.set(false)
//                    engine?.stop(1000)
//                }
//            }
//        }
//            .start(wait = true)
//    } else null
//    // REPL 主线程
//    while (terminal != null && running.get() && !commandLine.hasOption("s")) {
//        val line = try {
//            readLine().trim()
//        } catch (e: Exception) {
//            running.set(false)
//            break
//        }
//        val tokens = line.split(" ", limit = 2)
//        when (tokens.firstOrNull()) {
//            "add" -> tokens.getOrNull(1)?.let {
//                val match = regex.find(it) ?: return@let
//                val active = match.groupValues[1].isBlank()
//                val url = match.groupValues[2]
//                val q = extract(match.groupValues[3], "q:(\\S+)".toRegex(), "720p")
//                println("${if (active) "[+]" else "[X]"} $q $url")
//                val room = withRetryOrNull(5, { it.message?.contains("404") == true }) {
//                    proxiedClient.fetchRoomFromUrl(url, q)
//                } ?: return@let
//                if (recorders.any { it.room.name == room.name }) {
//                    println("Exist ${room.name}")
//                    return@let
//                }
//                println(room)
//                val recorder = RoomRecorder(
//                    room,
//                    client,
//                    commandLine.getOptionValue("o", "/mnt/download/_Crawler/Video/R18/rec/raw"),
//                    commandLine.getOptionValue("t", "./"),
//                )
//                recorder.active = active
//                recorders.add(recorder)
//                println("Start ${recorder.room.name}")
//                jobList.add(
//                    recorder to launch {
//                        while (isActive) {
//                            if (recorder.isOpen() && recorder.active)
//                                recorder.start().join()
//                            delay(60_000)
//                        }
//                    })
//
//                jobFile.writeText(recorders.joinToString("\n") {
//                    "${if (it.active) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
//                })
//            }
//
//            "remove" -> tokens.getOrNull(1)?.let { input ->
//                val job =
//                    jobList.find { it.first.room.id.toString() == input || it.first.room.name == input } ?: return@let
//                recorders.removeIf { input.contains(it.room.name) }
//                job.second.cancel()
//                job.first.stop()
//                println("[${job.first.room.name}] removed")
//
//                jobFile.writeText(recorders.joinToString("\n") {
//                    "${if (it.active) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
//                })
//            }
//
//            "stop" -> tokens.getOrNull(1)?.let { input ->
//                val job =
//                    jobList.find { it.first.room.id.toString() == input || it.first.room.name == input } ?: return@let
//                job.second.cancel()
//                job.first.stop()
//                println("[${job.first.room.name}] stopped")
//            }
//
//            "start" -> tokens.getOrNull(1)?.let { input ->
//                val recorder =
//                    recorders.find { it.room.id.toString() == input || it.room.name == input } ?: return@let
//                jobList.add(
//                    recorder to launch {
//                        val old = recorder.active
//                        recorder.active = true
//                        while (isActive) {
//                            if (recorder.isOpen() && recorder.active) {
//                                recorder.start().join()
//                                recorder.active = old
//                            }
//                            delay(60_000)
//                        }
//                    })
//                println("[${recorder.room.name}] started")
//
//            }
//
//            "active" -> tokens.getOrNull(1)?.let { input ->
//                val recorder =
//                    recorders.find { it.room.id.toString() == input || it.room.name == input } ?: return@let
//                recorder.active = true
//                println("[${recorder.room.name}] active")
//                jobFile.writeText(recorders.joinToString("\n") {
//                    "${if (it.active) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
//                })
//            }
//
//            "deactivate" -> tokens.getOrNull(1)?.let { input ->
//                val recorder =
//                    recorders.find { it.room.id.toString() == input || it.room.name == input } ?: return@let
//                recorder.active = false
//                println("[${recorder.room.name}] deactivate")
//                jobFile.writeText(recorders.joinToString("\n") {
//                    "${if (it.active) "" else "#"}https://zh.xhamsterlive.com/${it.room.name} q:${it.room.quality}"
//                })
//            }
//
//            "fatal" -> {
//                exitProcess(1)
//            }
//
//            "list" -> {
//                println(
//                    "streaming recorder job room room_id quality"
//                )
//                jobList.map { (recorder, job) ->
//                    async {
//                        listOf(
//                            if (recorder.isOpen()) "[*]" else "[ ]",
//                            if (recorder.active) "active" else "idle  ",
//                            if (job.isActive) "listening" else "X stopped",
//                            recorder.room.name,
//                            recorder.room.id.toString(),
//                            recorder.room.quality,
//                        )
//                    }
//                }.awaitAll()
//                    // column
//                    .associateWith { it.map(String::length) }.let {
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
//            }
//
//            "exit" -> running.set(false)
//            else -> {
//                println("Unknown command. Try: add/remove/start/stop/active/deactivate/list/exit/fatal")
//            }
//        }
//    }
//    server?.join()
//    sc.cancel()
//    jobList.map { (rec, job) ->
//        job.cancel()
//        launch { rec.stop() }
//    }.joinAll()
//
//    logger.cancel()
//    println("all done")
//    proxiedClient.close()
//    _clients.forEach {
//        it.close()
//    }
//    terminal?.close()
//    exitProcess(0)
}

