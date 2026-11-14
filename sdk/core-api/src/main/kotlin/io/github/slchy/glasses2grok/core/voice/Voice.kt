package io.github.slchy.glasses2grok.core.voice

import io.github.slchy.glasses2grok.core.config.GlassesProtocol
import io.github.slchy.glasses2grok.core.pb.Pb

/**
 * `Voice`(type=14) 的**上行事件**（眼镜 → 手机）解析。
 *
 * 所有结构都来自 2026-11-10 真机抓到的原样回包（实测），不是推测。
 *
 * ⚠️ **`id` 是双向的，字段号按方向不同**：本文只处理上行。
 * 手机→眼镜的 `id=10 WakeUpEvent` 用的是 `f1`（实测），
 * 而设备→手机的 `id=10` 用的是 `f11` —— 混用会导致设备完全不响应。
 */
public object VoiceIds {
    public const val TYPE: Int = GlassesProtocol.Voice.TYPE

    /** 会话状态回报（上行）。实测 `f1{f1{f1=state f13=0}}`。 */
    public const val ID_STATUS: Int = GlassesProtocol.Voice.ID_STATUS

    /** 错误同步（上行）。实测 `f6{f1=errorCode}`；`4` = 摘下。 */
    public const val ID_ERROR: Int = GlassesProtocol.Voice.ID_ERROR

    /** 长按触控板 / 唤醒（上行）。实测 `f11{f1{f1=uuid1 f2=uuid2} f3=2}`。 */
    public const val ID_OPEN_DEVICE: Int = GlassesProtocol.Voice.ID_WAKE

    /**
     * 会话状态同步（**下行**，手机→眼镜）。载荷 `Voice{f5(VOICE_STATUS)}`。
     *
     * ⚠️ **这条没有真机原始字节**：仓库里只有参考实现抓包的符号记录
     * （实测）。
     * 容器结构是照 `id=6` 上行同型推断的（`32 02 08 04` = `f6{ f1=4 }`）。
     */
    public const val ID_SYNC_STATUS: Int = GlassesProtocol.Voice.ID_SYNC_STATUS

    /** 拍照请求（下行）。必须复用 `id=10` 下发的两个 ID。 */
    public const val ID_MULTIMODAL: Int = GlassesProtocol.Voice.ID_MULTIMODAL
}

/** 语音链路的上行事件。 */
public sealed interface VoiceEvent {

    /**
     * 会话状态。实测 `state=3`，`extra=0`。
     *
     * ⚠️ `state` 的枚举语义未确认（0/2/3 都可能出现），宿主不要直接展示。
     */
    public data class Status(val state: Int, val extra: Int) : VoiceEvent

    /**
     * 错误同步。`errorCode` **4 = 摘下**（真机实测，与 `CAPABILITY-MATRIX`一致）。
     */
    public data class Error(val errorCode: Int) : VoiceEvent {

        val isTakenOff: Boolean get() = errorCode == ERROR_TAKEN_OFF

        public companion object {
            public const val ERROR_TAKEN_OFF: Int = GlassesProtocol.Voice.ERROR_TAKEN_OFF
        }
    }

    /**
     * 长按触控板 / 唤醒事件。
     *
     * 两个 ID 都是 32 字符 ASCII，但**按原始字节保留** —— `multiModel` 要求原样复用，
     * 转成 String 再转回会丢失非 ASCII 的可能（客户端里它们本来就是 `byte[]`）。
     */
    public class OpenDevice(
        public val requestIdBytes: ByteArray,
        public val transactionIdBytes: ByteArray,
        public val kind: Int,
    ) : VoiceEvent {
        public val requestId: String get() = String(requestIdBytes, Charsets.UTF_8)
        public val transactionId: String get() = String(transactionIdBytes, Charsets.UTF_8)

        override fun toString(): String =
            "OpenDevice(requestId=$requestId, transactionId=$transactionId, kind=$kind)"
    }

    /** 认识 id 但结构没解过：原样交 hex，不猜。 */
    public data class Unknown(val id: Int, val hex: String) : VoiceEvent

    /**
     * 会话状态六字段（`Voice{f5{VoiceStatus}}`，`id=5`）。**双向同构**。
     *
     * 真机样本（2026-11-11，设备发给手机）：`2a 0c 08 00 10 00 18 00 20 00 28 00 30 00`。
     * 枚举见 `参考协议`（`语音状态` 0/1、`运行状态` 0–6 …）。
     */
    public data class SyncStatus(
        val voice: Int,
        val tts: Int,
        val mediaPlay: Int,
        val image: Int,
        val operating: Int,
        val device: Int,
    ) : VoiceEvent
}

/** 上行 `Voice` 包解析。入口是 `(id, payload)`，即 `WearPacket` 的 id 与 payload。 */
public object VoiceCodec {

