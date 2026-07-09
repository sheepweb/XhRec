package github.rikacelery.v3.m3u8

import github.rikacelery.v3.crypto.Decrypter
import github.rikacelery.v3.events.Segment
import org.slf4j.LoggerFactory

object M3u8Parser {
    private val logger = LoggerFactory.getLogger("v3.M3u8Parser")
    private val mapRegex = Regex("#EXT-X-MAP:URI=\"([^\"]+)\"")
    private fun parseInitUrl(lines: List<String>): String {
        return try {
            lines.first { it.startsWith("#EXT-X-MAP") }.substringAfter("#EXT-X-MAP:URI=").removeSurrounding("\"")
        } catch (e: NoSuchElementException) {
            logger.error("Missing #EXT-X-MAP tag in playlist", e)
            throw IllegalArgumentException("Missing #EXT-X-MAP tag in playlist", e)
        } catch (e: Exception) {
            logger.error("Failed to parse init URL", e)
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
                    logger.error("Decrypter.decode failed for mouflon URI", e)
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

    private val segmentIDRegex = Regex("_(\\d+)_[a-zA-Z0-9]{16}_\\d{10}")
    fun segmentIDFromUrl(url: String): Int? {
//        roomid_480p_h265_SEGMENTID_XXXXXXXXXXXXXXXX_timestamp.mp4 transcended stream
//        roomid_240p_h264_SEGMENTID_iRAiezcS7w4MfUvZ_1779960801.mp4
//        roomid_SEGMENTID_XXXXXXXXXXXXXXXX_timestamp.mp4 raw stream
//        roomid_SEGMENTID_QdsH2dA1G46tXepO_1779960890.mp4
        val match = segmentIDRegex.find(url)
        if (match != null) {
            return match.groupValues[1].toIntOrNull()
        }
        return null
    }

    fun parseMaster(m3u8Text: String): MasterPlaylist {
        val lines = m3u8Text.lines()
        val pschKeys = mutableListOf<String>()
        val variants = mutableListOf<VariantStream>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                line.startsWith("#EXT-X-MOUFLON:PSCH:") -> {
                    pschKeys.add(line.substringAfter("PSCH:"))
                }
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = parseAttributes(line.substringAfter("#EXT-X-STREAM-INF:"))
                    i++
                    while (i < lines.size && (lines[i].isBlank() || lines[i].trimStart().startsWith("#"))) {
                        i++
                    }
                    val url = if (i < lines.size) lines[i].trim() else ""
                    variants.add(VariantStream(
                        name = buildQualityName(attrs["RESOLUTION"] ?: "", attrs["FRAME-RATE"] ?: ""),
                        resolution = attrs["RESOLUTION"] ?: "",
                        bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        url = url
                    ))
                }
            }
            i++
        }
        return MasterPlaylist(variants, pschKeys)
    }

    private fun parseAttributes(attrStr: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex("""([A-Z-]+)=("[^"]*"|[^,]+)""")
        regex.findAll(attrStr).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].removeSurrounding("\"")
            result[key] = value
        }
        return result
    }

    private fun buildQualityName(resolution: String, frameRate: String): String {
        val height = resolution.split("x").lastOrNull()?.toIntOrNull() ?: return ""
        val fps = frameRate.split(".").firstOrNull()?.toIntOrNull() ?: 30
        return if (fps != 30) "${height}p$fps" else "${height}p"
    }
}

data class ParsedPlaylist(
    val initUrl: String?,
    val segments: List<Segment>
)

data class VariantStream(
    val name: String,
    val resolution: String,
    val bandwidth: Long,
    val url: String
)

data class MasterPlaylist(
    val variants: List<VariantStream>,
    val pschKeys: List<String>
)
