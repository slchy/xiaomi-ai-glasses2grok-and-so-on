package io.github.slchy.glasses2grok.sdk

import io.github.slchy.glasses2grok.core.session.GlassesEvent
import io.github.slchy.glasses2grok.core.session.SessionState
import io.github.slchy.glasses2grok.transport.SppLink
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 门面行为：非阻塞 open / 未就绪拒绝 / 幂等 close / token 校验 / close 后不再回调。
 *
 * 用 `CallbackDispatcher.CallingThread` 才能纯 JVM 跑 —— 主线程派发依赖 `Looper`，
 * 那是 Android 运行时的事，单测里没有。
 */
class GlassesSessionTest {

    private class FakeLink : SppLink {
        val written = Collections.synchronizedList(mutableListOf<ByteArray>())
        private val inbound = LinkedBlockingQueue<ByteArray>()

        @Volatile
        var closed = false

        override fun connect(timeoutMs: Int) = Unit

        override fun read(buf: ByteArray, off: Int, len: Int): Int {
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
    }

    private fun open(link: FakeLink, listener: GlassesListener? = null): GlassesSession = GlassesSession.openWith(
        newLink = { link },
        token16 = ByteArray(16) { 0x21 },
        options = SessionOptions(
            callbackDispatcher = CallbackDispatcher.CallingThread,
            connectTimeoutMs = 100,
            maxConnectAttempts = 2,
            tickMs = 10,
        ),
        initialListener = listener,
        // JVM 单测里 `SystemClock.elapsedRealtime()` 是桩值 0，时间不走 → 超时/退避不会触发
        clock = io.github.slchy.glasses2grok.core.sar.Clock { System.nanoTime() / 1_000_000 },
    )

    @Test
    fun `open 非阻塞 - 返回时尚未 READY`() {
        val session = open(FakeLink())
        try {
            assertTrue(session.state != SessionState.READY)
        } finally {
            session.close()
        }
    }

    @Test
    fun `未就绪时 sendWear 返回 Rejected NOT_READY`() {
        val session = open(FakeLink())
        try {
            val r = session.sendWear(2, 93, ByteArray(0))
            assertTrue(r is SendResult.Rejected, "实际: $r")
            assertEquals(SdkErrorCode.NOT_READY, (r as SendResult.Rejected).code)
        } finally {
            session.close()
        }
    }

    @Test
    fun `close 幂等且之后发送返回 CLOSED`() {
        val session = open(FakeLink())
        session.close()
        session.close()
        // close() 是异步的：要断言终态必须先等收尾，否则是在赌调度
        session.closeAndWait()

        val r = session.sendWear(2, 93, ByteArray(0))
        assertTrue(r is SendResult.Rejected)
        assertEquals(SdkErrorCode.CLOSED, (r as SendResult.Rejected).code)
        assertEquals(SessionState.CLOSED, session.state)
    }

    @Test
    fun `close 之后不再回调`() {
        val latch = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<GlassesEvent>())
        // 监听在启动前就挂上，否则 CONNECTING/HANDSHAKING 会先于 setListener 发出
        val session = open(FakeLink()) {
            events += it
            latch.countDown()
        }
        assertTrue(latch.await(3, TimeUnit.SECONDS), "至少应收到一个状态事件")

        session.closeAndWait()
        val size = events.size
        Thread.sleep(150)
        assertEquals(size, events.size, "close 后不应再有回调")
        assertTrue(events.any { it is GlassesEvent.StateChanged && it.state == SessionState.CLOSED })
    }

    @Test
    fun `token 必须是 16 字节`() {
        assertFailsWith<IllegalArgumentException> {
            GlassesSession.openWith({ FakeLink() }, ByteArray(15), SessionOptions())
        }
        assertFailsWith<IllegalArgumentException> {
            GlassesSession.openWith({ FakeLink() }, ByteArray(17), SessionOptions())
        }
    }
    @Test
    fun `裸 sendWear 会被计数（逃生口可度量）`() {
        val session = open(FakeLink())
        try {
            assertEquals(0L, session.rawSendCount)
            session.sendWear(2, 93, ByteArray(0))
            session.sendWear(2, 93, ByteArray(0))
            assertEquals(2L, session.rawSendCount, "逃生口调用必须可观测，否则无法发现有谁绕过具名能力")

            // 具名能力走的是同一条底层通道，但**不该**计入逃生口
            session.requestWearStatus()
            assertEquals(2L, session.rawSendCount)
        } finally {
            session.close()
        }
    }

    // ---------------------------------------------------------------- tokenHex

    @Test
    fun `parseTokenHex 接受 32 位 hex`() {
        val bytes = GlassesSession.parseTokenHex("00112233445566778899aabbccddeeff")
        assertEquals(16, bytes.size)
        assertEquals(0x4a, bytes[0].toInt() and 0xFF)
        assertEquals(0x92, bytes[15].toInt() and 0xFF)
    }

    @Test
    fun `parseTokenHex 忽略空格与冒号`() {
        val compact = GlassesSession.parseTokenHex("00112233445566778899aabbccddeeff")
        val spaced = GlassesSession.parseTokenHex("4aa7 a621 3502 b9a0 d1e6 27ea 053b 7c92")
        // 冒号按 4 位一组：按 2 位一组会拼出 `xx:xx:xx:xx:xx:xx`，被公开仓的 MAC 泄漏扫描
        // 误判成真地址（那条规则宁严勿松，改测试比放宽门禁便宜）
        val dotted = GlassesSession.parseTokenHex("4aa7:a621:3502:b9a0:d1e6:27ea:053b:7c92")
        assertTrue(compact.contentEquals(spaced), "空格应被忽略")
        assertTrue(compact.contentEquals(dotted), "冒号应被忽略")
    }

    @Test
    fun `parseTokenHex 长度不是 32 位就抛`() {
        assertFailsWith<IllegalArgumentException> { GlassesSession.parseTokenHex("") }
        assertFailsWith<IllegalArgumentException> {
            GlassesSession.parseTokenHex("4aa7a6213502b9a0d1e627ea053b7c9")
        }
        assertFailsWith<IllegalArgumentException> {
            GlassesSession.parseTokenHex("00112233445566778899aabbccddeeff00")
        }
    }

    @Test
    fun `parseTokenHex 含非 hex 字符就抛`() {
        val e = assertFailsWith<IllegalArgumentException> {
            GlassesSession.parseTokenHex("4aa7a6213502b9a0d1e627ea053b7c9z")
        }
        assertTrue(e.message.orEmpty().contains("z"), "报错要指出哪个字符不合法：${e.message}")
    }

    @Test
    fun `tokenHex 重载与 ByteArray 重载等价 - 非法输入同样抛`() {
        // 只验参数校验这一层：真正的 open 需要 Android BluetoothDevice，纯 JVM 造不出来
        assertEquals(
            GlassesSession.parseTokenHex("00112233445566778899aabbccddeeff").toList(),
            ByteArray(16) { (it * 0x11).toByte() }.toList(),
        )
        assertFailsWith<IllegalArgumentException> { GlassesSession.parseTokenHex("not-a-token") }
    }
}