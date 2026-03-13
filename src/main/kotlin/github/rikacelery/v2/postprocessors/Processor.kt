package github.rikacelery.v2.postprocessors

import org.slf4j.LoggerFactory
import java.io.File

abstract class Processor(var context: ProcessorCtx) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    abstract fun process(input: File): List<File>
    fun info(msg: String) {
        logger.info("[${context.room.name}] $msg")
    }

    fun debug(msg: String) {
        logger.debug("[${context.room.name}] $msg")
    }

    fun warn(msg: String) {
        logger.warn("[${context.room.name}] $msg")
    }

    fun warn(msg: String, throwable: Throwable) {
        logger.warn("[${context.room.name}] $msg", throwable)
    }

    fun error(msg: String) {
        logger.error("[${context.room.name}] $msg")
    }

    fun error(msg: String, throwable: Throwable) {
        logger.error("[${context.room.name}] $msg", throwable)
    }
}