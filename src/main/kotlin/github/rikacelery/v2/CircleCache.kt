package github.rikacelery.v2

class CircleCache(private val maxSize: Int) {
    private val cache = mutableListOf<String>()
    fun add(item: String) {
        if (cache.contains(item)) return
        cache.add(item)
        if (cache.size > maxSize) cache.removeFirst()
    }
    fun contains(item: String): Boolean {
        return cache.contains(item)
    }
}