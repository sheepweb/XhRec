package github.rikacelery

import github.rikacelery.utils.withRetry
import github.rikacelery.utils.withRetryOrNull
import io.ktor.client.*
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.CoroutineContext

class RoomRecorder(
    val room: Room,
    private val client: HttpClient,
    var onLiveSegmentDownloaded: (b: ByteArray, sum: Long) -> Unit = { _, _ -> }
) : CoroutineScope {
    var onRecordingStarted: (room: Room) -> Unit = {}
    var onLiveStopped: (room: Room) -> Unit = {}
    var onRecordingStopped: (room: Room) -> Unit = {}
    var flag = false


    sealed interface Event {
        class LiveSegmentInit(val url: String, room: Room) : Event
        class LiveSegmentData(val url: String, val initUrl: String, room: Room) : Event
    }

    private var job: Job? = null

    val streamUrl: String
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
                val lines = withTimeout(2000) {
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
                        println("[${room.name}] 正在更正清晰度设置")
                        val q = qualities.lastOrNull { it.contains("720p") } ?: qualities.last()
                        room.quality = q
                        println("[${room.name}] 更正清晰度设置 ${q}")
                    }
                }.onFailure {
                    println("[${room.name}] 无法更正清晰度设置 $it")
                }.onSuccess {
                    flag = true
                }
                initUrl = lines.first() { it.startsWith("#EXT-X-MAP") }.substringAfter("#EXT-X-MAP:URI=")
                    .removeSurrounding("\"")
                if (!started) {
                    started = true
                    onRecordingStarted(room)
                }
                channel.send(Event.LiveSegmentInit(initUrl, room))
                lines.filter { it.startsWith("#EXT-X-PART") && it.contains("URI=\"") }
                    .map { it.substringAfter("URI=\"").substringBefore("\"") }.forEach {
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
                            return
                        }
                        println("generator: code: " + it.response.status.value)
                    }

                    is TimeoutCancellationException -> continue
                    is SocketTimeoutException -> continue
                    is SSLHandshakeException -> continue
                    is CancellationException -> return

                    else -> {
                        it.printStackTrace()
                    }
                }
            }
            delay(1000)
        }
    }

    private suspend fun segmentDownloader(
        channel: Channel<Event>, outputChannel: Channel<Deferred<ByteArray>>, client: HttpClient
    ) {
        var initBytes = ByteArray(0)
        supervisorScope {
            for (segmentUrl in channel) {
                when (segmentUrl) {
                    is Event.LiveSegmentData -> runCatching {
                        async {
                            withRetry(50, {
                                // stop retry if 404
                                it is ClientRequestException && it.response.status == HttpStatusCode.NotFound
                            }) {
                                client.get(segmentUrl.url).readBytes()
                            }
                        }.let {
                            outputChannel.send(it)
                        }
                    }.onFailure {
                        if (it is CancellationException) throw it
                        println("failure," + room.name + " " + segmentUrl.url)
                        outputChannel.send(async { initBytes })
                    }

                    is Event.LiveSegmentInit -> runCatching {
                        outputChannel.send(async { client.get(segmentUrl.url).readBytes().also { initBytes = it } })
                    }
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
                    println("downloader job cancelled")
                    onRecordingStopped(room)
                    throw e
                } catch (e: Exception) {
                    println("downloader job error" + e.message)
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