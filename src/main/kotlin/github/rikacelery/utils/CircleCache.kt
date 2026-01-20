package github.rikacelery.utils

import java.util.*

class CircleCache(private val maxSize: Int) {
    private val cache = LinkedList<String>()
    fun add(item: String) {
        if (cache.contains(item)) return
        cache.add(item)
        if (cache.size > maxSize) cache.removeFirst()
    }
    fun contains(item: String): Boolean {
        return cache.contains(item)
    }
    fun clear() {
        cache.clear()
    }
}