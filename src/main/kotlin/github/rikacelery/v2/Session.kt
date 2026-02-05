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
import java.io.File
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
        private val MODEL_RENAME_REGEX = "Model has new name: newName=(.*)".toRegex()

        val DECRYPT_KEY = String(
            String(
                Base64.getDecoder().decode("NTEgNzUgNjUgNjEgNmUgMzQgNjMgNjEgNjkgMzkgNjIgNmYgNGEgNjEgMzUgNjE=")
            ).split(" ").map { it.toByte(16) }.toByteArray()
        )
        val DECRYPT_KEY_V2 = String(
            String(
                Base64.getDecoder().decode("NDUgNTEgNzUgNjUgNjUgNDcgNjggMzIgNmIgNjEgNjUgNzcgNjEgMzMgNjMgNjg=")
            ).split(" ").map { it.toByte(16) }.toByteArray()
        )
        val AUTH_KEY = String(
            String(
                Base64.getDecoder().decode("NWEgNmYgNmIgNjUgNjUgMzIgNGYgNjggNTAgNjggMzkgNmIgNzUgNjcgNjggMzQ=")
            ).split(" ").map { it.toByte(16) }.toByteArray()
        )
        val AUTH_KEY_V2 = String(
            String(
                Base64.getDecoder().decode("NGYgNmYgNmIgMzcgNzEgNzUgNjEgNjkgNGUgNjcgNjkgNzkgNzUgNjggNjEgNjk=")
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

    // 当清晰度对应的流返回 404 时,回退到 source (无后缀 URL)
    private var useSourceFallback = false

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
                if (replacedUrl[it.key] == true) {
                    it.key.replace(regexCache) { result ->
                        "${result.groupValues[1]}.doppiocdn.live"
                    }
                } else {
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
                    MODEL_RENAME_REGEX.matches(reason) -> {
                        val newName = MODEL_RENAME_REGEX.find(reason)!!.groupValues[1]
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
            // 从 v2 API 获取 pixelated 数组
            val camResponse = ClientManager.getProxiedClient("room-test")
                .get("https://zh.xhamsterlive.com/api/front/v2/models/username/${room.name}/cam")
            val camElement = Json.Default.parseToJsonElement(camResponse.bodyAsText())

            // pixelated 在 cam.broadcastSettings.presets.pixelated 路径下
            val pixelated = camElement.PathSingle("cam.broadcastSettings.presets.pixelated").jsonArray
            val qualities = pixelated.map { it.asString() }
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
//        "https://media-hls.doppiocdn.org/b-hls-24/roomid/roomid_480p_h265_7970_XXXXXXXXXXX_timestamp.mp4"
        val parts = url.substringAfterLast("/").split("_")
        return parts[(parts.size - 3).coerceAtLeast(0)].toIntOrNull()
    }

    var metric: MetricUpdater? = null
    suspend fun start() {
        // 重置回退状态，允许新的录制尝试使用指定清晰度
        useSourceFallback = false

        if (!_isActive.compareAndSet(false, true)) {
            throw IllegalStateException("Session is already active")
        }
        logger.info(
            "[+] start recording {}({}) q:{}(want {}) open:{}",
            room.name,
            room.id,
            currentQuality,
            room.quality,
            isOpen
        )

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
                // TODO: 如果 pending 积压过多可能导致 OOM，考虑添加超时跳过机制
                val pending = mutableMapOf<Int, Deferred<Result<ByteArray>>>()
                val readyToEmit = PriorityQueue<Int>()
                var nextIndex = 0
                var emittedIndex = 0

                // 用于标记切分事件的特殊索引
                val SPLIT_MARKER = -1

                segmentGenerator().map { event ->
                    // FileSplit 事件不需要下载，直接返回特殊标记
                    if (event is Event.FileSplit) {
                        return@map SPLIT_MARKER to scope.async { Result.success(ByteArray(0)) }
                    }

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
                                    diff / 1000
                                )
                            }
                        }
                        // 调试日志：下载完成时记录数据大小
                        result.onSuccess { data ->
                            logger.debug("[{}] Segment {} downloaded, size={}KB", room.name, index, data.size / 1024)
                        }
                    }
                }.buffer(Channel.UNLIMITED).collect { (index, deferred) ->
                    // 调试日志：监控内存和pending状态
                    if (index % 10 == 0 || pending.size > 20) {
                        val runtime = Runtime.getRuntime()
                        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                        val maxMB = runtime.maxMemory() / 1024 / 1024
                        logger.debug(
                            "[{}] Memory: {}MB/{}MB, pending.size={}, readyToEmit.size={}, nextIndex={}, emittedIndex={}, index={}",
                            room.name, usedMB, maxMB, pending.size, readyToEmit.size, nextIndex, emittedIndex, index
                        )
                        if (pending.size > 20) {
                            val waitingIndices = pending.keys.sorted().take(10)
                            logger.warn(
                                "[{}] Large pending detected! Waiting for indices: {}, emittedIndex={}",
                                room.name, waitingIndices, emittedIndex
                            )
                        }
                    }

                    // 处理 FileSplit 事件
                    if (index == SPLIT_MARKER) {
                        logger.info("[{}] FileSplit event received, waiting for pending downloads...", room.name)
                        // 等待所有 pending 下载完成并写入当前文件
                        while (readyToEmit.isNotEmpty() || pending.isNotEmpty()) {
                            if (readyToEmit.peek() == emittedIndex) {
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
                            } else if (pending.isNotEmpty()) {
                                // 等待下一个 pending 完成
                                delay(10)
                            } else {
                                break
                            }
                        }
                        logger.info("[{}] All pending downloads completed, splitting file...", room.name)
                        // 关闭当前文件并启动后处理
                        try {
                            val file = writer.done()
                            if (file != null) {
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
                            }
                        } catch (e: Exception) {
                            logger.error("[${room.name}] Failed to close file for split", e)
                        }
                        // 创建新文件并重置状态
                        writer.init()
                        metric.reset()
                        total.set(0)
                        success.set(0)
                        failed.set(0)
                        bytesWrite.set(0)
                        nextIndex = 0
                        emittedIndex = 0
                        pending.clear()
                        readyToEmit.clear()
                        logger.info("[{}] New file started after split", room.name)
                        return@collect
                    }

                    // 正常处理 segment
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
                            // 调试日志：写入完成
                            logger.trace("[{}] Segment {} written, size={}KB, pending.size={}", room.name, current, data.size / 1024, pending.size)
                        } else {
                            metric.failedIncrement()
                            failed.incrementAndGet()
                            logger.debug("[{}] Segment {} failed, skipping. pending.size={}", room.name, current, pending.size)
                        }
                        emittedIndex++
                    }
                }
                if (success.get() <= 1) {
                    logger.info(
                        "[{}}] No valid segments downloaded({}/{}) since start. clean empty file",
                        room.name,
                        success.get(),
                        total.get()
                    )
                    // some model start and stop their frequently
                    // this cause the stream url become invalid immediately
                    // so we need to reset writer to avoid empty files
                    writer.dispose()
                    writerReference.set(null)
                }
            }

            generatorJob?.join()
        } finally {
            // 使用 compareAndSet 而非 set，避免覆盖 stop() 已设置的状态
            // 场景：stop() 先执行 compareAndSet(true, false)，然后 start() 的 finally 执行
            // 如果用 set(false)，没问题；但如果 stop() 还没执行，这里需要重置
            _isActive.compareAndSet(true, false)
            logger.info("[-] stop recording {}({}) q:{}(want {})", room.name, room.id, currentQuality, room.quality)
            runCatching {
                Metric.removeMetric(room.id)
            }.onFailure {
                logger.error("[{}] Failed to remove metric", room.name, it)
            }
        }
    }

    suspend fun stop() {
        // 只有成功将状态从 true 改为 false 时才取消 job
        // 这确保了 stop() 只执行一次核心逻辑
        if (!_isActive.compareAndSet(true, false)) {
            return  // 已经停止或从未启动，直接返回
        }
        generatorJob?.cancelAndJoin()
        val file = writerReference.getAndSet(null)?.done() ?: return
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
            // 如果已回退到 source,或者用户选择 raw,使用无后缀 URL
            if (useSourceFallback || currentQuality == "raw" || currentQuality.isBlank()) {
                return "https://media-hls.doppiocdn.org/b-hls-%d/%d/%d.m3u8".format(
                    Random().nextInt(12, 13), room.id, room.id
                )
            }
            // 否则使用带清晰度后缀的 URL
            return "https://media-hls.doppiocdn.org/b-hls-%d/%d/%d_%s.m3u8".format(
                Random().nextInt(12, 13), room.id, room.id, currentQuality
            )
        }

    private fun segmentGenerator(): Flow<Event> = flow {
        var initSent = false
        var initUrl = ""
        val cache = CircleCache(100)
        var retry = 0
        var ms = System.currentTimeMillis()
        var startTime = ms
        while (currentCoroutineContext().isActive) {
            retry++
            try {
                val lines = withTimeout(5_000) {
                    val rawList = (ClientManager.getProxiedClient(room.name)).get(
                        streamUrl
                    ) {
//                        parameter("psch", "v1")
//                        parameter("pkey", AUTH_KEY)
                        parameter("psch", "v2")
                        parameter("pkey", AUTH_KEY_V2)
                        parameter("preferredVideoCodec", "H265")
                    }.bodyAsText().lines()
                    if (logger.isTraceEnabled) {
                        File("${room.name}.m3u8").writeText(rawList.joinToString("\n"))
                    }
                    val newList = mutableListOf<String>()
                    for (idx in rawList.indices) {
                        if (rawList[idx].startsWith("#EXT-X-MOUFLON:URI:")) {
                            val mouflon = rawList[idx].substringAfterLast("#EXT-X-MOUFLON:URI:")
                            val encrypted =
                                mouflon.replace("(_part\\d)?\\.mp4".toRegex(), "")
                                    .substringBeforeLast("_")
                                    .substringAfterLast("_")

                            val decrypted = try {
                                val result = runCatching {
                                    Decrypter.decode(
                                        encrypted.reversed(),
                                        DECRYPT_KEY_V2
                                    )
                                }
                                logger.trace(
                                    "decode {} key={} result={}",
                                    encrypted,
                                    DECRYPT_KEY_V2,
                                    result.getOrElse { "Failed" },
                                    result.exceptionOrNull()
                                )
                                result.getOrThrow()
                            } catch (e: Exception) {
                                logger.error("[ERROR] failed to decrypt $mouflon(${encrypted.reversed()})", e)
                                println(rawList.joinToString("\n"))
                                throw e
                            }
                            val dec = rawList[idx + 1].replace(
                                """https://media-hls\.doppiocdn\.\w+/b-hls-\d+/media.mp4""".toRegex(),
                                mouflon.replace(encrypted, decrypted)
                            )
                            newList.add(dec)
                        } else {
                            newList.add(rawList[idx])
                        }
                    }

                    newList.filterNot { it.contains("media.mp4") }
                }

                if (logger.isTraceEnabled) {
                    File("${room.name}.decoded.m3u8").writeText(lines.joinToString("\n"))
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

                if (!initSent) {
                    initSent = true
                    emit(Event.LiveSegmentInit(initUrlCur))
                }
                logger.trace("[{}] fetched: {}", room.name, videos.size)
                if (videos.isEmpty()) {
                    logger.warn("[{}] Got 0 videos from playlist, maybe decode failed!", room.name)
                }
                for (url in videos) {
                    // record time limit - 发送切分事件而不是直接处理
                    val timeLimitReached = room.limit.isFinite() && System.currentTimeMillis() - startTime > room.limit.inWholeMilliseconds
                    // record size limit - 检测文件大小是否超过限制 (sizeLimit 单位为 MB)
                    val sizeLimitReached = room.sizeLimit > 0 && bytesWrite.get() > room.sizeLimit * 1024 * 1024

                    if ((timeLimitReached || sizeLimitReached) && !cache.contains(url)) {
                        if (timeLimitReached) {
                            logger.info("[{}] Time limit reached, splitting file...", room.name)
                        }
                        if (sizeLimitReached) {
                            logger.info("[{}] Size limit reached ({}MB > {}MB), splitting file...", room.name, bytesWrite.get() / 1024 / 1024, room.sizeLimit)
                        }
                        emit(Event.FileSplit)
                        // 重置生产者端状态
                        initSent = false
                        initUrl = ""
                        startTime = System.currentTimeMillis()
                        cache.clear()
                        break
                    }
                    // normal segments
                    if (currentCoroutineContext().isActive && !cache.contains(url)) {
                        cache.add(url)
                        emit(Event.LiveSegmentData(url))
                    }
                }
                retry = 0
            } catch (_: TimeoutCancellationException) {
                logger.warn("[${room.name}] Refresh list timeout {}, trys={}", streamUrl, retry)
                if (!runCatching { testAndConfigure() }.getOrElse { false }) {
                    logger.info("[STOP] [{}] Room off or non-public", room.name)
                    break
                }
                continue
            } catch (e: ClientRequestException) {
                if (e.response.status.value == 404) {
                    // 如果还没尝试过 source,先回退到 source 再试
                    if (!useSourceFallback && currentQuality != "raw") {
                        logger.info(
                            "[{}] Stream url {} returns 404, trying fallback to source",
                            room.name,
                            streamUrl
                        )
                        useSourceFallback = true
                        continue
                    }
                    // 已经是 source 了还是 404,说明真的没有流
                    logger.info(
                        "[STOP] [{}] Stream url returns 404 (already using source), this is caused by model's network connection issue",
                        room.name
                    )
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
        val lowLatency = lines.filter { it.startsWith("#EXT-X-PART") && it.contains("URI=\"") }.mapNotNull { line ->
            try {
                line.substringAfter("URI=\"").substringBefore("\"")
            } catch (e: Exception) {
                println("Failed to parse segment URL from line: $line, $e")
                null
            }
        }
        return lowLatency.ifEmpty {
            lines.filter { it.startsWith("https://") && it.endsWith(".mp4") }
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
            is Event.FileSplit -> throw IllegalArgumentException("FileSplit event should not be downloaded")
        }
        val created =
            (event.url().replace("(_part\\d)?.mp4".toRegex(), "").substringAfterLast("_").substringAfterLast("_")
                .toLongOrDefault(0))
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
