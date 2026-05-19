package github.rikacelery.v3.core

import github.rikacelery.v3.data.*
import github.rikacelery.v3.events.EndReason
import java.time.Instant

class OrderedEmitter(
    private val roomId: Long,
    private val output: suspend (DataChannelMsg) -> Unit
) {
    private var nextIndex = 0L
    private val buffer = sortedMapOf<Long, DownloadResult>()
    private var cutPending: CutState? = null

    private data class CutState(
        val index: Long,
        val roomName: String,
        val startTime: Instant,
        val reason: EndReason
    )

    suspend fun complete(idx: Long, result: DownloadResult) {
        buffer[idx] = result
        drain()
    }

    suspend fun acquire(): Long {
        return 0L
    }

    fun signalCut(index: Int, roomName: String, startTime: Instant, reason: EndReason) {
        cutPending = CutState(index.toLong(), roomName, startTime, reason)
    }

    fun reset(fromIndex: Int) {
        buffer.clear()
        cutPending = null
        nextIndex = fromIndex.toLong()
    }

    private suspend fun drain() {
        while (buffer.isNotEmpty() && buffer.firstKey() == nextIndex) {
            val result = buffer.remove(nextIndex)!!
            emitIfSuccess(nextIndex, result)
            nextIndex++

        }
    }

    private suspend fun emitIfSuccess(idx: Long, result: DownloadResult) {
        when (result) {
            is DownloadResult.Success -> {
                output(
                    StreamData(
                        roomId = roomId,
                        data = result.data,
                        segmentIndex = idx.toInt(),
                        meta = result.meta
                    )
                )
            }

            is DownloadResult.Failed -> { /* skip */
            }

            is DownloadResult.Skipped -> { /* skip */
            }

            is DownloadResult.CutPoint -> {
                val cut = result.cut
                output(StreamEnd(roomId, cut.reason))
                if (cut.reason != EndReason.UserStop)
                    output(StreamStart(roomId, cut.roomName, cut.startTime))
            }
        }
    }
}