    // ---- 下行字段号：与 `docs/a5a5.md` 规范一致（改字段号前先核对规范） ----
    private const val VOICE_FIELD_MULTIMODAL = 4
    private const val MULTIMODAL_FIELD_REQUEST_INFO = 1
    private const val MULTIMODAL_FIELD_CMD = 2
    private const val MULTIMODAL_CMD_PHOTO = 1L
    private const val REQ_INFO_FIELD_REQUEST_ID = 1
    private const val REQ_INFO_FIELD_TRANSACTION_ID = 2
    private const val REQ_INFO_FIELD_PAYLOAD = 3

    // ---- id=10 下行（手机 → 眼镜）----
    /**
     * `Voice.f1` = `WakeUpEvent` —— **只有下行是这个字段号**。
     *
     * 上行（设备 → 手机）的 `id=10` 用 `f11`；混用会让设备**完全不响应**
     * （`Voice` 类头注释、`参考协议`）。
     */
    private const val VOICE_FIELD_WAKE_UP = 1
    private const val WAKE_UP_FIELD_REQUEST_INFO = 1
    private const val WAKE_UP_FIELD_CMD = 2

    /** 参考实现样本 `WakeUpEvent.f2 = 5`（实测）。 */
    private const val WAKE_UP_CMD_DEFAULT = 5L

    // ---- id=5 下行 ----
    /** `Voice.f5`（tag `0x2A`）—— 由 `protobuf 消息类` 常量表确认，见 `参考协议`。 */
    private const val VOICE_FIELD_STATUS = 5

    /** `VoiceStatus.f1` = `语音状态`；`0` = `VOICE_IDLE`。 */
    private const val VOICE_STATUS_FIELD_VOICE = 1
    private const val VOICE_STATUS_FIELD_TTS = 2
    private const val VOICE_STATUS_FIELD_MEDIA_PLAY = 3
    private const val VOICE_STATUS_FIELD_IMAGE = 4
    private const val VOICE_STATUS_FIELD_OPERATING = 5
    private const val VOICE_STATUS_FIELD_DEVICE = 6

    /**
     * 构造 `Voice id=4` `multiModel` 的**内容**（不含 `WearPacket` 头，供 `sendWear(14, 4, …)` 用）。
     *
     * 依据 `docs/a5a5.md` 规范实现，与真机行为逐字节比对一致：
     *
     * - `VoiceRequestInfo` **f1 = requestId、f2 = transactionId**（写反 → 设备 0 字节响应，
     *   见 `docs/a5a5.md` 规范）
     * - `resourceId = "multimodal_" + transactionId + hex(salt)`
     * - `cmd = 1`（`IMAGE_CAPTURE_AND_TRANS`）
     * - `requestId` 为 null 时**仍写 f1**（空 payload 也发 tag+00，否则设备不认）
     *
     * @param transactionId 设备在 `Voice id=10` 里下发的 transactionId，必须原样复用
     * @param requestId 同上；可为 null
     * @param salt 任意字节，只用于让 resourceId 唯一（测试传固定值以复现字节）
     */
    public fun buildMultiModel(transactionId: String, requestId: ByteArray?, salt: ByteArray): ByteArray {
        val resourceId = "multimodal_" + transactionId + salt.toHexPlain()
        val json = "{\"type\":\"OTHER\",\"minLen\":\"\",\"quality\":\"\"," +
            "\"isSkipOcr\":\"0\",\"resizeRatio\":\"1\"," +
            "\"resourceId\":\"$resourceId\"}"

        val reqInfo = java.io.ByteArrayOutputStream()
        Pb.bytes(reqInfo, REQ_INFO_FIELD_REQUEST_ID, requestId ?: ByteArray(0))
        Pb.string(reqInfo, REQ_INFO_FIELD_TRANSACTION_ID, transactionId)
        Pb.string(reqInfo, REQ_INFO_FIELD_PAYLOAD, json)

        val multiModel = java.io.ByteArrayOutputStream()
        Pb.bytes(multiModel, MULTIMODAL_FIELD_REQUEST_INFO, reqInfo.toByteArray())
        Pb.uint(multiModel, MULTIMODAL_FIELD_CMD, MULTIMODAL_CMD_PHOTO)

        val voice = java.io.ByteArrayOutputStream()
        Pb.bytes(voice, VOICE_FIELD_MULTIMODAL, multiModel.toByteArray())
        return voice.toByteArray()
    }

