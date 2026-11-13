package io.github.slchy.glasses2grok.core.media

import io.github.slchy.glasses2grok.core.config.GlassesProtocol
import io.github.slchy.glasses2grok.core.pb.Pb
import java.io.ByteArrayOutputStream

/**
 * `Media`(18) 的录音命令与字段号。
 *
 * 全部取自参考实现（`静态分析` 的 nano protobuf，**字段名没被混淆**）：
 *
 * ```text
 * REQUEST_RECORD       = 20
 * GET_RECORD_STATUS    = 21
 * 设备侧录音状态上报 = 22          // 设备主动上报
 * RECORD_REQUEST_FIELD_NUMBER  = 17  // Media.f17 = RecordRequest
 * RECORD_RESPONSE_FIELD_NUMBER = 18  // Media.f18 = RecordResponse
 * RECORD_STATUS_FIELD_NUMBER   = 19  // Media.f19 = RecordStatus
 * ```
 *
 * `RecordRequest{ f1 = type int32, f2 = action uint32 }`。
 */
public object RecordIds {
    /** `WearPacket.type`：Media。 */
    public const val TYPE: Int = GlassesProtocol.Record.TYPE

    public const val ID_REQUEST_RECORD: Int = GlassesProtocol.Record.ID_REQUEST
    public const val ID_GET_RECORD_STATUS: Int = GlassesProtocol.Record.ID_STATUS

    /** 设备主动上报录音状态（`Media.f19`）。 */
    public const val ID_REPORT_RECORD_STATUS: Int = GlassesProtocol.Record.ID_REPORT

    public const val FIELD_RECORD_REQUEST: Int = 17
    public const val FIELD_RECORD_RESPONSE: Int = 18
    public const val FIELD_RECORD_STATUS: Int = 19
}

/**
 * `RecordRequest.type` —— **参考实现常量名原样照搬**（`protobuf 定义`）。
 *
 * 五个值与参考实现里那五个匿名子类（0..4）逐一对应。
 */
public object RecordType {
    /** 普通录音（`RECORD_NORMAL`）。 */
    public const val NORMAL: Int = 0

    /** 实时转写（`RECORD_REALTIME`）。 */
    public const val REALTIME: Int = 1

    /** 听歌识曲（`RECORD_MUSIC`）。 */
    public const val MUSIC: Int = 2

    /** 同传 / 翻译（`RECORD_TRANSLATION`）。 */
    public const val TRANSLATION: Int = 3

    /** 面对面翻译（`RECORD_TRANSLATION_FACE`）。 */
    public const val TRANSLATION_FACE: Int = 4

    public fun name(value: Int): String = when (value) {
        NORMAL -> "NORMAL"
        REALTIME -> "REALTIME"
        MUSIC -> "MUSIC"
        TRANSLATION -> "TRANSLATION"
        TRANSLATION_FACE -> "TRANSLATION_FACE"
        else -> "UNKNOWN($value)"
    }
}

/**
 * `RecordRequest.action`。
 *
 * 只有两个值有证据：参考实现里 `开始录音` 传 `action=0`、
 * `停止录音` 传 `action=1`（方法名 + 日志原文 "开始录音---result:"）。
 * 同包枚举里还有一个 `2`，全仓无调用点 —— **语义未知，不给它起名字**。
 */
public object RecordAction {
    public const val START: Int = 0
    public const val STOP: Int = 1
}

/**
 * `MediaFile.type` —— 录音/录像文件的容器格式（`MediaFile` 常量）。
 *
 * 用来判断「这条录音状态描述的是音频还是视频」：真机上普通录音给的是 **OPUS(5)**。
 */

/**
 * `Media.f18 = RecordResponse{ f1 = code uint32, f2 = mediaFileId, f3 = duration uint32 }`。
 *
 * `code == 0` 表示成功。非 0 的取值表见 [RecordCode]。
 */
