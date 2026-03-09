package github.rikacelery

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val cookie: String,
    val userId: Long,
    val username: String,
    var coins: Int,
){
    override fun toString(): String {
        return "User(***${cookie.subSequence((cookie.lastIndex - 10).coerceAtLeast(0)..cookie.lastIndex)}, userId=$userId, username=$username, coins=$coins)"
    }
}
