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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

suspend fun splitDownload(client: HttpClient, segmentUrl: String) = coroutineScope {
    withRetry(10) {
        client.fetchContentLength(segmentUrl)
    }.withMeasureTime {
        chunked(1024 * 10).asFlow().map {
            // cold flow doesn't allow concurrent download
//            mt.println(segmentUrl.hashCode().mod(30).toString(16) + " ${it.first}" + " Start")
            async {
                val d = withRetry(10, {
                    // stop retry if 404
                    (it as? ClientRequestException)?.response?.status == HttpStatusCode.NotFound
                }) { _->
                    client.get(segmentUrl) {
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