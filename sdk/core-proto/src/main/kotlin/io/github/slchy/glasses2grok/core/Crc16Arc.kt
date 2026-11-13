package io.github.slchy.glasses2grok.core

/**
 * CRC-16/ARC（反射，poly 0xA001，init 0，无 xorout）。
 *
 * 与 `参考实现` 逐位一致；L1 帧头 `[6:8]` 以小端存放。
 *
 * 覆盖范围：**仅 payload**，不含 `dataLength` 字段 —— 见 `参考协议`。
 *
 * 参数放在这里而不是 `GlassesProtocol`：`:core-proto` 与 `:core-api` 都要用，
 * 而 `:core-api` 已经依赖本模块，反向依赖会成环。`GlassesProtocol.Crc` 反过来引用这里的常量。
 */
public object Crc16Arc {

    public const val REFLECTED_POLY: Int = 0xA001
    public const val POLY: Int = 0x8005
    public const val INIT: Int = 0x0000
    public const val XOR_OUT: Int = 0
    public const val MASK: Int = 0xFFFF

    private const val CRC_POLY = REFLECTED_POLY
    private const val CRC_INIT = INIT
    private const val CRC_MASK = MASK

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "range out of bounds: offset=$offset length=$length size=${data.size}"
        }
        var crc = CRC_INIT
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor CRC_POLY else crc ushr 1
            }
        }
        return crc and CRC_MASK
    }
}
