package com.miaiglasses.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `createBond()` 节流。
 *
 * `isAvailable()` 每 500 ms 被问一次，而 `createBond()` 会弹系统配对框 ——
 * 不节流就是每 500 ms 弹一次，用户根本没机会点。
 */
class BondRetryTest {

    @Test
    fun `首次总是放行`() {
        val r = BondRetry(intervalMs = 10_000)
        assertTrue(r.shouldRetry(nowMs = 0), "第一次必须放行")
    }

    @Test
    fun `间隔内拒绝`() {
        val r = BondRetry(intervalMs = 10_000)
        assertTrue(r.shouldRetry(nowMs = 1_000))
        assertFalse(r.shouldRetry(nowMs = 1_500), "500 ms 后再问不能重弹")
        assertFalse(r.shouldRetry(nowMs = 10_999))
    }

    @Test
    fun `到点后放行`() {
        val r = BondRetry(intervalMs = 10_000)
        assertTrue(r.shouldRetry(nowMs = 1_000))
        assertTrue(r.shouldRetry(nowMs = 11_000), "满 10 s 应再次尝试")
    }

    @Test
    fun `reset 后立刻放行`() {
        val r = BondRetry(intervalMs = 10_000)
        assertTrue(r.shouldRetry(nowMs = 1_000))
        assertFalse(r.shouldRetry(nowMs = 2_000))
        r.reset()
        assertTrue(r.shouldRetry(nowMs = 2_001), "观测到配对丢失后应立刻重配对")
    }
}
