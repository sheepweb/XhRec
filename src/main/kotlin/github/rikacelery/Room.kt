package github.rikacelery

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class Room(
    val name: String, val id: Long,
    //config
    var quality: String,
    //config
    @Serializable(with = DurationMillisSerializer::class)
    var limit: Duration = Duration.INFINITE,
    val lastSeen: String? = null,
    var autoPay: Boolean = false,
    )

object DurationMillisSerializer : KSerializer<Duration> {
    override val descriptor = PrimitiveSerialDescriptor("DurationMillis", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Duration) {
        if (value == Duration.INFINITE) {
            encoder.encodeLong(0) // INFINITE
        } else {
            encoder.encodeLong(value.inWholeMilliseconds)
        }
    }

    override fun deserialize(decoder: Decoder): Duration {
        val millis = decoder.decodeLong()
        return if (millis == 0L) Duration.INFINITE else millis.milliseconds
    }
}