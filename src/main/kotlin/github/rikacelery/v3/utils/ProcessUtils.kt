package github.rikacelery.v3.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PROCESS_LOG_INITIAL_LINES = 50
private const val PROCESS_LOG_INTERVAL = 200

fun shouldLogProcessOutputLine(lineNumber: Int): Boolean {
    return lineNumber <= PROCESS_LOG_INITIAL_LINES || lineNumber % PROCESS_LOG_INTERVAL == 0
}

fun processOutputSuppressedMessage(streamName: String): String {
    return "[$streamName] suppressing noisy process output after $PROCESS_LOG_INITIAL_LINES lines; logging every ${PROCESS_LOG_INTERVAL}th line"
}

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

    val stderrJob = launch {
        process.errorStream.bufferedReader().use { reader ->
            var lineNumber = 0
            var suppressionLogged = false
            var line = reader.readLine()
            while (line != null) {
                lineNumber += 1
                if (shouldLogProcessOutputLine(lineNumber)) {
                    onStderr(line)
                } else if (!suppressionLogged) {
                    onStderr(processOutputSuppressedMessage("stderr"))
                    suppressionLogged = true
                }
                line = reader.readLine()
            }
        }
    }

    val stdoutJob = async {
        process.inputStream.bufferedReader().use { reader ->
            var lastLine = ""
            var line = reader.readLine()
            while (line != null) {
                lastLine = line
                line = reader.readLine()
            }
            lastLine
        }
    }
    val code = process.waitFor()
    stderrJob.join()
    val stdout = stdoutJob.await()
    if (code != 0) throw Exception("Process exit with code $code")
    stdout.trim()
}
