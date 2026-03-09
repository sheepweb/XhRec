package github.rikacelery.v2

import github.rikacelery.HOST
import github.rikacelery.Room
import github.rikacelery.User
import github.rikacelery.rootLogger
import github.rikacelery.utils.*
import github.rikacelery.v2.exceptions.DeletedException
import github.rikacelery.v2.exceptions.RenameException
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

object API {
    private val client = ClientManager.getProxiedClient("api")
    private val logger = LoggerFactory.getLogger(API::class.java)


    suspend fun getRoomFromUrlOrSlug(path: String, quality: String): Room {
        try {
            val roomSlug = path.substringBefore("#").substringAfterLast("/").substringBefore("?")
            val str = client.get("https://$HOST/api/front/v1/broadcasts/$roomSlug").bodyAsText()
            val j = Json.Default.parseToJsonElement(str)
            return Room(j.PathSingle("item.username").asString(), j.PathSingle("item.modelId").asLong(), quality)
        } catch (e: Exception) {
            rootLogger.error("failed to get room info", e)
            throw e
        }
    }

    suspend fun getUserFromCookie(cookie: String): User {
        val initialData = client.get("https://$HOST/api/front/v3/config/initial") {
            headers["Cookie"] = cookie
            expectSuccess = true
        }.body<JsonObject>()
        val userData = initialData.PathSingle("initial.client.user")
        return User(
            cookie,
            userData.Long("id"),
            userData.String("username"),
            userData.Int("tokens"),
        )
    }

    suspend fun userFetchInitial(user: User): JsonObject {
        val initialData = client.get("https://$HOST/api/front/v3/config/initial") {
            headers["Cookie"] = user.cookie
            expectSuccess = true
        }.body<JsonObject>()
        return initialData
    }

    /**
     * @throws github.rikacelery.v2.exceptions.RenameException
     * @throws github.rikacelery.v2.exceptions.DeletedException
     */
    suspend fun roomFetchCamInfo(room: Room, cookie: String?=null): JsonObject {
        val camInfo = client.get("https://$HOST/api/front/v2/models/username/${room.name}/cam") {
            headers["Cookie"] = cookie?:""
            expectSuccess = true
        }.body<JsonObject>()
        return camInfo
    }
    suspend fun roomFetchModelToken(room: Room, user: User): String? {
        val camInfo = roomFetchCamInfo(room,user.cookie)
        return camInfo.PathSingle("cam.modelToken").asString().ifBlank { null }
    }

    suspend fun roomRequestGroupShow(room: Room, user: User) {
        val initial = userFetchInitial(user)
        client.put("https://$HOST/api/front/show/models/${room.id}/groupShows/${user.userId}") {
            expectSuccess = true
            headers["Cookie"] = user.cookie
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("csrfToken", initial.PathSingle("initial.client.csrfToken").asString())
                put("csrfTimestamp", initial.PathSingle("initial.client.csrfTimestamp").asString())
            })

        }.body<JsonObject>()

    }

    suspend fun roomFetchBroadcastInfo(room: Room): JsonObject {
        val get = client
            .get("https://$HOST/api/front/v1/broadcasts/${room.name}") {
                expectSuccess = false
            }
        if (get.status == HttpStatusCode.NotFound) {
            val reason =
                runCatching { Json.Default.parseToJsonElement(get.bodyAsText()).String("description") }.getOrNull()
            logger.trace("[{}] request api 404 reason={}", room.name, reason)
            if (reason == null) {
                throw IllegalStateException("request api failed")
            }
            when {
                reason.matches("Model has new name: newName=(.*)".toRegex()) -> {
                    val newName = "Model has new name: newName=(.*)".toRegex().find(reason)!!.groupValues[1]
                    logger.debug("[{}] model renamed to {}", room.name, newName)
                    throw RenameException(
                        newName
                    )
                }

                reason == "model already deleted" -> {
                    logger.debug("[{}] model deleted", room.name)
                    throw DeletedException(room.name)
                }
            }
        }
        val info = Json.Default.parseToJsonElement(get.bodyAsText()).jsonObject
        return info
    }
}