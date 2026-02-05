package github.rikacelery.v2

import github.rikacelery.Room
import github.rikacelery.v2.exceptions.DeletedException
import github.rikacelery.v2.exceptions.RenameException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.*

class Scheduler(
    private val dest: String,
    private val tmp: String,
    private val listUpdate: suspend (self: Scheduler) -> Unit = {}
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
                while (currentCoroutineContext().isActive) {
                    try {
                        loop()
                    } catch (e: Exception) {
                        logger.error("scheduler loop failed", e)
                    }
                    delay(30_000)
                }
            }
        }
        logger.info("scheduler started")
        if (wait) job?.join()
        return job
    }

    suspend fun loop() = opLock.withLock {
        sessions.filterKeys { it.listen }.forEach { (k, v) ->
            if (v.isActive) return@forEach
            if (k.room.quality == "model already deleted") return@forEach
            scope.launch {
                try {
                    if (v.testAndConfigure()) {
                        logger.debug("start ${k.room.name}")
                        v.start()
                        logger.debug("stop ${k.room.name}")
                        v.stop()
                    }
                } catch (ex: RenameException) {
                    add(k.room.copy(name = ex.newName), k.listen)
                    listUpdate(this@Scheduler)
                    logger.info("model {} renamed to {}", k.room.name, ex.newName)
                } catch (e: DeletedException) {
                    logger.info("model {} deleted", e.name)
                    deactivate(e.name)
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

    // 注意：stop() 在锁外调用，避免 PostProcessor 长时间阻塞其他操作
    suspend fun remove(roomName: String) {
        val sessionToStop = opLock.withLock {
            val found = sessions.filter { it.key.room.name == roomName }.entries.singleOrNull()
            if (found != null) {
                logger.info("remove {}", found.key.room.name)
                sessions.remove(found.key)
                found.value
            } else {
                null
            }
        }
        sessionToStop?.stop()
    }

    suspend fun deactivate(roomName: String) {
        val entry = sessions.filterKeys { it.room.name == roomName }.entries.singleOrNull()
            ?: return
        val sessionToStop = opLock.withLock {
            logger.info("deactivate {}", entry.key.room.name)
            entry.key.listen = false
            if (entry.value.isActive) entry.value else null
        }
        sessionToStop?.stop()
    }

    suspend fun stopRecorder(roomName: String) {
        val sessionToStop = opLock.withLock {
            val entry = sessions.filterKeys { it.room.name == roomName }.entries.singleOrNull()
            if (entry != null && entry.value.isActive) {
                logger.info("stop {}", entry.key.room.name)
                entry.value
            } else {
                null
            }
        }
        sessionToStop?.stop()
    }

    suspend fun active(roomName: String) {
        val entry = sessions.filterKeys { it.room.name == roomName }.entries.singleOrNull()
            ?: return
        opLock.withLock {
            logger.info("activate {}", entry.key.room.name)
            entry.key.listen = true
        }
    }
}