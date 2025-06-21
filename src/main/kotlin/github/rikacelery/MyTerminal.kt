package github.rikacelery

import okio.withLock
import org.jline.jansi.Ansi
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.TerminalBuilder
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

class MyTerminal {
    val lock = ReentrantLock()
    val terminal = runCatching{ TerminalBuilder.builder().dumb(true).build() }.getOrNull()
    val reader = LineReaderBuilder.builder().terminal(terminal).build()
    var statusLines = AtomicInteger(3)
    val twidth get() = if (terminal?.width == 0) 40 else terminal?.width?:40
    private var lastStatus = AtomicReference("")
    fun status(logs: SortedMap<Long, LogInfo>) {
        if(terminal==null){
            return
        }
        val msg = Ansi.ansi()
            .saveCursorPosition()
            .cursorUpLine()
            .eraseLine()
            .cursorUpLine()
            .eraseLine()
            .cursorUpLine()
            .eraseLine()
            .apply {
                logs.toSortedMap().forEach { (t, u) ->
                    it.a("[${u.name} ${u.size} ")
                    if (u.progress.first != u.progress.third)
                        it.fgBrightYellow()
                    else
                        it.fgGreen()
                    it.a("${u.progress.first}/${u.progress.third}(${u.progress.second})")
                    it.reset()
                    it.a(" ")
                    if (u.latency > 4000)
                        it.fgBrightRed()
                    else if (u.latency > 2000)
                        it.fgYellow()
                    else
                        it.fgGreen()
                    it.a("%.1f".format(u.latency.div(1000.0)))
                    it.reset()
                    it.a(" s], ")
                }
            }
            .restoreCursorPosition()
            .reset()
            .toString()
        lastStatus.set(msg)
        lock.lock()
        terminal.writer().print(
            msg
        )
        terminal.writer().flush()
        lock.unlock()
    }

    fun println(vararg any: Any) {
        lock.withLock {
            val msg = any.joinToString(" ")
            if (terminal==null){
                kotlin.io.println(msg)
                return@withLock
            }
            val msgLine = msg.lines().sumOf { l ->
                (l.length + terminal.width.minus(1)) / twidth
            }
            reader.printAbove(
                Ansi.ansi()
                    .saveCursorPosition()
                    .cursorUpLine(statusLines.get())
                    .eraseLine(Ansi.Erase.FORWARD)
                    .a(msg)
                    .restoreCursorPosition()
                    .apply {
                        for (i in 0 until msgLine) {
                            it.newline()
                        }
                    }
                    .toString()
            )

            terminal.writer().print(lastStatus)
            terminal.writer().flush()
        }
    }

    fun readLine(): String {
        if (terminal==null){
            return ""
        }
        val d = reader.readLine("> ").trim()
        lock.lock()
        terminal.writer().print(
            Ansi.ansi().cursorUpLine().eraseLine().saveCursorPosition().cursorUpLine().eraseLine().cursorUpLine()
                .eraseLine().cursorUpLine().eraseLine().restoreCursorPosition()
        )
        terminal.writer().flush()
        lock.unlock()
        return d
    }
}