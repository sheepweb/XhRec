package github.rikacelery.v2.postprocessors

import github.rikacelery.utils.runProcessGetStdout
import github.rikacelery.utils.toLocalDateTime
import java.io.File
import java.time.format.DateTimeFormatter

class ShellProcessor(room: ProcessorCtx, val script: List<String>, val noreturn: Boolean = true, val removeInput: Boolean = true) : Processor(room) {
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
                    context.startTime.toLocalDateTime().format(DateTimeFormatter.ISO_DATE_TIME)
                )
                .replace(
                    "{{RECORD_END}}",
                    context.endTime.toLocalDateTime().format(DateTimeFormatter.ISO_DATE_TIME)
                )
                .replace("{{RECORD_DURATION}}", context.duration.toString())
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
                    ).replace("\\{\\{TOTAL_FRAMES_GUESS}}".toRegex(), {
                        runProcessGetStdout(
                            "ffprobe",
                            "-v",
                            "error",
                            "-select_streams",
                            "v:0",
                            "-show_entries",
                            "stream=r_frame_rate",
                            "-of",
                            "default=noprint_wrappers=1:nokey=1",
                            input.absolutePath
                        ).split("/").reduce { a,b->
                            try {
                                a.toInt() / b.toInt()
                            } catch (e: Exception) {
                                1
                            }.toString()
                        }.toLong().times(context.duration/1000).toString()
                    })
                })
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
                }).replace("\\{\\{TOTAL_FRAMES_GUESS}}".toRegex(), {
                    runProcessGetStdout(
                        "ffprobe",
                        "-v",
                        "error",
                        "-select_streams",
                        "v:0",
                        "-show_entries",
                        "stream=r_frame_rate",
                        "-of",
                        "default=noprint_wrappers=1:nokey=1",
                        input.absolutePath
                    ).split("/").reduce { a,b->
                        try {
                            a.toInt() / b.toInt()
                        } catch (e: Exception) {
                            1
                        }.toString()
                    }.toLong().times(context.duration).toString()
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