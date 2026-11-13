package io.github.slchy.glasses2grok.sample

import java.io.ByteArrayOutputStream

/**
 * 云端线协议：[type:u8][len:u32 LE][payload]。
 *
 * 与 `cloud/tools/fake-phone.ts` 是同一份约定的两种实现 —— 那边是 JS 的参照版，
 * 这边是手机上真正跑的那份。**没有第三方依赖**：这层只做字节，不做 IO。
 */
internal object Wire {

    // 上行
    const val UP_AUDIO = 1
    const val UP_IMAGE = 2
    const val UP_WAKE = 3
    const val UP_PING = 4

    /** 状态事件（UTF-8 JSON）：高清照流程的「就绪 / 出图 / 失败」靠它告诉云端 */
    const val UP_STATUS = 5
    const val UP_HELLO = 6

    // 下行
    const val DOWN_AUDIO = 1
    const val DOWN_CONTROL = 2
    const val DOWN_PONG = 5

    private const val HEADER = 5

    fun frame(type: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER + payload.size)
        out[0] = type.toByte()
        out[1] = (payload.size and 0xFF).toByte()
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        out[3] = ((payload.size ushr 16) and 0xFF).toByte()
        out[4] = ((payload.size ushr 24) and 0xFF).toByte()
        payload.copyInto(out, HEADER)
        return out
    }

    /** `Hello`。字段都可选；token 为空就不带（服务端没配鉴权时它不关心）。 */
    fun hello(deviceId: String, token: String): ByteArray {
        val json = buildString {
            append('{')
            append("\"proto\":1")
            append(",\"deviceId\":\"").append(escape(deviceId)).append('"')
            if (token.isNotEmpty()) {
                append(",\"token\":\"").append(escape(token)).append('"')
            }
            append('}')
        }
        return frame(UP_HELLO, json.toByteArray(Charsets.UTF_8))
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * 状态事件。高清照只有在**人按下快门**之后才会有图，这中间要几十秒，
     * 云端必须知道「就绪了，等他按」和「他没按，别等了」，否则只能盲目超时。
     */
    fun status(event: String, vararg fields: Pair<String, String>): ByteArray {
        val json = buildString {
            append("{\"ev\":\"").append(escape(event)).append('"')
            for ((key, value) in fields) {
                append(",\"").append(escape(key)).append("\":\"").append(escape(value)).append('"')
            }
            append('}')
        }
        return frame(UP_STATUS, json.toByteArray(Charsets.UTF_8))
    }

    /** 增量解帧。一次 `push` 可能拿到半帧，也可能拿到好几帧。 */
    class Decoder {
        private val buffer = ByteArrayOutputStream()

        fun push(chunk: ByteArray, at: Int, size: Int): List<Pair<Int, ByteArray>> {
            buffer.write(chunk, at, size)
            val all = buffer.toByteArray()
            val frames = ArrayList<Pair<Int, ByteArray>>(2)
            var offset = 0
            while (all.size - offset >= HEADER) {
                val type = all[offset].toInt() and 0xFF
                val len = (all[offset + 1].toInt() and 0xFF) or
                    ((all[offset + 2].toInt() and 0xFF) shl 8) or
                    ((all[offset + 3].toInt() and 0xFF) shl 16) or
                    ((all[offset + 4].toInt() and 0xFF) shl 24)
                if (len < 0 || all.size - offset - HEADER < len) {
                    break
                }
                frames += type to all.copyOfRange(offset + HEADER, offset + HEADER + len)
                offset += HEADER + len
            }
            buffer.reset()
            if (offset < all.size) {
                buffer.write(all, offset, all.size - offset)
            }
            return frames
        }
    }
}
