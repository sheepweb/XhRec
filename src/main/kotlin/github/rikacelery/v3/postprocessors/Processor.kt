package github.rikacelery.v3.postprocessors

import org.slf4j.LoggerFactory
import java.io.File

abstract class Processor {
    protected val logger = LoggerFactory.getLogger(this::class.java)
    abstract suspend fun process(input: File, ctx: ProcessorCtx): List<File>
}
