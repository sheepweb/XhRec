package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.StreamData
import github.rikacelery.v3.data.StreamEnd
import github.rikacelery.v3.data.StreamEvent
import github.rikacelery.v3.data.StreamStart
import github.rikacelery.v3.events.FileReady
import github.rikacelery.v3.events.WriterFatal
import github.rikacelery.v3.hooks.WriterHook
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

sealed interface WriterMsg

data class ActiveFile(
    val file: File,
    val eventFile: File,
    val fos: FileOutputStream,
    val eventFos: FileOutputStream,
    val roomId: Long,
    val roomName: String,
    val startTime: Instant,
    var bytesWritten: Long = 0
) {
    fun dispose() {
        try {
            fos.close()
        } catch (_: Exception) {
        }
        try {
            eventFos.close()
        } catch (_: Exception) {
        }
        file.delete()
        eventFile.delete()
    }
}

class WriterComponent(
    private val dataChannel: DataChannel,
    private val tmpDir: File,
    private val hooks: List<WriterHook> = emptyList(),
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<WriterMsg>("WriterComponent", eventBus, parentScope) {

    private val files = ConcurrentHashMap<Long, ActiveFile>()
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
        .withZone(ZoneId.systemDefault())

    override suspend fun onStart(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                when (val msg = dataChannel.receive()) {
                    is StreamStart -> handleStreamStart(msg)
                    is StreamData -> handleStreamData(msg)
                    is StreamEnd -> handleStreamEnd(msg)
                    is StreamEvent -> handleStreamEvent(msg)
                }
            }
        }
    }

    override suspend fun handle(msg: WriterMsg) {}

    private suspend fun handleStreamStart(msg: StreamStart) {
        val timestamp = timeFormatter.format(msg.startTime)
        var path = "${tmpDir.absolutePath}/${msg.roomName}-$timestamp-init.mp4"
        try {
            hooks.forEach { path = it.beforeFileOpen(msg.roomId, path) }

            val file = File(path)
            file.parentFile?.mkdirs()
            val eventFile = File("$path.event")
            withContext(Dispatchers.IO) {
                files[msg.roomId] = ActiveFile(
                    file = file, eventFile = eventFile,
                    fos = FileOutputStream(file), eventFos = FileOutputStream(eventFile),
                    roomId = msg.roomId, roomName = msg.roomName, startTime = msg.startTime
                )
            }
            logger.info("Opened file: $path")
        } catch (e: Exception) {
            logger.error("Failed to open file for room ${msg.roomId}: ${e.message}", e)
            eventBus.publish(WriterFatal(msg.roomId, e.message ?: "Unknown error"))
            files.remove(msg.roomId)?.dispose()
        }
    }

    private suspend fun handleStreamData(msg: StreamData) {
        val active = files[msg.roomId] ?: return
        try {
            var data = msg.data
            hooks.forEach { data = it.beforeWrite(msg.roomId, data) }
            logger.trace("Receive {} {}", msg.roomId, msg.meta.url)
            withContext(Dispatchers.IO) {
                active.fos.write(data)
            }
            active.bytesWritten += data.size
        } catch (e: Exception) {
            logger.error("Failed to write data for room ${msg.roomId}: ${e.message}", e)
            eventBus.publish(WriterFatal(msg.roomId, e.message ?: "Unknown error"))
            files.remove(msg.roomId)?.dispose()
        }
    }

    private suspend fun handleStreamEnd(msg: StreamEnd) {
        val active = files.remove(msg.roomId) ?: return
        withContext(NonCancellable) {
            try {
                if (active.bytesWritten < 1024) {
                    active.dispose()
                    return@withContext
                }
                active.fos.close()
                active.eventFos.close()

                val endTime = Instant.now()
            val durationMs = java.time.Duration.between(active.startTime, endTime).toMillis()
            val durFmt = formatDurationHM(durationMs)
            val finalName = "${active.roomName}-${timeFormatter.format(active.startTime)}-${durFmt}.mp4"
            val finalFile = File(tmpDir, finalName)
            active.file.renameTo(finalFile)

            val finalEvent = File(tmpDir, "$finalName.event")
            active.eventFile.renameTo(finalEvent)

            if (finalEvent.length() == 0L) {
                finalEvent.delete()
            }
            if (finalFile.length() == 0L) {
                finalFile.delete()
                logger.info("Remove empty file: ${finalFile.absolutePath}, reason=${msg.reason}")
                return@withContext
            }

            hooks.forEach { it.afterFileClosed(msg.roomId, finalFile) }
            eventBus.publish(FileReady(msg.roomId, finalFile, msg.reason))
            logger.info("Closed file: ${finalFile.absolutePath}, reason=${msg.reason}")
        } catch (e: Exception) {
            logger.error("Failed to close file for room ${msg.roomId}: ${e.message}", e)
            eventBus.publish(WriterFatal(msg.roomId, e.message ?: "Unknown error"))
            active.dispose()
        }
        }
    }

    private suspend fun handleStreamEvent(msg: StreamEvent) {
        val active = files[msg.roomId] ?: return
        try {
            withContext(Dispatchers.IO) {
                active.eventFos.write((msg.eventJson + "\n").toByteArray())
            }
        } catch (e: Exception) {
            logger.error("Failed to write event for room ${msg.roomId}: ${e.message}", e)
            eventBus.publish(WriterFatal(msg.roomId, e.message ?: "Unknown error"))
            files.remove(msg.roomId)?.dispose()
        }
    }

    private fun formatDurationHM(ms: Long): String {
        val h = ms / 3600_000
        val m = (ms % 3600_000) / 60_000
        val s = (ms % 60_000) / 1000
        return if (h > 0) "${h}h${m}m${s}s" else if (m > 0) "${m}m${s}s" else "${s}s"
    }
}
