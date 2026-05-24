package github.rikacelery.v3.postprocessors

import github.rikacelery.v3.utils.runProcessGetStdoutBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ShellProcessor(
    private val command: List<String>,
    private val noreturn: Boolean = true,
    private val removeInput: Boolean = false
) : Processor() {
    override suspend fun process(input: File, ctx: ProcessorCtx): List<File> {
        val cmd = command.map { substitute(it, ctx, input) }
        val builder = ProcessBuilder(cmd)
        val p = withContext(Dispatchers.IO) {
            builder.start()
        }
        p.errorStream.bufferedReader().use {
            while (true) {
                val line = it.readLine() ?: break
                println(line)
            }
        }
        if (withContext(Dispatchers.IO) {
                p.waitFor()
            } == 0) {
            if (noreturn) return listOf(input)
            val outputFile = File(p.inputStream.bufferedReader().readLines().last())
            if (!outputFile.exists()) throw IllegalStateException("Output file not found: $outputFile")
            if (removeInput) input.delete()
            return listOf(outputFile)
        } else {
            if (noreturn) return listOf(input)
            throw RuntimeException("Shell command failed: ${cmd.joinToString(" ")}")
        }
    }

    private fun substitute(template: String, ctx: ProcessorCtx, file: File): String {
        return template
            .replace("{{ROOM_NAME}}", ctx.roomName)
            .replace("{{ROOM_ID}}", ctx.roomId.toString())
            .replace("{{RECORD_START}}", formatInstant(ctx.startTime))
            .replace("{{RECORD_END}}", formatInstant(ctx.endTime))
            .replace("{{RECORD_DURATION}}", ctx.durationMs.toString())
            .replace("{{RECORD_DURATION_STR}}", formatDuration(ctx.durationMs))
            .replace("{{RECORD_quality}}", ctx.quality)
            .replace("{{INPUT}}", file.absolutePath)
            .replace("{{INPUT_DIR}}", file.parentFile.absolutePath)
            .replace("{{FILE_NAME}}", file.name)
            .replace("{{FILE_NAME_NOEXT}}", file.nameWithoutExtension)
            .replace("\\{\\{TOTAL_FRAMES}}".toRegex()) {
                runProcessGetStdoutBlocking(
                    "ffprobe", "-v", "error", "-select_streams", "v:0",
                    "-count_frames", "-show_entries", "stream=nb_frames",
                    "-of", "default=noprint_wrappers=1:nokey=1", file.absolutePath
                )
            }
            .replace("\\{\\{TOTAL_FRAMES_GUESS}}".toRegex()) {
                runProcessGetStdoutBlocking(
                    "ffprobe", "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=r_frame_rate",
                    "-of", "default=noprint_wrappers=1:nokey=1", file.absolutePath
                ).split("/").map { it.toIntOrNull() ?: 1 }.reduce { a, b -> a / b }
                    .toLong().times(ctx.durationMs / 1000).toString()
            }
    }

    private fun formatInstant(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_DATE_TIME)

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return if (days > 0) "%02dd%02dh%02dm%02ds".format(days, hours % 24, minutes % 60, seconds % 60)
        else "%02dh%02dm%02ds".format(hours % 24, minutes % 60, seconds % 60)
    }
}
