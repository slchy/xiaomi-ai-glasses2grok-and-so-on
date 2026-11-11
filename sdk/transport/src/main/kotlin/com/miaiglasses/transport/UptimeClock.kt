package com.miaiglasses.transport

import com.miaiglasses.core.sar.Clock

/**
 * 单调时钟。**不用 `System.currentTimeMillis()`** —— 它会被用户改时间/NTP 校正，
 * 一旦回拨，SAR 的 10 秒超时会变成「永远不到」或「立刻到期」。
 */
object UptimeClock : Clock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000
}
