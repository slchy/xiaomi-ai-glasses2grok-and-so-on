package io.github.slchy.glasses2grok.core.sar

import io.github.slchy.glasses2grok.core.Hex
import io.github.slchy.glasses2grok.core.l1.GoldenVectors
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SAR 黄金向量对账：读 `tests/golden/vectors.txt` 的 `kind=S` 八条。
 *
 * 脚本文法与 `tests/golden/gen.py::run_sar` 严格一致：
 * ```
 * send:ch:op:hex    → sendData(ch, op, hex)
 * cmd:id:hex        → sendCmd(id, hex)
 * feed:hex          → feed(hex)
 * tick:ms           → 时钟推进 ms 后 tick()
 * ```
 * 期望值是「所有发出字节的拼接」，所以字节顺序也必须一致。
 *
 * 窗口策略固定用 `Local(32)` —— 参考实现就是这么算的。
 * SDK 默认的 `Negotiated` 策略另有专项测试（`SarEngineTest`）。
 */
class SarGoldenTest {

    @Test
    fun `八条 SAR 向量逐字节一致`() {
        val rows = GoldenVectors.sarRows()
        assertEquals(
            listOf(
                "sar_ack_path", "sar_cmd_seq", "sar_nak_goback", "sar_recv_ack_now",
                "sar_recv_cum", "sar_recv_nak", "sar_timeout", "sar_window",
            ),
            rows.map { it.name }.sorted(),
            "SAR 向量缺失或被改名 —— 先跑 python tests/golden/gen.py",
        )

        for (row in rows) {
            assertEquals(row.expectedHex, runScript(row.script), "向量 ${row.name}")
        }
    }

    private fun runScript(script: String): String {
        val out = ByteArrayOutputStream()
        val clock = Clock.Fake(1000L)          // 与 gen.py 的 t0 = 1.0 s 对齐
        val engine = SarEngine(
            sink = { out.write(it) },
            clock = clock,
            config = SarConfig(txWindowPolicy = TxWindowPolicy.Local(32)),
        )

        for (step in script.split("|")) {
            if (step.isEmpty()) continue
            val parts = step.split(":")
            when (parts[0]) {
                "send" -> {
                    val ch = parts[1].toInt()
                    val op = parts[2].toInt()
                    val payload = if (parts.size > 3 && parts[3].isNotEmpty()) Hex.decode(parts[3]) else ByteArray(0)
                    engine.sendData(ch, op, payload)
                }
                "cmd" -> {
                    val id = parts[1].toInt()
                    val body = if (parts.size > 2 && parts[2].isNotEmpty()) Hex.decode(parts[2]) else ByteArray(0)
                    engine.sendCmd(id, body)
                }
                "feed" -> engine.feed(Hex.decode(parts[1]))
                "tick" -> {
                    clock.advance(parts[1].toLong())
                    engine.tick()
                }
                else -> error("未知步骤 ${parts[0]}")
            }
        }
        return Hex.encode(out.toByteArray())
    }

    @Test
    fun `窗口满时只发出 32 帧 - 对应 sar_window`() {
        val row = GoldenVectors.sarRows().first { it.name == "sar_window" }
        val bytes = Hex.decode(runScript(row.script))
        assertEquals(32, countFrames(bytes), "本地窗口 32 ⇒ 40 次发送只应发出 32 帧")
    }

    private fun countFrames(bytes: ByteArray): Int {
        var n = 0
        var i = 0
        while (i + 8 <= bytes.size) {
            require(bytes[i] == 0xA5.toByte() && bytes[i + 1] == 0xA5.toByte()) { "不在帧边界上: i=$i" }
            val len = (bytes[i + 4].toInt() and 0xFF) or ((bytes[i + 5].toInt() and 0xFF) shl 8)
            i += 8 + len
            n++
        }
        return n
    }
}
