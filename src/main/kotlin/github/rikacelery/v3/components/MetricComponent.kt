package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.events.*
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.set

sealed interface MetricMsg
data class OnMetricEvent(val event: Any) : MetricMsg
data class OnRoomAddEvent(val roomId: Long, val name: String) : MetricMsg
data class HandleMetricCommand(val env: CommandEnvelope) : MetricMsg

data class RoomMetrics(
    val totalSegments: AtomicLong = AtomicLong(0),
    val totalDownloaded: AtomicLong = AtomicLong(0),
    val totalFailed: AtomicLong = AtomicLong(0),
    val totalBytes: AtomicLong = AtomicLong(0),
    val proxyCount: AtomicLong = AtomicLong(0),
    val directCount: AtomicLong = AtomicLong(0),
    val latencySamples: ArrayDeque<Long> = ArrayDeque(10),
    var quality: String = "",
    val totalLatencyMs: AtomicLong = AtomicLong(0),
    val fileCount: AtomicLong = AtomicLong(0)
)

class MetricComponent(
    eventBus: EventBus,
    parentScope: CoroutineScope,
    val requestBus: RequestBus
) : Actor<MetricMsg>("MetricComponent", eventBus, parentScope) {

    private val metrics = ConcurrentHashMap<Long, RoomMetrics>()
    private val roomNames = ConcurrentHashMap<Long, String>()

    fun setRoomName(roomId: Long, name: String) {
        roomNames[roomId] = name
    }

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<SegmentDownloaded>(SegmentDownloaded::class)
        subscribe<DownloadError>(DownloadError::class)
        subscribe<FileReady>(FileReady::class)
        subscribe<FileProcessed>(FileProcessed::class)
        subscribe<RoomAdded>(RoomAdded::class)
        subscribe<CommandEnvelope>(CommandEnvelope::class)
    }

    override suspend fun wrapEvent(event: Any): MetricMsg? = when (event) {
        is SegmentDownloaded -> OnMetricEvent(event)
        is DownloadError -> OnMetricEvent(event)
        is FileReady -> OnMetricEvent(event)
        is FileProcessed -> OnMetricEvent(event)
        is RoomAdded -> OnRoomAddEvent(event.roomId, event.name)
        is CommandEnvelope -> HandleMetricCommand(event)
        else -> null
    }

    override suspend fun handle(msg: MetricMsg) {
        when (msg) {
            is OnMetricEvent -> when (val e = msg.event) {
                is SegmentDownloaded -> {
                    val m = metrics.getOrPut(e.roomId) { RoomMetrics() }
                    m.totalSegments.incrementAndGet()
                    m.totalDownloaded.incrementAndGet()
                    m.totalBytes.addAndGet(0)  // size tracked via writer
                    m.latencySamples.addLast(e.durationMs)
                    if (m.latencySamples.size > 10) m.latencySamples.removeFirst()
                    if (e.proxied) m.proxyCount.incrementAndGet() else m.directCount.incrementAndGet()
                }

                is DownloadError -> {
                    metrics.getOrPut(e.roomId) { RoomMetrics() }.totalFailed.incrementAndGet()
                }

                is FileReady -> {
                    metrics.getOrPut(e.roomId) { RoomMetrics() }.fileCount.incrementAndGet()
                }

                is FileProcessed -> {}
                else -> {}
            }

            is HandleMetricCommand -> handleCommand(msg.env)
            is OnRoomAddEvent -> {
                setRoomName(msg.roomId, msg.name)
            }
        }
    }

    private suspend fun handleCommand(env: CommandEnvelope) {
        val ack = when (env.command) {
            is GetMetrics -> MetricsResponse(metrics.mapValues { (_, m) ->
                mapOf(
                    "total" to m.totalSegments.get().toInt(),
                    "success" to m.totalDownloaded.get().toInt(),
                    "failed" to m.totalFailed.get().toInt(),
                    "bytesWrite" to m.totalBytes.get()
                )
            })

            else -> return
        }
        eventBus.publish(CommandAck(env.id, ack))
    }

    fun addBytes(roomId: Long, bytes: Long) {
        metrics.getOrPut(roomId) { RoomMetrics() }.totalBytes.addAndGet(bytes)
    }

    suspend fun prometheusText(): String {
        val sb = StringBuilder()
        metrics.forEach { (roomId, m) ->
            val avgLatency = if (m.latencySamples.isNotEmpty())
                m.latencySamples.average() else 0.0
            sb.appendLine("xhrec_room_${roomId}_segments_total ${m.totalSegments.get()}")
            sb.appendLine("xhrec_room_${roomId}_downloaded_total ${m.totalDownloaded.get()}")
            sb.appendLine("xhrec_room_${roomId}_failed_total ${m.totalFailed.get()}")
            val total = m.proxyCount.get() + m.directCount.get()
            val proxyRatio = if (total > 0) m.proxyCount.get().toDouble() / total else 0.0
            sb.appendLine("xhrec_room_${roomId}_proxy_ratio $proxyRatio")
            sb.appendLine("xhrec_room_${roomId}_avg_latency_ms $avgLatency")
            sb.appendLine("xhrec_room_${roomId}_files_total ${m.fileCount.get()}")
            val rn = roomNames[roomId] ?: requestBus.request<RoomNameResponse>(GetRoomName(roomId))
            sb.appendLine("rikacelery_v2_segment_missing_total{name=\"${rn}\"} 0")
        }
        return sb.toString()
    }
}
