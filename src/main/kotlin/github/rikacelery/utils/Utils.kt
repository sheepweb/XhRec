package github.rikacelery.utils

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

private val logger = LoggerFactory.getLogger("github.rikacelery.utils.Utils")

suspend fun <R, T> R.withMeasureTime(block: suspend R.() -> T): T {
    val start = System.currentTimeMillis()
    val res = block()
    val delta = System.currentTimeMillis() - start
    if (delta > 3000) {
        logger.warn("time: {}ms", delta)
    }
    return res
}

@Suppress("unused")
fun bytesToHumanReadable(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var bytesLeft = bytes.toDouble()
    var unitIndex = 0
    while (bytesLeft > 1024 && unitIndex < units.size - 1) {
        bytesLeft /= 1024
        unitIndex++
    }
    return "${"%.3f".format(bytesLeft)}${units[unitIndex]}"
}

suspend fun <T> withRetry(i: Int, stopIf: (Throwable) -> Boolean = { false }, function: suspend (n:Int) -> T): T {
    var err: Throwable? = null
    (0 until i).forEach { _ ->
        runCatching {
            return function(i)
        }.onFailure {
            if (stopIf(it)) {
                throw it
            }
//            println("retry $j $it")
            err = it
            delay(1000)
        }
    }
    throw err!!
}
suspend fun <T> withRetryOrNull(i: Int, stopIf: (Throwable) -> Boolean = { false }, function: suspend () -> T): T? {
    (0 until i).forEach { _ ->
        runCatching {
            return function()
        }.onFailure {
            if (stopIf(it)) {
                return null
            }
            delay(1000)
        }
    }
    return null
}

fun Long.chunked(chunkSize: Int): List<LongRange> {
    val result = mutableListOf<LongRange>()
    var start = 0L
    while (start < this) {
        val end = (start + chunkSize).coerceAtMost(this)
        result.add(start until end)
        start = end
    }
    return result
}

fun Date.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(toInstant(), ZoneId.systemDefault())


@Suppress("unused")
fun runProcess( command: List<String>,onStdout:(line:String)-> Unit,onStderr: (line:String)-> Unit): Int {
    val process = ProcessBuilder(command).start()

    process.inputStream.bufferedReader().use {
        while (true) {
            val char = it.readLine() ?: break
            onStdout(char.replace("\r", ""))
        }
    }
    process.errorStream.bufferedReader().use {
        while (true) {
            val char = it.readLine() ?: break
            onStderr(char.replace("\r", ""))
        }
    }
    return process.waitFor()
}
fun runProcessGetStdout(vararg  command: String): String {
    val process = ProcessBuilder(command.asList()).start()

    val txt = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    if (code !=0){
        throw Exception("Process exit with code $code")
    }
    return txt.trim()
}