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
import kotlinx.serialization.json.*
import okhttp3.internal.toLongOrDefault
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * 分片下载结果，使用临时文件存储数据避免内存堆积
     */
    private data class SegmentData(val tempFile: File, val size: Long) {
        fun deleteFile() {
            tempFile.delete()
        }
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

    private data class CleanupThresholds(
        val minDurationSeconds: Long,
        val minSizeMB: Long
    )

    private fun readCleanupThresholds(): CleanupThresholds? {
        val config = runCatching { PostProcessor.config }.getOrNull() ?: return null
        val cleanup = config.firstOrNull { element ->
            element.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "cleanup"
        } ?: return null
        val json = cleanup.jsonObject
        val minDurationSeconds = json["min_duration_seconds"]?.jsonPrimitive?.longOrNull ?: 0L
        val minSizeMB = json["min_size_mb"]?.jsonPrimitive?.longOrNull ?: 0L
        return CleanupThresholds(minDurationSeconds, minSizeMB)
    }

    private fun shouldPostProcess(file: File): Boolean {
        if (file.length() == 0L) {
            logger.info("[{}] 文件大小 0B，跳过后处理并删除: {}", room.name, file.name)
            file.delete()
            return false
        }
        val thresholds = readCleanupThresholds() ?: return true
        val fileSizeMB = file.length() / (1024 * 1024)
        if (thresholds.minSizeMB > 0 && fileSizeMB < thresholds.minSizeMB) {
            logger.info(
                "[{}] 文件大小 {}MB < {}MB，跳过后处理并删除: {}",
                room.name,
                fileSizeMB,
                thresholds.minSizeMB,
                file.name
            )
            file.delete()
            return false
        }

        if (thresholds.minDurationSeconds > 0) {
            val duration = try {
                runProcessGetStdout(
                    "ffprobe",
                    "-v",
                    "error",
                    "-show_entries",
                    "format=duration",
                    "-of",
                    "default=noprint_wrappers=1:nokey=1",
                    file.absolutePath
                ).trim().toDoubleOrNull() ?: 0.0
            } catch (e: Exception) {
                logger.warn("[{}] 无法获取文件时长: {}", room.name, e.message)
                0.0
            }

            if (duration < thresholds.minDurationSeconds) {
                logger.info(
                    "[{}] 文件时长 {}s < {}s，跳过后处理并删除: {}",
                    room.name,
                    duration.toLong(),
                    thresholds.minDurationSeconds,
                    file.name
                )
                file.delete()
                return false
            }
        }

        return true
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
            // 画质选择现在由 getStreamUrl() 从 Master Playlist 动态获取
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
                // 使用 SegmentData 存储临时文件路径，避免 ByteArray 堆积导致 OOM
                val pending = mutableMapOf<Int, Deferred<Result<SegmentData>>>()
                val readyToEmit = PriorityQueue<Int>()
                var nextIndex = 0
                var emittedIndex = 0

                // 用于标记切分事件的特殊索引
                val SPLIT_MARKER = -1

                segmentGenerator().map { event ->
                    // FileSplit 事件不需要下载，直接返回特殊标记
                    if (event is Event.FileSplit) {
                        return@map SPLIT_MARKER to scope.async { Result.success(SegmentData(File(""), 0)) }
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
                                    val tempFile = File.createTempFile("seg_proxy_", ".tmp", File(tmp))
                                    try {
                                        val response = ClientManager.getProxiedClient(room.name).get(event.url())
                                        val channel = response.bodyAsChannel()
                                        tempFile.outputStream().buffered().use { output ->
                                            val buffer = ByteArray(8192)
                                            while (!channel.isClosedForRead) {
                                                val read = channel.readAvailable(buffer)
                                                if (read > 0) {
                                                    output.write(buffer, 0, read)
                                                }
                                            }
                                        }
                                        SegmentData(tempFile, tempFile.length()).also {
                                            metric.successProxiedIncrement()
                                            successProxied.incrementAndGet()
                                        }
                                    } catch (e: Exception) {
                                        tempFile.delete()
                                        throw e
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
                                val createdSeconds = segmentCreatedSeconds(event.url())
                                val diffSeconds = System.currentTimeMillis() / 1000 - createdSeconds
                                logger.warn(
                                    "Download segment:{} failed({}), delayed: {}s",
                                    index,
                                    (it as? ClientRequestException)?.response?.status?.value ?: it.message,
                                    diffSeconds
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
                                    val segmentData = result.getOrThrow()
                                    if (segmentData.size > 0) {
                                        metric.bytesWriteIncrement(segmentData.size)
                                        bytesWrite.addAndGet(segmentData.size)
                                        writer.appendFromFile(segmentData.tempFile)
                                        segmentData.deleteFile()
                                    }
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
                                if (shouldPostProcess(file.first)) {
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
                            val segmentData = result.getOrThrow()
                            metric.bytesWriteIncrement(segmentData.size)
                            bytesWrite.addAndGet(segmentData.size)
                            writer.appendFromFile(segmentData.tempFile)
                            segmentData.deleteFile()
                            // 调试日志：写入完成
                            logger.trace("[{}] Segment {} written, size={}KB, pending.size={}", room.name, current, segmentData.size / 1024, pending.size)
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

            // 处理正常结束的情况：如果 writerReference 还有值，说明不是通过 stop() 结束的
            // 需要在这里完成文件处理和后处理
            val file = writerReference.getAndSet(null)?.done()
            if (file != null) {
                if (shouldPostProcess(file.first)) {
                    logger.info("[{}] Processing file after normal exit: {}", room.name, file.first.name)
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
        // 注意：文件处理已移至 start() 的 finally 块中统一处理
        // 这里只需要取消 job，writerReference 会在 finally 中被处理
    }


    /**
     * 从 Master Playlist 获取可用画质的流 URL
     */
    private suspend fun getStreamUrl(): String {
        val host = "doppiocdn." + listOf("org", "com", "net").random()
        val masterUrl = "https://edge-hls.$host/hls/${room.id}/master/${room.id}_auto.m3u8"

        var lastException: ClientRequestException? = null
        repeat(3) { attempt ->
            try {
                val content = ClientManager.getProxiedClient(room.name).get(masterUrl).bodyAsText()
                val lines = content.lines()

                // 解析 #EXT-X-STREAM-INF 获取可用画质
                val qualities = lines.mapIndexedNotNull { i, line ->
                    if (line.startsWith("#EXT-X-STREAM-INF:") && i + 1 < lines.size) {
                        val name = line.substringAfter("NAME=\"", "")
                            .substringBefore("\"", "")
                            .takeIf { it.isNotEmpty() }
                        val url = lines[i + 1].trim()
                        if (name != null && url.startsWith("https://")) name to url else null
                    } else null
                }.toMap()

                require(qualities.isNotEmpty()) { "No available qualities in master playlist" }

                // 画质选择: raw -> source, 720p60 -> 720p60 或 720p, 720 -> 720p
                val key = if (currentQuality == "raw" || currentQuality.isBlank()) "source"
                else if (currentQuality.contains("p")) currentQuality
                else currentQuality + "p"

                return qualities[key]
                    ?: qualities[key.replace("""p\d+$""".toRegex(), "p")]
                    ?: qualities["source"]
                    ?: qualities.values.first()
            } catch (e: ClientRequestException) {
                lastException = e
                val status = e.response.status.value
                if (status == 404 || status == 403) {
                    val online = runCatching { testAndConfigure() }.getOrElse { false }
                    if (!online) {
                        logger.info("[STOP] [{}] Room off or non-public (master {})", room.name, status)
                        throw e
                    }
                    if (attempt < 2) {
                        logger.info(
                            "[{}] Master playlist unavailable ({}), retry after 3s ({}/{})",
                            room.name,
                            status,
                            attempt + 1,
                            3
                        )
                        delay(3_000)
                        return@repeat
                    }
                }
                throw e
            }
        }

        throw lastException ?: IllegalStateException("Failed to fetch master playlist")
    }

    private fun segmentGenerator(): Flow<Event> = flow {
        var initSent = false
        var initUrl = ""
        val cache = CircleCache(100)
        var retry = 0
        var ms = System.currentTimeMillis()
        var startTime = ms
        if (isOpen) {
            logger.info("[{}] Broadcast is online, wait 3 seconds for CDN ready", room.name)
            delay(3_000)
        }
        // 该次录制只获取一次流 URL，下次主播重新上线时再重新获取
        val streamUrl = getStreamUrl()
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
                    logger.info(
                        "[{}] Init segment changed, exiting... old={}, new={}",
                        room.name,
                        initUrl,
                        initUrlCur
                    )
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
                val online = runCatching { testAndConfigure() }.getOrElse { false }
                logger.warn(
                    "[{}] Refresh list timeout, quality={}, trys={}, online={}",
                    room.name,
                    currentQuality,
                    retry,
                    online
                )
                if (!online) {
                    logger.info("[STOP] [{}] Room off or non-public after timeout", room.name)
                    break
                }
                continue
            } catch (e: ClientRequestException) {
                val status = e.response.status.value
                if (status == 404) {
                    logger.info(
                        "[STOP] [{}] Stream url returns 404, this is caused by model's network connection issue",
                        room.name
                    )
                    break
                }
                if (status == 403) {
                    val online = runCatching { testAndConfigure() }.getOrElse { false }
                    if (!online) {
                        logger.info("[STOP] [{}] Room off or non-public (403), online=false", room.name)
                        break
                    } else {
                        logger.error(
                            "[STOP] [{}] Refresh list error {}, online=true. Stop recording",
                            room.name,
                            status
                        )
                        break
                    }
                }
                logger.error(
                    "[{}] Refresh list error status={}, url={}",
                    room.name,
                    status,
                    e.response.request.url
                )
            } catch (e: CombinedException) {
                val shouldStop = e.exceptions.any(shouldStop())
                if (shouldStop) {
                    logger.error(
                        "[STOP] [{}] CombinedException requires stop. reasons={}",
                        room.name,
                        e.exceptions.joinToString { it::class.simpleName ?: "Unknown" }
                    )
                    scope.launch { stop() }
                    break
                } else {
                    logger.error("[{}] Generator error", room.name, e)
                }
            } catch (_: CancellationException) {
                logger.error("[{}] Segment generator is cancelled, exiting...", room.name)
                break
            } catch (e: Exception) {
                val online = runCatching { testAndConfigure() }.getOrElse { false }
                logger.error("[{}] Unexpected error in segment generator, online={}", room.name, online, e)
                if (!online) {
                    logger.error("[STOP] [{}] Room off or non-public after exception", room.name)
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

    private fun segmentCreatedSeconds(url: String): Long {
        val created = url.replace("(_part\\d)?.mp4".toRegex(), "")
            .substringAfterLast("_")
            .substringAfterLast("_")
            .toLongOrDefault(0)
        return if (created > 1_000_000_000_000L) created / 1000 else created
    }

    private fun tryDownload(event: Event): Deferred<SegmentData?> = scope.async {
        val c = when (event) {
            is Event.LiveSegmentData -> ClientManager.getClient(room.name)
            is Event.LiveSegmentInit -> ClientManager.getProxiedClient(room.name)
            is Event.FileSplit -> throw IllegalArgumentException("FileSplit event should not be downloaded")
        }
        val createdSeconds = segmentCreatedSeconds(event.url())
        val diffSeconds = System.currentTimeMillis() / 1000 - createdSeconds
        val wait = (20L - diffSeconds) * 1000
        withTimeoutOrNull(if (wait > 0) wait else 0) {
            withRetry(25) { attempt ->
                try {
                    val tempFile = File.createTempFile("seg_", ".tmp", File(tmp))
                    try {
                        val response = c.get(event.url())
                        val channel = response.bodyAsChannel()
                        tempFile.outputStream().buffered().use { output ->
                            val buffer = ByteArray(8192)
                            while (!channel.isClosedForRead) {
                                val read = channel.readAvailable(buffer)
                                if (read > 0) {
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        SegmentData(tempFile, tempFile.length())
                    } catch (e: Exception) {
                        tempFile.delete()
                        throw e
                    }
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
