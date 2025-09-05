package github.rikacelery.v2.postprocessors

import github.rikacelery.utils.runProcessGetStdout
import java.io.File

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