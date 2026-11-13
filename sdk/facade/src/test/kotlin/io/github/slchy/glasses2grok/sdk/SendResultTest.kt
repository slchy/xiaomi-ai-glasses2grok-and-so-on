package io.github.slchy.glasses2grok.sdk

import io.github.slchy.glasses2grok.core.session.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `SendResult` 的语义护栏。
 *
 * 曾经的 bug：`SessionDriver.sendWear` 用 `pump.post` 投递（`() -> Unit`），引擎的
 * 返回值被丢掉，于是「车道满 / 超 MPS / 引擎已停」对外全报成 `Queued`；
 * 而 `REJECTED` 只会出现 `NOT_READY`，`SdkErrorCode.QUEUE_FULL` 永远走不到。
 *
 * 这些用例直接钉住「码 → 结果」的映射：把 `sendResultOf` 改成永远 `Queued`，
 * 或者把 QUEUE_FULL 折成 NOT_READY，都会红。
 */
class SendResultTest {

    @Test
    fun `零码就是受理 - Queued`() {
        assertEquals(SendResult.Queued, sendResultOf(ErrorCode.NONE))
    }

    @Test
    fun `车道满必须原样返回 QUEUE_FULL`() {
        val r = sendResultOf(ErrorCode.QUEUE_FULL) as SendResult.Rejected
        assertEquals(SdkErrorCode.QUEUE_FULL, r.code, "宿主按 QUEUE_FULL 做退避，折成别的码等于断路")
        assertEquals(ErrorCode.QUEUE_FULL, r.code)
        assertTrue(r.reason.contains("退避"), "理由要提示可以重试：${r.reason}")
    }

    @Test
    fun `超 MPS - 空载荷归为 PROTOCOL 而不是 Queued`() {
        val r = sendResultOf(ErrorCode.PROTOCOL) as SendResult.Rejected
        assertEquals(SdkErrorCode.PROTOCOL, r.code)
        assertTrue(r.reason.contains("MPS"), "理由要指出是载荷过大：${r.reason}")
    }

    @Test
    fun `每个非零码都不允许映射成 Queued`() {
        val codes = listOf(
            ErrorCode.NOT_READY, ErrorCode.CLOSED, ErrorCode.TRANSPORT,
            ErrorCode.PROTOCOL, ErrorCode.TIMEOUT, ErrorCode.QUEUE_FULL,
        )
        for (code in codes) {
            assertTrue(sendResultOf(code) is SendResult.Rejected, "code=$code 必须被拒绝")
        }
    }
}
