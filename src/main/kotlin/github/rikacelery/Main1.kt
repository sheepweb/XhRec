package github.rikacelery

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

enum class TaskStatus {
    ACTIVE, PAUSED, COMPLETED, INACTIVE
}

data class DownloadTask(
    val name: String, var downloaded: Long = 0, var total: Long = 1000, var status: TaskStatus = TaskStatus.INACTIVE
)

object TaskManager {
    val tasks = ConcurrentHashMap<String, DownloadTask>()
    private val lock = Any()

    init {
        addTask("test")
    }

    fun addTask(name: String) {
        tasks[name] = DownloadTask(name)
    }

    fun removeTask(name: String) {
        tasks.remove(name)
    }

    fun pauseTask(name: String) {
        tasks[name]?.status = TaskStatus.PAUSED
    }

    fun resumeTask(name: String) {
        tasks[name]?.status = TaskStatus.ACTIVE
    }

    fun activateTask(name: String) {
        tasks[name]?.status = TaskStatus.ACTIVE
    }

    fun deactivateTask(name: String) {
        tasks[name]?.status = TaskStatus.INACTIVE
    }

    fun simulateDownloads() {
        tasks.values.forEach {
            if (it.status == TaskStatus.ACTIVE) {
                it.downloaded += (10..50).random()
                if (it.downloaded >= it.total) {
                    it.downloaded = it.total
                    it.status = TaskStatus.COMPLETED
                }
            }
        }
    }

    fun display(): String {
        val sb = StringBuilder()
//        sb.appendLine("Name       | Status     | Downloaded")
//        sb.appendLine("-------------------------------------")
        tasks.values.forEach {
            sb.append("[${it.name.padEnd(10)} | ${it.status.name.padEnd(10)} | ${it.downloaded}/${it.total}]")
        }
        return sb.toString()
    }
}
