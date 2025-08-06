package github.rikacelery.v2

import github.rikacelery.Event
import github.rikacelery.Room
import github.rikacelery.client
import github.rikacelery.proxiedClient
import github.rikacelery.utils.resilientSelect
import github.rikacelery.utils.withRetry
import github.rikacelery.utils.withRetryOrNull
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import okhttp3.internal.toLongOrDefault
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class Session(

    private val room: Room,
    private val dest: String,
    private val tmp: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + job)

    private val _isOpen = AtomicBoolean(false)
    private val _isActive = AtomicBoolean(false)
    val isActive: Boolean get() = _isActive.get()
    val isOpen: Boolean get() = _isOpen.get()
    var currentQuality = room.quality

    private val writerReference = AtomicReference<Writer?>(null)
    private var generatorJob: Job? = null

    private val total = AtomicInteger(0)
    private val success = AtomicInteger(0)
    private val successProxied = AtomicInteger(0)
    private val successDirect = AtomicInteger(0)
    private val failed = AtomicInteger(0)
    private val running = AtomicInteger(0)
    private val bytesWrite = AtomicLong(0)

    private val runningUrl = Hashtable<String, UrlInfo>()

    @Serializable
    enum class ClientType {
        DIRECT, PROXY
    }

    @Serializable
    data class UrlInfo(val type: ClientType, val startAt: Long)

    @Serializable
    data class Status(
        val total: Int,
        val success: Int,
        val failed: Int,
        val bytesWrite: Long,
        val running: Map<String, UrlInfo>
    )

    fun status(): Status {
        synchronized(runningUrl) {
            return Status(total.get(), success.get(), failed.get(), bytesWrite.get(), runningUrl.toMap())
        }
    }

    suspend fun testAndConfigure(): Boolean {
        val b = withRetryOrNull(3) {
            try {
                if (room.quality != "raw") {
                    val qualities =
                        withTimeout(5_000) {
                            val response = resilientSelect {
                                on {
                                    proxiedClient.get(
                                        "https://b-hls-06.doppiocdn.live/hls/%d/%d.m3u8?playlistType=lowLatency".format(
                                            room.id, room.id
                                        )
                                    )
                                }
                                on {
                                    client.get(
                                        "https://b-hls-06.doppiocdn.live/hls/%d/%d.m3u8?playlistType=lowLatency".format(
                                            room.id, room.id
                                        )
                                    )
                                }
                            }
                            require(response.status == HttpStatusCode.OK) {
                                "[${room.name}] 直播未开始"
                            }
                            response
                        }.bodyAsText().lines().filter {
                            it.startsWith("#EXT-X-RENDITION-REPORT") && !it.contains("blurred")
                        }.map {
                            it.substringAfter(":URI=\"").substringBefore("\"").substringAfter("_")
                                .substringBefore(".")
                        }
                    val q = qualities.lastOrNull { it.contains(room.quality) } ?: qualities.minByOrNull {
                        val split = it.split("p")
                        val split1 = room.quality.split("p")
                        if (split1.size == 2) {
                            // 尝试获取帧率和清晰度都接近的
                            abs(split[0].toInt() - split1[0].toInt()) + abs(split1[1].toInt() - split.getOrElse(1) { "30" }
                                .toInt())
                        } else {
                            // 只判断清晰度，选到什么纯看运气
                            abs(split[0].toInt() - split1[0].toInt())
                        }
                    }
                    val new = q ?: "raw" // 只有一种清晰度的
                    if (currentQuality != new && !isActive) {
                        println("[${room.name}] 更正清晰度设置 ${currentQuality} -> ${new}, 期望${room.quality}")
                        currentQuality = new
                    }
                    runCatching {
                        resilientSelect {
                            on { proxiedClient.get(streamUrl) }
                            on { client.get(streamUrl) }
                        }
                    }.getOrElse {
                        println("[${room.name}] 更正清晰度后目标直播流依然不可用: $streamUrl $it")
                        throw it
                    }
                } else {
                    currentQuality = room.quality
                    resilientSelect {
                        on {
                            proxiedClient.get(
                                "https://b-hls-06.doppiocdn.live/hls/%d/%d.m3u8?playlistType=lowLatency".format(
                                    room.id, room.id
                                )
                            )
                        }
                        on {
                            client.get(
                                "https://b-hls-06.doppiocdn.live/hls/%d/%d.m3u8?playlistType=lowLatency".format(
                                    room.id, room.id
                                )
                            )
                        }
                    }
                }
                true
            } catch (e: ClientRequestException) {
                false
            }
        } ?: false
        _isOpen.set(b)
        return b
    }

    suspend fun start() {
        if (!_isActive.compareAndSet(false, true)) {
            throw IllegalStateException("Session is already active")
        }
        println("[+] ${room.name} ${room.quality} ${currentQuality} $streamUrl")

        val writer = Writer(room.name, dest, tmp).apply { init() }
        writerReference.set(writer)
        total.set(0)
        success.set(0)
        failed.set(0)
        running.set(0)
        bytesWrite.set(0)

        try {
            generatorJob = scope.launch {
                val pending = mutableMapOf<Int, Deferred<Result<ByteArray>>>()
                val readyToEmit = PriorityQueue<Int>()
                var nextIndex = 0
                var emittedIndex = 0

                segmentGenerator()
                    .map { event ->
                        val index = nextIndex++
                        index to scope.async {
                            running.incrementAndGet()
                            total.incrementAndGet()
                            val result = runCatching {
                                synchronized(runningUrl) {
                                    runningUrl[event.url()] = UrlInfo(ClientType.DIRECT, System.currentTimeMillis())
                                }
                                tryDownload(event).await()?.also {
                                    successDirect.incrementAndGet()
                                } ?: run {
//                                    println("Falling back to proxy download for ${room.name}")
                                    synchronized(runningUrl) {
                                        runningUrl[event.url()] = UrlInfo(ClientType.PROXY, System.currentTimeMillis())
                                    }
                                    proxiedClient.get(event.url()).readBytes().also {
                                        successProxied.incrementAndGet()
                                    }
                                }
                            }
                            synchronized(runningUrl) {
                                runningUrl.remove(event.url())
                            }
                            result.onFailure {
                                if (event is Event.LiveSegmentInit) {
                                    throw InitSegmentDownloadFiledException(it)
                                } else {
                                    val created = (event.url().substringBeforeLast("_").substringAfterLast("_")
                                        .toLongOrDefault(0))
                                    val diff = System.currentTimeMillis() / 1000 - created
                                    println("Download segment:${index} failed, delayed: ${diff}ms")
                                }
                            }
                        }
                    }
                    .buffer(Channel.UNLIMITED)
                    .collect { (index, deferred) ->
                        pending[index] = deferred
                        readyToEmit.add(index)

                        while (readyToEmit.peek() == emittedIndex) {
                            val current = readyToEmit.poll()
                            val result = pending.remove(current)?.await()
                            running.decrementAndGet()
                            if (result != null && result.isSuccess) {
                                success.incrementAndGet()
                                val data = result.getOrThrow()
                                bytesWrite.addAndGet(data.size.toLong())
                                writer.append(data)
                            } else {
                                failed.incrementAndGet()
                                println("[${room.name}] Download segment:${index} failed (${result?.exceptionOrNull()?.cause?.message ?: result?.exceptionOrNull()?.message}).")
                            }
                            emittedIndex++
                        }
                    }
            }

            generatorJob?.join()
        } finally {
            println("[-] ${room.name} ${room.quality} ${currentQuality} $streamUrl")
        }
    }

    suspend fun stop() {
        if (_isActive.compareAndSet(true, false)) {
            generatorJob?.cancelAndJoin()
        }
        writerReference.getAndSet(null)?.done()
    }

    suspend fun dispose() {
        stop()
        job.complete()
        println("[${room.name}] Cleanup")
    }


    private val streamUrl: String
        get() {
            return if (currentQuality != "raw" && currentQuality.isNotBlank())
                "https://b-hls-%02d.doppiocdn.live/hls/%d/%d_%s.m3u8?playlistType=lowLatency".format(
                    Random().nextInt(1, 23), room.id, room.id, currentQuality
                )
            else
                "https://b-hls-%02d.doppiocdn.live/hls/%d/%d.m3u8?playlistType=lowLatency".format(
                    Random().nextInt(
                        1,
                        23
                    ), room.id, room.id
                )
        }

    private fun segmentGenerator(): Flow<Event> = flow {
        var started = false
        var initUrl: String = ""
        val cache = CircleCache(100)

        while (currentCoroutineContext().isActive) {
            val url = streamUrl
            try {
                val lines = withTimeout(15_000) {
                    proxiedClient.get(url).bodyAsText().lines()
                }
                val initUrl0 = parseInitUrl(lines)
                if (initUrl.isEmpty())
                    initUrl = initUrl0
                if (initUrl0 != initUrl) {
                    println("[${room.name}] Init segment changed, exiting...")
                    break
                }
                val videos = parseSegmentUrl(lines)

                if (!started) {
                    started = true
                    emit(Event.LiveSegmentInit(initUrl0, room))
                }

                videos.forEach { url ->
                    if (currentCoroutineContext().isActive && !cache.contains(url)) {
                        cache.add(url)
                        emit(Event.LiveSegmentData(url, initUrl0, room))
                    }
                }
            } catch (e: TimeoutCancellationException) {
                println("[ERROR]Refresh List: $url")
                println("[${room.name}] Segment generator timeout, retrying...")
            } catch (e: ClientRequestException) {
                if (shouldStop()(e)) {
                    scope.launch { stop() }
                } else {
                    println("[${room.name}] Generator error: ${e.message}")
                }
            } catch (_: CancellationException) {
                println("[${room.name}] Segment generator is cancelled, exiting...")
                break
            } catch (e: Exception) {
                println("[${room.name}] Unexpected error in segment generator: ${e.message}")
            }

            delay(500)
        }
        println("[${room.name}] Segment generator exited.")
    }

    private fun parseSegmentUrl(lines: List<String>): List<String> {
        return lines.filter { it.startsWith("#EXT-X-PART") && it.contains("URI=\"") }
            .mapNotNull { line ->
                try {
                    line.substringAfter("URI=\"").substringBefore("\"")
                } catch (e: Exception) {
                    println("Failed to parse segment URL from line: $line")
                    null
                }
            }
    }

    private fun parseInitUrl(lines: List<String>): String {
        return try {
            lines.first { it.startsWith("#EXT-X-MAP") }
                .substringAfter("#EXT-X-MAP:URI=")
                .removeSurrounding("\"")
        } catch (e: NoSuchElementException) {
            throw IllegalArgumentException("Missing #EXT-X-MAP tag in playlist", e)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse init URL", e)
        }
    }

    private fun tryDownload(event: Event): Deferred<ByteArray?> = scope.async {
        val c = when (event) {
            is Event.LiveSegmentData -> client
            is Event.LiveSegmentInit -> proxiedClient
        }
        val created = (event.url().substringBeforeLast("_").substringAfterLast("_").toLongOrDefault(0))
        val diff = System.currentTimeMillis() / 1000 - created
        val wait = (17L - diff) * 1000
        withTimeoutOrNull(if (wait > 0) wait else 0) {
            withRetry(25) { attempt ->
                try {
                    c.get(event.url()).readBytes()
                } catch (_: TimeoutCancellationException) {
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
//                    println("Download attempt $attempt failed: ${e.message}")
                    throw e
                }
            }
        }
    }

    class InitSegmentDownloadFiledException(cause: Throwable) : Throwable(cause)

    private fun shouldStop(): (Throwable) -> Boolean = { error ->
        error is ClientRequestException && error.response.status == HttpStatusCode.NotFound ||
                error is ClientRequestException && error.response.status == HttpStatusCode.Forbidden ||
                error is CancellationException ||
                !isActive
    }
}
