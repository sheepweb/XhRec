package github.rikacelery

import github.rikacelery.utils.withRetry
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext

class Session(
    override val coroutineContext: CoroutineContext,
    private val room: Room,
    private val client: HttpClient,
    private val clientProxy: HttpClient
) :
    CoroutineScope {
    suspend fun start() {
        val writer = Writer(room.name, "out", "tmp")
        writer.init()
        //请帮我修改代码使 segmentGenerator 可以被停止（使用下面的stop方法），而不影响后续操作
        segmentGenerator()
            .map(::tryDownload)
            .map { it.await() }
            .collect {
                if (it.isSuccess) {
                    writer.append(it.getOrThrow())
                } else {
                    println("failed")
                }
            }
        writer.done()
    }

    suspend fun stop() {
//        停止segment Generator，等待后续操作完成，最后返回
        TODO()
    }

    private var currentQuality = room.quality

    private val streamUrl: String
        get() {
            return if (room.quality != "raw" && room.quality.isNotBlank()) "https://b-hls-11.doppiocdn.live/hls/%d/%d_%s.m3u8?playlistType=lowLatency".format(
                room.id, room.id, currentQuality
            )
            else "https://b-hls-11.doppiocdn.live/hls/%d/%d.m3u8?playlistType=lowLatency".format(room.id, room.id)
        }

    private fun segmentGenerator(): Flow<String> = flow {
        var started = false
        var initUrl: String
        val cache = CircleCache(100)

        while (currentCoroutineContext().isActive) {
            try {
                val lines = withTimeout(1000) {
                    clientProxy.get(streamUrl).bodyAsText().lines()
                }
                initUrl = parseInitUrl(lines)
                val videos = parseSegmentUrl(lines)
                if (!started) {
                    started = true
                    emit(initUrl)
                }
                videos.forEach {
                    if (!cache.contains(it)) {
                        cache.add(it)
                        emit(it)
                    }
                }
            } catch (_: TimeoutCancellationException) {
            } catch (it: ClientRequestException) {
                if (shouldStop()(it)){
                    stop()
                }else {
                    mt.println("generator: error: " + it.stackTraceToString())
                }
            }
            delay(500)
        }
    }

    private fun parseSegmentUrl(lines: List<String>) =
        lines.filter { it.startsWith("#EXT-X-PART") && it.contains("URI=\"") }
            .map { it.substringAfter("URI=\"").substringBefore("\"") }

    private fun parseInitUrl(lines: List<String>) =
        lines.first { it.startsWith("#EXT-X-MAP") }.substringAfter("#EXT-X-MAP:URI=")
            .removeSurrounding("\"")

    private fun tryDownload(url: String): Deferred<Result<ByteArray>> = async {
        runCatching {
            val data =
                withTimeoutOrNull(10_000L) {
                    withRetry(15, shouldStop()) { _ ->
                        withTimeout(5_000) {
                            client.get(url)
                                .readBytes()
                        }
                    }
                } ?: run {
                    mt.println("proxy download: ${room.name}")
                    proxiedClient.get(url).readBytes()
                }
            data
        }
    }

    private fun shouldStop(): (Throwable) -> Boolean = {
        it is ClientRequestException && it.response.status == HttpStatusCode.NotFound ||
                it is CancellationException
    }
}



