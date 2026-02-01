package github.rikacelery.v2.metric

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

class MetricUpdater(
    var metrics: Hashtable<Long, MetricItem>?,
    val id: Long,
) {
    private val lock = Mutex()
    fun dispose() {
        metrics = null
    }

    suspend fun segmentMissing(segmentMissing: Int) = lock.withLock {
        metrics?.let {
            it[id] = it[id]!!.copy(segmentMissing = segmentMissing)
        }
    }

    suspend fun quality(q: String) = lock.withLock {
        metrics?.let {
            it[id] = it[id]!!.copy(quality = q)
        }
    }

    val windowSize = 10 // 滑动窗口大小
    val window = mutableListOf<Long>()
    suspend fun updateLatency(value: Long) = lock.withLock {
        window.add(value)
        // 如果超过窗口大小，移除最老的元素
        if (window.size > windowSize) {
            window.removeAt(0)
        }
        metrics?.let {
            it[id] = it[id]!!.copy(latencyMS = window.average())
        }
    }

    val windowRefreshSize = 10 // 滑动窗口大小
    val windowRefresh = mutableListOf<Long>()
    suspend fun updateRefreshLatency(value: Long) = lock.withLock {
        windowRefresh.add(value)
        // 如果超过窗口大小，移除最老的元素
        if (windowRefresh.size > windowRefreshSize) {
            windowRefresh.removeAt(0)
        }
        metrics?.let {
            it[id] = it[id]!!.copy(refreshLatencyMS = windowRefresh.average())
        }
    }

    suspend fun totalIncrement() = lock.withLock {
        metrics?.let {
            it[id] = it[id]!!.copy(total = it[id]!!.total + 1)
        }
    }

    suspend fun doneIncrement() = lock.withLock {
        metrics?.let {
            it[id] = it[id]!!.copy(done = it[id]!!.done + 1)
        }
    }

    suspend fun downloadingIncrement() = lock.withLock {
        metrics?.let {
            it[id] = it[id]!!.copy(downloading = it[id]!!.downloading + 1)
        }
    }

    suspend fun downloadingDecrement() = lock.withLock {
        metrics?.let {
            it[id] = it[id]!!.copy(downloading = it[id]!!.downloading - 1)
        }
    }

    suspend fun segmentID(segmentID: Int) = lock.withLock {
        metrics?.let {
            it[id] = it[id]!!.copy(segmentID = segmentID)
        }
    }


    suspend fun successProxiedIncrement() = lock.withLock {
        metrics?.let {
            it[id] = it[id]!!.copy(successProxied = it[id]!!.successProxied + 1)
        }
    }

    suspend fun successDirectIncrement() = lock.withLock {
        metrics?.let {
            it[id] = it[id]!!.copy(successDirect = it[id]!!.successDirect + 1)
        }
    }

    //failed increment
    suspend fun failedIncrement() = lock.withLock {
        metrics?.let {
            it[id] = it[id]!!.copy(failed = it[id]!!.failed + 1)
        }
    }

    suspend fun bytesWriteIncrement(new: Long) = lock.withLock {
        metrics?.let {
            it[id] = it[id]!!.copy(bytesWrite = it[id]!!.bytesWrite + new)
        }
    }

    fun reset() {
        metrics?.let {
            it[id] = MetricItem()
        }
        window.clear()
        windowRefresh.clear()
    }


}