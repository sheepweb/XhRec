package github.rikacelery.v3.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiClientTest {
    @Test
    fun `deleted broadcast reasons include missing model response`() {
        assertTrue(isDeletedBroadcastReason("model already deleted"))
        assertTrue(isDeletedBroadcastReason("Entity \"Model\" not found"))
    }

    @Test
    fun `rename response is not treated as deleted`() {
        assertFalse(isDeletedBroadcastReason("Model has new name: newName=alice"))
    }
}
