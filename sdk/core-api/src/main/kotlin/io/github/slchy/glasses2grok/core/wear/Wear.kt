package io.github.slchy.glasses2grok.core.wear

import io.github.slchy.glasses2grok.core.pb.Pb
import java.io.ByteArrayOutputStream

/**
 * WearPacket：L2 载荷里的应用层信封。
 *
 * 编码（实测）：
 * ```
 * 08 <type:varint>               field1 = type
 * 10 <id:varint>                 field2 = id
 * <tag:varint> <len:varint> <payload>   tag = ((type + 2) << 3) | 2
 * ```
 *
 * 三条不能违反的（都有测试）：
 * 1. **`type` 和 `id` 的值本身是 varint** —— 当作单字节写，`id ≥ 128` 时产出非法编码。
 * 2. **payload 的字段号 = `type + 2`，必须动态算**；写死 `0x22`（field 4）只能发 System。
 * 3. **空 payload 也要显式写 `<tag> 00`**，不能省略 —— 省略与「没有这个字段」在
 *    语义上不同，设备实测按「有字段、长度 0」处理。
 */
public class WearPacket(
    public val type: Int,
    public val id: Int,
    public val payload: ByteArray,
) {
    /** 容器字段号。诊断日志用它解释原始 hex。 */
    public val payloadField: Int get() = type + 2

    override fun toString(): String = "Wear(type=$type, id=$id, len=${payload.size})"
}

public object WearCodec {

    public fun build(type: Int, id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 16)
        out.write(0x08)
        Pb.varint(out, type.toLong())
        out.write(0x10)
        Pb.varint(out, id.toLong())
        Pb.varint(out, (((type + 2).toLong()) shl 3) or 2L)
        Pb.varint(out, payload.size.toLong())
        out.write(payload, 0, payload.size)
        return out.toByteArray()
    }

    /**
     * 解析。缺 `type` 或 `id` 时返回 null。
     *
     * 不要退化成「读 body[0]/body[1]/body[3]」—— 那隐含假设字段顺序固定且
     * `id < 128`，而两者都是 varint。
     */
    public fun parse(data: ByteArray?): WearPacket? {
        if (data == null || data.isEmpty()) return null
        val f = Pb.fields(data)
        val t = f[1] ?: return null
        val i = f[2] ?: return null
        val type = Pb.varintValue(t).toInt()
        val id = Pb.varintValue(i).toInt()
        return WearPacket(type, id, f[type + 2] ?: ByteArray(0))
    }
}
