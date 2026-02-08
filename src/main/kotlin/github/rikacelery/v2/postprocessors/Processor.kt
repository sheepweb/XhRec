package github.rikacelery.v2.postprocessors

import org.slf4j.LoggerFactory
import java.io.File

abstract class Processor(var context: ProcessorCtx) {
    protected val logger = LoggerFactory.getLogger(this::class.java)

    abstract fun process(input: File): List<File>
    fun log(msg: String) {
        logger.info("[{}] {}", context.room.name, msg)
    }
}