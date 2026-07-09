package github.rikacelery.v3.postprocessors

import github.rikacelery.v3.utils.processOutputSuppressedMessage
import github.rikacelery.v3.utils.shouldLogProcessOutputLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        logger.debug("run: {}", cmd.joinToString(" "))
        if (!noreturn) ensureShellOutputParents(cmd)
        val p = withContext(Dispatchers.IO) { ProcessBuilder(cmd).start() }

        val (exitCode, lastLine) = coroutineScope {
            var outputLine: String? = null
            val stdoutJob = async(Dispatchers.IO) {
                p.inputStream.bufferedReader().use { r ->
                    var lineNumber = 0
                    var suppressionLogged = false
                    while (true) {
                        val line = r.readLine() ?: break
                        lineNumber += 1
                        if (shouldLogProcessOutputLine(lineNumber)) {
                            logger.info("[stdout] {}", line)
                        } else if (!suppressionLogged) {
                            logger.info(processOutputSuppressedMessage("stdout"))
                            suppressionLogged = true
                        }
                        outputLine = line
                    }
                }
            }
            val stderrJob = async(Dispatchers.IO) {
                p.errorStream.bufferedReader().use { r ->
                    var lineNumber = 0
                    var suppressionLogged = false
                    while (true) {
                        val line = r.readLine() ?: break
                        lineNumber += 1
                        if (shouldLogProcessOutputLine(lineNumber)) {
                            logger.info("[stderr] {}", line)
                        } else if (!suppressionLogged) {
                            logger.info(processOutputSuppressedMessage("stderr"))
                            suppressionLogged = true
                        }
                    }
                }
            }
            val exitJob = async(Dispatchers.IO) { p.waitFor() }
            stdoutJob.await()
            stderrJob.await()
            Pair(exitJob.await(), outputLine)
        }

        if (exitCode == 0) {
            if (noreturn) return listOf(input)
            val outputLine = lastLine ?: throw IllegalStateException("No output from shell command")
            val outputFile = File(outputLine)
            if (!outputFile.exists()) {
                val parent = outputFile.parentFile
                val parentHint = if (parent != null && !parent.exists()) {
                    " (parent directory does not exist: $parent)"
                } else {
                    ""
                }
                throw IllegalStateException("Output file not found: $outputFile$parentHint")
            }
            if (removeInput) input.delete()
            return listOf(outputFile)
        } else {
            if (noreturn) return listOf(input)
            throw RuntimeException("Shell command failed: ${cmd.joinToString(" ")}")
        }
    }

    private suspend fun substitute(template: String, ctx: ProcessorCtx, file: File): String {
        var s = template
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
        if ("{{TOTAL_FRAMES}}" in s) s = s.replace("{{TOTAL_FRAMES}}", totalFrames(file))
        if ("{{TOTAL_FRAMES_GUESS}}" in s) s = s.replace("{{TOTAL_FRAMES_GUESS}}", totalFramesGuess(file, ctx.durationMs))
        return s
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return if (days > 0) "%02dd%02dh%02dm%02ds".format(days, hours % 24, minutes % 60, seconds % 60)
        else "%02dh%02dm%02ds".format(hours % 24, minutes % 60, seconds % 60)
    }

    private suspend fun totalFrames(input: File): String = withContext(Dispatchers.IO) {
        runCatching {
            ProcessBuilder(
                "ffprobe", "-v", "error", "-select_streams", "v:0",
                "-count_frames", "-show_entries", "stream=nb_frames",
                "-of", "default=noprint_wrappers=1:nokey=1", input.absolutePath
            ).start().inputStream.bufferedReader().readText().trim()
        }.getOrElse { "" }
    }

    private suspend fun totalFramesGuess(input: File, durationMs: Long): String = withContext(Dispatchers.IO) {
        runCatching {
            val fpsStr = ProcessBuilder(
                "ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=r_frame_rate",
                "-of", "default=noprint_wrappers=1:nokey=1", input.absolutePath
            ).start().inputStream.bufferedReader().readText().trim()
            val fps = fpsStr.split("/").mapNotNull { it.toLongOrNull() }
                .takeIf { it.size == 2 }?.let { it[0].toDouble() / it[1] } ?: return@withContext ""
            (fps * durationMs / 1000).toLong().toString()
        }.getOrElse { "" }
    }
}

private val shellOutputAssignmentRegex = Regex("""\b(?:OUT|OUTPUT)=("([^"]+)"|'([^']+)'|([^;\s]+))""")

internal fun shellAssignedOutputFiles(command: List<String>): List<File> =
    command.flatMap { arg ->
        shellOutputAssignmentRegex.findAll(arg).mapNotNull { match ->
            val rawPath = match.groups[2]?.value
                ?: match.groups[3]?.value
                ?: match.groups[4]?.value
                ?: return@mapNotNull null
            File(rawPath).takeIf { it.isAbsolute && it.parentFile != null }
        }
    }

internal fun ensureShellOutputParents(command: List<String>) {
    shellAssignedOutputFiles(command).forEach { outputFile ->
        outputFile.parentFile.mkdirs()
    }
}
