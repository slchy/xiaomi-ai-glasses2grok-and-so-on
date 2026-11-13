package io.github.slchy.glasses2grok.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 建连/看门狗的竞态裁决。
 *
 * 真机上这个窗口只有几毫秒宽，靠真蓝牙几乎不可能稳定复现 ——
 * 所以把「谁先到」抽成 [ConnectGate]，在这里把两种顺序都钉死。
 */
class ConnectGateTest {

    @Test
    fun `建连先到看门狗不得关闭链路`() {
        val g = ConnectGate()
        g.begin()

        assertTrue(g.onConnected(), "建连方应赢下 CAS")
        assertFalse(g.onDeadline(), "看门狗晚到，不得再关刚建好的 socket")
        assertFalse(g.timedOut, "没超时就别置超时位")
        g.end()
    }

    @Test
    fun `看门狗先到建连必须当失败处理`() {
        val g = ConnectGate()
        g.begin()

        assertTrue(g.onDeadline(), "看门狗应先赢")
        assertTrue(g.timedOut)
        assertFalse(g.onConnected(), "建连晚到必须知道自己输了，不能当成功")
        g.end()
    }

    @Test
    fun `end 之后重新 begin 可以再来一次`() {
        val g = ConnectGate()
        g.begin()
        assertTrue(g.onDeadline())
        g.end()

        g.begin()
        assertFalse(g.timedOut, "begin 要清掉上一轮的判定")
        assertTrue(g.onConnected())
        g.end()
    }
}
