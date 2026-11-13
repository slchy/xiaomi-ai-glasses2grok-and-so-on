package io.github.slchy.glasses2grok.sample

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `PhotoFsm` 的单测：只喂事件，断言「相位 + 动作」。
 *
 * 这些判断以前埋在 `HighResPhoto.capture()` 的过程式代码里，失败一律 `return null`，
 * 云端只能知道「没图」，看不出卡在哪一步。现在纯到可以直接断言。
 */
class PhotoFsmTest {

    private fun ready(): PhotoFsm {
        val fsm = PhotoFsm()
        assertEquals(listOf<PhotoAction>(PhotoAction.Arm), fsm.dispatch(PhotoEvent.Request))
        assertEquals(PhotoState.ARMING, fsm.state)
        return fsm
    }

    private fun waiting(): PhotoFsm {
        val fsm = ready()
        fsm.dispatch(PhotoEvent.Armed(false))
        assertEquals(PhotoState.WAITING, fsm.state)
        return fsm
    }

    @Test
    fun `正常一条链：就绪 → 新条目 → 拉图 → 缩图 → 上行 → 完成`() {
        val fsm = waiting()
        assertEquals(listOf<PhotoAction>(PhotoAction.Poll), fsm.dispatch(PhotoEvent.Tick))
        assertEquals(listOf<PhotoAction>(PhotoAction.Download), fsm.dispatch(PhotoEvent.Fresh("IMG_1")))
        assertEquals(PhotoState.FETCHING, fsm.state)
        assertEquals("IMG_1", fsm.identifier)
        assertEquals(listOf<PhotoAction>(PhotoAction.Encode), fsm.dispatch(PhotoEvent.Fetched))
        assertEquals(listOf<PhotoAction>(PhotoAction.Upload), fsm.dispatch(PhotoEvent.Encoded))
        assertEquals(listOf<PhotoAction>(PhotoAction.Cleanup), fsm.dispatch(PhotoEvent.Sent))
        assertEquals(PhotoState.DONE, fsm.state)
        assertEquals("ARMING>WAITING>FETCHING>ENCODING>UPLOADING>DONE", fsm.path)
    }

    @Test
    fun `就绪上报里带 fresh，用户提前按过快门时云端不播提示`() {
        val fsm = ready()
        val actions = fsm.dispatch(PhotoEvent.Armed(true))
        val report = actions.filterIsInstance<PhotoAction.Report>().single()
        assertEquals("hq_armed", report.event)
        assertEquals("true", report.fields["fresh"])
    }

    @Test
    fun `就绪后用户不按：超时判 no_shutter，并主动上报`() {
        val fsm = waiting()
        val actions = fsm.dispatch(PhotoEvent.Timeout(PhotoState.WAITING))
        assertEquals(PhotoState.FAILED, fsm.state)
        val report = actions.filterIsInstance<PhotoAction.Report>().single()
        assertEquals("hq_failed", report.event)
        assertEquals("no_shutter", report.fields["reason"])
        assertTrue(actions.contains(PhotoAction.Cleanup), "终态必须走唯一出口")
    }

    @Test
    fun `上一阶段的定时器迟到：阶段对不上就作废`() {
        val fsm = waiting()
        assertEquals(emptyList(), fsm.dispatch(PhotoEvent.Timeout(PhotoState.ARMING)))
        assertEquals(PhotoState.WAITING, fsm.state)
        // 反过来：建组阶段收到等快门的超时也不该生效
        val arming = ready()
        assertEquals(emptyList(), arming.dispatch(PhotoEvent.Timeout(PhotoState.WAITING)))
        assertEquals(PhotoState.ARMING, arming.state)
    }

    @Test
    fun `取消：任何运行态都能立刻收尾，并告诉云端是 cancelled`() {
        for (state in listOf(PhotoState.ARMING, PhotoState.WAITING)) {
            val fsm = if (state == PhotoState.ARMING) ready() else waiting()
            val actions = fsm.dispatch(PhotoEvent.Cancel)
            assertEquals(PhotoState.ABORTED, fsm.state)
            val report = actions.filterIsInstance<PhotoAction.Report>().single()
            assertEquals("cancelled", report.fields["reason"])
            assertTrue(actions.contains(PhotoAction.Cleanup))
        }
    }

    @Test
    fun `单飞：正在跑的时候再来一次请求被忽略`() {
        val fsm = ready()
        assertEquals(emptyList(), fsm.dispatch(PhotoEvent.Request))
        assertEquals(PhotoState.ARMING, fsm.state)
    }

    @Test
    fun `终态之后的事件一律吞掉`() {
        val fsm = waiting()
        fsm.dispatch(PhotoEvent.Timeout(PhotoState.WAITING))
        assertEquals(emptyList(), fsm.dispatch(PhotoEvent.Fresh("IMG_9")))
        assertEquals(emptyList(), fsm.dispatch(PhotoEvent.Tick))
        assertEquals(emptyList(), fsm.dispatch(PhotoEvent.Cancel))
        assertEquals(PhotoState.FAILED, fsm.state)
    }

    @Test
    fun `终态之后的新请求是一次全新的开始`() {
        val fsm = waiting()
        fsm.dispatch(PhotoEvent.Cancel)
        assertEquals(listOf<PhotoAction>(PhotoAction.Arm), fsm.dispatch(PhotoEvent.Request))
        assertEquals(PhotoState.ARMING, fsm.state)
        assertEquals("ARMING", fsm.path)
        assertEquals(null, fsm.identifier)
    }

    @Test
    fun `解码失败：报 decode，不让云端干等`() {
        val fsm = waiting()
        fsm.dispatch(PhotoEvent.Fresh("IMG_2"))
        fsm.dispatch(PhotoEvent.Fetched)
        val actions = fsm.dispatch(PhotoEvent.Failed(PhotoFail.Decode))
        assertEquals(PhotoState.FAILED, fsm.state)
        assertEquals("decode", actions.filterIsInstance<PhotoAction.Report>().single().fields["reason"])
    }

    @Test
    fun `isRunning 只在运行态为真：SPP 低清回图靠它决定丢不丢`() {
        val fsm = PhotoFsm()
        assertFalse(fsm.state.isRunning)
        fsm.dispatch(PhotoEvent.Request)
        assertTrue(fsm.state.isRunning)
        fsm.dispatch(PhotoEvent.Cancel)
        assertFalse(fsm.state.isRunning)
        assertTrue(fsm.state.isTerminal)
    }
}
