package com.miaiglasses.sdk

import com.miaiglasses.core.config.GlassesOptions

/**
 * 宿主回调的有界队列。
 *
 * 抽成纯 Kotlin 类而不是直接写在 `EventDispatcher` 里：`Handler` 在 JVM 单测里不可用，
 * 而「满了怎么办」恰恰是**最需要被测住**的部分。
 *
 * 语义：
 * - 满了先丢**非关键**条目并计数（`dropped`），这是常态：宿主处理不过来，事件就该被丢
 * - **关键**条目（终态 `CLOSED`）不丢：腾位置时淘汰最老的**非关键**条目；全是关键条目才丢自己
 *
 * 线程安全：`offer` 来自引擎/传输线程，`poll` 来自回调线程，全部走 `synchronized`。
 */
internal class CallbackQueue(private val capacity: Int = GlassesOptions.CALLBACK_QUEUE_CAPACITY) {

    private class Entry(val block: () -> Unit, val critical: Boolean)

    private val items = ArrayList<Entry>(minOf(capacity, 64))

    /** 因队列满而**没有被派发**的条目数。宿主可据此判断自己是不是处理不过来了。 */
    var dropped: Long = 0L
        private set

    /** 累计入队成功的条目数（诊断用）。 */
    var accepted: Long = 0L
        private set

    val size: Int get() = synchronized(items) { items.size }

    /**
     * @return 是否入队成功。`false` 表示这条**被丢弃了**（已计入 [dropped]）。
     */
    fun offer(block: () -> Unit, critical: Boolean = false): Boolean = synchronized(items) {
        if (items.size >= capacity) {
            if (!critical) {
                dropped++
                return false
            }
            // 关键条目：淘汰最老的非关键条目给它让位
            val victim = items.indexOfFirst { !it.critical }
            if (victim < 0) {
                dropped++
                return false
            }
            items.removeAt(victim)
            dropped++
        }
        items.add(Entry(block, critical))
        accepted++
        return true
    }

    fun poll(): (() -> Unit)? = synchronized(items) {
        if (items.isEmpty()) null else items.removeAt(0).block
    }

    fun clear() = synchronized(items) { items.clear() }
}
