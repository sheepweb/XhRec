package github.rikacelery.v2

import github.rikacelery.Room
import github.rikacelery.v2.exceptions.DeletedException
import github.rikacelery.v2.exceptions.RenameException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

class Scheduler(
    private val dest:String,
    private val tmp:String,
    private val listUpdate:suspend (self: Scheduler)-> Unit = {}
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

    val sessions = Hashtable<State, Session>()
    val scope = CoroutineScope(SupervisorJob()+ CoroutineExceptionHandler { coroutineContext, throwable ->
        throwable.printStackTrace()
    })
    val opLock = Mutex()
    var job: Job? = null

    suspend fun stop() {
        job?.cancel()
        job?.join()
        sessions.values.map {
            scope.launch { it.stop() }
        }.joinAll()
    }

    suspend fun start(wait: Boolean = true): Job? {
        opLock.withLock {
            if (job != null) return@withLock
            job = scope.launch {
                while (currentCoroutineContext().isActive) {
                    loop()
                    delay(30_000)
                }
            }
        }
        if (wait) job?.join()
        return job
    }

    fun loop() {
//        println("loop")
        sessions.onEach {
//            println("${it.key.listen} ${it.key.room.name}")
        }.filterKeys { it.listen }.forEach { (k, v) ->
            if (v.isActive) return@forEach
            if (k.room.quality=="model already deleted") return@forEach
            scope.launch {
//                println("loop launch ${k.room.name}")
                try {
                    if (v.testAndConfigure()) {
                        v.start()
                        v.stop()
                    }
                }catch(ex: RenameException) {
                    add(k.room.copy(name = ex.newName),k.listen)
                    listUpdate(this@Scheduler)
                }catch (e: DeletedException){
                    deactivate(e.name)
                    k.room.quality = "model already deleted"
                    listUpdate(this@Scheduler)
                }
            }
        }
    }

    suspend fun add(room: Room, listen: Boolean) {
        opLock.withLock {
            if (sessions.containsKey(State(room, listen))) {
                return
            }
            sessions[State(room, listen)] = Session(room, dest,tmp)
        }
    }

    suspend fun remove(roomName: String) {
        opLock.withLock {
            val found = sessions.filter { it.key.room.name == roomName }.entries.singleOrNull()
            if (found != null) {
                found.value.stop()
                sessions.remove(found.key)
            }
        }
    }

    suspend fun deactivate(roomName: String) {
        val entry = sessions.filterKeys { it.room.name == roomName }.entries.singleOrNull()
        if (entry == null) {
            return
        }
        opLock.withLock {
            if (entry.value.isActive) {
                scope.launch { entry.value.stop() }
            }
            entry.key.listen = false
        }
    }

    suspend fun stopRecorder(roomName: String){
        val entry = sessions.filterKeys { it.room.name == roomName }.entries.singleOrNull()
        if (entry == null) {
            return
        }
        opLock.withLock {
            if (entry.value.isActive){
                entry.value.stop()
            }
        }
    }

    suspend fun active(roomName: String) {
        val entry = sessions.filterKeys { it.room.name == roomName }.entries.singleOrNull()
        if (entry == null) {
            return
        }
        opLock.withLock {
            entry.key.listen = true
        }

    }
}