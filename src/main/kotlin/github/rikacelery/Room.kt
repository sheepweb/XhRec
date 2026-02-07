package github.rikacelery

import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Serializable
data class Room(
    val name: String, val id: Long,
    //config
    var quality: String,
    //config
    var limit: Duration = Duration.INFINITE,
    val lastSeen: String? = null
)

