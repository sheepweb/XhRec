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

suspend fun Flow<String>.segmentStreamDownloader(client: HttpClient): Flow<ByteArray> {
    val scope = CoroutineScope(SupervisorJob())
    var inited = false
    return map { segmentUrl ->
        if (segmentUrl.contains("init") && inited) return@map null
//        println(segmentUrl)
        withRetry(10) {
            client.fetchContentLength(segmentUrl)
        }.withMeasureTime {
            chunked(1024 * 1024).asFlow().map {
                // cold flow dosent allow concurent download
                scope.async {
                    withRetry(10, {
                        // stop retry if 404
                        (it as? ClientRequestException)?.response?.status == HttpStatusCode.NotFound
                    }) {
                        client.get(segmentUrl) {
                            headers {
                                append("Range", "bytes=${it.first}-${it.last}")
                            }
                        }.readBytes()
                    }
                }
            }.flowOn(Dispatchers.IO).toList().awaitAll()
                .reduce { acc, bytes -> acc + bytes }
                .also { inited = true }
        }
    }.filterNotNull()
}