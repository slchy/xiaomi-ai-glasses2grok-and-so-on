package com.miaiglasses.transport

import com.miaiglasses.core.session.GlassesEvent
import com.miaiglasses.core.session.SessionState
import java.io.IOException
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 传输层的行为测试：**真线程** + 假 link（内存双端）。
 *
 * 不测协议正确性（那是 `:core` 的事），只测「线程/生命周期/重连」这些
 * 只有真跑线程才会暴露的问题。
 */
class SessionDriverTest {

    private class FakeLink(
        private val failConnectWith: Exception? = null,
        private val hangConnect: Boolean = false,
        private val avail: java.util.concurrent.atomic.AtomicBoolean? = null,
        /** 建连成功后立刻「对端消失」：模拟 socket 开了但马上断。 */
        private val dropRightAfterConnect: Boolean = false,
        /** 每次 read 前卡这么久（模拟卡死的蓝牙读）—— 用来验证关闭路径不被它拖住。 */
        private val readDelayMs: Long = 0,
    ) : SppLink {
        val written = Collections.synchronizedList(mutableListOf<ByteArray>())
        private val inbound = LinkedBlockingQueue<ByteArray>()

        /** 模拟「退不出来的建连」：故意不响应 close()。 */
        private val never = java.util.concurrent.CountDownLatch(1)

        /** 传输是否可用（门禁用）。由 Rig 共享，测试可随时翻转。 */
        override fun isAvailable(): Boolean = avail?.get() ?: true

        /** 不可用原因，模拟 `AndroidSppLink` 缺权限时的诊断信息。 */
        override val unavailableReason: String? =
            if (avail != null) "缺少 BLUETOOTH_CONNECT 权限" else null

        @Volatile
        var closed = false

        override fun connect(timeoutMs: Int) {
            failConnectWith?.let { throw it }
            if (hangConnect) never.await(20, TimeUnit.SECONDS)
            if (dropRightAfterConnect) closed = true // read() 立刻返回 -1
        }

        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            if (readDelayMs > 0) {
                try {
                    Thread.sleep(readDelayMs) // 故意不看 closed：真机上 socket 读也可能卡住
                } catch (e: InterruptedException) {
                    return -1
                }
            }
            while (!closed) {
                val chunk = try {
                    inbound.poll(20, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    return -1
                } ?: continue
                val n = minOf(len, chunk.size)
                chunk.copyInto(buf, off, 0, n)
                if (n < chunk.size) inbound.add(chunk.copyOfRange(n, chunk.size))
                return n
            }
            return -1
        }

        override fun write(bytes: ByteArray) {
            if (closed) throw IOException("closed")
            written += bytes.copyOf()
        }

        override fun close() {
            closed = true
        }

        fun push(bytes: ByteArray) {
            inbound.add(bytes)
        }
    }

