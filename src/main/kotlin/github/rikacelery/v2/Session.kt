package github.rikacelery.v2

import github.rikacelery.Event
import github.rikacelery.Room
import github.rikacelery.utils.*
import github.rikacelery.v2.exceptions.DeletedException
import github.rikacelery.v2.exceptions.RenameException
import github.rikacelery.v2.metric.Metric
import github.rikacelery.v2.metric.MetricUpdater
import github.rikacelery.v2.postprocessors.PostProcessor
import github.rikacelery.v2.postprocessors.ProcessorCtx
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import okhttp3.internal.toLongOrDefault
import java.util.*
import java.util.concurrent.TimeoutException
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
    companion object {
        val KEY = String(
            String(
                Base64.getDecoder().decode("NTEgNzUgNjUgNjEgNmUgMzQgNjMgNjEgNjkgMzkgNjIgNmYgNGEgNjEgMzUgNjE=")
            ).split(" ").map { it.toByte(16) }.toByteArray()
        )
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + job)

    private val _isOpen = AtomicBoolean(false)
    private val _isActive = AtomicBoolean(false)
    val isActive: Boolean get() = _isActive.get()
    val isOpen: Boolean get() = _isOpen.get()
    var currentQuality = room.quality

    private val writerReference = AtomicReference<Writer?>(null)
    private var generatorJob: Job? = null

    //metrics
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
        val total: Int, val success: Int, val failed: Int, val bytesWrite: Long, val running: Map<String, UrlInfo>
    )

    fun status(): Status {
        synchronized(runningUrl) {
            return Status(total.get(), success.get(), failed.get(), bytesWrite.get(), runningUrl.toMap())
        }
    }

    /**
     * @throws github.rikacelery.v2.exceptions.RenameException
     * @throws github.rikacelery.v2.exceptions.DeletedException
     */
    suspend fun testAndConfigure(): Boolean {
        try {
            val get = ClientManager.getProxiedClient()
                .get("https://zh.xhamsterlive.com/api/front/v1/broadcasts/${room.name}") {
                    this.expectSuccess = false
                }
            if (get.status == HttpStatusCode.NotFound) {
                val reason =
                    runCatching { Json.Default.parseToJsonElement(get.bodyAsText()).String("description") }.getOrNull()
                if (reason == null) {
                    return false
                }
                when {
                    reason.matches("Model has new name: newName=(.*)".toRegex()) -> {
                        throw RenameException(
                            "Model has new name: newName=(.*)".toRegex().find(reason)!!.groupValues[1]
                        )
                    }

                    reason == "model already deleted" -> {
                        throw DeletedException(room.name)
                    }
                }
            }

            val element = Json.Default.parseToJsonElement(get.bodyAsText())
            val status = element.PathSingle("item.status").asString()
            val presets = element.PathSingle("item.settings.presets").jsonArray
            if (status != "public") {
                _isOpen.set(false)
                return false//不开播
            }
            if (room.quality == "raw") {
                _isOpen.set(true)
                return true
            }
            val qualities = presets.map { element -> element.asString()}
                .filterNot { it.contains("blurred") }
            val q = qualities.lastOrNull { it == room.quality } ?: qualities.minByOrNull {
                val split = it.split("p").filterNot(String::isEmpty)
                val split1 = room.quality.split("p").filterNot(String::isEmpty)
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
            _isOpen.set(false)
            return true
        } catch (e: ClientRequestException) {
            println(e.stackTraceToString())
        } catch (e: TimeoutException) {
            println(e.stackTraceToString())
        } catch (e: Exception) {
            println(e.stackTraceToString())
        }
        println("[WARNING] [${room.name}] Using deprecated quality selecting logic")
        val b = withRetryOrNull(3) {
            try {
                if (room.quality != "raw") {
                    val qualities = withTimeout(9_000) {
                        val response = ClientManager.getProxiedClient().get(
                            "https://b-hls-06.doppiocdn.live/hls/%d/%d.m3u8?playlistType=lowLatency".format(
                                room.id, room.id
                            )
                        )
                        require(response.status == HttpStatusCode.OK) {
                            "[${room.name}] 直播未开始"
                        }
                        response
                    }.bodyAsText().lines().filter {
                        it.startsWith("#EXT-X-RENDITION-REPORT") && !it.contains("blurred")
                    }.map {
                        it.substringAfter(":URI=\"").substringBefore("\"").substringAfter("_").substringBefore(".m3u8")
                    }
                    val q = qualities.lastOrNull { it == room.quality } ?: qualities.minByOrNull {
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
                        ClientManager.getProxiedClient().get(streamUrl)
                    }.getOrElse {
                        println("[${room.name}] 更正清晰度后目标直播流依然不可用: $streamUrl $it")
                        throw it
                    }
                } else {
                    currentQuality = room.quality
                    ClientManager.getProxiedClient().get(
                        "https://b-hls-06.doppiocdn.live/hls/%d/%d.m3u8?playlistType=lowLatency".format(
                            room.id, room.id
                        )
                    )
                }
                true
            } catch (_: ClientRequestException) {
                false
            }
        } ?: false
        _isOpen.set(b)
        return b
    }

    fun createDiscontinuityCounter(): suspend (List<Int>) -> Int {
        var lastMax: Int? = null        // 上次输入的最大值
        var totalGaps = 0               // 累计的不连续数字个数（所有间隙之和）
        val lock = Mutex()
        return counter@{ numbers: List<Int> ->
            lock.withLock {
                if (numbers.isEmpty()) return@counter totalGaps

                val currentMin = numbers.first()      // 连续递增，首项最小
                val currentMax = numbers.last()       // 尾项最大

                if (lastMax != null) {
                    val gap = currentMin - lastMax!! - 1
                    if (gap > 0) {
                        totalGaps += gap
                    }
                    // 如果 gap <= 0，说明重叠或紧接，无新增不连续
                }

                lastMax = currentMax  // 更新状态
                totalGaps
            }
        }
    }

    fun segmentIDFromUrl(url: String): Int? {
        val parts = url.substringAfterLast("/").split("_")
        return parts[(parts.size - 4).coerceAtLeast(0)].toIntOrNull()
    }

    var metric: MetricUpdater? = null
    suspend fun start() {
        if (!_isActive.compareAndSet(false, true)) {
            throw IllegalStateException("Session is already active")
        }
        println("[+] ${room.name} ${room.quality} ${currentQuality} $streamUrl")

        val writer = Writer(room.name, dest, tmp).apply { init() }
        writerReference.set(writer)
        metric = Metric.newMetric(room.id, room.name)
        val metric = metric!!
        val counter = createDiscontinuityCounter()
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

                segmentGenerator().map { event ->
                    segmentIDFromUrl(event.url())?.let { segmentID ->
                        metric.segmentID(segmentID)
                        metric.segmentMissing(counter(listOf(segmentID)))
                        metric.quality(currentQuality)
                    }
                    val index = nextIndex++
                    index to scope.async {
                        metric.downloadingIncrement()
                        metric.totalIncrement()
                        running.incrementAndGet()
                        total.incrementAndGet()
                        val ms = System.currentTimeMillis()
                        val result = runCatching {
                            synchronized(runningUrl) {
                                runningUrl[event.url()] = UrlInfo(ClientType.DIRECT, System.currentTimeMillis())
                            }
                            tryDownload(event).await()?.also {
                                metric.successDirectIncrement()
                                successDirect.incrementAndGet()
                            } ?: run {
//                                    println("Falling back to proxy download for ${room.name}")
                                synchronized(runningUrl) {
                                    runningUrl[event.url()] = UrlInfo(ClientType.PROXY, System.currentTimeMillis())
                                }
                                withRetry(2) {
                                    ClientManager.getProxiedClient().get(
                                        event.url()
                                    ).readBytes().also {
                                        metric.successProxiedIncrement()
                                        successProxied.incrementAndGet()
                                    }
                                }
                            }
                        }
                        metric.updateLatency(System.currentTimeMillis() - ms)
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
                }.buffer(Channel.UNLIMITED).collect { (index, deferred) ->
                    pending[index] = deferred
                    readyToEmit.add(index)

                    while (readyToEmit.peek() == emittedIndex) {
                        val current = readyToEmit.poll()
                        val result = pending.remove(current)?.await()
                        metric.downloadingDecrement()
                        metric.doneIncrement()
                        running.decrementAndGet()
                        if (result != null && result.isSuccess) {
                            success.incrementAndGet()
                            val data = result.getOrThrow()
                            metric.bytesWriteIncrement(data.size.toLong())
                            bytesWrite.addAndGet(data.size.toLong())
                            writer.append(data)
                        } else {
                            metric.failedIncrement()
                            failed.incrementAndGet()
                            println("[${room.name}] Download segment:${index} failed (${result?.exceptionOrNull()?.cause?.message ?: result?.exceptionOrNull()?.message}).")
                            result?.exceptionOrNull()?.printStackTrace()
                        }
                        emittedIndex++
                    }
                }
            }

            generatorJob?.join()
        } finally {
            println("[-] ${room.name} ${room.quality} ${currentQuality} $streamUrl")
            Metric.removeMetric(room.id)
        }
    }

    suspend fun stop() {
        if (_isActive.compareAndSet(true, false)) {
            generatorJob?.cancelAndJoin()
        }
        val file = writerReference.getAndSet(null)?.done()
        if (file == null) {
            return
        }
        runCatching {
            PostProcessor.process(
                file.first,
                ProcessorCtx(room, file.second, Date(), file.third, currentQuality)
            )
        }.onFailure {
            it.printStackTrace()
        }
    }


    private val streamUrl: String
        get() {
            return if (currentQuality != "raw" && currentQuality.isNotBlank()) "https://media-hls.doppiocdn.org/b-hls-%d/%d/%d_%s.m3u8?playlistType=lowLatency".format(
                Random().nextInt(12, 13), room.id, room.id, currentQuality
            )
            else "https://media-hls.doppiocdn.org/b-hls-%d/%d/%d.m3u8?playlistType=lowLatency".format(
                Random().nextInt(
                    12, 13
                ), room.id, room.id
            )
        }


    private val edgeUrl: String
        get() {
            return if (currentQuality != "raw" && currentQuality.isNotBlank()) "https://edge-hls.doppiocdn.com/hls/%d/master/%d_%s.m3u8".format(
                room.id, room.id, currentQuality
            )
            else "https://edge-hls.doppiocdn.com/hls/%d/master/%d.m3u8".format(
                room.id, room.id
            )
        }

    private fun segmentGenerator(): Flow<Event> = flow {
        var started = false
        var initUrl = ""
        val cache = CircleCache(100)
        var retry = 0
        var ms = System.currentTimeMillis()
        var startTime = ms
        val mouflon = ClientManager.getProxiedClient().get(edgeUrl).bodyAsText().lines()
            .singleOrNull { it.startsWith("#EXT-X-MOUFLON") }
        val pk = mouflon?.substringAfterLast(":")
        val regexCache = """media-hls\.doppiocdn\.\w+/(b-hls-\d+)""".toRegex()
        while (currentCoroutineContext().isActive) {
            retry++

            val url = streamUrl
            try {
                val lines = withTimeout(5_000) {
                    val rawList = (ClientManager.getProxiedClient()).get(
                        url
                    ) {
                        if (pk != null) {
                            parameter("psch", "v1")
                            parameter("pkey", pk)
                        }
                    }.bodyAsText().lines()
                    val newList = mutableListOf<String>()
                    for (idx in rawList.indices) {
                        if (rawList[idx].startsWith("#EXT-X-MOUFLON:FILE:")) {
                            val enc = rawList[idx].substringAfterLast(":")
                            val dec = try {
                                Decrypter.decode(enc, KEY)
                            } catch (e: Exception) {
                                println("[ERROR] failed to decrypt $enc")
                                throw e
                            }
                            newList.add(rawList[idx + 1].replace("media.mp4", dec))
                        } else {
                            newList.add(rawList[idx])
                        }
                    }

                    newList.filterNot { it.contains("media.mp4") }.map {
                        it.replace(regexCache) { it ->
                            "${it.groupValues[1]}.doppiocdn.live"
                        }
                    }
                }

                metric?.updateRefreshLatency(System.currentTimeMillis() - ms)
                ms = System.currentTimeMillis()
                val initUrlCur = parseInitUrl(lines)
                if (initUrl.isEmpty()) initUrl = initUrlCur
                if (initUrlCur != initUrl) {
                    println("[${room.name}] Init segment changed, exiting...")
                    break
                }
                val videos = parseSegmentUrl(lines)

                if (!started) {
                    started = true
                    emit(Event.LiveSegmentInit(initUrlCur, room))
                }

                for (url in videos) {
                    // record time limit
                    if (System.currentTimeMillis() - startTime > room.limit.inWholeMilliseconds && !cache.contains(url) && url.endsWith(
                            "_part0.mp4"
                        )
                    ) {
                        try {
                            val file = writerReference.get()!!.done()
                            requireNotNull(file)
                            scope.launch(NonCancellable) {
                                runCatching {
                                    PostProcessor.process(
                                        file.first,
                                        ProcessorCtx(room, file.second, Date(), file.third, currentQuality)
                                    )
                                }.onFailure {
                                    it.printStackTrace()
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            // reset state
                            cache.clear()
                            started = false
                            initUrl = ""
                            startTime = System.currentTimeMillis()
                            writerReference.get()?.init()
                            break
                        }
                    }
                    // normal segments
                    if (currentCoroutineContext().isActive && !cache.contains(url)) {
                        cache.add(url)
                        emit(Event.LiveSegmentData(url, initUrlCur, room))
                    }
                }
                retry = 0
            } catch (_: TimeoutCancellationException) {
                println("[ERROR] [${room.name}] Refresh list timeout: $url trys:$retry")
                if (!runCatching { testAndConfigure() }.getOrElse { false }) {
                    println("[STOP] [${room.name}] Room off or non-public: $room trys:$retry")
                    break
                }
                continue
            } catch (e: ClientRequestException) {
                if (e.response.status.value == 404) {
                    println("[STOP] [${room.name}] Room off or non-public: $room trys:$retry")
                    break
                }
                if (e.response.status.value == 403) {
                    if (!runCatching { testAndConfigure() }.getOrElse { false }) {
                        println("[STOP] [${room.name}] Room off or non-public: $room trys:$retry")
                        break
                    } else if (currentQuality == "raw") {
                        // https://github.com/RikaCelery/XhRec/issues/2
                        println("[WARNING] [${room.name}] Unable to use 'raw' quality, try using the highest one. Room will stop record now.")
                        room.quality = "2560p60" // try selecting the highest quality
                        break
                    } else {
                        println("[ERROR] [${room.name}] Refresh list error: $url ${e.response.status}. Stop recording.")
                        break
                    }
                }
            } catch (e: CombinedException) {
                if (e.exceptions.any(shouldStop())) {
                    scope.launch { stop() }
                    break
                } else {
                    println("[${room.name}] Generator error: ${e.message}")
                }
            } catch (_: CancellationException) {
                println("[${room.name}] Segment generator is cancelled, exiting...")
                break
            } catch (e: Exception) {
                println("[${room.name}] Unexpected error in segment generator: ${e.message}")
                e.printStackTrace()
                if (!runCatching { testAndConfigure() }.getOrElse { false }) {
                    println("[STOP] [${room.name}] Room off or non-public:: $room trys:$retry")
                    break
                }
            }

            delay(500)
        }
        println("[${room.name}] Segment generator exited.")
    }

    private fun parseSegmentUrl(lines: List<String>): List<String> {
        return lines.filter { it.startsWith("#EXT-X-PART") && it.contains("URI=\"") }.mapNotNull { line ->
            try {
                line.substringAfter("URI=\"").substringBefore("\"")
            } catch (e: Exception) {
                println("Failed to parse segment URL from line: $line, $e")
                null
            }
        }
    }

    private fun parseInitUrl(lines: List<String>): String {
        return try {
            lines.first { it.startsWith("#EXT-X-MAP") }.substringAfter("#EXT-X-MAP:URI=").removeSurrounding("\"")
        } catch (e: NoSuchElementException) {
            throw IllegalArgumentException("Missing #EXT-X-MAP tag in playlist", e)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse init URL", e)
        }
    }

    private fun tryDownload(event: Event): Deferred<ByteArray?> = scope.async {
        val c = when (event) {
            is Event.LiveSegmentData -> ClientManager.getClient()
            is Event.LiveSegmentInit -> ClientManager.getProxiedClient()
        }
        val created = (event.url().substringBeforeLast("_").substringAfterLast("_").toLongOrDefault(0))
        val diff = System.currentTimeMillis() / 1000 - created
        val wait = (16L - diff) * 1000
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
        error is ClientRequestException && error.response.status == HttpStatusCode.NotFound || error is ClientRequestException && error.response.status == HttpStatusCode.Forbidden || error is CancellationException || !isActive
    }
}
