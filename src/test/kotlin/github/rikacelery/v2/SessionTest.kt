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

    @Test
    fun `504 keeps current session alive when room is still recordable`() {
        val session = newSession()

        val action = session.playlistFailureAction(
            statusCode = 504,
            roomStillRecordable = true,
            currentQuality = "720p",
        )

        assertEquals(Session.PlaylistFailureAction.RETRY_CURRENT_SESSION, action)
    }

    @Test
    fun `low quality presets fall back to raw`() {
        val session = newSession()

        val selected = session.selectPlaybackQuality(
            requestedQuality = "720p",
            availableQualities = listOf("480p", "240p", "160p"),
        )

        assertEquals("raw", selected)
    }

    @Test
    fun `requested quality is preserved when presets include matching quality family`() {
        val session = newSession()

        val selected = session.selectPlaybackQuality(
            requestedQuality = "720p",
            availableQualities = listOf("1080p", "720p", "480p"),
        )

        assertEquals("720p", selected)
    }

    @Test
    fun `master playlist selects requested named variant before source fallback`() {
        val session = newSession()

        val selected = session.selectVariantFromMasterPlaylist(
            requestedQuality = "720p",
            lines = listOf(
                "#EXTM3U",
                "#EXT-X-STREAM-INF:BANDWIDTH=3009740,NAME=\"source\"",
                "https://media-hls.doppiocdn.org/b-hls-18/1/1.m3u8?playlistType=lowLatency",
                "#EXT-X-STREAM-INF:BANDWIDTH=1000000,NAME=\"480p\"",
                "https://media-hls.doppiocdn.org/b-hls-18/1/1_240p.m3u8",
                "#EXT-X-STREAM-INF:BANDWIDTH=2500000,NAME=\"720p\"",
                "https://media-hls.doppiocdn.org/b-hls-18/1/1_720p.m3u8",
            ),
        )

        assertEquals("https://media-hls.doppiocdn.org/b-hls-18/1/1_720p.m3u8", selected)
    }

    @Test
    fun `master playlist falls back to source when requested quality is unavailable`() {
        val session = newSession()

        val selected = session.selectVariantFromMasterPlaylist(
            requestedQuality = "720p",
            lines = listOf(
                "#EXTM3U",
                "#EXT-X-STREAM-INF:BANDWIDTH=3009740,NAME=\"source\"",
                "https://media-hls.doppiocdn.org/b-hls-18/1/1.m3u8?playlistType=lowLatency",
                "#EXT-X-STREAM-INF:BANDWIDTH=1000000,NAME=\"480p\"",
                "https://media-hls.doppiocdn.org/b-hls-18/1/1_240p.m3u8",
            ),
        )

        assertEquals("https://media-hls.doppiocdn.org/b-hls-18/1/1.m3u8?playlistType=lowLatency", selected)
    }

    @Test
    fun `master playlist selects source for raw requests`() {
        val session = newSession()

        val selected = session.selectVariantFromMasterPlaylist(
            requestedQuality = "raw",
            lines = listOf(
                "#EXTM3U",
                "#EXT-X-STREAM-INF:BANDWIDTH=3009740,NAME=\"source\"",
                "https://media-hls.doppiocdn.org/b-hls-18/1/1.m3u8?playlistType=lowLatency",
                "#EXT-X-STREAM-INF:BANDWIDTH=1000000,NAME=\"480p\"",
                "https://media-hls.doppiocdn.org/b-hls-18/1/1_480p.m3u8",
            ),
        )

        assertEquals("https://media-hls.doppiocdn.org/b-hls-18/1/1.m3u8?playlistType=lowLatency", selected)
    }
}
