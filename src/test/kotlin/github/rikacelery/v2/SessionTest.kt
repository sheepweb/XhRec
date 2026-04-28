package github.rikacelery.v2

import github.rikacelery.Room
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTest {
    private fun newSession(): Session = Session(
        room = Room(name = "demo", id = 1L, quality = "720p"),
        dest = "out",
        tmp = "tmp",
    )

    @Test
    fun `404 keeps current session alive when room is still recordable`() {
        val session = newSession()

        val action = session.playlistFailureAction(
            statusCode = 404,
            roomStillRecordable = true,
            currentQuality = "720p",
        )

        assertEquals(Session.PlaylistFailureAction.RETRY_CURRENT_SESSION, action)
    }

    @Test
    fun `403 keeps current session alive when room is still recordable and quality is not raw`() {
        val session = newSession()

        val action = session.playlistFailureAction(
            statusCode = 403,
            roomStillRecordable = true,
            currentQuality = "720p",
        )

        assertEquals(Session.PlaylistFailureAction.RETRY_CURRENT_SESSION, action)
    }

    @Test
    fun `403 stops current session when raw quality needs fallback`() {
        val session = newSession()

        val action = session.playlistFailureAction(
            statusCode = 403,
            roomStillRecordable = true,
            currentQuality = "raw",
        )

        assertEquals(Session.PlaylistFailureAction.FALLBACK_QUALITY_AND_STOP, action)
    }

    @Test
    fun `playlist failure stops current session when room is no longer recordable`() {
        val session = newSession()

        val action = session.playlistFailureAction(
            statusCode = 404,
            roomStillRecordable = false,
            currentQuality = "720p",
        )

        assertEquals(Session.PlaylistFailureAction.STOP_CURRENT_SESSION, action)
    }
}
