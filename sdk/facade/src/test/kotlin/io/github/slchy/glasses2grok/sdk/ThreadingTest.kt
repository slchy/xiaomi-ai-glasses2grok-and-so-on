package io.github.slchy.glasses2grok.sdk

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 主线程护栏：判定逻辑与报错文案。
 *
 * 「当前线程是不是主线程」依赖 `Looper`，纯 JVM 单测里是桩（`unitTests.isReturnDefaultValues`），
 * 所以真正能在这里验的是**判定结果的处理**：给 `onMainThread = true` 必须抛、`false` 必须放行。
 * 真机上的主线程行为由 `sample` 与实测覆盖。
 */
class ThreadingTest {

    @Test
    fun `主线程上抛 IllegalStateException 且文案可操作`() {
        val e = assertFailsWith<IllegalStateException> {
            Threading.checkNotBlockingMainThread(onMainThread = true, timeoutMs = 8_000)
        }
        val msg = e.message.orEmpty()
        assertTrue("8000ms" in msg, "要报出这次实际会等多久，实际=$msg")
        assertTrue("主线程" in msg, "要指出是被主线程挡下的，实际=$msg")
        assertTrue("Dispatchers.IO" in msg, "要给出可直接照做的改法，实际=$msg")
        assertTrue("threadGuards" in msg, "要说明怎么关掉，实际=$msg")
    }

    @Test
    fun `后台线程上放行`() {
        Threading.checkNotBlockingMainThread(onMainThread = false, timeoutMs = 3_000)
    }

    @Test
    fun `纯 JVM 里没有 Looper 时不判定为主线程`() {
        // isReturnDefaultValues ⇒ Looper 返回 null，必须当成「不是主线程」，否则单测全被拦死
        assertTrue(!Threading.isMainThread(), "纯 JVM 单测里没有主线程")
    }
}
