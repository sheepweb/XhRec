package github.rikacelery.v3.m3u8

import github.rikacelery.v3.crypto.Decrypter
import github.rikacelery.v3.events.Segment

object M3u8Parser {
    private val mapRegex = Regex("#EXT-X-MAP:URI=\"([^\"]+)\"")
    private fun parseInitUrl(lines: List<String>): String {
        return try {
            lines.first { it.startsWith("#EXT-X-MAP") }.substringAfter("#EXT-X-MAP:URI=").removeSurrounding("\"")
        } catch (e: NoSuchElementException) {
            throw IllegalArgumentException("Missing #EXT-X-MAP tag in playlist", e)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse init URL", e)
        }
    }

    // TODO: handle #EXT-X-PART low-latency segments (v2 fallback: plain https://*.mp4 URLs)
    fun parse(m3u8Text: String, decryptKey: String): ParsedPlaylist {
        val rawList = m3u8Text.lines()
        val segments = mutableListOf<Segment>()

        val newList = mutableListOf<String>()
        val initUrl: String = parseInitUrl(rawList)
        for (idx in rawList.indices) {
            if (rawList[idx].startsWith("#EXT-X-MOUFLON:URI:")) {
                val mouflon = rawList[idx].substringAfterLast("#EXT-X-MOUFLON:URI:")
                val encrypted =
                    mouflon.replace("(_part\\d)?\\.mp4".toRegex(), "")
                        .substringBeforeLast("_")
                        .substringAfterLast("_")

                val decrypted = try {
                    val result = runCatching {
                        Decrypter.decode(
                            encrypted.reversed(),
                            decryptKey
                        )
                    }
                    result.getOrThrow()
                } catch (e: Exception) {
                    throw e
                }
                val dec = rawList[idx].substringAfterLast("#EXT-X-MOUFLON:URI:").replace(encrypted, decrypted)
                newList.add(dec)
            }
        }
        segments.addAll(newList.map {
            Segment(it, segmentIDFromUrl(it) ?: 0)
        })
        return ParsedPlaylist(initUrl, segments)
    }


    fun segmentIDFromUrl(url: String): Int? {
//        roomid_480p_h265_SEGMENTID_XXXXXXXXXXX_timestamp.mp4 transcended stream
//        roomid_SEGMENTID_XXXXXXXXXXX_timestamp.mp4 raw stream
        if (url.contains("h265_"))
            return url.substringAfterLast("h265_").substringBefore("_").toIntOrNull()
        else
            return url.substringAfterLast("h264_").substringBefore("_").toIntOrNull()

    }
}

data class ParsedPlaylist(
    val initUrl: String?,
    val segments: List<Segment>
)