public data class RecordResponse(
    val code: Int,
    val mediaFileId: String,
    val duration: Int,
    val raw: String,
) {
    public val isOk: Boolean get() = code == 0

    /** 参考实现给这个 code 的文案标识（`录音辅助类` 的映射）。 */
    public val codeName: String get() = RecordCode.name(code)

    public companion object {
        public fun parse(payload: ByteArray): RecordResponse? {
            val f18 = Pb.fields(payload)[RecordIds.FIELD_RECORD_RESPONSE] ?: return null
            val f = Pb.fields(f18)
            val id = f[2]?.let { Pb.fields(it) }?.get(1)?.let { bytes -> String(bytes, Charsets.UTF_8) }
            return RecordResponse(
                code = Pb.varintValue(f[1] ?: ByteArray(0)).toInt(),
                mediaFileId = id ?: "",
                duration = Pb.varintValue(f[3] ?: ByteArray(0)).toInt(),
                raw = io.github.slchy.glasses2grok.core.Hex.encode(payload),
            )
        }
    }
}

/**
 * `RecordResponse.code` 的取值表 —— 来自参考实现（静态分析）里那个
 * `switch 分支表`（字符串资源名略）。
 *
 * | code | 含义 |
 * | ---: | --- |
 * | -3 | 停止超时 |
 * | -2 | 请求超时 |
 * | -1 | 蓝牙断开 |
 * | 0 | 成功（`code == 0` 才算成功） |
 * | 1 | 未知 type |
 * | 2 | 空间不足 |
 * | 3 | ⚠️ 参考实现映射疑似放错，语义未知 |
 * | 4 | 没在录音（对「停止」而言） |
 * | 5 | ⚠️ 同上；真机 start 失败时返回过它 |
 * | 6 | 通话中 |
 * | 7 | 录像中 |
 * | 8 | 相机协同中 |
 * | 9 | 语音会话占用 |
 * | 10 | 已在录音 |
 * | 11 | 电量过低 |
 * | 12 | 过热 |
 *
 * 只有 0 是「成功」；负数与正数都是失败，但**数值的正负不表示严重程度**。
 */
internal object RecordCode {
    public const val OK: Int = 0
    public const val STOP_TIMEOUT: Int = -3
    public const val REQUEST_TIMEOUT: Int = -2
    public const val BT_DISCONNECT: Int = -1
    public const val TYPE_UNKNOWN: Int = 1
    public const val LACK_OF_SPACE: Int = 2
    public const val NOT_IN_RECORDING: Int = 4
    public const val IN_CALL: Int = 6
    public const val IN_VIDEO: Int = 7
    public const val CAMERA_COLLABORATION: Int = 8
    public const val VOICE: Int = 9
    public const val ALREADY_RECORDING: Int = 10
    public const val BATTERY_LOW: Int = 11
    public const val OVERHEAT: Int = 12

    public fun name(code: Int): String = when (code) {
        OK -> "OK"
        STOP_TIMEOUT -> "STOP_TIMEOUT"
        REQUEST_TIMEOUT -> "REQUEST_TIMEOUT"
        BT_DISCONNECT -> "BT_DISCONNECT"
        TYPE_UNKNOWN -> "TYPE_UNKNOWN"
        LACK_OF_SPACE -> "LACK_OF_SPACE"
        NOT_IN_RECORDING -> "NOT_IN_RECORDING"
        3, 5 -> "UNKNOWN($code)" // 参考实现把这两个映射到了 text_alipay_connect_tips
        IN_CALL -> "IN_CALL"
        IN_VIDEO -> "IN_VIDEO"
        CAMERA_COLLABORATION -> "CAMERA_COLLABORATION"
        VOICE -> "VOICE"
        ALREADY_RECORDING -> "ALREADY_RECORDING"
        BATTERY_LOW -> "BATTERY_LOW"
        OVERHEAT -> "OVERHEAT"
        else -> "UNKNOWN($code)"
    }
}

public object RecordCodec {
    /**
     * 构造 `Media.f17 = RecordRequest{type, action}` —— 发给 `Media id=20` 的载荷。
     *
     * 参考实现用 `protobuf 编码` + `protobuf 编码`；非负值下与我们的
     * varint 编码逐字节相同（`Hex` 黄金向量钉住）。
     */
    public fun requestPayload(type: Int, action: Int): ByteArray {
        val inner = ByteArrayOutputStream().also {
            Pb.uint(it, 1, type.toLong())
            Pb.uint(it, 2, action.toLong())
        }.toByteArray()
        return ByteArrayOutputStream().also {
            Pb.bytes(it, RecordIds.FIELD_RECORD_REQUEST, inner)
        }.toByteArray()
    }

    /** 解析 `Media.f18 = RecordResponse`。 */
    public fun parseResponse(payload: ByteArray): RecordResponse? = RecordResponse.parse(payload)
}
