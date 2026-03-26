package github.rikacelery.utils

import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class PerfStats(
    private val category: String,
    private val subject: String,
    private val logger: Logger,
    private val intervalMs: Long = 60_000L
) {
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val latencyCounts = ConcurrentHashMap<String, AtomicLong>()
    private val latencyTotals = ConcurrentHashMap<String, AtomicLong>()
    private val latencyMax = ConcurrentHashMap<String, AtomicLong>()
    private val lastLogAt = AtomicLong(System.currentTimeMillis())

    fun inc(name: String, delta: Long = 1L) {
        if (delta == 0L) return
        counters.computeIfAbsent(name) { AtomicLong(0L) }.addAndGet(delta)
    }

    fun observe(name: String, elapsedMs: Long) {
        if (elapsedMs < 0) return
        latencyCounts.computeIfAbsent(name) { AtomicLong(0L) }.incrementAndGet()
        latencyTotals.computeIfAbsent(name) { AtomicLong(0L) }.addAndGet(elapsedMs)
        val maxRef = latencyMax.computeIfAbsent(name) { AtomicLong(0L) }
        while (true) {
            val current = maxRef.get()
            if (elapsedMs <= current) break
            if (maxRef.compareAndSet(current, elapsedMs)) break
        }
    }

    fun maybeLog(extraFields: Map<String, Any?> = emptyMap()) {
        val now = System.currentTimeMillis()
        val last = lastLogAt.get()
        if (now - last < intervalMs) return
        if (!lastLogAt.compareAndSet(last, now)) return

        val counterSummary = counters.entries
            .sortedBy { it.key }
            .joinToString(" ") { (key, value) -> "$key=${value.getAndSet(0L)}" }

        val latencySummary = latencyCounts.keys
            .sorted()
            .joinToString(" ") { key ->
                val count = latencyCounts[key]?.getAndSet(0L) ?: 0L
                val total = latencyTotals[key]?.getAndSet(0L) ?: 0L
                val max = latencyMax[key]?.getAndSet(0L) ?: 0L
                val avg = if (count == 0L) 0L else total / count
                "$key.avg=${avg}ms $key.max=${max}ms $key.count=$count"
            }

        val extraSummary = extraFields.entries
            .joinToString(" ") { (key, value) -> "$key=$value" }

        val parts = listOf(counterSummary, latencySummary, extraSummary)
            .filter { it.isNotBlank() }
            .joinToString(" | ")

        logger.info("[perf][{}][{}] {}", category, subject, if (parts.isBlank()) "no-data" else parts)
    }
}

