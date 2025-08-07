package github.rikacelery.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException

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
        // 创建一个通道来接收成功的结果
        val channel = Channel<R>(1)
        val completionJob = Job() // 用于统一取消所有监听

        val exceptions = mutableListOf<Throwable>()
        val exceptionLock = Object()

        val listeners = mutableListOf<Job>()

        // 启动每个任务
        for ((index, action) in builder.actions.withIndex()) {
            val job = launch(CoroutineName("worker-$index") + completionJob) {
                try {
                    val result = action()
                    // 尝试发送成功结果
                    if (channel.trySend(result).isSuccess) {
                        // 发送成功后，我们不需要取消其他任务立即，但可以尽快退出
                        completionJob.cancel() // 触发其他协程取消
                    }
                } catch (e: Throwable) {
                    // 收集异常
                    synchronized(exceptionLock) {
                        exceptions.add(e)
                    }
                    // 不做任何事，等待所有完成
                }
            }
            listeners.add(job)
        }

        // 等待：要么收到成功结果，要么所有任务都结束
        val waitForAll = launch {
            // 等待所有 listener 完成（成功发送 or 抛出异常）
            listeners.joinAll()
            // 所有都完成了，但还没发送成功结果 → 说明全失败
            channel.close() // 关闭通道，防止 receive 挂起
        }

        // 尝试接收成功结果
        try {
            val success = channel.receive()
            completionJob.cancel() // 确保所有任务取消
            waitForAll.cancel()
            success
        } catch (e: ClosedReceiveChannelException) {
            // 通道被关闭且无元素 → 无成功结果
            waitForAll.join() // 确保完成监听已退出
            throw CombinedException(exceptions)
        }
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