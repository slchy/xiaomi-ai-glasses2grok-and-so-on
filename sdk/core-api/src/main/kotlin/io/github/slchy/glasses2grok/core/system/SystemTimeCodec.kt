package io.github.slchy.glasses2grok.core.system

import io.github.slchy.glasses2grok.core.pb.Pb
import java.io.ByteArrayOutputStream
import java.util.TimeZone

/**
 * `System id=3` `SetSystemTime` 的载荷：`System.f4 = SystemTime`。
 *
 * 结构逐字段来自参考实现 `命令实现类` 与
 * `protobuf 消息类` / `protobuf 消息类` / `protobuf 消息类` /
 * `protobuf 消息类` 的 `writeTo`（实测）：
 *
 * ```text
 * SystemTime {
 *   f1 Date     { f1 year u32, f2 month u32, f3 day u32 }              month 是 1–12
 *   f2 Time     { f1 hour u32, f2 minuter u32, f3 second u32, f4 millisecond u32 }
 *   f3 Timezone { f1 offset sint32, f2 dstSaving sint32, f3 id string }
 *   f4 is12Hours bool
 * }
 * ```
 *
 * ⚠️ **两个容易写错的点**（参考实现代码里看出来的，不是猜的）：
 *
 * 1. `Timezone.offset` 的单位是**15 分钟一格**，不是分钟：
 *    `offset = ((zoneOffsetMs / 1000) / 60) / 15`。UTC+8 → `32`，UTC−5 → `−20`。
 * 2. `Timezone.offset` / `dstSaving` 是 **sint32（zigzag）**。用 int32 编码时正数碰巧一样，
 *    负时区会全错。
 *
 * 参考实现对这些字段都是**无条件写出**（`设置毫秒` / `设置夏令时` / `设置标识` /
 * `设置 12 小时制` 都会置位），这里也不省略 —— 省掉会让设备读到默认值。
 */
public object SystemTimeCodec {

    /** `System.f4`。 */
    private const val SYSTEM_FIELD_TIME = 4

    /**
     * 构造 `id=3` 的**请求载荷**（不含 `WearPacket` 头，供 `sendWear(2, 3, …)` 用）。
     *
     * `SetSystemTime.needResponse = false`，参考实现发完不等回包。
     *
     * @param epochMs 毫秒时间戳，通常是 `System.currentTimeMillis()`
     * @param zone 时区，默认本机时区；`Timezone.id` 取 `zone.id`（如 `Asia/Shanghai`）
     * @param is24Hour 12/24 小时制，默认 24 小时
     */
    public fun encode(
        epochMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
        is24Hour: Boolean = true,
    ): ByteArray {
        val offsetMs = zone.getOffset(epochMs)
        // 用 UTC 读「加过偏移的毫秒」，得到该时区的墙上时间，不受默认时区影响
        val c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = epochMs + offsetMs
        }

        val date = ByteArrayOutputStream()
        Pb.uint(date, 1, c.get(java.util.Calendar.YEAR).toLong())
        Pb.uint(date, 2, (c.get(java.util.Calendar.MONTH) + 1).toLong())
        Pb.uint(date, 3, c.get(java.util.Calendar.DAY_OF_MONTH).toLong())

        val time = ByteArrayOutputStream()
        Pb.uint(time, 1, c.get(java.util.Calendar.HOUR_OF_DAY).toLong())
        Pb.uint(time, 2, c.get(java.util.Calendar.MINUTE).toLong())
        Pb.uint(time, 3, c.get(java.util.Calendar.SECOND).toLong())
        Pb.uint(time, 4, Math.floorMod(epochMs, 1000L))

        val timezone = ByteArrayOutputStream()
        Pb.sint32(timezone, 1, (((offsetMs / 1000) / 60) / 15).toLong())
        Pb.sint32(timezone, 2, 0L) // dstSaving：参考实现恒发 0
        Pb.string(timezone, 3, zone.id)

        val systemTime = ByteArrayOutputStream()
        Pb.bytes(systemTime, 1, date.toByteArray())
        Pb.bytes(systemTime, 2, time.toByteArray())
        Pb.bytes(systemTime, 3, timezone.toByteArray())
        Pb.uint(systemTime, 4, if (is24Hour) 0L else 1L)

        val out = ByteArrayOutputStream()
        Pb.bytes(out, SYSTEM_FIELD_TIME, systemTime.toByteArray())
        return out.toByteArray()
    }
}
