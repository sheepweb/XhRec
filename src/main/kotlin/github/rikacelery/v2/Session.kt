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
import org.slf4j.LoggerFactory
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
        val KEY2 = String(
            String(
                Base64.getDecoder().decode("NWEgNmYgNmIgNjUgNjUgMzIgNGYgNjggNTAgNjggMzkgNmIgNzUgNjcgNjggMzQ=")
            ).split(" ").map { it.toByte(16) }.toByteArray()
        )

        private val logger = LoggerFactory.getLogger(Session::class.java)
    }

    private val scope =
        CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { coroutineContext, throwable ->
            logger.error("Exception in {}", coroutineContext, throwable)
        })

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
    private val replacedUrl = Hashtable<String, Boolean>()

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
            return Status(total.get(), success.get(), failed.get(), bytesWrite.get(), runningUrl.toMap().mapKeys {
                if (replacedUrl[it.key] == true){
                    it.key.replace(regexCache) { result ->
                        "${result.groupValues[1]}.doppiocdn.live"
                    }
                }else{
                    it.key
                }
            })
        }
    }

    /**
     * @throws github.rikacelery.v2.exceptions.RenameException
     * @throws github.rikacelery.v2.exceptions.DeletedException
     */
    suspend fun testAndConfigure(): Boolean {
        try {
            val get = ClientManager.getProxiedClient("room-test")
                .get("https://zh.xhamsterlive.com/api/front/v1/broadcasts/${room.name}") {
                    this.expectSuccess = false
                }
            logger.trace("[{}] request api code={}", room.name, get.status.value)
            if (get.status == HttpStatusCode.NotFound) {
                val reason =
                    runCatching { Json.Default.parseToJsonElement(get.bodyAsText()).String("description") }.getOrNull()
                logger.trace("[{}] request api reason={}", room.name, reason)
                if (reason == null) {
                    logger.trace("[{}] -> false", room.name)
                    _isOpen.set(false)
                    return false
                }
                when {
                    reason.matches("Model has new name: newName=(.*)".toRegex()) -> {
                        val newName = "Model has new name: newName=(.*)".toRegex().find(reason)!!.groupValues[1]
                        logger.debug("[{}] model renamed to {}", room.name, newName)
                        throw RenameException(
                            newName
                        )
                    }

                    reason == "model already deleted" -> {
                        logger.debug("[{}] model deleted", room.name)
                        throw DeletedException(room.name)
                    }
                }
            }

            val element = Json.Default.parseToJsonElement(get.bodyAsText())
            val status = element.PathSingle("item.status").asString()
            val presets = element.PathSingle("item.settings.presets").jsonArray
            if (status != "public") {
                logger.trace("[{}] -> false, status={}", room.name, status)
                _isOpen.set(false)
                return false//不开播
            }
            if (room.quality == "raw") {
                logger.trace("[{}] -> true, skip quality selection for 'raw'", room.name)
                _isOpen.set(true)
                return true
            }
            val qualities = presets.map { element -> element.asString() }
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
            logger.trace("[{}] select {} from {} (want {})", room.name, new, qualities, room.quality)
            if (currentQuality != new && !isActive) {
                logger.info("[{}] quality changed to {} (want {})", room.name, new, room.quality)
                currentQuality = new
            }
            logger.trace("[{}] -> true", room.name)
            _isOpen.set(true)
            return true
        } catch (e: ClientRequestException) {
            println(e.stackTraceToString())
        } catch (e: TimeoutException) {
            println(e.stackTraceToString())
        } catch (e: Exception) {
            println(e.stackTraceToString())
        }
        logger.warn("[{}] Failed to check room state", room.name)
        logger.trace("[{}] -> true", room.name)
        _isOpen.set(false)
        return false
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
        logger.info("[+] start recording {}({}) q:{}(want {})", room.name, room.id, currentQuality, room.quality)

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
                                    ClientManager.getProxiedClient(room.name).get(
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
                                logger.error("failed to download init segment {}, download stopped", event.url())
                                throw InitSegmentDownloadFiledException(it)
                            } else {
                                val created = (event.url().substringBeforeLast("_").substringAfterLast("_")
                                    .toLongOrDefault(0))
                                val diff = System.currentTimeMillis() / 1000 - created
                                logger.warn(
                                    "Download segment:{} failed({}), delayed: {}s",
                                    index,
                                    (it as? ClientRequestException)?.response?.status?.value ?: it.message,
                                    diff
                                )
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
                        }
                        emittedIndex++
                    }
                }
            }

            generatorJob?.join()
        } finally {
            logger.info("[-] stop recording {}({}) q:{}(want {})", room.name, room.id, currentQuality, room.quality)
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
            logger.error("[{}] Postprocess failed", room.name, it)
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

    private fun segmentGenerator(): Flow<Event> = flow {
        var started = false
        var initUrl = ""
        val cache = CircleCache(100)
        var retry = 0
        var ms = System.currentTimeMillis()
        var startTime = ms
        var useRawCDN:Boolean? = false
        while (currentCoroutineContext().isActive) {
            retry++
            val url = streamUrl
            try {
                val lines = withTimeout(5_000) {
                    val rawList = (ClientManager.getProxiedClient(room.name)).get(
                        url
                    ) {
                        parameter("psch", "v1")
                        parameter("pkey", KEY2)
                    }.bodyAsText().lines()
                    val newList = mutableListOf<String>()
                    for (idx in rawList.indices) {
                        if (rawList[idx].startsWith("#EXT-X-MOUFLON:FILE:")) {
                            val enc = rawList[idx].substringAfterLast(":")
                            val dec = try {
                                Decrypter.decode(enc, KEY)
                            } catch (e: Exception) {
                                logger.error("[ERROR] failed to decrypt $enc", e)
                                throw e
                            }
                            newList.add(rawList[idx + 1].replace("media.mp4", dec))
                        } else {
                            newList.add(rawList[idx])
                        }
                    }

                    newList.filterNot { it.contains("media.mp4") }
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

                val replacedInitUrl = initUrlCur.replace(regexCache) { it ->
                    "${it.groupValues[1]}.doppiocdn.live"
                }
                if (useRawCDN==null) try {
                    ClientManager.getProxiedClient(room.name).get(replacedInitUrl).readBytes()
                    useRawCDN=true
                }catch (e: ClientRequestException) {
                    useRawCDN=false
                }catch (e: Exception){
                    throw e
                }
                requireNotNull(useRawCDN) // stupid type infer
                if (!started) {
                    started = true

                    emit(Event.LiveSegmentInit(if (useRawCDN)replacedInitUrl  else initUrlCur, room))
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
                                    logger.error("[{}] Postprocess failed", room.name, it)
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("[${room.name}] Failed to postprocess", e)
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
                        emit(Event.LiveSegmentData(if (useRawCDN)url.replace(regexCache) { it ->
                            "${it.groupValues[1]}.doppiocdn.live"
                        }else url, if (useRawCDN) replacedInitUrl  else initUrlCur, room))
                    }
                }
                retry = 0
            } catch (_: TimeoutCancellationException) {
                logger.warn("[${room.name}] Refresh list timeout, trys=$retry")
                if (!runCatching { testAndConfigure() }.getOrElse { false }) {
                    logger.info("[STOP] [{}] Room off or non-public", room.name)
                    break
                }
                continue
            } catch (e: ClientRequestException) {
                if (e.response.status.value == 404) {
                    logger.info("[STOP] [{}] Room off or non-public (404)", room.name)
                    break
                }
                if (e.response.status.value == 403) {
                    if (!runCatching { testAndConfigure() }.getOrElse { false }) {
                        logger.info("[STOP] [{}] Room off or non-public (403)", room.name)
                        break
                    } else if (currentQuality == "raw") {
                        // https://github.com/RikaCelery/XhRec/issues/2
                        logger.warn(
                            "[{}] Unable to use 'raw' quality, try using the highest one. Room will stop record now",
                            room.name
                        )
                        room.quality = "2560p60" // try selecting the highest quality
                        break
                    } else {
                        logger.error(
                            "[STOP] [{}] Refresh list error {}. Stop recording",
                            room.name,
                            e.response.status.value
                        )
                        break
                    }
                }
            } catch (e: CombinedException) {
                if (e.exceptions.any(shouldStop())) {
                    scope.launch { stop() }
                    break
                } else {
                    logger.error("[{}] Generator error", room.name, e)
                }
            } catch (_: CancellationException) {
                logger.error("[{}] Segment generator is cancelled, exiting...", room.name)
                break
            } catch (e: Exception) {
                logger.error("[{}] Unexpected error in segment generator", room.name, e)
                if (!runCatching { testAndConfigure() }.getOrElse { false }) {
                    logger.error("[STOP] [{}] Room off or non-public:", room.name)
                    break
                }
            }

            delay(500)
        }
        logger.info("[${room.name}] Segment generator exited.")
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
    private val regexCache = """media-hls\.doppiocdn\.\w+/(b-hls-\d+)""".toRegex()

    private fun tryDownload(event: Event): Deferred<ByteArray?> = scope.async {
        val c = when (event) {
            is Event.LiveSegmentData -> ClientManager.getClient(room.name)
            is Event.LiveSegmentInit -> ClientManager.getProxiedClient(room.name)
        }
        val created = (event.url().substringBeforeLast("_").substringAfterLast("_").toLongOrDefault(0))
        val diff = System.currentTimeMillis() / 1000 - created
        val wait = (20L - diff) * 1000
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
