package github.rikacelery.v3.components

import github.rikacelery.utils.ClientManager
import github.rikacelery.utils.withRetry
import github.rikacelery.v3.core.*
import github.rikacelery.v3.data.*
import github.rikacelery.v3.events.*
import github.rikacelery.v3.hooks.DownloaderHook
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.selects.select
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.random.Random

sealed interface DownloaderMsg
data class DoDownload(val cmd: Download) : DownloaderMsg
data class DoCutPoint(val cut: CutPoint) : DownloaderMsg
data class DoStopFetch(val roomId: Long) : DownloaderMsg
data class SetConcurrency(val limit: Int) : DownloaderMsg
data class HandleDownloaderCommand(val env: CommandEnvelope) : DownloaderMsg

data class ActiveDownload(
    val emitter: OrderedEmitter,
    val semaphore: Semaphore,
    val runningJobs: MutableSet<Job>,
    var concurrency: Int = 16,
    var active: Boolean = true,
    var idx: AtomicInteger = AtomicInteger(-1),
    val runningUrls: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
)

class DownloaderComponent(
    private val dataChannel: DataChannel,
    private val hooks: List<DownloaderHook> = emptyList(),
    eventBus: EventBus,
    parentScope: CoroutineScope,
    private val initialConcurrency: Int = 16
) : Actor<DownloaderMsg>("DownloaderComponent", eventBus, parentScope) {

    private val rooms = ConcurrentHashMap<Long, ActiveDownload>()
    private val workerScope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob() + CoroutineName("downloader-worker")
    )

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<CommandEnvelope>(CommandEnvelope::class)
    }
    override suspend fun wrapEvent(event: Any): DownloaderMsg? = when (event) {
        is CommandEnvelope -> HandleDownloaderCommand(event)
        else -> null
    }
    override suspend fun handle(msg: DownloaderMsg) {
        when (msg) {
            is DoDownload -> handleDownload(msg.cmd)
            is DoCutPoint -> handleCutPoint(msg.cut)
            is DoStopFetch -> rooms.remove(msg.roomId)?.also { it.active = false }
            is SetConcurrency -> rooms.values.forEach { it.concurrency = msg.limit }
            is HandleDownloaderCommand -> handleCommand(msg.env)
        }
    }

    private suspend fun handleCommand(env: CommandEnvelope) {
        val ack = when (env.command) {
            is GetActiveDownloads -> {
                val result = rooms.mapValues { (_, ad) ->
                    ad.runningUrls.map { (url, startAt) ->
                        ActiveDownloadInfo(url, "DIRECT", startAt)
                    }
                }.filterValues { it.isNotEmpty() }
                ActiveDownloadsResponse(result)
            }
            else -> return
        }
        eventBus.publish(CommandAck(env.id, ack))
    }

    private suspend fun handleDownload(cmd: Download) {
        val active = rooms.getOrPut(cmd.roomId) {
            ActiveDownload(
                emitter = OrderedEmitter(cmd.roomId) { dataChannel.send(it) },
                semaphore = Semaphore(initialConcurrency),
                runningJobs = mutableSetOf()
            )
        }
        if (!active.active) return

        for ((i, seg) in cmd.urls.withIndex()) {
            val idx = active.idx.incrementAndGet()
            var url = seg.url
            hooks.forEach { url = it.beforeDownload(url) }

            val finalUrl = url
            val startAt = System.currentTimeMillis()
            active.runningUrls[finalUrl] = startAt

            val job = workerScope.launch {
                active.semaphore.withPermit {
                    val result = downloadSegment(finalUrl, idx)
                    val hooked = hooks.fold(result) { acc, hook -> hook.onDownloadResult(cmd.roomId, acc) }
                    active.emitter.complete(idx.toLong(), hooked)
                    active.runningUrls.remove(finalUrl)

                    when (result) {
                        is DownloadResult.Success -> {
                            eventBus.publish(SegmentDownloaded(cmd.roomId, idx, seg.url,
                                result.meta.fetchDurationMs, result.meta.proxied))
                        }
                        is DownloadResult.Failed -> {
                            eventBus.publish(DownloadError(cmd.roomId, idx, seg.url, result.reason))
                        }
                        is DownloadResult.Skipped -> {}
                        is DownloadResult.CutPoint -> {}
                    }
                }
            }
            active.runningJobs.add(job)
            job.invokeOnCompletion { active.runningJobs.remove(job) }
        }
    }

    private suspend fun handleCutPoint(cut: CutPoint) {
        val active = rooms[cut.roomId] ?: return
        val idx = active.idx.incrementAndGet().toLong()
        active.emitter.complete(idx,  DownloadResult.CutPoint(cut))
    }

    private val raceThresholdMs: Long = 8000

    private suspend fun downloadSegment(url: String, idx: Int): DownloadResult {
        val start = System.currentTimeMillis()

        return try {
            val directDeferred = scope.async {
                downloadWithClient(ClientManager.getClient("dl_${Random.nextInt(5)}"), url, idx, false)
            }

            val directResult = withTimeoutOrNull(raceThresholdMs) { directDeferred.await() }
            if (directResult is DownloadResult.Success) {
                val dur = System.currentTimeMillis() - start
                return directResult.copy(meta = directResult.meta.copy(fetchDurationMs = dur, proxied = false))
            }

            // Phase 2: proxy joins the race
            val proxyDeferred = scope.async {
                downloadWithClient(ClientManager.getProxiedClient("px_$idx"), url, idx, true)
            }

            val result = select<DownloadResult> {
                directDeferred.onAwait { r ->
                    (r as? DownloadResult.Success)?.copy(meta = r.meta.copy(
                        fetchDurationMs = System.currentTimeMillis() - start, proxied = false)) ?: r
                }
                proxyDeferred.onAwait { r ->
                    (r as? DownloadResult.Success)?.copy(meta = r.meta.copy(
                        fetchDurationMs = System.currentTimeMillis() - start, proxied = true)) ?: r
                }
            }

            if (!directDeferred.isCompleted) directDeferred.cancel()
            if (!proxyDeferred.isCompleted) proxyDeferred.cancel()
            result
        } catch (e: Exception) {
            DownloadResult.Failed(idx, url, e.message ?: "download failed")
        }
    }

    private suspend fun downloadWithClient(
        client: HttpClient, url: String, idx: Int, proxied: Boolean
    ): DownloadResult {
        return try {
            val response = client.get(url)
            val stream = response.bodyAsChannel()
            val bos = ByteArrayOutputStream()
            while (!stream.isClosedForRead) {
                val buf = ByteArray(8192)
                val read = stream.readAvailable(buf)
                if (read <= 0) break
                bos.write(buf, 0, read)
            }
            DownloadResult.Success(bos.toByteArray(), DownloadMeta(url, 0, proxied, Instant.now()))
        } catch (e: Exception) {
            DownloadResult.Failed(idx, url, e.message ?: "download failed")
        }
    }
}