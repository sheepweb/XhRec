package github.rikacelery.v2.postprocessors

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FixStampProcessor(room: ProcessorCtx, val destinationFolder: String) : Processor(room) {
    override fun process(input: File): List<File> {
        val eventFile = input.parentFile.resolve(input.nameWithoutExtension + ".event")
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
        if(eventFile.exists())
            Files.move(eventFile.toPath(),File(destinationFolder).resolve(output.nameWithoutExtension+".event").toPath())

        p.inputStream.bufferedReader().use {
            info("Transcoding...")
            while (true) {
                val char = it.readLine() ?: break
                debug(char.replace("\r", ""))
            }
        }
        if (p.waitFor() == 0) {
            input.delete()
            info("Moving...")
            try {
                Files.move(
                    output.toPath(),
                    File(destinationFolder).resolve(output.name).toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                info("Moved")
                return listOf(File(destinationFolder).resolve(output.name))
            } catch (e: Exception) {
                error("Failed to move file",e)
                throw e
            }
        } else {
            error("Transcoding failed, please check the command output")
            throw Exception("Transcoding failed")
        }
    }
}