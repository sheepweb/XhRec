package github.rikacelery.v3.components

import github.rikacelery.utils.*
import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.DataChannel
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.data.StreamEvent
import github.rikacelery.v3.data.StreamStart
import github.rikacelery.v3.data.User
import github.rikacelery.v3.events.*
import github.rikacelery.v3.m3u8.M3u8Parser
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

sealed interface SessionMsg
data class OnSessionEvent(val event: Any) : SessionMsg
data class DoStart(val roomId: Long, val roomName: String, val quality: String, val pkey: String = "") : SessionMsg
data class DoStop(val roomId: Long) : SessionMsg
data class DoBreak(val roomId: Long) : SessionMsg
data class DoCutPointDone(val roomId: Long) : SessionMsg
data class HandleSessionCommand(val env: CommandEnvelope) : SessionMsg

enum class SessionState { Idle, Armed, Fetching, Recording, Closing }

data class RoomSession(
    val roomId: Long,
    var roomName: String,
    var quality: String,
    var targetquality: String,
    var state: SessionState = SessionState.Idle,
    var playlistUrl: String = "",
    var initUrl: String? = null,
    var token: String? = null,
    var pkey: String = "",
    var segmentIndex: Int = 0,
    val circleCache: CircleCache = CircleCache(100),
    var startTime: Instant = Instant.now(),
    var totalBytes: Long = 0,
    var timeLimitMs: Long = 0,
    var sizeLimitBytes: Long = 0,
    var pollingJob: Job? = null,
    var lastSegmentId: Int? = null
)

class CircleCache(private val capacity: Int) {
    private val set = LinkedHashSet<String>()
    fun add(url: String): Boolean {
        if (set.size >= capacity) {
            set.clear(); return true
        }
        return set.add(url)
    }

    fun remove(url: String) = set.remove(url)
    fun clear() = set.clear()
}

