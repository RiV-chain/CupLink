package org.rivchain.cuplink.util

import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class ExecutorManager {
    @Volatile private var executor: ThreadPoolExecutor = Executors.newCachedThreadPool() as ThreadPoolExecutor

    fun getExecutor(): ThreadPoolExecutor {
        if (executor.isShutdown || executor.isTerminated) {
            synchronized(this) {
                if (executor.isShutdown || executor.isTerminated) {
                    executor = Executors.newCachedThreadPool() as ThreadPoolExecutor
                    println("Executor has been recreated")
                }
            }
        }
        return executor
    }

    fun shutdownExecutor() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(4L, TimeUnit.SECONDS)) {
                executor.shutdownNow()
                if (!executor.awaitTermination(4L, TimeUnit.SECONDS)) {
                    println("Executor did not terminate")
                }
            }
        } catch (ie: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}