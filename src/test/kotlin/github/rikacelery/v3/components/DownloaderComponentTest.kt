package github.rikacelery.v3.components

import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.StreamEnd
import github.rikacelery.v3.events.CutPoint
import github.rikacelery.v3.events.EndReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