class SessionComponent(
    private val dataChannel: DataChannel,
    private val downloader: DownloaderComponent,
    private val m3u8Parser: M3u8Parser,
    private val requestBus: RequestBus,
    private val apiClient: ApiClient,
    private val streamAuthKey: String,
    eventBus: EventBus,
    parentScope: CoroutineScope
) : Actor<SessionMsg>("SessionComponent", eventBus, parentScope) {

    private val sessions = ConcurrentHashMap<Long, RoomSession>()

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<RoomStatusChanged>(RoomStatusChanged::class)
        subscribe<LiveMessage>(LiveMessage::class)
        subscribe<QualitiesAvailable>(QualitiesAvailable::class)
        subscribe<SegmentDownloaded>(SegmentDownloaded::class)
        subscribe<CommandEnvelope>(CommandEnvelope::class)
        subscribe<QualityChangeRequested>(QualityChangeRequested::class)

        scope.launch {
            while (isActive) {
                delay(30.seconds)
                pollQualities()
            }
        }
    }

    override suspend fun wrapEvent(event: Any): SessionMsg? = when (event) {
        is RoomStatusChanged -> OnSessionEvent(event)
        is LiveMessage -> OnSessionEvent(event)
        is QualitiesAvailable -> OnSessionEvent(event)
        is SegmentDownloaded -> OnSessionEvent(event)
        is QualityChangeRequested -> OnSessionEvent(event)
        is CommandEnvelope -> HandleSessionCommand(event)
        else -> null
    }

    override suspend fun handle(msg: SessionMsg) {
        when (msg) {
            is DoStart -> startSession(msg.roomId, msg.roomName, msg.quality, msg.pkey)
            is DoStop -> stopSession(msg.roomId)
            is DoBreak -> {
                val rs = sessions[msg.roomId] ?: return
                if (rs.state == SessionState.Recording) {
                    downloader.tell(
                        DoCutPoint(
                            CutPoint(
                                msg.roomId, rs.segmentIndex - 1, rs.roomName, Instant.now(), EndReason.SizeLimit
                            )
                        )
                    )
                    rs.startTime = Instant.now()
                    rs.totalBytes = 0
                    rs.segmentIndex = 0
                    rs.initUrl?.let { rs.circleCache.remove(it) }
                }
            }

            is OnSessionEvent -> handleEvent(msg.event)
            is DoCutPointDone -> onCutPointDone(msg.roomId)
            is HandleSessionCommand -> {
                handleCommand(msg.env)
            }
        }
    }

    private suspend fun handleCommand(env: CommandEnvelope) {
        val ack = when (env.command) {
            is GetSessions -> sessions.values.map { it.copy() }
            else -> return
        }
        eventBus.publish(CommandAck(env.id, ack))
    }

    private suspend fun startSession(roomId: Long, name: String, quality: String, pkey: String = "", reconfigure: Boolean = true) {
        if (sessions.containsKey(roomId) && (sessions[roomId]!!.state == SessionState.Fetching ||
                    sessions[roomId]!!.state == SessionState.Recording)
        ) {
            return
        }
        var token: String? = null
        if (reconfigure) {
            token = configureSession(roomId, name) ?: run {
                logger.info("[{}] Session not started: configure returned false", name)
                return
            }
        }
        val resolvedPkey = pkey.ifBlank { streamAuthKey }
        val qualities = apiClient.roomQualities(name)
        val selectedQuality = selectQuality(qualities, quality)
        logger.info("Session starting: roomId={}, name={}, quality={}", roomId, name, selectedQuality)
        val rs = RoomSession(roomId, name, selectedQuality, selectedQuality, pkey = resolvedPkey)
        sessions[roomId] = rs
        rs.token = token.takeIf { !it.isNullOrEmpty() }
        rs.state = SessionState.Fetching
        rs.startTime = Instant.now()
        dataChannel.send(StreamStart(roomId, name, rs.startTime))
        rs.pollingJob = scope.launch { pollingLoop(rs) }
        rs.playlistUrl = buildPlaylistUrl(roomId, rs.quality, rs.pkey, rs.token)
    }

    private suspend fun stopSession(roomId: Long) {
        val rs = sessions[roomId] ?: return
        logger.info("Session stopping: roomId={}, name={}, state={}", roomId, rs.roomName, rs.state)
        rs.state = SessionState.Closing
        rs.pollingJob?.cancel()
        downloader.tell(DoCutPoint(CutPoint(roomId, rs.segmentIndex, rs.roomName, Instant.now(), EndReason.UserStop)))
    }

    private suspend fun handleEvent(event: Any) {
        when (event) {
            is RoomStatusChanged -> {
                val rs = sessions[event.roomId] ?: return
                if (event.newStatus == "public" || event.newStatus == "groupShow") {
                    if (rs.state == SessionState.Armed) {
                        startSession(event.roomId, rs.roomName, rs.quality, rs.pkey)
                    }
                } else if (event.newStatus == "offline" || event.newStatus == "private") {
                    if (rs.state == SessionState.Recording) {
                        rs.state = SessionState.Closing
                        rs.pollingJob?.cancel()
                        downloader.tell(
                            DoCutPoint(
                                CutPoint(
                                    event.roomId, rs.segmentIndex, rs.roomName, Instant.now(), EndReason.StreamEnd
                                )
                            )
                        )
                    }
                }
            }

            is LiveMessage -> {
                dataChannel.send(StreamEvent(event.roomId, Instant.now(), event.body.toString()))
            }

            is QualitiesAvailable -> {
                val rs = sessions[event.roomId] ?: return
                if (rs.state != SessionState.Recording) return
                var newQuality = selectQuality(event.qualities, rs.targetquality)
                if (newQuality != rs.quality) {
                    logger.info(
                        "Quality switched for {}: {} -> {} (available: {})",
                        rs.roomName,
                        rs.quality,
                        newQuality,
                        event.qualities
                    )
                    rs.quality = newQuality
                    rs.playlistUrl = buildPlaylistUrl(event.roomId, newQuality, rs.pkey, rs.token)
                } else {
                    logger.debug(
                        "Quality unchanged for {}: {} (available: {})",
                        rs.roomName,
                        rs.quality,
                        event.qualities
                    )
                }
            }

            is QualityChangeRequested -> {
                val rs = sessions[event.roomId] ?: return
                logger.info("Quality change requested for {}: {} -> {}", rs.roomName, rs.quality, event.newQuality)
                rs.targetquality = event.newQuality
                if (rs.state == SessionState.Recording||rs.state==SessionState.Fetching) {
                    pollQualityForRoom(rs)
                }
            }

            is SegmentDownloaded -> {
                val rs = sessions[event.roomId] ?: return
                rs.totalBytes += event.bytes
            }

            is NewSegments -> {
                val rs = sessions[event.roomId] ?: return
                event.urls.forEach { url ->
                    val segId = m3u8Parser.segmentIDFromUrl(url.url)
                    if (segId != null) {
                        val prev = rs.lastSegmentId
                        if (prev != null) {
                            val gap = segId - prev - 1
                            if (gap > 0) {
                                eventBus.publish(SegmentGapDetected(event.roomId, gap))
                            }
                        }
                        if (prev == null || segId > prev) {
                            rs.lastSegmentId = segId
                        }
                    }
                }
            }

            else -> {}
        }
    }

    private fun selectQuality(available: List<String>, requested: String): String {
        if (requested == "raw") return "raw"
        val clean = available.filterNot { it.contains("blurred") }
        if (clean.isEmpty()) return "raw"
        if (requested == available[0])
            return "raw"
        if (requested in available)
            return requested
        val reqParts = requested.split("p").filterNot(String::isEmpty)
        val final = clean.minByOrNull { q ->
            val qParts = q.split("p").filterNot(String::isEmpty)
            if (reqParts.size == 2) {
                abs((qParts[0].toIntOrNull() ?: 0) - (reqParts[0].toIntOrNull() ?: 0)) +
                        abs((reqParts[1].toIntOrNull() ?: 30) - (qParts.getOrElse(1) { "30" }.toIntOrNull() ?: 30))
            } else {
                abs((qParts[0].toIntOrNull() ?: 0) - (reqParts[0].toIntOrNull() ?: 0))
            }
        } ?: requested
        if (final == available[0])
            return "raw"
        return final
    }

    private suspend fun pollQualities() {
        for (rs in sessions.values) {
            if (rs.state != SessionState.Recording) continue
            pollQualityForRoom(rs)
        }
    }

    private suspend fun pollQualityForRoom(rs: RoomSession) {
        try {
            val qualities = apiClient.roomQualities(rs.roomName)
//            logger.debug("Available qualities for {}: {}", rs.roomName, qualities)
            if (qualities.isNotEmpty()) {
                eventBus.publish(QualitiesAvailable(rs.roomId, qualities))
            }
        } catch (_: Exception) {
            // quality polling is best-effort; ignore transient errors
        }
    }

    /** Returns model token for groupShow, empty string for public, null if can't record */
    private suspend fun configureSession(roomId: Long, roomName: String): String? {
        try {
            val info = apiClient.roomFetchBroadcastInfo(roomName)
            val status = info.PathSingle("item.status").asString()
            when (status) {
                "public" -> return ""
                "groupShow" -> {
                    val config = requestBus.request<RoomConfigResponse>(GetRoomConfig(roomId))
                    if (!config.autoPay) {
                        logger.warn("[{}] Room not enable autopay", roomName)
                        return null
                    }
                    val camInfo = apiClient.roomFetchCamInfo(roomName, "")
                    val price = camInfo.PathSingle("user.user.ticketRate").asInt()
                    val users = requestBus.request<List<User>>(GetValidPaymentAccount(price.toLong()))
                    val u = users.firstOrNull()
                    if (u == null) {
                        logger.warn("[{}] No account to pay. price={}", roomName, price)
                        return null
                    }
                    var token = apiClient.roomFetchModelToken(roomName, u)
                    if (token == null) {
                        apiClient.roomRequestGroupShow(roomId, u)
                        requestBus.request<OkResponse>(DeductCoins(u.userId, price.toLong()))
                        delay(1.seconds)
                        token = apiClient.roomFetchModelToken(roomName, u)
                    }
                    if (token == null) {
                        logger.warn("[{}] Failed to get model token", roomName)
                        return null
                    }
                    return token
                }

                else -> {
                    logger.trace("[{}] -> false, status={}", roomName, status)
                    return null
                }
            }
        } catch (e: ClientRequestException) {
            logger.warn("Room configure failed for {}: {}", roomName, e.message)
        } catch (e: Exception) {
            logger.warn("Room configure failed for {}: {}", roomName, e.message)
        }

        return null
    }

    private fun buildPlaylistUrl(roomId: Long, quality: String, pkey: String, token: String? = null): String = buildUrl {
        protocol = URLProtocol.HTTPS
        host = "media-hls.doppiocdn.org"
        encodedPath = if (quality != "raw" && quality.isNotBlank()) "b-hls-%d/%d/%d_%s.m3u8".format(
            22, roomId, roomId, quality
        ) else "b-hls-%d/%d/%d.m3u8".format(
            22, roomId, roomId
        )
        parameters["psch"] = "v2"
        parameters["pkey"] = pkey
        parameters["preferredVideoCodec"] = "H265"
        if (token != null) {
            parameters["aclAuth"] = token
        }
    }.toString()

    private suspend fun CoroutineScope.pollingLoop(rs: RoomSession) {
        try {
            while (isActive && (rs.state == SessionState.Fetching || rs.state == SessionState.Recording)) {
                delay(2.seconds)
                try {
                    val client = ClientManager.getProxiedClient("m3u8_${rs.roomId}")
                    val response = withRetry(3) {
                        client.get(rs.playlistUrl)
                    }
                    val text = response.bodyAsText()

                    val key = (requestBus.request<ConfigResponse>(GetDecryptKey(rs.pkey)).value as? String) ?: run {
                        logger.error("No decrypt key for room ${rs.roomId}")
                        delay(5.seconds)
                        continue
                    }
                    val parsed = m3u8Parser.parse(text, key)
                    requireNotNull(parsed.initUrl)

                    if (parsed.initUrl != rs.initUrl) {
                        downloader.tell(
                            DoCutPoint(
                                CutPoint(
                                    rs.roomId, rs.segmentIndex - 1, rs.roomName, Instant.now(), EndReason.NewInit
                                )
                            )
                        )
                        rs.segmentIndex = 0
                        rs.circleCache.clear()
                        rs.initUrl = parsed.initUrl
                    }
                    if (rs.circleCache.add(parsed.initUrl)) {
                        downloader.tell(
                            DoDownload(
                                Download(
                                    rs.roomId,
                                    listOf(Segment(parsed.initUrl, -1)),
                                    rs.segmentIndex
                                )
                            )
                        )
                        rs.segmentIndex += 1
                    }
                    val unseen = parsed.segments.filter { rs.circleCache.add(it.url) }
                    if (unseen.isNotEmpty()) {
                        eventBus.publish(NewSegments(rs.roomId, unseen))
                        downloader.tell(DoDownload(Download(rs.roomId, unseen, rs.segmentIndex)))
                        rs.segmentIndex += unseen.size
                    }

                    val limit = if (rs.timeLimitMs > 0) rs.timeLimitMs else Long.MAX_VALUE
                    val elapsed = Duration.between(rs.startTime, Instant.now()).toMillis()
                    if (elapsed >= limit || (rs.sizeLimitBytes > 0 && rs.totalBytes >= rs.sizeLimitBytes)) {
                        val reason = if (elapsed >= limit) EndReason.TimeLimit else EndReason.SizeLimit
                        downloader.tell(
                            DoCutPoint(
                                CutPoint(
                                    rs.roomId, rs.segmentIndex - 1, rs.roomName, Instant.now(), reason
                                )
                            )
                        )
                        rs.startTime = Instant.now()
                        rs.totalBytes = 0
                        rs.segmentIndex = 0
                        rs.circleCache.remove(parsed.initUrl)
                    }

                    if (rs.state == SessionState.Fetching) rs.state = SessionState.Recording

                } catch (e: CancellationException) {
                    if (e is TimeoutCancellationException) {
                        logger.warn("[{}] Refresh list timeout", rs.roomName)
                        if (configureSession(rs.roomId, rs.roomName) == null) {
                            logger.info("[STOP] [{}] Room off or non-public after timeout", rs.roomName)
                        }
                        rs.playlistUrl = buildPlaylistUrl(rs.roomId, rs.quality, rs.pkey, rs.token)
                        continue
                    }
                    throw e
                } catch (e: ClientRequestException) {
                    if (e.response.status == HttpStatusCode.Forbidden) {
                        val token = configureSession(rs.roomId, rs.roomName)
                        if (token != null) {
                            rs.token = token.takeIf { it.isNotEmpty() }
                            rs.playlistUrl = buildPlaylistUrl(rs.roomId, rs.quality, rs.pkey, rs.token)
                            continue
                        }
                        logger.info("[STOP] [{}] Room off or non-public (403)", rs.roomName)
                    }
                    if (e.response.status == HttpStatusCode.NotFound) {
                        logger.info("[STOP] [{}] Stream url returns 404", rs.roomName)
                    }
                    logger.error("Polling error room ${rs.roomId}: ${e.message}")
                    eventBus.publish(DownloadError(rs.roomId, null, null, e.message))
                } catch (e: Exception) {
                    logger.error("[{}] Unexpected error in polling", rs.roomName, e)
                    if (configureSession(rs.roomId, rs.roomName) == null) {
                        logger.info("[STOP] [{}] Room off or non-public", rs.roomName)
                    }
                    rs.playlistUrl = buildPlaylistUrl(rs.roomId, rs.quality, rs.pkey, rs.token)
                }
            }
        } finally {
            eventBus.publish(RecordingStopped(rs.roomId))
        }
    }

    private suspend fun onCutPointDone(roomId: Long) {
        val rs = sessions[roomId] ?: return
        rs.pollingJob = scope.launch { pollingLoop(rs) }
    }
}
