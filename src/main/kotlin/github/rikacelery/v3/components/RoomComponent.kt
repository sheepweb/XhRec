package github.rikacelery.v3.components

import github.rikacelery.utils.asString
import github.rikacelery.utils.PathSingle
import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.Room
import github.rikacelery.v3.data.SizeStrSerializer
import github.rikacelery.v3.events.*
import github.rikacelery.v3.exceptions.DeletedException
import github.rikacelery.v3.exceptions.RenameException
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed interface RoomMsg
data class OnRoomEvent(val event: Any) : RoomMsg
data class HandleRoomCommand(val env: CommandEnvelope) : RoomMsg
object RefreshRooms : RoomMsg

class RoomComponent(
    private val apiClient: ApiClient,
    private val listConfPath: String,
    private val platformHost: String,
    private val requestBus: RequestBus,
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<RoomMsg>("RoomComponent", eventBus, parentScope) {

    private val rooms = ConcurrentHashMap<Long, Room>()
    private var nextId = 1L
    private var ready = false

    fun setReady() {
        ready = true
    }

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<RoomStatusChanged>(RoomStatusChanged::class)
        subscribe<CommandEnvelope>(CommandEnvelope::class)
        subscribe<PersistConfig>(PersistConfig::class)
        scope.launch {
            tell(RefreshRooms)
            while (isActive) {
                delay(10 * 1000L); tell(RefreshRooms)
            }
        }
    }

    override suspend fun wrapEvent(event: Any): RoomMsg? = when (event) {
        is RoomStatusChanged -> OnRoomEvent(event)
        is CommandEnvelope -> HandleRoomCommand(event)
        is PersistConfig -> OnRoomEvent(event)
        else -> null
    }

    override suspend fun handle(msg: RoomMsg) {
        when (msg) {
            is OnRoomEvent -> when (val event = msg.event) {
                is RoomStatusChanged -> {
                    rooms[event.roomId]?.let {
                        rooms[event.roomId] = it.copy(status = event.newStatus)
                        logger.debug("Room {} status: {} -> {}", event.roomId, event.oldStatus, event.newStatus)
                    }
                }
                is PersistConfig -> saveListConf()
                else -> {}
            }

            is HandleRoomCommand -> {
                handleCommand(msg.env)
            }

            is RefreshRooms -> refreshAll()

        }
    }

    private suspend fun handleCommand(env: CommandEnvelope) {
        val ack = when (val cmd = env.command) {
            is GetRoomName -> {
                val r =
                    rooms[cmd.roomId]; if (r != null) RoomNameResponse(r.name) else ErrorResponse("not found: ${cmd.roomId}")
            }

            is GetRoomStatus -> {
                val r = rooms[cmd.roomId]
                if (r != null) RoomStatusResponse(r.status)
                else ErrorResponse("not found: ${cmd.roomId}")
            }
            is GetRoomConfig -> {
                val r = rooms[cmd.roomId]; if (r != null) RoomConfigResponse(
                    r.quality,
                    r.timeLimitMs,
                    r.sizeLimitBytes,
                    r.autoPay
                ) else ErrorResponse("not found: ${cmd.roomId}")
            }

            is SetRoomQuality -> {
                rooms[cmd.roomId]?.let { rooms[it.id] = it.copy(quality = cmd.quality) }
                logger.info("User changed quality for room {} to {}", cmd.roomId, cmd.quality)
                eventBus.publish(QualityChangeRequested(cmd.roomId, cmd.quality))
                OkResponse
            }

            is SetRoomTimeLimit -> {
                rooms[cmd.roomId]?.let { rooms[it.id] = it.copy(timeLimitMs = cmd.limitMs) }; OkResponse
            }

            is SetRoomSizeLimit -> {
                rooms[cmd.roomId]?.let { rooms[it.id] = it.copy(sizeLimitBytes = cmd.limitBytes) }; OkResponse
            }

            is SetRoomAutoPay -> {
                rooms[cmd.roomId]?.let { rooms[it.id] = it.copy(autoPay = cmd.autoPay) }; OkResponse
            }

            is AddRoom -> {
                if (!ready) {
                    ErrorResponse("system initializing, please retry")
                } else try {
                    val (id, name) = apiClient.getRoomFromUrlOrSlug(cmd.name, cmd.quality)
                    if (rooms.containsKey(id) || rooms.values.any { it.name.equals(name, true) }) {
                        logger.warn("Duplicate room: id={}, name={}", id, name)
                        ErrorResponse("Exist $name")
                    } else {
                        rooms[id] = Room(id, name, cmd.quality, cmd.timeLimitMs, cmd.sizeLimitBytes, cmd.autoPay, null)
                        logger.info("Room added: id={}, name={}, quality={}", id, name, cmd.quality)
                        eventBus.publish(RoomAdded(id, name))
                        RoomNameResponse(name)
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to add room '{}': {}", cmd.name, e.message)
                    ErrorResponse("failed to add room: ${e.message}")
                }
            }

            is RemoveRoom -> {
                if (!ready) {
                    ErrorResponse("system initializing, please retry")
                } else {
                    val removed = rooms.remove(cmd.roomId)
                    logger.info("Room removed: id={}, name={}", cmd.roomId, removed?.name)
                    eventBus.publish(RoomRemoved(cmd.roomId, removed?.name ?: ""))
                    OkResponse
                }
            }

            is GetRooms -> rooms.values.map { it.copy() }
            else -> return
        }
        eventBus.publish(CommandAck(env.id, ack))
    }

    private suspend fun refreshAll() {
        rooms.values.forEach { room ->
            try {
                val info = apiClient.roomFetchBroadcastInfo(room.name)
                val status = info.PathSingle("item.status").asString()
                val oldStatus = room.status
                if (status != oldStatus) {
                    rooms[room.id] = room.copy(status = status)
                    eventBus.publish(RoomStatusChanged(room.id, oldStatus, status))
                    logger.debug("refreshAll: room {} status {} -> {}", room.id, oldStatus, status)
                }
            } catch (e: RenameException) {
                val oldName = room.name
                rooms[room.id] = room.copy(name = e.newName)
                eventBus.publish(RoomRenamed(room.id, oldName, e.newName))
                logger.info("Room ${room.id} renamed: $oldName -> ${e.newName}")
            } catch (e: DeletedException) {
                rooms.remove(room.id)
                eventBus.publish(RoomRemoved(room.id, room.name))
                logger.info("Room ${room.id} deleted: ${room.name}")
            } catch (e: Exception) {
                logger.warn("refreshAll error room ${room.id}: ${e.message}")
            }
        }
    }

    fun internalAdd(
        id: Long,
        name: String,
        quality: String,
        timeLimitMs: Long,
        sizeLimitBytes: Long,
        autoPay: Boolean
    ) {
        rooms[id] = Room(id, name, quality, timeLimitMs, sizeLimitBytes, autoPay, null)
    }

    fun getRoom(roomId: Long): Room? = rooms[roomId]
    fun allRooms(): List<Room> = rooms.values.toList()

    private suspend fun saveListConf() {
        try {
            val armedIds = requestBus.request<List<Long>>(GetArmedRoomIds).toSet()
            val file = File(listConfPath)
            synchronized(file) {
                file.writeText(rooms.values.joinToString("\n") { room ->
                    val prefix = if (room.id in armedIds) "" else "#"
                    val sb = StringBuilder("${prefix}https://$platformHost/${room.name} q:${room.quality}")
                    if (room.timeLimitMs > 0) sb.append(" limit:${room.timeLimitMs / 1000}")
                    if (room.sizeLimitBytes > 0) sb.append(" size:${formatSize(room.sizeLimitBytes)}")
                    if (room.autoPay) sb.append(" autopay")
                    sb.toString()
                }.let { lines -> if (lines.isNotEmpty()) lines + "\n" else "" })
            }
        } catch (e: Exception) {
            logger.warn("Failed to save list.conf: ${e.message}")
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L*1024*1024*1024 -> "${bytes / (1024L*1024*1024*1024)}Ti"
        bytes >= 1024*1024*1024 -> "${bytes / (1024*1024*1024)}Gi"
        bytes >= 1024*1024 -> "${bytes / (1024*1024)}Mi"
        bytes >= 1024 -> "${bytes / 1024}Ki"
        else -> "${bytes}Bi"
    }
}
