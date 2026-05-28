package github.rikacelery.v3.postprocessors

import github.rikacelery.v3.utils.runProcessStreaming
import java.io.File

class FixStampProcessor(private val destinationFolder: File) : Processor() {
    override suspend fun process(input: File, ctx: ProcessorCtx): List<File> {
        destinationFolder.mkdirs()
        val output = File(destinationFolder, input.name.replace(".mp4", ".fixed.mp4"))
        runProcessStreaming(
            { line -> logger.info("[ffmpeg] {}", line) },
            "ffmpeg",
            "-hide_banner",
            "-loglevel", "error",
            "-stats",
            "-i", input.absolutePath, "-c", "copy",
            output.absolutePath
        )
        input.delete()

        val eventFile = input.parentFile.resolve(input.nameWithoutExtension + ".event")
        if (eventFile.exists()) {
            val destEvent = File(destinationFolder, output.nameWithoutExtension + ".event")
            eventFile.copyTo(destEvent, overwrite = true)
            eventFile.delete()
        }
        return listOf(output)
    }
}
