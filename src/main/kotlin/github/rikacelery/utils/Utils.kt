package github.rikacelery.utils

import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

suspend fun <R, T> R.withMeasureTime(block: suspend R.() -> T): T {
    val start = System.currentTimeMillis()
    val res = block()
    val delta = System.currentTimeMillis() - start
    if (delta > 3000) {
//        println("time: ${delta}ms")
    }
    return res
}

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

suspend fun <T> withRetry(i: Int, stopIf: (Throwable) -> Boolean = { false }, function: suspend () -> T): T {
    var err: Throwable? = null
    for (j in 0 until i) {
        runCatching {
            return function()
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
    for (j in 0 until i) {
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

public fun Date.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(toInstant(), ZoneId.systemDefault())