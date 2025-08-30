package github.rikacelery.v2

import github.rikacelery.utils.toLocalDateTime
import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.format.DateTimeFormatter
import java.util.*

class Writer(
    private val name: String,
    private val destFolder: String,
    private val tmpfolder: String,
    private val maxDurationSeconds: Long = 0, // 由配置控制；0表示不分割
    private val contactSheetEnabled: Boolean = false,
    private val siteName: String = "xhamsterlive"
) {
    private lateinit var file: File
    private lateinit var bufferedWriter: BufferedOutputStream
    private lateinit var timeStarted: Date
    private var isInit = false
    private val ext = "mp4"

    // 分片相关
    private var segmentIndex = 0
    private var segmentStartMs = 0L
    private val postProcessExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()


    fun init() {
        timeStarted = Date()
        segmentIndex = 0
        segmentStartMs = System.currentTimeMillis()
        file = File(tmpfolder, "${name}-${formatedStartTime()}-part${segmentIndex}.${ext}")
        bufferedWriter = file.outputStream().buffered(bufferSize = 1024 * 1024 * 8)
        isInit = true
        if (File(tmpfolder).exists().not()) {
            File(tmpfolder).mkdirs()
        }
        if (File(destFolder).exists().not()) {
            File(destFolder).mkdirs()
        }
    }

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

    fun append(data: ByteArray) {
        bufferedWriter.write(data)
        val now = System.currentTimeMillis()
        val elapsedSec = (now - segmentStartMs) / 1000
        if (maxDurationSeconds > 0 && elapsedSec >= maxDurationSeconds) {
            rotate()
        }
    }

    private fun rotate() {
        try {
            bufferedWriter.flush()
            bufferedWriter.close()
        } catch (_: Exception) {}
        val duration = System.currentTimeMillis() - segmentStartMs
        val src = file
        val renamed = File(tmpfolder, "${name}-${formatedStartTime()}-part${segmentIndex}-${format(duration)}.${ext}")
        if (src.renameTo(renamed)) {
            // 异步处理并搬运
            postProcessExecutor.submit {
                processAndMove(renamed)
            }
        }
        segmentIndex += 1
        segmentStartMs = System.currentTimeMillis()
        file = File(tmpfolder, "${name}-${formatedStartTime()}-part${segmentIndex}.${ext}")
        bufferedWriter = file.outputStream().buffered(bufferSize = 1024 * 1024 * 8)
    }

    private fun processAndMove(input: File) {
        val output = File(tmpfolder, input.nameWithoutExtension + ".fixed.$ext")
        val builder = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-stats",
            "-i", input.absolutePath, "-c", "copy", output.absolutePath
        )
        builder.redirectErrorStream(true)
        val p = builder.start()
        p.inputStream.bufferedReader().use {
            while (true) {
                val line = it.readLine() ?: break
                println("[$name] ${line.replace("\r", "")}")
            }
        }
        val code = p.waitFor()
        if (code == 0) {
            input.delete()
            try {
                val finalBase = "${name}_${siteName}_${formatedStartTime()}"
                val modelDir = File(destFolder, name).apply { if (!exists()) mkdirs() }
                val finalFile = File(modelDir, "$finalBase.$ext")
                Files.move(output.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                if (contactSheetEnabled) runCatching { generateContactSheet(finalFile) }.onFailure { println("[$name] contactsheet error: ${it.message}") }
            } catch (e: Exception) {
                println("[$name] 无法移动文件$e")
            }
        } else {
            println("[$name] 分片转码失败")
        }
    }

    private fun generateContactSheet(processedVideo: File) {
        val outJpg = File(processedVideo.parentFile, processedVideo.nameWithoutExtension + ".jpg")
        val cols = 10
        val rows = 9
        val totalWidth = 1920
        val padding = 4
        val thumbWidth = (totalWidth - (cols + 1) * padding) / cols
        val filter = "fps=1,scale=${'$'}thumbWidth:-1,tile=${'$'}colsx${'$'}rows:color=0x333333:margin=${'$'}padding:padding=${'$'}padding,scale=${'$'}totalWidth:-1"
        val builder = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-stats",
            "-i", processedVideo.absolutePath,
            "-vf", filter,
            "-frames:v", "1",
            outJpg.absolutePath
        )
        builder.redirectErrorStream(true)
        val p = builder.start()
        p.inputStream.bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                println("[$name] ${'$'}line")
            }
        }
        val exitCode = p.waitFor()
        if (exitCode != 0) println("[$name] contactsheet ffmpeg exited with $exitCode")
    }

    private fun formatedStartTime(): String? =
        timeStarted.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

    fun done() {
        if (!isInit) return
        try {
            bufferedWriter.flush()
            bufferedWriter.close()
        } catch (_: Exception) {}
        // 处理最后一个分片
        val duration = System.currentTimeMillis() - segmentStartMs
        val last = File(tmpfolder, "${name}-${formatedStartTime()}-part${segmentIndex}-${format(duration)}.${ext}")
        if (file.renameTo(last)) {
            postProcessExecutor.submit { processAndMove(last) }
        }
        postProcessExecutor.shutdown()
    }
}