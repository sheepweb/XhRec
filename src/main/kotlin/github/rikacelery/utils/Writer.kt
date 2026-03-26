package github.rikacelery.utils

import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.*

class Writer(private val name: String, private val destFolder: String, private val tmpfolder: String) {
    private val logger = LoggerFactory.getLogger(Writer::class.java)
    private val perf = PerfStats("writer", name, logger)
    private lateinit var file: File
    private lateinit var bufferedWriter: BufferedOutputStream
    private lateinit var timeStarted: Date
    private var isInit = false
    private var total = 0L
    private val ext = "mp4"


    fun init() {
        val start = System.currentTimeMillis()
        timeStarted = Date()
        file = File(tmpfolder, "${name}-${formatedStartTime()}-init.${ext}")
        bufferedWriter = file.outputStream().buffered()
        total = 0L
        isInit = true
        if (File(tmpfolder).exists().not()) {
            File(tmpfolder).mkdirs()
        }
        if (File(destFolder).exists().not()) {
            File(destFolder).mkdirs()
        }
        perf.inc("writerInitCount")
        perf.observe("writerInitLatency", System.currentTimeMillis() - start)
        perf.maybeLog(mapOf("currentFile" to file.name, "isInit" to isInit, "totalBytes" to total))
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

    fun append(data: ByteArray): Long {
        if (!isInit) throw Exception("Writer not initialized yet.")
        val start = System.currentTimeMillis()
        bufferedWriter.write(data)
        total += data.size
        perf.inc("writerAppendCount")
        perf.inc("writerAppendBytes", data.size.toLong())
        perf.observe("writerAppendLatency", System.currentTimeMillis() - start)
        perf.maybeLog(mapOf("currentFile" to file.name, "isInit" to isInit, "totalBytes" to total))
        return total
    }

    fun appendEvent(data: JsonObject) {
        if (!isInit) throw Exception("Writer not initialized yet.")
        val start = System.currentTimeMillis()
        file.parentFile.resolve(file.nameWithoutExtension + ".event").appendText(data.toString() + "\n")
        perf.inc("writerAppendEventCount")
        perf.observe("writerAppendEventLatency", System.currentTimeMillis() - start)
        perf.maybeLog(mapOf("currentFile" to file.name, "isInit" to isInit, "totalBytes" to total))
    }

    private fun formatedStartTime(): String =
        timeStarted.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss"))

    fun done(): Triple<File, Date, Long>? {
        if (!isInit) return null
        val start = System.currentTimeMillis()
        isInit = false
        bufferedWriter.close()
        val duration = Date().time - timeStarted.time
        val formatted = File(tmpfolder, "${name}-${formatedStartTime()}-${format(duration)}.$ext")
        if (!file.renameTo(formatted)) {
            throw Exception("Failed to rename $formatted")
        }
        file.parentFile.resolve(file.nameWithoutExtension + ".event").renameTo(File(tmpfolder, "${name}-${formatedStartTime()}-${format(duration)}.event"))
        perf.inc("writerDoneCount")
        perf.inc("writerBytesWritten", total)
        perf.observe("writerDoneLatency", System.currentTimeMillis() - start)
        perf.observe("writerOutputDuration", duration)
        perf.maybeLog(mapOf("currentFile" to formatted.name, "isInit" to isInit, "totalBytes" to total, "durationMs" to duration))
        return Triple(formatted, timeStarted, duration)
    }

    fun dispose() {
        if (!isInit) return
        val start = System.currentTimeMillis()
        isInit = false
        bufferedWriter.close()
        file.delete()
        file.parentFile.resolve(file.nameWithoutExtension + ".event").delete()
        perf.inc("writerDisposeCount")
        perf.observe("writerDisposeLatency", System.currentTimeMillis() - start)
        perf.maybeLog(mapOf("currentFile" to file.name, "isInit" to isInit, "totalBytes" to total))
    }
}