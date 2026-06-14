package github.rikacelery.v3.postprocessors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ShellProcessor(
    private val command: List<String>,
    datePattern: String,
    private val noreturn: Boolean = true,
    private val removeInput: Boolean = false
) : Processor() {
    private val fmt = DateTimeFormatter.ofPattern(datePattern).withZone(ZoneId.systemDefault())

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
            .replace("{{RECORD_START}}", fmt.format(Instant.ofEpochMilli(ctx.startTime)))
            .replace("{{RECORD_END}}", fmt.format(Instant.ofEpochMilli(ctx.endTime)))
            .replace("{{RECORD_DURATION}}", (ctx.durationMs / 1000).toString())
            .replace("{{RECORD_DURATION_STR}}", formatDuration(ctx.durationMs))
            .replace("{{RECORD_QUALITY}}", ctx.quality)
            .replace("{{INPUT_ABS}}", file.absolutePath)
            .replace("{{INPUT_DIR}}", file.absoluteFile.parent)
            .replace("{{INPUT_NAME}}", file.name)
            .replace("{{INPUT_NAME_NOEXT}}", file.nameWithoutExtension)
            .replace("{{TOTAL_FRAMES}}", totalFrames(file))
            .replace("{{TOTAL_FRAMES_GUESS}}", totalFramesGuess(file, ctx.durationMs))
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return if (days > 0) "%02dd%02dh%02dm%02ds".format(days, hours % 24, minutes % 60, seconds % 60)
        else "%02dh%02dm%02ds".format(hours % 24, minutes % 60, seconds % 60)
    }

    private fun totalFrames(input: File): String = runCatching {
        ProcessBuilder(
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-count_frames", "-show_entries", "stream=nb_frames",
            "-of", "default=noprint_wrappers=1:nokey=1", input.absolutePath
        ).start().inputStream.bufferedReader().readText().trim()
    }.getOrElse { "" }

    private fun totalFramesGuess(input: File, durationMs: Long): String = runCatching {
        val fpsStr = ProcessBuilder(
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=r_frame_rate",
            "-of", "default=noprint_wrappers=1:nokey=1", input.absolutePath
        ).start().inputStream.bufferedReader().readText().trim()
        val fps = fpsStr.split("/").mapNotNull { it.toLongOrNull() }
            .takeIf { it.size == 2 }?.let { it[0].toDouble() / it[1] } ?: return@runCatching ""
        (fps * durationMs / 1000).toLong().toString()
    }.getOrElse { "" }
}
