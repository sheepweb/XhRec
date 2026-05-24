package github.rikacelery.v3.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun runProcessGetStdout(vararg command: String): String {
    return withContext(Dispatchers.IO) {
        runProcessGetStdoutBlocking(*command)
    }
}

fun runProcessGetStdoutBlocking(vararg command: String): String {
    val process = ProcessBuilder(command.asList()).start()
    val txt = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    if (code != 0) throw Exception("Process exit with code $code")
    return txt.trim()
}
