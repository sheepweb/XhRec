package github.rikacelery.v3.components

import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.StreamEnd
import github.rikacelery.v3.events.Download
import github.rikacelery.v3.events.CutPoint
import github.rikacelery.v3.events.EndReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderComponentTest {
    @Test
    fun `cut point without active download still emits stream end`() = runTest(UnconfinedTestDispatcher()) {
        val dataChannel = DataChannel()
        val downloader = DownloaderComponent(dataChannel, eventBus = EventBus(), parentScope = backgroundScope)
        downloader.start()

        downloader.tell(
            DoCutPoint(
                CutPoint(
                    roomId = 42,
                    index = 0,
                    roomName = "room",
                    startTime = Instant.now(),
                    reason = EndReason.UserStop,
                    quality = "highest"
                )
            )
        )

        val msg = dataChannel.receive()
        assertTrue(msg is StreamEnd)
        assertEquals(42, msg.roomId)
        assertEquals(EndReason.UserStop, msg.reason)

        downloader.stop()
    }

    @Test
    fun `terminal cut point retires active download before later cut points`() = runTest(UnconfinedTestDispatcher()) {
        val dataChannel = DataChannel()
        val downloader = DownloaderComponent(dataChannel, eventBus = EventBus(), parentScope = backgroundScope)
        downloader.start()

        downloader.tell(DoDownload(Download(roomId = 42, urls = emptyList(), startIndex = 0, generation = 0)))
        downloader.tell(cutPoint(EndReason.UserStop))

        val first = dataChannel.receive()
        assertTrue(first is StreamEnd)
        assertEquals(EndReason.UserStop, first.reason)

        downloader.tell(cutPoint(EndReason.NewInit))

        val unexpected = withTimeoutOrNull(50) { dataChannel.receive() }
        assertEquals(null, unexpected)

        downloader.stop()
    }

    private fun cutPoint(reason: EndReason) =
        DoCutPoint(
            CutPoint(
                roomId = 42,
                index = 0,
                roomName = "room",
                startTime = Instant.now(),
                reason = reason,
                quality = "highest"
            )
        )
}
