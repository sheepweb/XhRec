package github.rikacelery.v2.postprocessors

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
        ).jsonObject.get("default")?.jsonArray ?: JsonArray(listOf())
        println("Post processors loads: ${config.map { it.jsonObject["type"]!!.jsonPrimitive.content }}")
    }


    private fun buildProcessors(context: ProcessorCtx): MutableList<Processor> {
        val processors = mutableListOf<Processor>()
        for (processor in config) {
            val type = processor.jsonObject["type"]!!.jsonPrimitive.content
            when (type) {
                "fix_stamp" -> {
                    processors.add(FixStampProcessor(context, processor.jsonObject["output"]!!.jsonPrimitive.content))
                }

                "shell" -> {
                    val args = processor.jsonObject["args"]!!.jsonArray
                    val script = args.map { it.jsonPrimitive.content }
                    processors.add(
                        ShellProcessor(
                            context,
                            script,
                            processor.jsonObject["noreturn"]?.jsonPrimitive?.booleanOrNull == true,
                            processor.jsonObject["remove_input"]?.jsonPrimitive?.booleanOrNull == true,
                        )
                    )
                }

                "slice" -> {
                    val duration = Duration.Companion.parse(processor.jsonObject["duration"]!!.jsonPrimitive.content)
                    processors.add(SliceProcessor(context, duration))
                }

                "move" -> {
                    processors.add(
                        MoveProcessor(
                            context,
                            processor.jsonObject["dest"]?.jsonPrimitive!!.content,
                            processor.jsonObject["date_pattern"]?.jsonPrimitive!!.content,
                        )
                    )
                }
            }
        }
        println("Post processors build(${context.room}): ${processors.map { it }}")
        return processors
    }

    suspend fun process(input: File, context: ProcessorCtx): List<File> = concurrency.withPermit {
        val processors = buildProcessors(context)
        var files = listOf(input)
        for (processor in processors) {

            val tmp = files.flatMapIndexed { idx, file ->
                println("[${context.room.name}] $idx/${files.size} ${processor.javaClass.simpleName} $file")
                val files = try {
                    processor.process(file)
                } catch (e: Exception) {
                    throw e
                }
                println("[${context.room.name}] $idx/${files.size} ${processor.javaClass.simpleName} -> $files")
                files
            }
            files = tmp
        }
        return files
    }
}