    /**
     * 构造 `Voice id=10 WakeUpEvent` 的**内容**（手机 → 眼镜方向，不含 `WearPacket` 头）。
     *
     * 出处：`参考协议`「伴随帧」——参考实现在发 `Voice id=4` 拍照请求**之前**先发它：
     *
     * ```text
     * ch=1 tx seq=34  Voice id=10  WakeUpEvent { f1{transactionId, requestId}, f2=5 }
     * ```
     *
     * ⚠ **这是未验证能力。** 参考实现发它的上下文是"拍照前建立 Voice 会话"，
     * 不等于"拉起语音助手"。发出去之后眼镜会不会开始语音采集，**没人测过**。
     *
     * 判定方式（见 `docs/` 的拉起实验）：发完观察
     * - 有 `WAKEUP_VOICE_FIRST`(6) / `FOLLOW_VOICE`(2) 的 MASS 帧 → 拉起了
     * - 只有 `Voice id=6` 错误、或彻底无反应 → 拉不起
     *
     * @param transactionId 会话事务 ID；的参考实现样本是 32 位十六进制
     * @param requestId 可为 null（仍显式写 f1 空值，与 [buildMultiModel] 同规）
     * @param cmd `WakeUpEvent.f2`，默认 5（参考实现样本值）
     */
    public fun buildWakeUp(
        transactionId: String,
        requestId: ByteArray? = null,
        cmd: Long = WAKE_UP_CMD_DEFAULT,
    ): ByteArray {
        val reqInfo = java.io.ByteArrayOutputStream()
        Pb.bytes(reqInfo, REQ_INFO_FIELD_REQUEST_ID, requestId ?: ByteArray(0))
        Pb.string(reqInfo, REQ_INFO_FIELD_TRANSACTION_ID, transactionId)

        val wakeUp = java.io.ByteArrayOutputStream()
        Pb.bytes(wakeUp, WAKE_UP_FIELD_REQUEST_INFO, reqInfo.toByteArray())
        Pb.uint(wakeUp, WAKE_UP_FIELD_CMD, cmd)

        val voice = java.io.ByteArrayOutputStream()
        Pb.bytes(voice, VOICE_FIELD_WAKE_UP, wakeUp.toByteArray())
        return voice.toByteArray()
    }

    /**
     * 构造 `Voice id=5 SYNC_VOICE_STATUS` 的**内容**（不含 `WearPacket` 头）。
     *
     * @param status 直接塞进 `Voice.f5` 的 `VoiceStatus` 字节，由调用方负责
     */
    public fun buildVoiceStatus(status: ByteArray): ByteArray {
        val voice = java.io.ByteArrayOutputStream()
        Pb.bytes(voice, VOICE_FIELD_STATUS, status)
        return voice.toByteArray()
    }

    /**
     * **结束语音会话**的载荷：`Voice{f5{ VoiceStatus }}`，**6 个字段全部显式写出**。
     *
     * ```text
     * 2a 0c  08 00  10 00  18 00  20 00  28 00  30 00
     * f5     f1=0   f2=0   f3=0   f4=0   f5=0   f6=0
     * ```
     *
     * ★ **必须写全 6 个字段**（2026-11-11 真机对照实验，`实测`）：
     * 只写 `f1` 的 `2a 02 08 00` 发出去后眼镜**继续推流 23 秒**、速率不变；
     * 换成 6 字段全量帧后，之后 12 秒只多 1 个包 —— 立刻停采集。
     * 说明设备按 proto2 语义解析（字段缺席 ≠ 0），或解析器要求完整消息。
     *
     * 这 6 个字段的「全 0」写法不是我们想出来的，是照抄设备自己 `id=5` 上报帧的编码
     * （真机 `2a 0c 08 00 10 00 18 00 20 00 28 00 30 00`）。
     */
    public fun endVoicePayload(): ByteArray {
        return syncStatusPayload()
    }

