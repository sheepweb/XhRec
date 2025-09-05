package github.rikacelery.v2.postprocessors

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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