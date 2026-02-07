package github.rikacelery.v2.postprocessors

import github.rikacelery.utils.BooleanOrElse
import github.rikacelery.utils.JsonArray
import github.rikacelery.utils.Long
import github.rikacelery.utils.String
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.io.File
import kotlin.time.Duration

object PostProcessor {
    val concurrency = Semaphore(4)
    lateinit var config: JsonArray

    fun loadConfig(file: File) {
        config = Json.Default.decodeFromString(
            JsonObject.Companion.serializer(),
            file.readText()
        ).jsonObject["default"]?.jsonArray ?: JsonArray(listOf())
        println("Post processors loads: ${config.map { it.String("type") }}")
    }


    private fun buildProcessors(context: ProcessorCtx): MutableList<Processor> {
        val processors = mutableListOf<Processor>()
        for (processor in config) {
            val type = processor.String("type")
            when (type) {
                "fix_stamp" -> {
                    processors.add(FixStampProcessor(context, processor.String("output")))
                }

                "shell" -> {
                    val args = processor.JsonArray("args")
                    val script = args.map { it.jsonPrimitive.content }
                    processors.add(
                        ShellProcessor(
                            context,
                            script,
                            processor.BooleanOrElse("noreturn",false),
                            processor.BooleanOrElse("remove_input",false),
                        )
                    )
                }

                "slice" -> {
                    val duration = Duration.Companion.parse(processor.String("duration"))
                    processors.add(SliceProcessor(context, duration))
                }

                "move" -> {
                    processors.add(
                        MoveProcessor(
                            context,
                            processor.String("dest"),
                            processor.String("date_pattern"),
                        )
                    )
                }

                "cleanup" -> {
                    processors.add(
                        CleanupProcessor(
                            context,
                            processor.Long("min_duration_seconds"),
                            processor.Long("min_size_mb"),
                        )
                    )
                }
            }
        }
        println("Post processors build(${context.room}): ${processors.map { it }}")
        return processors
    }

    suspend fun process(input: File, context: ProcessorCtx): List<File> = concurrency.withPermit {
        // 检查输入文件是否为空
        if (!input.exists() || input.length() == 0L) {
            println("[${context.room.name}] Skipping empty or non-existent file: $input")
            if (input.exists()) input.delete()
            return@withPermit emptyList()
        }

        val processors = buildProcessors(context)
        var files = listOf(input)
        for (processor in processors) {

            val tmp = files.flatMapIndexed { idx, file ->
                println("[${context.room.name}] $idx/${files.size} ${processor.javaClass.simpleName} $file")
                val outs = try {
                    processor.process(file)
                } catch (e: Exception) {
                    throw e
                }
                println("[${context.room.name}] $idx/${outs.size} ${processor.javaClass.simpleName} -> $outs")
                outs
            }
            files = tmp
        }
        return files
    }
}