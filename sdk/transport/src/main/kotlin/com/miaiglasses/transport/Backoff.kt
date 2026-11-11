package com.miaiglasses.transport

import com.miaiglasses.core.config.GlassesOptions

/**
 * 重连退避：指数增长 + **上限 + 抖动**。
 *
 * 抖动是必需的 —— 没有它，同一台手机上多个会话（或重连风暴）会整齐地同时重试，
 * 把一次瞬时故障放大成持续的建连失败。
 *
 * `random` 注入是为了让单测确定化（默认 `Math.random()`）。
 */
class Backoff(
    private val initialMs: Long = GlassesOptions.BACKOFF_INITIAL_MS,
    private val maxMs: Long = GlassesOptions.BACKOFF_MAX_MS,
    private val factor: Double = GlassesOptions.BACKOFF_FACTOR,
    private val jitterRatio: Double = GlassesOptions.BACKOFF_JITTER_RATIO,
    private val random: () -> Double = { Math.random() },
) {
    private var attempt = 0

    /** 第 n 次重试的等待时长（毫秒）。调用即推进计数器。 */
    fun nextDelayMs(): Long {
        val base = (initialMs * Math.pow(factor, attempt.toDouble())).toLong().coerceAtMost(maxMs)
        attempt++
        val jitter = (base * jitterRatio * (random() * 2.0 - 1.0)).toLong()
        return (base + jitter).coerceIn(0, maxMs)
    }

    /** 建连成功后必须调用，否则下一次断线会直接跳到很长的等待。 */
    fun reset() {
        attempt = 0
    }
}
