package github.rikacelery.v2.postprocessors

import java.io.File

abstract class Processor(var context: ProcessorCtx) {

    abstract fun process(input: File): List<File>
    fun log(msg: String) {
        println("[${context.room.name}] $msg")
    }
}