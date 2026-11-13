package io.github.slchy.glasses2grok.transport

import io.github.slchy.glasses2grok.core.sar.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 引擎线程：**独占** [io.github.slchy.glasses2grok.core.session.SessionEngine]。
 *
 * 一个线程同时承担三件事（与参考实现同构，见 `参考协议`）：
 * 状态机、同步写、定时器（[tickMs] 周期）。因为一切都在这一个线程上，
 * 引擎内部**全程无锁**。
 *
 * RX 线程只做 [submitRx]（入队），**不解析**：解析放在 RX 会让慢解析直接丢字节。
 * 队列有字节上限，满了丢**最旧**并计数 —— 让「谁慢谁丢」可观测，而不是静默增长。
 *
 * 处理次序固定为 **任务 → RX 字节 → tick**：宿主 `post` 的 `onConnected` 一定先于
 * 同一轮到达的字节执行，否则首批帧会撞上「还没开始握手」。
 */
class EnginePump(
    private val onBytes: (ByteArray) -> Unit,
    private val onTick: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val name: String = "glasses-engine",
    private val tickMs: Long = 50,
    private val rxCapacityBytes: Int = 256 * 1024,
    private val clock: Clock = UptimeClock,
) {
    private val lock = Object()
    private val tasks = ArrayDeque<() -> Unit>()
    private val rx = ArrayDeque<ByteArray>()
    private var rxBytes = 0
    private var thread: Thread? = null
    private var shutdownBlock: (() -> Unit)? = null
    private var finished = false

    @Volatile
    private var running = false

    /** 因为队列满被丢掉的字节数 / 块数（诊断用）。 */
    @Volatile
    var droppedRxBytes: Long = 0L
        private set

    @Volatile
    var droppedRxChunks: Long = 0L
        private set

    /** 因**世代不符**（上一轮 socket 的残留）被丢弃的块数。与队列满丢弃分开计数。 */
    @Volatile
    var staleRxChunks: Long = 0L
        private set

    val isAlive: Boolean get() = thread?.isAlive == true

    /** 泵是否还在接收任务。用于把 `call` 返回的 null 区分成「已停」与「写卡住」。 */
    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, name).also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * RX 字节的世代号。换 socket 时必须作废旧世代在队列里的残留字节 ——
     * 否则上一轮 socket 的尾巴会被当成新会话的帧喂进握手（跨 socket 污染）。
     */
    @Volatile
    private var rxEpoch: Long = 0L

    /** 当前 RX 世代。RX 线程启动时记下它，之后 [submitRx] 时带上。 */
    fun currentRxEpoch(): Long = rxEpoch

    /**
     * 开新世代：递增世代号并**丢弃已入队的 RX 字节**（它们属于上一个 socket）。
     * 返回新的世代号，由调用方交给新的读线程。
     */
    fun beginRxEpoch(): Long = synchronized(lock) {
        rxEpoch++
        if (rx.isNotEmpty()) {
            staleRxChunks += rx.size
            droppedRxBytes += rxBytes
            droppedRxChunks += rx.size
            rx.clear()
            rxBytes = 0
        }
        rxEpoch
    }

    /**
     * RX 线程调用：只入队。
     *
     * @param epoch 读线程启动时从 [currentRxEpoch] 取到的世代号；与当前世代不符的
     *   残留字节直接丢弃（计入 [staleRxChunks]）。
     */
    fun submitRx(chunk: ByteArray, epoch: Long = rxEpoch) {
        if (chunk.isEmpty()) return
        synchronized(lock) {
            if (!running) return
            if (epoch != rxEpoch) {
                staleRxChunks++
                return
            }
            while (rxBytes + chunk.size > rxCapacityBytes && rx.isNotEmpty()) {
                val evicted = rx.removeFirst()
                rxBytes -= evicted.size
                droppedRxBytes += evicted.size
                droppedRxChunks++
            }
            // 单块就超过容量：整块丢弃（不能把一帧拆成两半交给解析器）
            if (chunk.size > rxCapacityBytes) {
                droppedRxBytes += chunk.size
                droppedRxChunks++
                lock.notifyAll()
                return
            }
            rx.addLast(chunk)
            rxBytes += chunk.size
            lock.notifyAll()
        }
    }

    /** 当前线程是不是引擎线程。供跨层调用做「别在自己身上阻塞」的前置检查。 */
    val isOnEngineThread: Boolean get() = Thread.currentThread() === thread

    /** 任意线程调用：把一段逻辑搬到引擎线程上执行。返回是否真的入队（已停止 ⇒ false）。 */
    fun post(task: () -> Unit): Boolean {
        synchronized(lock) {
            if (!running) return false
            tasks.addLast(task)
            lock.notifyAll()
        }
        return true
    }

    /**
     * 在引擎线程上**同步**执行并取回结果，同时充当**屏障**：返回时，此前 [post]
     * 投递的任务都已在引擎线程上跑完。
     *
     * 双层状态机的跨层调用靠它拿回执 —— 「投递出去就当成功」会让被调方的静默
     * 拒绝变成永久挂起（真机 bug 的根因）。
     *
     * 返回 `null` 表示引擎没在跑或超时。超时**不**中断引擎线程：调用方应把它当作
     * 「引擎被 socket 写卡住」，主动关链路把它逼出来。
     */
    fun <T : Any> call(timeoutMs: Long, block: () -> T): T? {
        // 已经在引擎线程上：直接执行，避免自锁
        if (Thread.currentThread() === thread) {
            return try {
                block()
            } catch (e: Throwable) {
                onFailure(e)
                null
            }
        }
        val done = CountDownLatch(1)
        val result = AtomicReference<T?>()
        val error = AtomicReference<Throwable?>()
        // 超时后作废这条任务：它还排在队列里，稍后照样会跑 —— 而那时调用方
        // （supervisor）多半已经在关链路了，跑起来就是往已关闭的链路上写，
        // 状态在 HANDSHAKING / RECONNECTING 之间抖。
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val queued = post {
            if (cancelled.get()) {
                done.countDown()
                return@post
            }
            try {
                result.set(block())
            } catch (e: Throwable) {
                error.set(e)
            } finally {
                done.countDown()
            }
        }
        if (!queued) return null
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            cancelled.set(true)
            return null
        }
        error.get()?.let { onFailure(it) }
        return result.get()
    }

    /**
     * 停止。`beforeExit` 会在**引擎线程**上、在退出前执行一次
     * （用于让 `SessionEngine.close()` 仍处于单线程语境）。
     */
    fun stop(timeoutMs: Long = 2_000, beforeExit: (() -> Unit)? = null) {
        val t = thread
        val alreadyDone = synchronized(lock) {
            if (finished) {
                true
            } else {
                running = false
                if (shutdownBlock == null) shutdownBlock = beforeExit
                lock.notifyAll()
                false
            }
        }
        if (alreadyDone) return
        t?.join(timeoutMs)
        // 线程没能按时退出（或根本没启动）：就地收尾，保证 close 语义不丢
        val leftover = synchronized(lock) { shutdownBlock.also { shutdownBlock = null } }
        if (leftover != null) {
            try {
                leftover()
            } catch (e: Throwable) {
                onFailure(e)
            }
        }
    }

    private fun loop() {
        var lastTick = clock.nowMs()
        try {
            while (true) {
                val snapshot = awaitBatch(lastTick) ?: break

                for (task in snapshot.tasks) {
                    try {
                        task()
                    } catch (e: Throwable) {
                        onFailure(e)
                    }
                }
                for (chunk in snapshot.rx) {
                    try {
                        onBytes(chunk)
                    } catch (e: Throwable) {
                        onFailure(e)
                        return
                    }
                }
                val now = clock.nowMs()
                if (now - lastTick >= tickMs) {
                    lastTick = now
                    try {
                        onTick()
                    } catch (e: Throwable) {
                        onFailure(e)
                        return
                    }
                }
            }
        } finally {
            val block = synchronized(lock) { shutdownBlock.also { shutdownBlock = null; finished = true } }
            if (block != null) {
                try {
                    block()
                } catch (e: Throwable) {
                    onFailure(e)
                }
            }
            running = false
        }
    }

    private class Batch(val tasks: List<() -> Unit>, val rx: List<ByteArray>)

    /** 取走当前积压的一次快照并重置计数；返回 null 表示线程该退出了。 */
    private fun awaitBatch(lastTick: Long): Batch? = synchronized(lock) {
        while (running && tasks.isEmpty() && rx.isEmpty()) {
            val untilTick = lastTick + tickMs - clock.nowMs()
            if (untilTick <= 0) break
            try {
                lock.wait(untilTick)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        if (!running) return null
        val batch = Batch(tasks.toList(), rx.toList())
        tasks.clear()
        rx.clear()
        rxBytes = 0
        batch
    }
}
