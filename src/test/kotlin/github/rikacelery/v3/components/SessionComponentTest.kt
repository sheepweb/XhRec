package github.rikacelery.v3.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionComponentTest {
    @Test
    fun `circle cache evicts oldest entry without clearing all entries`() {
        val cache = CircleCache(3)

        assertTrue(cache.add("a"))
        assertTrue(cache.add("b"))
        assertTrue(cache.add("c"))

        assertTrue(cache.add("d"))

        assertFalse(cache.add("b"))
        assertFalse(cache.add("c"))
        assertFalse(cache.add("d"))
        assertTrue(cache.add("a"))
    }
}
