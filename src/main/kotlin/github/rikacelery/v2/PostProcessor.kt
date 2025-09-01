package github.rikacelery.v2

import github.rikacelery.Room
import github.rikacelery.utils.runProcessGetStdout
import github.rikacelery.utils.toLocalDateTime
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.times

object PostProcessor {
    val concurrency = Semaphore(4)
    lateinit var config: JsonArray

    fun loadConfig(file: File) {
        config = Json.Default.decodeFromString(
            JsonObject.serializer(),
            file.readText()
        ).jsonObject.get("default")?.jsonArray ?: JsonArray(listOf())
        println("Post processors loads: ${config.map { it.jsonObject["type"]!!.jsonPrimitive.content }}")
    }


    private fun buildProcessors(context: ProcessorCtx): MutableList<Processor> {
        val processors = mutableListOf<Processor>()
        for (processor in config) {
            val type = processor.jsonObject["type"]!!.jsonPrimitive.content
            when (type) {
                "fix_stamp" -> {
                    processors.add(FixStampProcessor(context, processor.jsonObject["output"]!!.jsonPrimitive.content))
                }

                "shell" -> {
                    val args = processor.jsonObject["args"]!!.jsonArray
                    val script = args.map { it.jsonPrimitive.content }
                    processors.add(
                        ShellProcessor(
                            context,
                            script,
                            processor.jsonObject["noreturn"]?.jsonPrimitive?.booleanOrNull == true,
                            processor.jsonObject["remove_input"]?.jsonPrimitive?.booleanOrNull == true,
                        )
                    )
                }

                "slice" -> {
                    val duration = Duration.parse(processor.jsonObject["duration"]!!.jsonPrimitive.content)
                    processors.add(SliceProcessor(context, duration))
                }

                "move" -> {
                    processors.add(
                        MoveProcessor(
                            context,
                            processor.jsonObject["dest"]?.jsonPrimitive!!.content,
                            processor.jsonObject["date_pattern"]?.jsonPrimitive!!.content,
                        )
                    )
                }
            }
        }
        println("Post processors build(${context.room}): ${processors.map { it }}")
        return processors
    }

    suspend fun process(input: File, context: ProcessorCtx): List<File> = concurrency.withPermit {
        val processors = buildProcessors(context)
        var files = listOf(input)
        for (processor in processors) {

            val tmp = files.flatMapIndexed { idx, file ->
                println("[${context.room.name}] $idx/${files.size} ${processor.javaClass.simpleName} $file")
                val files = try {
                    processor.process(file)
                } catch (e: Exception) {
                    throw e
                }
                println("[${context.room.name}] $idx/${files.size} ${processor.javaClass.simpleName} -> $files")
                files
            }
            files = tmp
        }
        return files
    }
}

data class ProcessorCtx(
    val room: Room,
    val startTime: Date,
    val endTime: Date,
    val duration: Long,
    val quality: String,
)

abstract class Processor(var context: ProcessorCtx) {

    abstract fun process(input: File): List<File>
    fun log(msg: String) {
        println("[${context.room.name}] $msg")
    }
}

class FixStampProcessor(room: ProcessorCtx, val destinationFolder: String) : Processor(room) {
    override fun process(input: File): List<File> {
        val output = input.parentFile.resolve("${input.nameWithoutExtension}.fixed.${input.extension}")
        val builder = ProcessBuilder(
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-stats",
            "-i",
            input.absolutePath,
            "-c",
            "copy",
            output.absolutePath
        )
        builder.redirectErrorStream(true)
        val p = builder.start()
        if (!File(destinationFolder).exists()) {
            File(destinationFolder).mkdirs()
        }
        p.inputStream.bufferedReader().use {
            while (true) {
                val char = it.readLine() ?: break
                log(char.replace("\r", ""))
            }
        }
        if (p.waitFor() == 0) {
            input.delete()
            log("moving...")
            try {
                Files.move(
                    output.toPath(),
                    File(destinationFolder).resolve(output.name).toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                log("移动成功")
                return listOf(File(destinationFolder).resolve(output.name))
            } catch (e: Exception) {
                log("无法移动文件$e")
                throw e
            }
        } else {
            log("转码失败，请查看命令输出")
            throw Exception("转码失败")
        }
    }
}

class ShellProcessor(room: ProcessorCtx, val script: List<String>, val noreturn: Boolean = true, val removeInput: Boolean = true) : Processor(room) {

