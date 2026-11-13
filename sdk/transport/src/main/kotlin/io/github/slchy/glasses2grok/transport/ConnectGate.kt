package io.github.slchy.glasses2grok.transport

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 建连与看门狗之间的**窄竞态裁决**。
 *
 * `BluetoothSocket.connect()` 没有超时参数，只能靠另一个线程到点 `close()` 把它打断。
 * 于是存在一个窗口：超时那一刻 `connect()` 恰好成功 —— 看门狗仍会 `close()`，
 * 刚建好的链路立刻断，上层看到一次莫名其妙的失败。
 *
 * 这里把「谁先到」变成一次 CAS，**只可能有一方赢**：
 * - 看门狗赢 ⇒ 建连方拿到 `false`，必须自己抛超时（socket 已被关）；
 * - 建连赢 ⇒ 看门狗拿到 `false`，什么也不做（不得关闭可用链路）。
 *
 * 抽成独立类是为了能在纯 JVM 单测里覆盖这个顺序，而不必起真蓝牙。
 */
internal class ConnectGate {

    private val settled = AtomicBoolean(true)

    /** 看门狗或建连方是否已判定超时（供异常路径判因）。 */
    @Volatile
    var timedOut: Boolean = false
        private set

    /** 开始一次建连。必须与 [end] 配对。 */
    fun begin() {
        timedOut = false
        settled.set(false)
    }

    /** 看门狗到点：`true` 表示应由它关闭 socket，`false` 表示建连已成功、别动。 */
    fun onDeadline(): Boolean {
        if (!settled.compareAndSet(false, true)) return false
        timedOut = true
        return true
    }

    /** 建连返回：`true` 表示来得及，`false` 表示看门狗已经判定超时。 */
    fun onConnected(): Boolean = settled.compareAndSet(false, true)

    /** 收尾（成功、失败、异常都要调）。 */
    fun end() {
        settled.set(true)
    }
}
