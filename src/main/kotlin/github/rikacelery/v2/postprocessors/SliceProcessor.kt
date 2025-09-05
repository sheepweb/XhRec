package github.rikacelery.v2.postprocessors

import github.rikacelery.utils.runProcessGetStdout
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.times

class SliceProcessor(room: ProcessorCtx, val duration: Duration) : Processor(room) {
    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }

    override fun process(input: File): List<File> {

        val total = runProcessGetStdout(
            "ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1",
            input.absolutePath,
        ).toDouble().seconds
        if (total <= duration) {
            return listOf(input)
        }
        val sliceCount = ((total + duration.minus(1.seconds)) / duration).toInt()
        val sliceDuration = total / sliceCount
        val outputFiles = (0 until sliceCount).mapNotNull { i ->
            val output = input.parentFile.resolve("${input.nameWithoutExtension}_${i}.${input.extension}")
            println(
                "[${context.room.name}] piece$i/${sliceCount} start:${formatDuration((i * sliceDuration).inWholeSeconds)} duration:${
                    formatDuration(
                        sliceDuration.inWholeSeconds
                    )
                }"
            )
            val builder = ProcessBuilder(buildList {
                add("ffmpeg")
                add("-hide_banner")
                add("-loglevel")
                add("error")
                add("-stats")
                add("-ss")
                add(formatDuration((i * sliceDuration).inWholeSeconds))
                add("-i")
                add(input.absolutePath)
                if (i != sliceCount - 1) {
                    add("-t")
                    add(formatDuration(sliceDuration.inWholeSeconds))
                }
                add("-c")
                add("copy")
                add(output.absolutePath)
            }
            )
            builder.redirectErrorStream(true)
            val p = builder.start()
            p.inputStream.bufferedReader().use {
                while (true) {
                    val char = it.readLine() ?: break
                    log(char.replace("\r", ""))
                }
            }
            if (p.waitFor() == 0) {
                output
            } else {
                throw Exception("裁剪分片失败")
            }
        }
        input.delete()
        return outputFiles
    }
}