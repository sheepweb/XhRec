package github.rikacelery.v3.components

import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.DownloadMeta
import github.rikacelery.v3.data.StreamData
import github.rikacelery.v3.data.StreamEnd
import github.rikacelery.v3.data.StreamStart
import github.rikacelery.v3.events.EndReason
import github.rikacelery.v3.events.FileReady
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WriterComponentTest {

    @Test
    fun `init-only recording is deleted without publishing FileReady`() = runTest(UnconfinedTestDispatcher()) {
        val dataChannel = DataChannel()
        val eventBus = EventBus()
        val tmpDir = createTempDirectory("xhrec-writer-test").toFile()
        val ready = mutableListOf<FileReady>()
        eventBus.subscribe(backgroundScope, FileReady::class) { ready.add(it) }

        val writer = WriterComponent(dataChannel, tmpDir, eventBus = eventBus, parentScope = backgroundScope)
        try {
            writer.start()
            val startTime = Instant.parse("2026-07-08T10:22:47Z")

            dataChannel.send(StreamStart(1, "room", startTime, "highest"))
            dataChannel.send(
                StreamData(
                    roomId = 1,
                    data = ByteArray(2_048) { 1 },
                    segmentIndex = 0,
                    meta = DownloadMeta(
                        url = "https://example.test/live/stream_init_000.m4s",
                        fetchDurationMs = 10,
                        proxied = false,
                        timestamp = startTime
                    )
                )
            )
            dataChannel.send(StreamEnd(1, EndReason.StreamEnd))
            advanceUntilIdle()

            assertEquals(emptyList(), ready)
            assertNoMp4Files(tmpDir)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun assertNoMp4Files(tmpDir: java.io.File) {
        repeat(50) {
            if (tmpDir.listFiles { file -> file.extension == "mp4" }.orEmpty().isEmpty()) {
                return
            }
            Thread.sleep(10)
        }
        assertTrue(tmpDir.listFiles { file -> file.extension == "mp4" }.orEmpty().isEmpty())
    }
}
