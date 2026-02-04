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
                var loopCount = 0L
                while (currentCoroutineContext().isActive) {
                    loopCount++
                    logger.debug("scheduler loop #{} starting...", loopCount)
                    val loopStart = System.currentTimeMillis()
                    try {
                        loop()
                    } catch (e: Exception) {
                        logger.error("scheduler loop #{} failed", loopCount, e)
                    }
                    val loopDuration = System.currentTimeMillis() - loopStart
                    logger.debug("scheduler loop #{} completed in {}ms, next loop in 30s", loopCount, loopDuration)
                    delay(30_000)
                }
                logger.warn("scheduler loop exited! isActive={}", currentCoroutineContext().isActive)
            }
        }
        logger.info("scheduler started")
        if (wait) job?.join()
        return job
    }

    suspend fun loop() {
        logger.debug("loop() trying to acquire opLock...")
        val lockAcquireStart = System.currentTimeMillis()
        opLock.withLock {
            val lockAcquireTime = System.currentTimeMillis() - lockAcquireStart
            if (lockAcquireTime > 1000) {
                logger.warn("loop() acquired opLock after {}ms (slow!)", lockAcquireTime)
            } else {
                logger.debug("loop() acquired opLock in {}ms", lockAcquireTime)
            }

            val activeCount = sessions.count { it.value.isActive }
            val listenCount = sessions.count { it.key.listen }
            logger.debug("loop() sessions: total={}, listen={}, active={}", sessions.size, listenCount, activeCount)

            sessions.onEach {
            }.filterKeys { it.listen }.forEach { (k, v) ->
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
        logger.debug("loop() completed")
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

    /*
     * ========== 卡死问题修复 ==========
     * 问题描述：2026-02-04 17:09:46 - 21:18:40 系统卡死 4 小时无日志
     *
     * 原因分析：
     * 1. remove() 在持有 opLock 的情况下调用 session.stop()
     * 2. stop() 会调用 PostProcessor.process() 进行视频转码，可能耗时很长
     * 3. 在此期间 opLock 一直被持有，导致：
     *    - loop() 无法执行（每30秒的定时任务被阻塞）
     *    - 其他 HTTP 请求（add/remove/deactivate）也被阻塞
     *
     * 修复方案：
     * 先在锁内完成数据结构操作，释放锁后再调用 stop()
     * ===================================
     */
    suspend fun remove(roomName: String) {
        // 在锁内只做数据结构操作，获取需要停止的 session
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
        // 在锁外调用 stop()，避免长时间持有锁
        sessionToStop?.stop()
    }

    suspend fun deactivate(roomName: String) {
        val entry = sessions.filterKeys { it.room.name == roomName }.entries.singleOrNull()
        if (entry == null) {
            return
        }
        opLock.withLock {
            logger.info("deactivate {}", entry.key.room.name)
            if (entry.value.isActive) {
                scope.launch { entry.value.stop() }
            }
            entry.key.listen = false
        }
    }

    // 修复：在锁外调用 stop()，避免长时间持有锁
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
        // 在锁外调用 stop()
        sessionToStop?.stop()
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
}