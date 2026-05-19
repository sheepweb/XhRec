package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.events.*
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed interface MetricMsg
data class OnMetricEvent(val event: Any) : MetricMsg
data class HandleMetricCommand(val env: CommandEnvelope) : MetricMsg

data class RunningUrlInfo(val type: String, val startAt: Long)

data class RoomMetrics(
    val totalAttempted: AtomicLong = AtomicLong(0),
    val totalDownloaded: AtomicLong = AtomicLong(0),
    val totalFailed: AtomicLong = AtomicLong(0),
    val totalBytes: AtomicLong = AtomicLong(0),
    val proxyCount: AtomicLong = AtomicLong(0),
    val directCount: AtomicLong = AtomicLong(0),
    val latencySamples: ArrayDeque<Long> = ArrayDeque(10),
    var quality: String = "",
    val totalLatencyMs: AtomicLong = AtomicLong(0),
    val fileCount: AtomicLong = AtomicLong(0),
    val segmentMissing: AtomicLong = AtomicLong(0),
    val runningUrls: ConcurrentHashMap<String, RunningUrlInfo> = ConcurrentHashMap()
)

class MetricComponent(
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<MetricMsg>("MetricComponent", eventBus, parentScope) {

    private val metrics = ConcurrentHashMap<Long, RoomMetrics>()

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<SegmentDownloaded>(SegmentDownloaded::class)
        subscribe<DownloadError>(DownloadError::class)
        subscribe<DownloadStarted>(DownloadStarted::class)
        subscribe<SegmentGapDetected>(SegmentGapDetected::class)
        subscribe<FileReady>(FileReady::class)
        subscribe<FileProcessed>(FileProcessed::class)
        subscribe<CommandEnvelope>(CommandEnvelope::class)
    }

    override suspend fun wrapEvent(event: Any): MetricMsg? = when (event) {
        is SegmentDownloaded -> OnMetricEvent(event)
        is DownloadError -> OnMetricEvent(event)
        is DownloadStarted -> OnMetricEvent(event)
        is SegmentGapDetected -> OnMetricEvent(event)
        is FileReady -> OnMetricEvent(event)
        is FileProcessed -> OnMetricEvent(event)
        is CommandEnvelope -> HandleMetricCommand(event)
        else -> null
    }

    override suspend fun handle(msg: MetricMsg) {
        when (msg) {
            is HandleMetricCommand -> {
                val ack = when (msg.env.command) {
                    is GetRoomDetailedStatus -> {
                        metrics.mapValues { (_, m) ->
                            mapOf<String, Any>(
                                "total" to (m.totalAttempted.get() + m.runningUrls.size),
                                "success" to m.totalDownloaded.get(),
                                "failed" to m.totalFailed.get(),
                                "bytesWrite" to m.totalBytes.get(),
                                "running" to m.runningUrls.mapKeys { it.key }
                                    .mapValues { (_, v) ->
                                        mapOf<String, Any>(
                                            "type" to v.type,
                                            "startAt" to v.startAt
                                        )
                                    }
                            )
                        }
                    }

                    else -> return
                }
                eventBus.publish(CommandAck(msg.env.id, ack))
            }

            is OnMetricEvent -> when (val e = msg.event) {
                is DownloadStarted -> {
                    val m = metrics.getOrPut(e.roomId) { RoomMetrics() }
                    m.totalAttempted.incrementAndGet()
                    m.runningUrls[e.url] = RunningUrlInfo("DIRECT", e.timestamp)
                }

                is SegmentDownloaded -> {
                    val m = metrics.getOrPut(e.roomId) { RoomMetrics() }
                    m.totalDownloaded.incrementAndGet()
                    m.totalBytes.addAndGet(e.bytes.toLong())
                    m.latencySamples.addLast(e.durationMs)
                    if (m.latencySamples.size > 10) m.latencySamples.removeFirst()
                    if (e.proxied) m.proxyCount.incrementAndGet() else m.directCount.incrementAndGet()
                    m.runningUrls.remove(e.originalUrl)
                }

                is DownloadError -> {
                    val m = metrics.getOrPut(e.roomId) { RoomMetrics() }
                    m.totalFailed.incrementAndGet()
                    e.url?.let { m.runningUrls.remove(it) }
                }

                is SegmentGapDetected -> {
                    metrics.getOrPut(e.roomId) { RoomMetrics() }.segmentMissing.addAndGet(e.gap.toLong())
                }

                is FileReady -> {
                    metrics.getOrPut(e.roomId) { RoomMetrics() }.fileCount.incrementAndGet()
                }

                is FileProcessed -> {}
                else -> {}
            }
        }
    }

    fun prometheusText(): String {
        val sb = StringBuilder()
        metrics.forEach { (roomId, m) ->
            val avgLatency = if (m.latencySamples.isNotEmpty())
                m.latencySamples.average() else 0.0
            val total = m.proxyCount.get() + m.directCount.get()
            val proxyRatio = if (total > 0) m.proxyCount.get().toDouble() / total else 0.0

            sb.appendLine("# HELP xhrec_attempted_total Total attempted segments")
            sb.appendLine("# TYPE xhrec_attempted_total counter")
            sb.appendLine("xhrec_attempted_total{roomId=\"$roomId\"} ${m.totalAttempted.get()}")
            sb.appendLine("# HELP xhrec_downloaded_total Successfully downloaded segments")
            sb.appendLine("# TYPE xhrec_downloaded_total counter")
            sb.appendLine("xhrec_downloaded_total{roomId=\"$roomId\"} ${m.totalDownloaded.get()}")
            sb.appendLine("# HELP xhrec_failed_total Failed segments")
            sb.appendLine("# TYPE xhrec_failed_total counter")
            sb.appendLine("xhrec_failed_total{roomId=\"$roomId\"} ${m.totalFailed.get()}")
            sb.appendLine("# HELP xhrec_bytes_write_total Bytes written")
            sb.appendLine("# TYPE xhrec_bytes_write_total counter")
            sb.appendLine("xhrec_bytes_write_total{roomId=\"$roomId\"} ${m.totalBytes.get()}")
            sb.appendLine("# HELP xhrec_proxy_ratio Proxy download ratio")
            sb.appendLine("# TYPE xhrec_proxy_ratio gauge")
            sb.appendLine("xhrec_proxy_ratio{roomId=\"$roomId\"} $proxyRatio")
            sb.appendLine("# HELP xhrec_success_direct_total Direct success count")
            sb.appendLine("# TYPE xhrec_success_direct_total counter")
            sb.appendLine("xhrec_success_direct_total{roomId=\"$roomId\"} ${m.directCount.get()}")
            sb.appendLine("# HELP xhrec_success_proxied_total Proxied success count")
            sb.appendLine("# TYPE xhrec_success_proxied_total counter")
            sb.appendLine("xhrec_success_proxied_total{roomId=\"$roomId\"} ${m.proxyCount.get()}")
            sb.appendLine("# HELP xhrec_avg_latency_ms Average download latency ms")
            sb.appendLine("# TYPE xhrec_avg_latency_ms gauge")
            sb.appendLine("xhrec_avg_latency_ms{roomId=\"$roomId\"} $avgLatency")
            sb.appendLine("# HELP xhrec_segment_missing_total Missing segments")
            sb.appendLine("# TYPE xhrec_segment_missing_total counter")
            sb.appendLine("xhrec_segment_missing_total{roomId=\"$roomId\"} ${m.segmentMissing.get()}")
            sb.appendLine("# HELP xhrec_files_total Files produced")
            sb.appendLine("# TYPE xhrec_files_total counter")
            sb.appendLine("xhrec_files_total{roomId=\"$roomId\"} ${m.fileCount.get()}")
        }
        return sb.toString()
    }
}
