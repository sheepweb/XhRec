package github.rikacelery

import github.rikacelery.utils.bytesToHumanReadable
import github.rikacelery.utils.withRetry
import github.rikacelery.utils.withRetryOrNull
import io.ktor.client.*
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.CoroutineContext

class RoomRecorder(
    val room: Room,
    private val client: HttpClient,
    private val destFolder: String,
    private val tmpFolder: String,
) : CoroutineScope {
    private val writer: Writer = Writer(room.name, destFolder,tmpFolder)
    var active = false
        get() = field
        set(value) {
            field = value
        }
    private var counter = 0
    var onLiveSegmentDownloaded: (b: ByteArray, sum: Long) -> Unit = { bytes, total ->
        writer.append(bytes)
        synchronized(logs) {
            val log = logs[room.id] ?: LogInfo(room.name, "")
            logs[room.id] = log.copy(size = bytesToHumanReadable(total))
        }
        if (counter == 3) counter = 0
    }
    var onLiveStopped: (room: Room) -> Unit = { room ->
        mt.println("[-] live stop ${room.id} https://zh.xhamsterlive.com/${room.name}")
    }
    var onRecordingStarted: (room: Room) -> Unit = { room ->
        writer.init()
        synchronized(logs) {
            if (!logs.containsKey(room.id)) {
                mt.println("[+] recorder start ${room.id} https://zh.xhamsterlive.com/${room.name}")
            }
        }
    }
    var onRecordingStopped: (room: Room) -> Unit = { room ->
        synchronized(logs) {
            val log = logs[room.id] ?: LogInfo(room.name, "")
            logs[room.id] = log.copy(size = " transcoding...")
        }
        writer.done()
        synchronized(logs) {
            logs.remove(room.id)
        }
        mt.println("[*] recorder stop ${room.id} https://zh.xhamsterlive.com/${room.name}")

    }
    private var flag = false

    private var retryMsg = AtomicReference("")


    sealed interface Event {
        class LiveSegmentInit(val url: String, room: Room) : Event
        class LiveSegmentData(val url: String, val initUrl: String, room: Room) : Event
    }

    private var job: Job? = null

    private val streamUrl: String
        get() = if (room.quality.isNotBlank()) "https://b-hls-11.doppiocdn.live/hls/%d/%d_%s.m3u8?playlistType=lowLatency".format(
            room.id, room.id, room.quality
        )
        else "https://b-hls-11.doppiocdn.live/hls/%d/%d.m3u8?playlistType=lowLatency".format(room.id, room.id)


    private suspend fun segmentGenerator(channel: Channel<Event>) {
        var started = false
        var initUrl: String
        val cache = CircleCache(100)

        while (isActive) {
            try {
                val lines = withTimeout(1000) {
                    proxiedClient.get(streamUrl).bodyAsText().lines()
                }
                runCatching {
                    if (flag) return@runCatching
                    val qualities = withRetry(10) {
                        client.get(
                            "https://b-hls-06.doppiocdn.live/hls/%d/%d.m3u8?playlistType=lowLatency".format(
                                room.id, room.id
                            )
                        ).bodyAsText().lines().filter {
                            it.startsWith("#EXT-X-RENDITION-REPORT") && !it.contains("blurred")
                        }.map {
                            it.substringAfter(":URI=\"").substringBefore("\"").substringAfter("_")
                                .substringBefore(".")
                        }
                    }
                    if (room.quality.isNotBlank() && !qualities.contains(room.quality)) {
                        mt.println("[${room.name}] 正在更正清晰度设置")
                        val q = qualities.lastOrNull { it.contains("720p") } ?: qualities.last()
                        room.quality = q
                        mt.println("[${room.name}] 更正清晰度设置 ${q}")
                    }
                }.onFailure {
                    mt.println("[${room.name}] 无法更正清晰度设置 $it")
                }.onSuccess {
                    flag = true
                }
                initUrl = lines.first { it.startsWith("#EXT-X-MAP") }.substringAfter("#EXT-X-MAP:URI=")
                    .removeSurrounding("\"")
                val videos = lines.filter { it.startsWith("#EXT-X-PART") && it.contains("URI=\"") }
                    .map { it.substringAfter("URI=\"").substringBefore("\"") }
                if (!started) {
                    channel.send(Event.LiveSegmentInit(initUrl, room))
                    videos.forEach {
                        if (!cache.contains(it)) {
                            cache.add(it)
                        }
                    }
                    started = true
                    onRecordingStarted(room)
                    continue
                }
                videos.forEach {
                    if (!cache.contains(it)) {
                        cache.add(it)
                        channel.send(Event.LiveSegmentData(it, initUrl, room))
                    }
                }
            } catch (it: Exception) {
                when (it) {
                    is ClientRequestException -> {
                        if (it.response.status == HttpStatusCode.NotFound || it.response.status == HttpStatusCode.Forbidden) {
                            onLiveStopped(room)
                            started = false
                            return
                        }
                        mt.println("generator: code: " + it.response.status.value)
                    }

                    is TimeoutCancellationException -> continue
                    is SocketTimeoutException -> continue
                    is SSLHandshakeException -> continue
                    is CancellationException -> return

                    else -> {
                        mt.println("generator: error: " + it.stackTraceToString())
//                        it.printStackTrace()
                    }
                }
            }
            delay(500)
        }
    }

    private suspend fun segmentDownloader(
        channel: Channel<Event>, outputChannel: Channel<Deferred<ByteArray>>, client: HttpClient
    ) {
        var initBytes = ByteArray(0)
        val idReg = "_\\d+p(?:\\d+)?_(\\d+)".toRegex()
        var total = 0
        var done = AtomicInteger(0)
        supervisorScope {
            for (segment in channel) {
                when (segment) {
                    is Event.LiveSegmentData -> {
                        total++
                        synchronized(logs) {
                            val log = logs[room.id] ?: LogInfo(room.name, "")
                            logs[room.id] = log.copy(progress = done.get() to total)
                        }
                        async {
                            val bytes = runCatching {
                                val start = System.currentTimeMillis()
                                val data =
                                    withRetry(30, {
                                        it is ClientRequestException && it.response.status == HttpStatusCode.NotFound ||
                                                it is CancellationException
                                    }) {
                                        withTimeout(30_000) {
                                            client.get(segment.url)
                                                .readBytes()
                                        }
                                    }

                                data.also {
                                    synchronized(logs) {
                                        val log = logs[room.id] ?: LogInfo(room.name, "")
                                        logs[room.id] =
                                            log.copy(latency = (System.currentTimeMillis() - start + log.latency) / 2)
                                    }
                                }
                            }.onFailure { e ->
                                when (e) {
                                    is SocketTimeoutException -> {
                                        mt.println("[${room.name}] downloader socket timeout " + segment.url)
                                    }

                                    is TimeoutCancellationException -> {
                                        mt.println("[${room.name}] downloader timeout " + segment.url)
                                    }

                                    is ClientRequestException -> {
                                        mt.println("[${room.name}] ${e.response.status} ${segment.url}")
                                    }

                                    is CancellationException -> {

                                    }

                                    is Exception -> {
                                        mt.println("[${room.name}] downloader job error " + e.message)
                                    }
                                }
                            }.getOrNull() ?: initBytes
                            synchronized(logs) {
                                val log = logs[room.id] ?: LogInfo(room.name, "")
                                logs[room.id] = log.copy(progress = done.incrementAndGet() to total)
                            }
                            bytes
                        }.let {
                            outputChannel.send(it)
                        }
                    }

                    is Event.LiveSegmentInit ->
                        outputChannel.send(async { client.get(segment.url).readBytes().also { initBytes = it } })

                }
            }
        }
    }

    suspend fun isOpen(): Boolean {
        return withRetryOrNull(10) {
            try {
                proxiedClient.get(streamUrl)
                true
            } catch (e: ClientRequestException) {
                false
            }
        } ?: false
    }

    fun start(): Job {
        val job1 = launch {
            var sum = 0L
            val channelSegments = Channel<Event>(Channel.UNLIMITED)
            val channelBytes = Channel<Deferred<ByteArray>>(Channel.UNLIMITED)
            launch {
                segmentGenerator(channelSegments)
                channelSegments.close()
            }
            launch {
                segmentDownloader(channelSegments, channelBytes, client)
                channelBytes.close()
            }
            for (deferred in channelBytes) {
                try {
                    val bytes = deferred.await()
                    sum += bytes.size.toLong()
                    onLiveSegmentDownloaded(bytes, sum)
                } catch (e: CancellationException) {
                    mt.println("downloader job cancelled")
                    onRecordingStopped(room)
                    throw e
                }
            }
            onRecordingStopped(room)
        }
        job = job1
        return job1
    }

    suspend fun join() {
        job?.join()
    }

    suspend fun stop() {
        job?.cancel()
        job?.join()
        onRecordingStopped(room)
    }

    override val coroutineContext: CoroutineContext
        get() = SupervisorJob() + CoroutineName("RoomRecorder-${room.id}-${room.name}")
}