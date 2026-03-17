package github.rikacelery.v2

import github.rikacelery.Event
import github.rikacelery.Room
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

    val sessions = Hashtable<State, Session>()
    val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { coroutineContext, throwable ->
        logger.error("Unhandled exception in {}", coroutineContext, throwable)
    })
    val opLock = Mutex()
    var job: Job? = null
    var gracefulStop = false

    suspend fun stop() {
        logger.info("cancel job")
        job?.cancel()
        job?.join()
        logger.info("stop sessions")
        sessions.values.map {
            scope.launch { it.stop() }
        }.joinAll()
        logger.info("scheduler stopped")
    }

    suspend fun start(wait: Boolean = true): Job? {
        opLock.withLock {
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
                        delay(10 * 60_000) // 10 minute
                    }
                }).joinAll()
            }
        }
        logger.info("scheduler started")
        if (wait) job?.join()
        return job
    }

    suspend fun looplisten() = opLock.withLock {
        sessions
            .filterKeys { it.listen }
            .filterKeys {
                it.room.quality != "model already deleted" && it.room.quality != "model already renamed"
            }
            .filterValues { !it.isActive }
            .forEach { (k, v) ->
                scope.launch {
                    try {
                        if (v.testAndConfigure()) {
                            logger.debug("start ${k.room.name}")
                            v.start()
                            logger.debug("stop ${k.room.name}")
                            v.stop()
                        }
                    } catch (ex: RenameException) {
                        logger.info("model {} renamed to {}", k.room.name, ex.newName)
                        deactivate(k.room.name)
                        k.room.quality = "model already renamed"
                        add(k.room.copy(name = ex.newName), k.listen)
                        listUpdate(this@Scheduler)
                    } catch (e: DeletedException) {
                        logger.info("model {} deleted", k.room.name)
                        deactivate(k.room.name)
                        k.room.quality = "model already deleted"
                        listUpdate(this@Scheduler)
                    } catch (e: Exception) {
                        logger.error("session start failed", e)
                    }
                }
            }

    }

    suspend fun add(room: Room, listen: Boolean) {
        opLock.withLock {
            if (sessions.containsKey(State(room, listen))) {
                return
            }
            logger.info("add {} active={}", room.name, listen)
            sessions[State(room, listen)] = Session(room, dest, tmp)
        }
    }

    suspend fun remove(roomName: String) {
        opLock.withLock {
            val found = sessions.filter { it.key.room.name == roomName }.entries.singleOrNull()
            if (found != null) {
                logger.info("remove {}", found.key.room.name)
                sessions.remove(found.key)
                scope.launch { found.value.stop() }
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
        }
    }
}