package github.rikacelery.v3.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

suspend fun runProcessStreaming(
    onStderr: (String) -> Unit,
    vararg command: String
): String = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(command.asList()).start()

    launch {
        process.errorStream.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                onStderr(line)
                line = reader.readLine()
            }
        }
    }

    val stdout = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    if (code != 0) throw Exception("Process exit with code $code")
    stdout.trim()
}
