package github.rikacelery

import github.rikacelery.utils.chunked
import github.rikacelery.utils.fetchContentLength
import github.rikacelery.utils.withMeasureTime
import github.rikacelery.utils.withRetry
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList

@Suppress("unused")
suspend fun HttpClient.splitDownload( segmentUrl: String) = coroutineScope {
    withRetry(10) {
        fetchContentLength(segmentUrl)
    }.withMeasureTime {
        chunked(1024 * 10).asFlow().map {
            // cold flow doesn't allow concurrent download
            async {
                val d = withRetry(10, {
                    // stop retry if 404
                    (it as? ClientRequestException)?.response?.status == HttpStatusCode.NotFound
                }) { _->
                    get(segmentUrl) {
                        headers {
                            append("Range", "bytes=${it.first}-${it.last}")
                        }
                    }.readBytes()
                }
                d
            }
        }.flowOn(Dispatchers.IO).toList().awaitAll()
            .reduce { acc, bytes -> acc + bytes }
    }
}