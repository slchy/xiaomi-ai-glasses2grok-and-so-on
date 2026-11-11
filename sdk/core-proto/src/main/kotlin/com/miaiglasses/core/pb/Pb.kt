package com.miaiglasses.core.pb

import java.io.ByteArrayOutputStream

/**
 * protobuf 编码原语。**只覆盖本项目用到的 wire type**，不追求完备。
 *
 * 为什么要有这个文件：`Wear` / `Voice` / 各业务命令各自手搓 varint 曾是
 * 「同一判定出现第二份实现」的开端（改一处漏一处）。统一到这里。
 */
public object Pb {

    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LEN = 2
    const val WIRE_FIXED32 = 5

    // ------------------------------------------------------------------ 编码

    fun varint(out: ByteArrayOutputStream, value: Long) {
        var n = value
        while (true) {
            val b = (n and 0x7F).toInt()
            n = n ushr 7
            if (n != 0L) {
                out.write(b or 0x80)
            } else {
                out.write(b)
                return
            }
        }
    }

    fun tag(out: ByteArrayOutputStream, field: Int, wire: Int) {
        varint(out, ((field.toLong()) shl 3) or wire.toLong())
    }

    fun uint(out: ByteArrayOutputStream, field: Int, value: Long) {
        tag(out, field, WIRE_VARINT)
        varint(out, value)
    }

    /**
     * sint32（ZigZag）。**`SystemTime.Timezone.offset` 用的是这个类型** —— 负时区必须走这里，
     * 写成 [uint] 正数碰巧一样、负数会全错。
     */
    fun sint32(out: ByteArrayOutputStream, field: Int, value: Long) {
        uint(out, field, (value shl 1) xor (value shr 63))
    }

    fun bytes(out: ByteArrayOutputStream, field: Int, value: ByteArray) {
        tag(out, field, WIRE_LEN)
        varint(out, value.size.toLong())
        out.write(value, 0, value.size)
    }

    fun string(out: ByteArrayOutputStream, field: Int, value: String) {
        bytes(out, field, value.toByteArray(Charsets.UTF_8))
    }

    /** 小端 32 位定长（wire type 5），用于 float / int32 位模式。 */
    fun fixed32(out: ByteArrayOutputStream, field: Int, bits: Int) {
        tag(out, field, WIRE_FIXED32)
        out.write(bits and 0xFF)
        out.write((bits ushr 8) and 0xFF)
        out.write((bits ushr 16) and 0xFF)
        out.write((bits ushr 24) and 0xFF)
    }

    fun float32(out: ByteArrayOutputStream, field: Int, value: Float) {
        fixed32(out, field, value.toRawBits())
    }

    // ------------------------------------------------------------------ 解码

    /**
     * 把 [fields] 给出的 varint **原始字节**解成数值。
     *
     * [fields] 对 wire type 0 存的是「最短 varint 编码」而不是数值，取值时用这个。
     */
    fun varintValue(raw: ByteArray): Long {
        var v = 0L
        var shift = 0
        for (b in raw) {
            v = v or ((b.toLong() and 0x7F) shl shift)
            shift += 7
            if (shift >= 64) break
        }
        return v
    }

    /**
     * 极简解析：返回 `字段号 → 原始值字节`（保序）。
     *
     * 只用于**读取设备下发的报文**。遇到不支持的 wire type 直接停止，**不抛异常** ——
     * 新固件加字段不该让旧客户端崩。
     *
     * - wire 0：存最短 varint 编码（用 [varintValue] 取数值）
     * - wire 2：存原始内容
     * - wire 5 / 1：跳过
     */
    fun fields(data: ByteArray?): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        if (data == null) return out

        var i = 0
        while (i < data.size) {
            var tagValue = 0L
            i = readVarint(data, i) { tagValue = it }
            if (i < 0) break
            val field = (tagValue ushr 3).toInt()
            when ((tagValue and 7L).toInt()) {
                WIRE_VARINT -> {
                    var v = 0L
                    i = readVarint(data, i) { v = it }
                    if (i < 0) break
                    out[field] = encodeMinimalVarint(v)
                }
                WIRE_LEN -> {
                    var len = 0L
                    i = readVarint(data, i) { len = it }
                    if (i < 0) break
                    val n = len.toInt()
                    if (n < 0 || i + n > data.size) break
                    out[field] = data.copyOfRange(i, i + n)
                    i += n
                }
                WIRE_FIXED32 -> i += 4
                WIRE_FIXED64 -> i += 8
                else -> return out
            }
        }
        return out
    }

    /**
     * 把 [data] 里 **wire 0 的 [field]** 换成 [value]，**其余字段原样保留**（顺序、重复字段、
     * 未知字段都不动）。字段不存在时追加到末尾；解析不动时**原样返回**（不截断、不抛异常）。
     *
     * 为什么不用 `fields()` 解出来再重编码：那会按 Map 重排字段、丢掉重复字段，
     * 而 `System id=15` 写设置要求**整段重发**（只改一个子字段、其余必须原值带回）。
     */
    fun replaceVarint(data: ByteArray, field: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        var found = false
        while (i < data.size) {
            val start = i
            var tag = 0L
            var j = readVarint(data, i) { tag = it }
            if (j < 0) return data
            when ((tag and 7L).toInt()) {
                WIRE_VARINT -> {
                    var v = 0L
                    j = readVarint(data, j) { v = it }
                    if (j < 0) return data
                    if ((tag ushr 3).toInt() == field) {
                        uint(out, field, value)
                        found = true
                    } else {
                        out.write(data, start, j - start)
                    }
                }
                WIRE_LEN -> {
                    var len = 0L
                    j = readVarint(data, j) { len = it }
                    if (j < 0) return data
                    val n = len.toInt()
                    if (n < 0 || j + n > data.size) return data
                    j += n
                    out.write(data, start, j - start)
                }
                WIRE_FIXED32 -> {
                    j += 4
                    if (j > data.size) return data
                    out.write(data, start, j - start)
                }
                WIRE_FIXED64 -> {
                    j += 8
                    if (j > data.size) return data
                    out.write(data, start, j - start)
                }
                else -> return data
            }
            i = j
        }
        if (!found) uint(out, field, value)
        return out.toByteArray()
    }

    private fun encodeMinimalVarint(value: Long): ByteArray {
        var n = value
        var count = 1
        while (count < 8 && (n ushr (count * 7)) != 0L) count++
        val out = ByteArray(count)
        for (k in 0 until count) {
            out[k] = ((n ushr (7 * k)) and 0x7F).toByte()
            if (k < count - 1) out[k] = (out[k].toInt() or 0x80).toByte()
        }
        return out
    }

    /** 读一个 varint；成功时回调数值并返回下一个位置，失败返回 -1。 */
    private inline fun readVarint(data: ByteArray, start: Int, sink: (Long) -> Unit): Int {        var v = 0L
        var shift = 0
        var i = start
        while (i < data.size) {
            val b = data[i++].toInt() and 0xFF
            v = v or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) {
                sink(v)
                return i
            }
            shift += 7
            if (shift > 63) return -1
        }
        return -1
    }
}