    private class Rig(
        maxAttempts: Int = 3,
        private val connectFailure: (Int) -> Exception? = { null },
        hangConnect: Boolean = false,
        dropRightAfterConnect: Boolean = false,
        readDelayMs: Long = 0,
    ) {
        val events = Collections.synchronizedList(mutableListOf<GlassesEvent>())
        val links = Collections.synchronizedList(mutableListOf<FakeLink>())

        /** 传输可用性共享开关（`isAvailable` 门禁用）。 */
        val avail = java.util.concurrent.atomic.AtomicBoolean(true)
        private val created = AtomicInteger(0)

        val driver = SessionDriver(
            newLink = {
                val n = created.getAndIncrement()
                val link = FakeLink(connectFailure(n), hangConnect, avail, dropRightAfterConnect, readDelayMs)
                links += link
                link
            },
            token16 = ByteArray(16) { 0x11 },
            model = "test-model",
            locale = "zh-CN",
            emit = { events += it },
            tickMs = 10,
            connectTimeoutMs = 100,
            suspendPollMs = 20,
            maxConnectAttempts = maxAttempts,
            backoff = Backoff(initialMs = 10, maxMs = 10, jitterRatio = 0.0),
        )

        fun awaitState(target: SessionState, timeoutMs: Long = 3_000) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (driver.state == target) return
                Thread.sleep(5)
            }
            throw AssertionError("等不到 $target，实际 ${driver.state}，事件=${events.map { it.toString() }}")
        }

        fun awaitTrue(what: String, timeoutMs: Long = 3_000, cond: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (cond()) return
                Thread.sleep(5)
            }
            throw AssertionError("等待超时：$what")
        }
    }

    @Test
    fun `start 后建连并发出首个协商帧`() {
        val rig = Rig()
        rig.driver.start()
        try {
            rig.awaitTrue("应有协商帧写出") { rig.links.firstOrNull()?.written?.isNotEmpty() == true }
            val first = rig.links[0].written[0]
            // A5 A5 帧头，第一帧必须是 CMD（L1 协商）
            assertEquals(0xA5.toByte(), first[0])
            assertEquals(0xA5.toByte(), first[1])
            assertTrue(rig.events.any { it is GlassesEvent.StateChanged && it.state == SessionState.HANDSHAKING })
        } finally {
            rig.driver.close()
        }
    }

    @Test
    fun `建连永久阻塞时监督线程仍能给出终态而不是卡死`() {
        // 真机回归（2026-11-10）：关蓝牙再开，BluetoothSocket.connect() 卡住且
        // close() 打不断它，结果 supervisor 跟着卡死 —— 不重试、不报终态，
        // 宿主永远停在 CONNECTING。这里用「永不返回的 connect」把它固定下来。
        val rig = Rig(maxAttempts = 1, hangConnect = true)
        val t0 = System.currentTimeMillis()
        rig.driver.start()

        rig.awaitState(SessionState.FAILED, timeoutMs = 8_000)
        val cost = System.currentTimeMillis() - t0
        assertEquals(SessionState.FAILED, rig.driver.state, "必须给出终态，不能停在 CONNECTING")
        assertTrue(cost < 8_000, "应在有界时间内放弃，实际 ${cost}ms")
        rig.driver.close()
    }

    @Test
    fun `传输不可用时不消耗重试预算 - 恢复后仍能建连`() {
        // 真机回归（2026-11-10）：关蓝牙期间硬试 4 次把预算烧光，蓝牙恢复后永不重试。
        // 正确行为：不可用 ⇒ 挂起（不计预算、不报错终态）；恢复后照常建连。
        val rig = Rig(maxAttempts = 2)
        rig.avail.set(false) // 模拟蓝牙关闭
        rig.driver.start()

        // 让它挂起若干轮（远超 maxAttempts=2 的次数）
        Thread.sleep(600)
        val before = rig.links.size
        assertTrue(before > 2, "挂起期间会反复 newLink，但不应计入 failures；实际建了 $before 个")
        assertTrue(
            rig.driver.state != SessionState.FAILED,
            "传输不可用不能判死，实际 ${rig.driver.state}",
        )

        rig.avail.set(true) // 蓝牙恢复
        rig.awaitState(SessionState.HANDSHAKING, timeoutMs = 5_000)
        assertEquals(
            SessionState.HANDSHAKING,
            rig.driver.state,
            "恢复后必须能重新建连（假 link 不做握手，到 HANDSHAKING 即为建连成功）",
        )
        rig.driver.close()
    }

    @Test
    fun `建连连续失败达上限后转 FAILED 且不再重试`() {
        val rig = Rig(maxAttempts = 3, connectFailure = { IOException("no route") })
        rig.driver.start()
        try {
            rig.awaitState(SessionState.FAILED)
            assertEquals(3, rig.links.size, "重试次数应等于上限")
            assertTrue(rig.events.filterIsInstance<GlassesEvent.Error>().any { it.message.contains("连接失败") })
        } finally {
            rig.driver.close()
        }
    }

    @Test
    fun `链路断开后重连并重做协商`() {
        val rig = Rig(maxAttempts = 5)
        rig.driver.start()
        try {
            rig.awaitTrue("首次建连") { rig.links.size >= 1 && rig.links[0].written.isNotEmpty() }
            rig.links[0].close() // 模拟对端断开 ⇒ read 返回 -1

            rig.awaitTrue("应新建链路重连") { rig.links.size >= 2 }
            rig.awaitTrue("重连后应重新发起协商") { rig.links[1].written.isNotEmpty() }
            assertTrue(
                rig.events.any { it is GlassesEvent.StateChanged && it.state == SessionState.RECONNECTING },
                "断链必须显式进入 RECONNECTING",
            )
        } finally {
            rig.driver.close()
        }
    }

    @Test
    fun `建连成功但立刻断链时达上限必转 FAILED 而不是静默停在 RECONNECTING`() {
        // 这一条以前靠 `lastState` 的滞后快照判定：supervisor 可能读不到 FAILED 就
        // 退出，宿主永远停在 RECONNECTING —— 和最初那个真机 bug 同一表现，只是没日志。
        val rig = Rig(maxAttempts = 2, dropRightAfterConnect = true)
        rig.driver.start()
        try {
            rig.awaitState(SessionState.FAILED, timeoutMs = 8_000)
            assertEquals(SessionState.FAILED, rig.driver.state, "监督线程退出前必须留下终态")
            assertEquals(2, rig.links.size, "断链也要计入重试预算")
        } finally {
            rig.driver.close()
        }
    }

    @Test
    fun `closeAndWait 幂等且进入 CLOSED 后不再回调`() {
        val rig = Rig()
        rig.driver.start()
        rig.awaitTrue("建连") { rig.links.firstOrNull()?.written?.isNotEmpty() == true }

        rig.driver.closeAndWait()
        assertEquals(SessionState.CLOSED, rig.driver.state, "closeAndWait 返回后应已是 CLOSED")

        val size = rig.events.size
        Thread.sleep(150)
        assertEquals(size, rig.events.size, "CLOSED 之后不应再有任何事件")

        rig.driver.closeAndWait() // 再调一次不应抛
        assertEquals(SessionState.CLOSED, rig.driver.state)
    }

    @Test
    fun `close 本身不阻塞调用线程`() {
        // 回归护栏：早先 close() 同步 join 了 300+1000+2000+1000 ms，
        // 主线程 onDestroy 调它就是 ANR 赌局。
        // 这里故意让读线程卡 800ms，同步实现必然被拖住 ≥800ms。
        val rig = Rig(readDelayMs = 800)
        rig.driver.start()
        rig.awaitTrue("建连") { rig.links.firstOrNull()?.written?.isNotEmpty() == true }

        val t0 = System.nanoTime()
        rig.driver.close()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 200, "close() 必须立即返回，实际 ${ms}ms")
        // 收尾可能已经跑完，所以只要求「不再是 READY/建连中」这种活着的状态
        assertTrue(
            rig.driver.state == SessionState.CLOSING || rig.driver.state == SessionState.CLOSED,
            "close() 之后应是 CLOSING 或 CLOSED，实际 ${rig.driver.state}",
        )

        rig.driver.closeAndWait()
        assertEquals(SessionState.CLOSED, rig.driver.state)
    }

    @Test
    fun `在引擎线程上做阻塞查询直接报错而不是白等`() {
        // 回归护栏：早先在 CallingThread 回调里调 request() 会内联跑在引擎线程上，
        // 然后 poll(3s) —— 回包没人收，必然超时且把引擎冻住 3 秒。
        val rig = Rig()
        rig.driver.start()
        rig.awaitTrue("引擎线程起来") { rig.driver.pump.isOnEngineThread.not() }

        var caught: Throwable? = null
        val done = java.util.concurrent.CountDownLatch(1)
        val posted = rig.driver.pump.post {
            try {
                rig.driver.request(2, 93, ByteArray(0))
            } catch (e: Throwable) {
                caught = e
            } finally {
                done.countDown()
            }
        }
        assertTrue(posted, "post 应被接受")
        assertTrue(done.await(3, TimeUnit.SECONDS), "请求应立刻返回而不是等 3 秒")
        assertTrue(caught is IllegalStateException, "应抛 IllegalStateException，实际 $caught")
        assertTrue(
            caught!!.message.orEmpty().contains("引擎线程"),
            "错误信息应点明引擎线程，实际 ${caught!!.message}",
        )
    }

    @Test
    fun `传输不可用时的原因要带进提示`() {
        // 回归护栏：早先 AndroidSppLink.isAvailable 用 runCatching{}.getOrDefault(false)
        // 把 SecurityException 吞成 false，supervisor 只能含糊地说「蓝牙关闭或未配对」——
        // 真实原因是没给 BLUETOOTH_CONNECT。
        val rig = Rig()
        rig.avail.set(false)
        rig.driver.start()
        rig.awaitTrue("报出原因") {
            rig.events.any {
                it is GlassesEvent.StateChanged &&
                    it.state == SessionState.RECONNECTING &&
                    it.reason.contains("缺少 BLUETOOTH_CONNECT")
            }
        }
        rig.driver.closeAndWait()
    }

    @Test
    fun `未 READY 时 sendWear 直接拒绝`() {
        val rig = Rig()
        rig.driver.start()
        try {
            // 还没握手（假 link 不会回包）⇒ 必拒
            assertTrue(!rig.driver.sendWear(2, 93, ByteArray(0)))
        } finally {
            rig.driver.close()
        }
    }
}
