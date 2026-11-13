package io.github.slchy.glasses2grok.core.l1

import io.github.slchy.glasses2grok.core.l1.GoldenVectors

/**
 * 读取 `tests/golden/vectors.txt`（由 Gradle 复制进测试 classpath）。
 *
 * 格式 4 列 TSV：`kind \t name \t script \t expected_hex`，`#` 开头为注释。
 * 与 `tests/golden/gen.py` 的 `render()` 严格对应。
 */
internal object GoldenVectors {

    private const val RESOURCE = "/golden/vectors.txt"

    data class Row(val kind: String, val name: String, val script: String, val expectedHex: String)

    private val cached: List<Row> by lazy {
        val stream = GoldenVectors::class.java.getResourceAsStream(RESOURCE)
            ?: error(
                "找不到 $RESOURCE —— 应由 Gradle 从 tests/golden/vectors.txt 复制；" +
                    "若直接跑单测，请先执行 python tests/golden/gen.py",
            )
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
                val cells = line.split('\t')
                if (cells.size < 4) return@mapNotNull null
                Row(cells[0], cells[1], cells[2], cells[3])
            }.toList()
        }
    }

    fun l1Rows(): List<Row> = cached.filter { it.kind == "P" && it.name.startsWith("l1_") }

    /** SAR 事件序列向量（`kind=S`），脚本格式见 `tests/golden/gen.py::run_sar`。 */
    fun sarRows(): List<Row> = cached.filter { it.kind == "S" }

    /** WearPacket 向量（`kind=P`，名字以 `wear_` 开头）。 */
    fun wearRows(): List<Row> = cached.filter { it.kind == "P" && it.name.startsWith("wear_") }

    /** 密码学原语向量（`kind=P`，按名字前缀取，如 `hkdf64` / `ctr` / `ccm24`）。 */
    fun primitiveRows(prefix: String): List<Row> =
        cached.filter { it.kind == "P" && it.name.startsWith(prefix) }

    fun size(): Int = cached.size
}
