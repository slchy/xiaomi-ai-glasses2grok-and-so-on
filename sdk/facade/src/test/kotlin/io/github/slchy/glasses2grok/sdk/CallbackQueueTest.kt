package io.github.slchy.glasses2grok.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  回调侧背压：队列满时丢**非关键**条目并计数，终态 `CLOSED` 不丢。
 */
class CallbackQueueTest {

    private fun queue(capacity: Int) = CallbackQueue(capacity)

    @Test
    fun `capacity 满了之后非关键条目被丢并计数`() {
        val q = queue(2)
        assertTrue(q.offer({}))
        assertTrue(q.offer({}))
        assertFalse(q.offer({}), "第 3 条应被丢")
        assertEquals(1L, q.dropped)
        assertEquals(2, q.size)
        assertEquals(2L, q.accepted)
    }

    @Test
    fun `关键条目腾位置时淘汰最老的非关键条目`() {
        val q = queue(2)
        val seen = mutableListOf<String>()
        q.offer({ seen += "old" })
        q.offer({ seen += "new" })

        assertTrue(q.offer({ seen += "closed" }, critical = true), "关键条目必须入队")
        assertEquals(1L, q.dropped, "被淘汰的那条要计入 dropped")

        while (true) (q.poll() ?: break).invoke()
        assertEquals(listOf("new", "closed"), seen, "被淘汰的是最老的 old，不是 new 也不是 closed")
    }

    @Test
    fun `全是关键条目时新来的关键条目被丢`() {
        val q = queue(2)
        assertTrue(q.offer({}, critical = true))
        assertTrue(q.offer({}, critical = true))
        assertFalse(q.offer({}, critical = true), "没有非关键条目可淘汰，只能丢新来的")
        assertEquals(1L, q.dropped)
        assertEquals(2, q.size)
    }

    @Test
    fun `poll 按入队顺序执行且空队列返回 null`() {
        val q = queue(8)
        val seen = mutableListOf<Int>()
        for (i in 1..3) q.offer({ seen += i })

        var n = q.poll()
        while (n != null) {
            n()
            n = q.poll()
        }
        assertEquals(listOf(1, 2, 3), seen)
        assertNull(q.poll())
        assertEquals(0, q.size)
    }
}
