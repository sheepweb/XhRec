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
    // size limit in MB, 0 means no limit
    var sizeLimit: Long = 0,
    val lastSeen: String? = null
)

