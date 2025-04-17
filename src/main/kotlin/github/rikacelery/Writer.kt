package github.rikacelery

import github.rikacelery.utils.toLocalDateTime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.format.DateTimeFormatter
import java.util.*

class Writer(val name: String) {
    private lateinit var file: File
    private lateinit var bufferedWriter: BufferedOutputStream
    private lateinit var timeStarted: Date
    private var inited = false
    private var n = 0
    val ext = "mp4"
    val folder = "./"

    //    val folder = "/Volumes/mnt/12t/rec/"
    val destFolder = "/mnt/download/_Crawler/Video/R18/rec/raw"

    fun init() {
        timeStarted = Date()
        file = File(folder, "rec_${name}-${formatedStartTime()}_init.${ext}")
        bufferedWriter = file.outputStream().buffered(bufferSize = 1024 * 1024 * 8)
        inited = true
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
        rename()
    }

    private fun rename() {
//        val duration = Date().time - timeStarted.time
//        val file1 = File(folder, "rec_${name}-${formatedStartTime()}-${format(duration)}.${ext}")
//        file.renameTo(file1)
//        file = file1
    }

    private fun formatedStartTime(): String? =
        timeStarted.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))

    fun done() {
        if (!inited) return
        bufferedWriter.close()
        val duration = Date().time - timeStarted.time
        val input = File(folder, "rec_${name}-${formatedStartTime()}-${format(duration)}.$ext")
        if (file.renameTo(input)) {
            val output = File(folder, "rec_${name}-${formatedStartTime()}-${format(duration)}.fixed.$ext")
            val builder = ProcessBuilder(
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "warning",
                "-stats",
                "-i",
                input.absolutePath,
                "-c",
                "copy",
                output.absolutePath
            )
            builder.redirectErrorStream(true)
            val p = builder.start()
            p.inputStream.bufferedReader().use {
                while (true) {
                    val char = it.readLine() ?: break
                    mt.println("[$name] ${char.replace("\r", "")}")
                }
            }
            if (p.waitFor() == 0) {
                input.delete()
                mt.println("[$name] moving...")
                try {
                    Files.move(
                        output.toPath(),
                        File(destFolder).resolve(output.name).toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    mt.println("[$name] 移动成功")
                } catch (e: Exception) {
                    mt.println("[$name] 无法移动文件$e")
                }
            } else {
                mt.println("[$name] 转码失败，请查看命令输出")
            }
        }
    }

}