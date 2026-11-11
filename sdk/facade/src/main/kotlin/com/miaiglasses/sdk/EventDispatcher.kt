package com.miaiglasses.sdk

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.miaiglasses.core.config.GlassesOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 事件派发。职责是**换线程 + 背压计数**：不做过滤，队列满了先丢非关键事件并计数，
 * 而不是让引擎线程被宿主拖住（那会把 ACK 也拖超时）。
 *
 * `CallingThread` 模式下没有队列：回调就在调用线程上跑，语义与以前一致。
 */
internal class EventDispatcher(
    mode: CallbackDispatcher,
    capacity: Int = GlassesOptions.CALLBACK_QUEUE_CAPACITY,
) {

    private val thread: HandlerThread? =
        if (mode is CallbackDispatcher.SingleThread) {
            HandlerThread(mode.name).apply { start() }
        } else {
            null
        }

    private val handler: Handler? = when (mode) {
        CallbackDispatcher.MainThread -> Handler(Looper.getMainLooper())
        is CallbackDispatcher.SingleThread -> Handler(thread!!.looper)
        CallbackDispatcher.CallingThread -> null
    }

    private val queue = CallbackQueue(capacity)
    private val draining = AtomicBoolean(false)

    /** 因回调队列满而没有派发的事件数。 */
    val dropped: Long get() = queue.dropped

    fun dispatch(block: () -> Unit) = dispatch(block, critical = false)

    /**
     * @param critical 终态事件（`CLOSED`）不参与丢弃：腾位置时淘汰最老的非关键事件。
     */
    fun dispatch(block: () -> Unit, critical: Boolean) {
        val h = handler
        if (h == null) {
            block()
            return
        }
        queue.offer(block, critical)
        scheduleDrain()
    }

    private fun scheduleDrain() {
        val h = handler ?: return
        if (draining.compareAndSet(false, true)) {
            h.post { drain() }
        }
    }

    /** 一次最多派发 [BATCH] 条，剩下的重新排队 —— 不把回调线程的 Looper 长时间占死。 */
    private fun drain() {
        var n = 0
        while (n < BATCH) {
            val next = queue.poll() ?: break
            next()
            n++
        }
        draining.set(false)
        if (queue.size > 0) scheduleDrain()
    }

    /**
     * 停掉专用线程。
     *
     * 早先这里从不 quit：每个用 `CallbackDispatcher.SingleThread` 的 session
     * 都会永久留一个 `HandlerThread`（它的 Looper 持有线程栈，session 关了也不回收）。
     *
     * 用 `quitSafely()` 而不是 `quit()`：已经排进队列的 `CLOSED` 事件仍要送达，
     * 迟到的消息才会被丢弃。`MainThread` / `CallingThread` 下无事可做。
     */
    fun shutdown() {
        thread?.quitSafely()
    }

    private companion object {
        const val BATCH = 32
    }
}
