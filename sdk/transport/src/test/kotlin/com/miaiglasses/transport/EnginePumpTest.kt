package com.miaiglasses.transport

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnginePumpTest {

    /** 把引擎线程停在任务里，之后的入队就是确定的（不会与消费竞争）。 */
    private fun parkedPump(
        onBytes: (ByteArray) -> Unit,
        tickMs: Long = 1_000,
        rxCapacityBytes: Int = 256 * 1024,
    ): Triple<EnginePump, CountDownLatch, CountDownLatch> {
        val gate = CountDownLatch(1)
        val parked = CountDownLatch(1)
        val pump = EnginePump(
            onBytes = onBytes,
            onTick = {},
            onFailure = { throw AssertionError(it) },
            tickMs = tickMs,
            rxCapacityBytes = rxCapacityBytes,
        )
        pump.start()
        pump.post {
            parked.countDown()
            gate.await(2, TimeUnit.SECONDS)
        }
        assertTrue(parked.await(2, TimeUnit.SECONDS), "任务没有在引擎线程上启动")
        return Triple(pump, gate, parked)
    }

    @Test
    fun `call 超时后排队的任务必须作废`() {
        // 回归护栏：超时只是「调用方不等了」，那条任务还排在队列里，稍后照样跑。
        // supervisor 此时正在关链路，跑起来就是往已关闭的链路上写。
        val gate = CountDownLatch(1)
        val parked = CountDownLatch(1)
        val pump = EnginePump(onBytes = {}, onTick = {}, onFailure = { throw AssertionError(it) }, tickMs = 1_000)
        pump.start()
        pump.post {
            parked.countDown()
            gate.await(3, TimeUnit.SECONDS)
        }
        assertTrue(parked.await(2, TimeUnit.SECONDS), "引擎线程没被占住")

        val ran = java.util.concurrent.atomic.AtomicBoolean(false)
        assertEquals(null, pump.call(80) { ran.set(true); 42 }, "引擎被占住时 call 必须超时")

        gate.countDown() // 放开引擎线程，被作废的那条会在这里被消费
        Thread.sleep(300)
        pump.stop(timeoutMs = 1_000)
        assertTrue(!ran.get(), "超时作废的任务不得再执行")
    }

    @Test
    fun `任务先于同一轮的 RX 字节执行`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        val (pump, gate, _) = parkedPump(onBytes = {
            order += "bytes"
            done.countDown()
        })

        // 引擎线程正停在任务里：此刻入队的字节与任务都会留到下一轮
        pump.submitRx(byteArrayOf(1, 2, 3))
        pump.post { order += "task" }
        gate.countDown()

        assertTrue(done.await(2, TimeUnit.SECONDS), "RX 字节没有被处理")
        pump.stop(timeoutMs = 1_000)
        assertEquals(listOf("task", "bytes"), order)
    }

    @Test
    fun `RX 超容量丢最旧并计数`() {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val (pump, gate, _) = parkedPump(
            onBytes = { seen += it.joinToString(",") },
            rxCapacityBytes = 8,
        )

        pump.submitRx(byteArrayOf(1, 1, 1, 1))
        pump.submitRx(byteArrayOf(2, 2, 2, 2))
        pump.submitRx(byteArrayOf(3, 3, 3, 3)) // 挤掉最早那块

        assertEquals(4L, pump.droppedRxBytes)
        assertEquals(1L, pump.droppedRxChunks)

        gate.countDown()
        // stop() 会直接让线程退出，不再消费积压 —— 所以先等它把两块都交给 onBytes
        val deadline = System.currentTimeMillis() + 2_000
        while (seen.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        pump.stop(timeoutMs = 2_000)
        assertEquals(listOf("2,2,2,2", "3,3,3,3"), seen)
    }

    @Test
    fun `单块超过总容量时整块丢弃`() {
        val (pump, gate, _) = parkedPump(onBytes = { throw AssertionError("不该被调用") }, rxCapacityBytes = 4)
        pump.submitRx(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(5L, pump.droppedRxBytes)
        assertEquals(1L, pump.droppedRxChunks)
        gate.countDown()
        pump.stop(timeoutMs = 1_000)
    }

    @Test
    fun `stop 在引擎线程上执行 beforeExit`() {
        val pump = EnginePump(
            onBytes = {},
            onTick = {},
            onFailure = { throw AssertionError(it) },
            tickMs = 1_000,
        )
        pump.start()
        var ranOn = ""
        pump.stop(timeoutMs = 2_000) { ranOn = Thread.currentThread().name }
        assertTrue(ranOn.endsWith("-engine"), "beforeExit 应跑在引擎线程，实际: $ranOn")
    }

    @Test
    fun `call 取回结果且充当屏障`() {
        val pump = EnginePump(
            onBytes = {},
            onTick = {},
            onFailure = { throw AssertionError(it) },
            tickMs = 1_000,
        )
        pump.start()
        var flag = false
        pump.post { flag = true }
        // 没有屏障的话，这里可能在引擎线程跑任务之前就读到 false
        val seen = pump.call(2_000) { flag }
        pump.stop(timeoutMs = 1_000)
        assertEquals(true, seen, "call 返回时此前投递的任务必须已执行完")
    }

    @Test
    fun `call 超时返回 null 且不会把调用方拖死`() {
        val gate = CountDownLatch(1)
        val parked = CountDownLatch(1)
        val pump = EnginePump(onBytes = {}, onTick = {}, onFailure = { throw AssertionError(it) }, tickMs = 1_000)
        pump.start()
        pump.post {
            parked.countDown()
            gate.await(3, TimeUnit.SECONDS)
        }
        assertTrue(parked.await(2, TimeUnit.SECONDS))

        val t0 = System.currentTimeMillis()
        val v = pump.call(100) { 42 }
        val elapsed = System.currentTimeMillis() - t0

        gate.countDown()
        pump.stop(timeoutMs = 1_000)
        assertEquals(null, v, "引擎被占住时 call 必须超时返回 null")
        assertTrue(elapsed in 60..1_000, "超时应接近 100ms，实际 ${elapsed}ms")
    }

    @Test
    fun `换世代丢弃已入队的残留字节`() {
        val seen = Collections.synchronizedList(mutableListOf<Int>())
        val (pump, gate, _) = parkedPump(onBytes = { seen += it.size })

        val old = pump.currentRxEpoch()
        pump.submitRx(byteArrayOf(1, 2, 3), old)
        val next = pump.beginRxEpoch()

        assertEquals(old + 1, next, "世代号必须递增")
        assertEquals(1L, pump.staleRxChunks, "队列里旧世代的字节要计入 stale")

        gate.countDown()
        Thread.sleep(200)
        pump.stop(timeoutMs = 1_000)
        assertEquals(emptyList(), seen.toList(), "上一个 socket 的残留字节不得喂给引擎")
    }

    @Test
    fun `旧世代的提交直接丢弃并计数`() {
        val (pump, gate, _) = parkedPump(onBytes = { throw AssertionError("旧世代字节不该被处理") })

        val old = pump.currentRxEpoch()
        pump.beginRxEpoch()
        pump.submitRx(byteArrayOf(9, 9), old)

        assertEquals(1L, pump.staleRxChunks)
        gate.countDown()
        pump.stop(timeoutMs = 1_000)
    }

    @Test
    fun `tick 按周期触发`() {
        val ticks = CountDownLatch(3)
        val pump = EnginePump(
            onBytes = {},
            onTick = { ticks.countDown() },
            onFailure = { throw AssertionError(it) },
            tickMs = 20,
        )
        pump.start()
        assertTrue(ticks.await(2, TimeUnit.SECONDS), "tick 没有按周期触发")
        pump.stop(timeoutMs = 1_000)
    }
}
