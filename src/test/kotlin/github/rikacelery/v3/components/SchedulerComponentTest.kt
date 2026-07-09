package github.rikacelery.v3.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class SchedulerComponentTest {
    @Test
    fun `zero segment rearm delay backs off to five minutes`() {
        var emptyStopCount = 0
        val delays = (1..6).map {
            val next = nextRearmDelay(0, emptyStopCount)
            emptyStopCount = next.emptyStopCount
            next.delay
        }

        assertEquals(listOf(30, 60, 120, 240, 300, 300).map { it.seconds }, delays)
        assertEquals(5, emptyStopCount)
    }

    @Test
    fun `non-empty recording resets empty stop count`() {
        val next = nextRearmDelay(12, 4)

        assertEquals(30.seconds, next.delay)
        assertEquals(0, next.emptyStopCount)
    }
}
