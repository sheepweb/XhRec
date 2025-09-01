package github.rikacelery.v2

import github.rikacelery.utils.toLocalDateTime
import java.io.BufferedOutputStream
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.*

class Writer(private val name: String, private val destFolder:String, private val tmpfolder:String) {
    private lateinit var file: File
    private lateinit var bufferedWriter: BufferedOutputStream
    private lateinit var timeStarted: Date
    private var isInit = false
    private val ext = "mp4"


    fun init() {
        timeStarted = Date()
        file = File(tmpfolder, "${name}-${formatedStartTime()}-init.${ext}")
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
    }

    private fun formatedStartTime(): String =
        timeStarted.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss"))

    fun done(): Triple<File, Date, Long>? {
        if (!isInit) return null
        bufferedWriter.close()
        val duration = Date().time - timeStarted.time
        val formatted = File(tmpfolder, "${name}-${formatedStartTime()}-${format(duration)}.$ext")
        if (!file.renameTo(formatted)) {
            throw Exception("Failed to rename $formatted")
        }
        return Triple(formatted,timeStarted,duration)
    }
}