package github.rikacelery.v3.utils

import kotlinx.coroutines.delay

suspend fun <T> withRetry(i: Int, stopIf: (Throwable) -> Boolean = { false }, function: suspend (n:Int) -> T): T {
    var err: Throwable? = null
    (0 until i).forEach { j ->
        runCatching {
            return function(i)
        }.onFailure {
            if (stopIf(it)) {
                throw it
            }
            err = it
            delay(1000)
        }
    }
    throw err!!
}

suspend fun <T> withRetryOrNull(i: Int, stopIf: (Throwable) -> Boolean = { false }, function: suspend () -> T): T? {
    (0 until i).forEach { j ->
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
