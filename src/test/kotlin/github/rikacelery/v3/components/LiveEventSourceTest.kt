package github.rikacelery.v3.components

import java.io.IOException
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveEventSourceTest {
    @Test
    fun `internal error tls alert is transient websocket failure`() {
        assertTrue(
            isTransientWebSocketFailure(
                SSLException("(internal_error) Received fatal alert: internal_error")
            )
        )
    }

    @Test
    fun `wrapped internal error tls alert is transient websocket failure`() {
        assertTrue(
            isTransientWebSocketFailure(
                IOException("websocket failed", SSLException("Received fatal alert: internal_error"))
            )
        )
    }

    @Test
    fun `unrelated websocket failure remains non transient`() {
        assertFalse(isTransientWebSocketFailure(IllegalStateException("bad frame")))
    }
}
