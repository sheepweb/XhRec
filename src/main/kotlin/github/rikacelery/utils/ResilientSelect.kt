package github.rikacelery.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * 并行执行多个协程任务，在任意一个任务成功完成时返回结果。
 * 如果所有任务都失败，则抛出一个包含所有异常的组合异常。
 *
 * @param block 包含多个待执行任务的构建器块
 * @return 任意一个成功完成的任务的结果
 * @throws CombinedException 当所有任务都失败时抛出
 */
suspend fun <R> resilientSelect(block: ResilientSelectBuilder<R>.() -> Unit): R {
    val builder = ResilientSelectBuilder<R>()
    block(builder)

    if (builder.actions.isEmpty()) {
        throw IllegalArgumentException("At least one action must be provided")
    }

    return withContext(Dispatchers.IO) {
        val deferredResults = mutableListOf<Deferred<Result<R>>>()

        // 启动所有子协程
        builder.actions.forEach { action ->
            val deferred = async(CoroutineName("resilient-select-worker")) {
                try {
                    Result.success(action())
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }
            deferredResults.add(deferred)
        }

        // 创建一个通道来接收成功的结果
        val channel = Channel<R>(Channel.UNLIMITED)

        // 为每个deferred启动一个协程来监听结果
        val listeners = mutableListOf<Job>()
        deferredResults.forEachIndexed { index, deferred ->
            val listener = launch(CoroutineName("listener-$index")) {
                val result = deferred.await()
                if (result.isSuccess) {
                    channel.send(result.getOrThrow())
                }
            }
            listeners.add(listener)
        }

        // 等待第一个成功的结果
        val firstSuccess = channel.receive()

        // 取消所有监听协程和剩余的deferred
        listeners.forEach { it.cancel() }
        deferredResults.forEach { if (!it.isCompleted) it.cancel() }
        channel.close()

        firstSuccess
    }
}

/**
 * 用于构建resilientSelect函数的多个操作的构建器类
 */
class ResilientSelectBuilder<R> {
    internal val actions = mutableListOf<suspend () -> R>()

    /**
     * 添加一个要执行的操作
     */
    fun on(block: suspend () -> R) {
        actions.add(block)
    }
}

/**
 * 当所有子协程都失败时抛出的组合异常
 */
class CombinedException(val exceptions: List<Throwable>) :
    RuntimeException("All select clauses failed", exceptions.firstOrNull()) {

    override val message: String?
        get() = "All ${exceptions.size} select clauses failed with exceptions: " + exceptions.joinToString { it::class.simpleName + ": " + it.message }
}