package io.github.slchy.glasses2grok.sdk.ktx

import io.github.slchy.glasses2grok.core.session.GlassesEvent
import io.github.slchy.glasses2grok.core.session.SessionState
import io.github.slchy.glasses2grok.sdk.GlassesEventSource
import io.github.slchy.glasses2grok.sdk.GlassesListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesFlowTest {

    /** 假的 `GlassesEventSource`：这正是 `GlassesEventSource` 抽出来的意义 —— 纯 JVM 可测。 */
    private class FakeSource(override var state: SessionState = SessionState.CONNECTING) : GlassesEventSource {
        /** 多订阅：ktx 的每个 flow 各占一格，谁取消只摘谁。 */
        val listeners = java.util.concurrent.CopyOnWriteArrayList<GlassesListener>()

        override fun setListener(listener: GlassesListener?) {
            listeners.clear()
            if (listener != null) listeners += listener
        }

        override fun addListener(listener: GlassesListener) {
            listeners += listener
        }

        override fun removeListener(listener: GlassesListener) {
            listeners -= listener
        }

        /** 第一个订阅者（只用于「等监听挂上」的轮询）。 */
        fun first(): GlassesListener? = listeners.firstOrNull()

        fun emit(event: GlassesEvent) {
            listeners.toList().forEach { it.onEvent(event) }
        }

        fun goReady() {
            state = SessionState.READY
            emit(GlassesEvent.StateChanged(SessionState.READY))
        }
    }

    @Test
    fun `events 转发全部事件并在取消时退订`() = runTest {
        val src = FakeSource()
        val received = mutableListOf<GlassesEvent>()
        val job = launch {
            src.events().take(2).toList(received)
        }
        // 等监听挂上
        while (src.first() == null) kotlinx.coroutines.yield()

        src.emit(GlassesEvent.StateChanged(SessionState.HANDSHAKING))
        src.emit(GlassesEvent.Error(0, "boom"))
        job.join()

        assertEquals(2, received.size)
        assertTrue(received[1] is GlassesEvent.Error)
        assertTrue(src.listeners.isEmpty(), "flow 结束后必须退订，实际还剩 ${src.listeners.size}")
    }

    @Test
    fun `多个 flow 互不顶掉`() = runTest {
        // 回归护栏：早先 events()/audio()/awaitReady() 都用 setListener，
        // 后挂的顶掉先挂的；任一 flow 结束时的 setListener(null) 还会把别人一起摘掉。
        val src = FakeSource()
        val all = mutableListOf<GlassesEvent>()
        val audio = mutableListOf<ByteArray>()

        val readyJob = launch { src.awaitReady(60_000) }
        while (src.listeners.size < 1) kotlinx.coroutines.yield()

        val eventsJob = launch { src.events().take(3).toList(all) }
        while (src.listeners.size < 2) kotlinx.coroutines.yield()

        val audioJob = launch { src.audio().take(1).toList(audio) }
        while (src.listeners.size < 3) kotlinx.coroutines.yield()
        assertEquals(3, src.listeners.size, "三个订阅必须并存")

        src.goReady() // readyJob 该结束，但不得摘掉另外两个
        readyJob.join()
        assertEquals(2, src.listeners.size, "awaitReady 结束只摘自己")

        src.emit(GlassesEvent.Audio(ByteArray(4)))
        audioJob.join()
        assertEquals(1, audio.size, "events 抢 listener 后 audio 收不到东西")
        assertEquals(1, src.listeners.size, "audio 结束只摘自己")

        src.emit(GlassesEvent.Error(0, "x"))
        src.emit(GlassesEvent.Error(0, "y"))
        eventsJob.join()
        assertTrue(all.any { it is GlassesEvent.Audio }, "events 也应看到音频事件")
        assertTrue(src.listeners.isEmpty(), "全部结束应清空，实际还剩 ${src.listeners.size}")
    }

    @Test
    fun `awaitReady 在 READY 时立即返回`() = runTest {
        val src = FakeSource(state = SessionState.READY)
        src.awaitReady(1_000) // 不挂起也不抛
        assertTrue(src.listeners.isEmpty(), "没有订阅就不该留下监听")
    }

    @Test
    fun `awaitReady 等到 READY 事件`() = runTest {
        val src = FakeSource()
        val job = launch { src.awaitReady(60_000) }
        while (src.first() == null) kotlinx.coroutines.yield()

        src.emit(GlassesEvent.StateChanged(SessionState.HANDSHAKING))
        src.goReady()
        job.join() // 抛异常会让 join 失败
        assertTrue(src.listeners.isEmpty(), "监听必须清空")
    }

    @Test
    fun `awaitReady 遇到 FAILED 抛 SdkStateException`() = runTest {
        val src = FakeSource()
        val job = launch {
            assertFailsWith<SdkStateException> { src.awaitReady(60_000) }
        }
        while (src.first() == null) kotlinx.coroutines.yield()

        src.state = SessionState.FAILED
        src.emit(GlassesEvent.StateChanged(SessionState.FAILED))
        job.join()
    }

    @Test
    fun `awaitReady 超时抛 SdkTimeoutException`() = runTest {
        val src = FakeSource()
        assertFailsWith<SdkTimeoutException> { src.awaitReady(50) }
    }

    @Test
    fun `audio 只放行音频块`() = runTest {
        val src = FakeSource()
        val chunk = ByteArray(57) { 1 }
        val got = mutableListOf<ByteArray>()
        val job = launch { src.audio().first().let { got += it } }
        while (src.first() == null) kotlinx.coroutines.yield()

        src.emit(GlassesEvent.StateChanged(SessionState.READY)) // 应被过滤
        src.emit(GlassesEvent.Audio(chunk))
        job.join()

        assertEquals(1, got.size)
        assertEquals(57, got[0].size)
    }
}