    override fun process(input: File): List<File> {

        val replace: (arg: String) -> String = { arg ->
            arg
                .replace("{{INPUT}}", input.absolutePath)
                .replace("{{INPUT_DIR}}", input.parentFile.absolutePath)
                .replace("{{FILE_NAME}}", input.name)
                .replace("{{FILE_NAME_NOEXT}}", input.nameWithoutExtension)
                .replace("\\{\\{TOTAL_FRAMES}}".toRegex(), {
                    runProcessGetStdout(
                        "ffprobe",
                        "-v",
                        "error",
                        "-select_streams",
                        "v:0",
                        "-count_frames",
                        "-show_entries",
                        "stream=nb_frames",
                        "-of",
                        "default=noprint_wrappers=1:nokey=1",
                        input.absolutePath
                    )
                })
        }
        val cmd = script.map(replace)
        println(cmd.joinToString(" "))
        val builder = ProcessBuilder(
            cmd
        )
        val p = builder.start()
        p.errorStream.bufferedReader().use {
            while (true) {
                val char = it.readLine() ?: break
                log(char.replace("\r", ""))
            }
        }
        if (p.waitFor() == 0) {
            if (noreturn) return listOf()
            val outputFile = File(p.inputStream.bufferedReader().readLines().last())
            assert(outputFile.exists())
            if (removeInput) {
                input.delete()
            }
            return listOf(outputFile)
        } else {
            throw Exception("运行失败")
        }
    }
}

class MoveProcessor(room: ProcessorCtx, val destPattern: String, val datePattern: String = "yyyy_MM_dd_HH_mm_ss") :
    Processor(room) {
    private fun format(time: Long): String {
        val seconds = time / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return if (days > 0) {
            "%02dd%02dh%02dm%02ds".format(days, hours % 24, minutes % 60, seconds % 60)
        } else {
            "%02dh%02dm%02ds".format(hours % 24, minutes % 60, seconds % 60)
        }
    }

    override fun process(input: File): List<File> {
        val replace: (arg: String) -> String = { arg ->
            arg
                .replace("{{ROOM_NAME}}", context.room.name)
                .replace("{{ROOM_ID}}", context.room.id.toString())
                .replace(
                    "{{RECORD_START}}",
                    context.startTime.toLocalDateTime().format(DateTimeFormatter.ofPattern(datePattern))
                )
                .replace(
                    "{{RECORD_END}}",
                    context.endTime.toLocalDateTime().format(DateTimeFormatter.ofPattern(datePattern))
                )
                .replace("{{RECORD_DURATION}}", context.room.id.toString())
                .replace("{{RECORD_DURATION_STR}}", format(context.duration))
                .replace("{{RECORD_quality}}", context.quality)
                .replace("{{INPUT}}", input.absolutePath)
                .replace("{{INPUT_DIR}}", input.parentFile.absolutePath)
                .replace("{{FILE_NAME}}", input.name)
                .replace("{{FILE_NAME_NOEXT}}", input.nameWithoutExtension)
                .replace("\\{\\{TOTAL_FRAMES}}".toRegex(), {
                    runProcessGetStdout(
                        "ffprobe",
                        "-v",
                        "error",
                        "-select_streams",
                        "v:0",
                        "-count_frames",
                        "-show_entries",
                        "stream=nb_frames",
                        "-of",
                        "default=noprint_wrappers=1:nokey=1",
                        input.absolutePath
                    )
                })
        }

        val dest = File(replace(destPattern))
        if (dest.absolutePath != input.absolutePath) {
            input.renameTo(dest)
            return listOf((dest))
        } else {
            return listOf(input)
        }
    }
}

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