    /**
     * 构造任意 `Voice id=5 SYNC_VOICE_STATUS` 的 `VoiceStatus`（6 字段**全部显式写出**）。
     *
     * 枚举见 `参考协议`的 `VoiceStatus` 字段表：
     *
     * | 字段 | 取值 |
     * |---|---|
     * | `voice` | 0 `VOICE_IDLE` / 1 `WAKE_UP` |
     * | `tts` | 0 `TTS_IDLE` / 1 `TTS_PLAYING` / 2 `TTS_THINKING` |
     * | `mediaPlay` | 0 空闲 / 1 播放 / 2 暂停 / 3 停止 |
     * | `image` | 0 空闲 / 1 准备 / 2 上传中 |
     * | `operating` | 0 空闲 / 1 执行中 / 2 录音中 / 3–6 各种待机 |
     * | `device` | 0 在线 / 1 连接但离线 / 2 断开 |
     *
     * ⚠️ **字段缺席 ≠ 0**：真机实测只写 `f1` 的 `2a 02 08 00` 完全无效（`实测`
     *），所以这里六个字段一律写出，包括等于 0 的。
     *
     * ⚠️ 每个字段**具体驱动设备的什么行为**尚未逐项实测。已确认的只有：六个全 0 会让
     * 眼镜立刻停止采集。`tts`/`operating` 与内侧指示灯的关系仍是推测。
     */
    public fun syncStatusPayload(
        voice: Long = 0,
        tts: Long = 0,
        mediaPlay: Long = 0,
        image: Long = 0,
        operating: Long = 0,
        device: Long = 0,
    ): ByteArray {
        val status = java.io.ByteArrayOutputStream()
        Pb.uint(status, VOICE_STATUS_FIELD_VOICE, voice) // f1 voiceStatus
        Pb.uint(status, VOICE_STATUS_FIELD_TTS, tts) // f2 ttsStatus
        Pb.uint(status, VOICE_STATUS_FIELD_MEDIA_PLAY, mediaPlay) // f3 mediaPlayStatus
        Pb.uint(status, VOICE_STATUS_FIELD_IMAGE, image) // f4 imageStatus
        Pb.uint(status, VOICE_STATUS_FIELD_OPERATING, operating) // f5 operatingStatus
        Pb.uint(status, VOICE_STATUS_FIELD_DEVICE, device) // f6 deviceStatus
        return buildVoiceStatus(status.toByteArray())
    }

    /** `Voice{f5{VoiceStatus}}` —— 会话状态，**双向同构**（设备→手机也发这个，真机见过）。 */
    private fun parseSyncStatus(payload: ByteArray): VoiceEvent {
        val m = Pb.fields(payload)[VOICE_FIELD_STATUS]
            ?: return VoiceEvent.Unknown(VoiceIds.ID_SYNC_STATUS, payload.toHex())
        val f = Pb.fields(m)
        fun at(n: Int) = (f[n] ?: ByteArray(1)).varint().toInt()
        return VoiceEvent.SyncStatus(
            voice = at(1),
            tts = at(2),
            mediaPlay = at(3),
            image = at(4),
            operating = at(5),
            device = at(6),
        )
    }

    public fun parse(id: Int, payload: ByteArray): VoiceEvent = when (id) {
        VoiceIds.ID_STATUS -> parseStatus(payload)
        VoiceIds.ID_SYNC_STATUS -> parseSyncStatus(payload)
        VoiceIds.ID_ERROR -> parseError(payload)
        VoiceIds.ID_OPEN_DEVICE -> parseOpenDevice(payload)
        else -> VoiceEvent.Unknown(id, payload.toHex())
    }

    /** `f1{f1{f1=state f13=extra}}` */
    private fun parseStatus(payload: ByteArray): VoiceEvent {
        val l1 = Pb.fields(payload)[1]
        val l2 = l1?.let { Pb.fields(it) }?.get(1)
        val l3 = l2?.let { Pb.fields(it) } ?: return VoiceEvent.Unknown(VoiceIds.ID_STATUS, payload.toHex())
        return VoiceEvent.Status(
            state = (l3[1] ?: ByteArray(1)).varint().toInt(),
            extra = (l3[13] ?: ByteArray(1)).varint().toInt(),
        )
    }

    /** `f6{f1=errorCode}` */
    private fun parseError(payload: ByteArray): VoiceEvent {
        val m = Pb.fields(payload)[6] ?: return VoiceEvent.Unknown(VoiceIds.ID_ERROR, payload.toHex())
        val code = (Pb.fields(m)[1] ?: ByteArray(1)).varint().toInt()
        return VoiceEvent.Error(code)
    }

    /** `f11{f1{f1=requestId f2=transactionId} f2=kind}` —— 外层第二个字段是 `0x10`（field 2）。 */
    private fun parseOpenDevice(payload: ByteArray): VoiceEvent {
        val m = Pb.fields(payload)[11]
            ?: return VoiceEvent.Unknown(VoiceIds.ID_OPEN_DEVICE, payload.toHex())
        val outer = Pb.fields(m)
        val pair = outer[1]?.let { Pb.fields(it) }
            ?: return VoiceEvent.Unknown(VoiceIds.ID_OPEN_DEVICE, payload.toHex())
        return VoiceEvent.OpenDevice(
            requestIdBytes = pair[1] ?: ByteArray(0),
            transactionIdBytes = pair[2] ?: ByteArray(0),
            kind = (outer[2] ?: ByteArray(1)).varint().toInt(),
        )
    }
}

private fun ByteArray?.varint(): Long = if (this == null) 0L else Pb.varintValue(this)

private fun ByteArray.utf8(): String = String(this, Charsets.UTF_8)

private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

/** 小写、无分隔（对齐 `docs/a5a5.md` 规范，resourceId 里要用同一个格式）。 */
internal fun ByteArray.toHexPlain(): String = joinToString("") { "%02x".format(it) }
