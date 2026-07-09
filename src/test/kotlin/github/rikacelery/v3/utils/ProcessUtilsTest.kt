package github.rikacelery.v3.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessUtilsTest {

    @Test
    fun `process output logging keeps initial lines and then samples`() {
        assertTrue(shouldLogProcessOutputLine(1))
        assertTrue(shouldLogProcessOutputLine(50))
        assertFalse(shouldLogProcessOutputLine(51))
        assertFalse(shouldLogProcessOutputLine(199))
        assertTrue(shouldLogProcessOutputLine(200))
        assertFalse(shouldLogProcessOutputLine(201))
        assertTrue(shouldLogProcessOutputLine(400))
    }
}
