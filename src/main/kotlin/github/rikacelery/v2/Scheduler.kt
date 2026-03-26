package github.rikacelery.v2

import github.rikacelery.Event
import github.rikacelery.Room
import github.rikacelery.utils.PerfStats
import github.rikacelery.v2.exceptions.DeletedException
import github.rikacelery.v2.exceptions.RenameException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.*

class Scheduler(
    private val dest: String, private val tmp: String, private val listUpdate: suspend (self: Scheduler) -> Unit = {}
) {
    data class State(val room: Room, var listen: Boolean) {
        override fun hashCode(): Int {
            return room.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as State

            return room == other.room
        }
    }

    val logger = LoggerFactory.getLogger(Scheduler::class.java)
    private val perf = PerfStats("scheduler", "main", logger)

    val sessions = Hashtable<State, Session>()
    val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { coroutineContext, throwable ->
        logger.error("Unhandled exception in {}", coroutineContext, throwable)
    })
    val opLock = Mutex()
    var job: Job? = null
    var gracefulStop = false

    suspend fun stop() {
        val start = System.currentTimeMillis()
        logger.info("cancel job")
        job?.cancel()
        job?.join()
        logger.info("stop sessions")
        sessions.values.map {
            scope.launch { it.stop() }
        }.joinAll()
        perf.inc("schedulerStopCount")
        perf.observe("schedulerStopLatency", System.currentTimeMillis() - start)
        perf.maybeLog(mapOf("trackedRooms" to sessions.size, "activeSessions" to sessions.values.count { it.isActive }))
        logger.info("scheduler stopped")
    }

    suspend fun start(wait: Boolean = true): Job? {
        val lockWaitStart = System.currentTimeMillis()
        opLock.withLock {
            perf.observe("schedulerOpLockWaitLatency", System.currentTimeMillis() - lockWaitStart)
            val holdStart = System.currentTimeMillis()
            if (job != null) return@withLock
            job = scope.launch {
                listOf(launch {
                    while (currentCoroutineContext().isActive && !gracefulStop) {
                        looplisten()
                        delay(30_000)
                    }
                }, launch {
                    while (currentCoroutineContext().isActive && !gracefulStop) {
                        //fixme data racing
                        sessions
                            .filterKeys { !it.listen }
                            .forEach { it.value.updateInfo() }
                        perf.inc("schedulerUpdateInfoCount", sessions.keys.count { !it.listen }.toLong())
                        perf.maybeLog(mapOf("trackedRooms" to sessions.size, "activeSessions" to sessions.values.count { it.isActive }))
                        delay(10 * 60_000) // 10 minute
                    }
                }).joinAll()
            }
            perf.inc("schedulerStartCount")
            perf.observe("schedulerOpLockHoldLatency", System.currentTimeMillis() - holdStart)
        }
        perf.maybeLog(mapOf("trackedRooms" to sessions.size, "activeSessions" to sessions.values.count { it.isActive }))
        logger.info("scheduler started")
        if (wait) job?.join()
        return job
    }

    suspend fun looplisten() {
        val tickStart = System.currentTimeMillis()
        val lockWaitStart = System.currentTimeMillis()
        opLock.withLock {
            perf.observe("schedulerOpLockWaitLatency", System.currentTimeMillis() - lockWaitStart)
            val holdStart = System.currentTimeMillis()
            perf.inc("schedulerTick")
            val candidates = sessions
                .filterKeys { it.listen }
                .filterKeys {
                    it.room.quality != "model already deleted" && it.room.quality != "model already renamed"
                }
                .filterValues { !it.isActive }

            perf.inc("schedulerRoomScanCount", sessions.size.toLong())
            perf.inc("schedulerCandidateCount", candidates.size.toLong())

            var launchCount = 0L
            candidates.forEach { (k, v) ->
                launchCount++
                scope.launch {
                    val launchStart = System.currentTimeMillis()
                    try {
                        if (v.testAndConfigure()) {
                            logger.debug("start ${k.room.name}")
                            v.start()
                            logger.debug("stop ${k.room.name}")
                            v.stop()
                        } else {
                            perf.inc("schedulerSessionSkipCount")
                        }
                    } catch (ex: RenameException) {
                        perf.inc("schedulerRenameCount")
                        logger.info("model {} renamed to {}", k.room.name, ex.newName)
                        deactivate(k.room.name)
                        k.room.quality = "model already renamed"
                        add(k.room.copy(name = ex.newName), k.listen)
                        listUpdate(this@Scheduler)
                    } catch (e: DeletedException) {
                        perf.inc("schedulerDeletedCount")
                        logger.info("model {} deleted", k.room.name)
                        deactivate(k.room.name)
                        k.room.quality = "model already deleted"
                        listUpdate(this@Scheduler)
                    } catch (e: Exception) {
                        perf.inc("schedulerSessionErrorCount")
                        logger.error("session start failed", e)
                    } finally {
                        perf.observe("schedulerSessionLaunchLatency", System.currentTimeMillis() - launchStart)
                        perf.maybeLog(mapOf("trackedRooms" to sessions.size, "activeSessions" to sessions.values.count { it.isActive }, "launchBatchSize" to launchCount))
                    }
                }
            }

            perf.inc("schedulerSessionLaunchCount", launchCount)
            perf.observe("schedulerOpLockHoldLatency", System.currentTimeMillis() - holdStart)
            perf.observe("schedulerTickLatency", System.currentTimeMillis() - tickStart)
            perf.maybeLog(mapOf("trackedRooms" to sessions.size, "activeSessions" to sessions.values.count { it.isActive }, "launchBatchSize" to launchCount))
        }
    }

    suspend fun add(room: Room, listen: Boolean) {
        opLock.withLock {
            if (sessions.containsKey(State(room, listen))) {
                perf.inc("schedulerAddSkipCount")
                return
            }
            logger.info("add {} active={}", room.name, listen)
            sessions[State(room, listen)] = Session(room, dest, tmp)
            perf.inc("schedulerAddCount")
            perf.maybeLog(mapOf("trackedRooms" to sessions.size, "activeSessions" to sessions.values.count { it.isActive }))
        }
    }

    suspend fun remove(roomName: String) {
        opLock.withLock {
            val found = sessions.filter { it.key.room.name == roomName }.entries.singleOrNull()
            if (found != null) {
                logger.info("remove {}", found.key.room.name)
                sessions.remove(found.key)
                scope.launch { found.value.stop() }
                perf.inc("schedulerRemoveCount")
                perf.maybeLog(mapOf("trackedRooms" to sessions.size, "activeSessions" to sessions.values.count { it.isActive }))
            }
        }
    }

    suspend fun deactivate(roomName: String) {
        val entry = sessions.filterKeys { it.room.name == roomName }.entries.singleOrNull()
        if (entry == null) {
            return
        }
        opLock.withLock {
            logger.info("deactivate {}", entry.key.room.name)
            entry.key.listen = false
            if (entry.value.isActive) {
                scope.launch { entry.value.stop() }
            }
            perf.inc("schedulerDeactivateCount")
            perf.maybeLog(mapOf("trackedRooms" to sessions.size, "activeSessions" to sessions.values.count { it.isActive }))
        }
    }

    suspend fun stopRecorder(roomName: String) {
        val entry = sessions.filterKeys { it.room.name == roomName }.entries.singleOrNull()
        if (entry == null) {
            return
        }
        opLock.withLock {
            logger.info("stop {}", entry.key.room.name)
            if (entry.value.isActive) {
                scope.launch { entry.value.stop() }
            }
            perf.inc("schedulerStopRecorderCount")
            perf.maybeLog(mapOf("trackedRooms" to sessions.size, "activeSessions" to sessions.values.count { it.isActive }))
        }
    }

    suspend fun active(roomName: String) {
        val entry = sessions.filterKeys { it.room.name == roomName }.entries.singleOrNull()
        if (entry == null) {
            return
        }
        opLock.withLock {
            logger.info("activate {}", entry.key.room.name)
            entry.key.listen = true
            perf.inc("schedulerActivateCount")
            perf.maybeLog(mapOf("trackedRooms" to sessions.size, "activeSessions" to sessions.values.count { it.isActive }))
        }

    }

    suspend fun cmdFinish(roomName: String, cmd: Event.CmdFinish) {
        val entry = sessions.filterKeys { it.room.name == roomName }.entries.singleOrNull()
        if (entry == null) {
            return
        }
        opLock.withLock {
            logger.info("inject {} {}", entry.key.room.name, cmd::class.simpleName)
            entry.value.cmdFinish()
            perf.inc("schedulerCmdFinishCount")
            perf.maybeLog(mapOf("trackedRooms" to sessions.size, "activeSessions" to sessions.values.count { it.isActive }))
        }
    }

    suspend fun restartRecorder(roomName: String) {
        val entry = sessions.filterKeys { it.room.name == roomName }.entries.singleOrNull()
        if (entry == null) {
            return
        }
        opLock.withLock {
            logger.info("restart {}", entry.key.room.name)
            scope.launch {
                entry.value.stop()
                entry.value.start()
            }
            perf.inc("schedulerRestartCount")
            perf.maybeLog(mapOf("trackedRooms" to sessions.size, "activeSessions" to sessions.values.count { it.isActive }))
        }
    }
}