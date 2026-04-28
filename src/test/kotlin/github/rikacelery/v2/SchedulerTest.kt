package github.rikacelery.v2

import github.rikacelery.Room
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class SchedulerTest {
    @Test
    fun `scheduler can restart after stop`() = runBlocking {
        val scheduler = Scheduler(dest = "out", tmp = "tmp")

        val firstJob = scheduler.start(wait = false)
        assertTrue(firstJob?.isActive == true)

        scheduler.stop()

        val restartedJob = scheduler.start(wait = false)

        assertNotSame(firstJob, restartedJob)
        assertTrue(restartedJob?.isActive == true)

        scheduler.stop()
    }

    @Test
    fun `inactive room refresh failure does not kill scheduler`() = runBlocking {
        val scheduler = Scheduler(
            dest = "out",
            tmp = "tmp",
            inactiveSessionRefresh = { throw IllegalStateException("boom") },
        )
        scheduler.add(Room(name = "demo", id = 1L, quality = "720p"), listen = false)

        val job = scheduler.start(wait = false)
        delay(100)

        assertTrue(job?.isActive == true)

        scheduler.stop()
    }
}
