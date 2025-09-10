package github.rikacelery.v2.metric

import kotlinx.serialization.Serializable

@Serializable
data class MetricItem(
    val total: Int = 0,
    val downloading: Int = 0,
    val segmentID: Int = 0,
    val segmentMissing: Int = 0,
    val done: Int = 0,
    val successProxied: Int = 0,
    val successDirect: Int = 0,
    val failed: Int = 0,
    val bytesWrite: Long = 0,
    val quality: String = "",
    val latencyMS: Double = 0.0,
    val refreshLatencyMS: Double = 0.0,
//    val running: Map<String, UrlInfo>
) {

}
