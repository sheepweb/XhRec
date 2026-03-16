package github.rikacelery.v2

import github.rikacelery.utils.ClientManager
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

object EventDispatcher {
    val client = ClientManager.getProxiedClient("EventDispatcher")
    val logger = LoggerFactory.getLogger(EventDispatcher::class.java)

    @Volatile
    var wssession: AtomicReference<WebSocketSession?> = AtomicReference(null)
        private set

    val flow = MutableSharedFlow<String>(replay = 0)

    private val seq = AtomicInteger(1)

    private val subscribedRooms = ConcurrentHashMap.newKeySet<Long>()

    private const val AUTH_TOKEN =
        "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiItMTA4MSIsImluZm8iOnsiaXNHdWVzdCI6dHJ1ZSwidXNlcklkIjotMTA4MX19.IXF36-UfCEmOPGvhl2a19rgLsh2rDCdXNJ3su9LkA9Y"
    private const val WS_URL = "wss://websocket-v6.xhamsterlive.com/connection/websocket"

    suspend fun run() {
        var reconnectDelay = 1.seconds

        while (true) {
            try {
                logger.info("Connecting to WebSocket...")
                client.ws(WS_URL) {
                    reconnectDelay = 1.seconds
                    wssession.set(this)
                    logger.info("WebSocket connected. Session established.")

                    val connectMsg = """{"connect":{"token":"$AUTH_TOKEN","name":"js"},"id":${seq.incrementAndGet()}}"""
                    outgoing.send(Frame.Text(connectMsg))

                    delay(1000)
                    if (subscribedRooms.isNotEmpty()) {
                        logger.info("Restoring ${subscribedRooms.size} subscriptions...")
                        subscribedRooms.forEach { roomId ->
                            sendSubscribeCommand(roomId)
                        }
                    }
                    while (true) {
                        select {
                            incoming.onReceive { frame ->
                                if (frame !is Frame.Text) return@onReceive

                                val data = frame.data.toString(Charsets.UTF_8)
                                when (data) {
                                    "{}" -> {
                                        outgoing.send(Frame.Text("{}"))
                                    }

                                    else -> {
                                        data.lines().filter {
                                            it.startsWith("{\"push\"")
                                        }.forEach {
                                            flow.emit(it )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                wssession.set(null)
                logger.warn("WebSocket connection lost or error: ${e.message}")
                logger.info("Reconnecting in ${reconnectDelay.inWholeSeconds}s...")

                delay(reconnectDelay)
                reconnectDelay = (reconnectDelay + 5.seconds).coerceAtMost(30.seconds)
            }
        }
    }

    private suspend fun WebSocketSession.sendSubscribeCommand(roomId: Long) {
        val msg = """
{"subscribe":{"channel":"userBanned@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"modelDiscountActivated@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"broadcastChanged@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"streamChanged@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"modelStatusChanged@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"topicChanged@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"tipMenuUpdated@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"goalChanged@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"userUpdated@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"interactiveToyStatusChanged@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"deleteChatMessages@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"tipMenuLanguageDetected@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"groupShow@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"fanClubUpdated@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"modelAppUpdated@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"newKing@$roomId"},"id":${seq.incrementAndGet()}}
{"subscribe":{"channel":"newChatMessage@$roomId"},"id":${seq.incrementAndGet()}}""".trimIndent()
        try {
            outgoing.send(Frame.Text(msg))
            logger.debug("Sent subscribe command for room $roomId")
        } catch (e: Exception) {
            logger.warn("Failed to send subscribe for room $roomId: ${e.message}")
        }
    }

    private suspend fun WebSocketSession.sendUnsubscribeCommand(roomId: Long) {
        val msg = """
{"unsubscribe":{"channel":"userBanned@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"modelDiscountActivated@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"broadcastChanged@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"streamChanged@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"modelStatusChanged@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"topicChanged@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"tipMenuUpdated@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"goalChanged@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"userUpdated@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"interactiveToyStatusChanged@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"deleteChatMessages@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"tipMenuLanguageDetected@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"groupShow@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"fanClubUpdated@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"modelAppUpdated@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"newKing@$roomId"},"id":${seq.incrementAndGet()}}
{"unsubscribe":{"channel":"newChatMessage@$roomId"},"id":${seq.incrementAndGet()}}""".trimIndent()
        try {
            outgoing.send(Frame.Text(msg))
            logger.debug("Sent unsubscribe command for room $roomId")
        } catch (e: Exception) {
            logger.warn("Failed to send unsubscribe for room $roomId: ${e.message}")
        }
    }

    fun subscribe(roomid: Long): Flow<JsonObject> {
        val isNewSubscription = subscribedRooms.add(roomid)

        if (isNewSubscription) {
            val session = wssession.get()
            if (session != null) {
                kotlinx.coroutines.GlobalScope.launch {
                    session.sendSubscribeCommand(roomid)
                }
            } else {
                logger.info("No active session. Room $roomid queued for subscription upon reconnect.")
            }
        }

        return flow.filter { data ->
            data.contains("@$roomid")
        }.map { data ->
            Json.Default.parseToJsonElement(data).jsonObject
        }
    }

    fun unsubscribe(roomid: Long) {
        val wasPresent = subscribedRooms.remove(roomid)
        if (!wasPresent) {
            logger.info("Room $roomid was not subscribed.")
            return
        }

        val session = wssession.get()
        if (session != null) {
            kotlinx.coroutines.GlobalScope.launch {
                session.sendUnsubscribeCommand(roomid)
            }
        } else {
            logger.debug("No active session. Room $roomid removed from queue (will not be resubscribed on reconnect).")
        }
    }

    fun getSubscribedRooms(): Set<Long> {
        return subscribedRooms.toSet()
    }
}