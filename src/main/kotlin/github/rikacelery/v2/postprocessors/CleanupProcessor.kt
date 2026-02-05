package github.rikacelery.v2.postprocessors

import github.rikacelery.utils.runProcessGetStdout
import java.io.File

/**
 * 清理小片段的后处理器
 * 如果文件时长小于 minDurationSeconds 或 文件大小小于 minSizeMB，则删除文件
 * 两个条件是 OR 关系，满足其一就删除
 */
class CleanupProcessor(
    context: ProcessorCtx,
    private val minDurationSeconds: Long,
    private val minSizeMB: Long
) : Processor(context) {

    override fun process(input: File): List<File> {
        // 检查文件大小 (仅当 minSizeMB > 0 时检查)
        val fileSizeMB = input.length() / (1024 * 1024)
        if (minSizeMB > 0 && fileSizeMB < minSizeMB) {
            log("文件大小 ${fileSizeMB}MB < ${minSizeMB}MB，删除文件: ${input.name}")
            input.delete()
            return emptyList()
        }

        // 检查文件时长 (仅当 minDurationSeconds > 0 时检查)
        if (minDurationSeconds > 0) {
            val duration = try {
                runProcessGetStdout(
                    "ffprobe",
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    input.absolutePath
                ).trim().toDoubleOrNull() ?: 0.0
            } catch (e: Exception) {
                log("无法获取文件时长: ${e.message}")
                0.0
            }

            if (duration < minDurationSeconds) {
                log("文件时长 ${duration.toLong()}s < ${minDurationSeconds}s，删除文件: ${input.name}")
                input.delete()
                return emptyList()
            }
        }

        log("文件通过清理检查: ${input.name} (${fileSizeMB}MB)")
        return listOf(input)
    }
}

