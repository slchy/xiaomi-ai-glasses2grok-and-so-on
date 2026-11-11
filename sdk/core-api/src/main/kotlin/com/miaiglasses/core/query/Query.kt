package com.miaiglasses.core.query

import com.miaiglasses.core.config.GlassesProtocol
import com.miaiglasses.core.media.MediaFormat
import com.miaiglasses.core.pb.Pb

/**
 * 查询类命令的 id 与**回包解析**。
 *
 * 本文件的字段号全部来自 2026-11-10 真机回包（实测），不是猜的。
 * 但**语义**（某个 int 是电量还是状态位）只有部分确认，未确认的在属性名/KDoc 上标 ⚠️。
 */
public object QueryIds {
    public const val SYSTEM: Int = GlassesProtocol.Wear.SYSTEM
    public const val VOICE: Int = GlassesProtocol.Wear.VOICE
    public const val MEDIA: Int = GlassesProtocol.Wear.MEDIA

    /** `System` 设备状态，回包外层 field **2**。 */
    public const val ID_DEVICE_STATUS: Int = GlassesProtocol.System.ID_DEVICE_STATUS

    /** `System` 设备信息，回包外层 field **3**。 */
    public const val ID_DEVICE_INFO: Int = GlassesProtocol.System.ID_DEVICE_INFO

    /** `System` 写系统时间，载荷 `f4 = SystemTime`，见 `SystemTimeCodec`。**参考实现不等回包。** */
    public const val ID_SET_SYSTEM_TIME: Int = GlassesProtocol.System.ID_SET_SYSTEM_TIME

    /** `System` 读设置，回包外层 field **35**。请求载荷 `f34{f1=bitmask}`，见 [SystemSettingRequest]。 */
    public const val ID_SYSTEM_SETTING: Int = GlassesProtocol.System.ID_SYSTEM_SETTING

    /** `System` **写**设置，载荷 `f35{ 段号 { 整段子字段 } }`。回执是**空 payload**。 */
    public const val ID_SYSTEM_SETTING_WRITE: Int = GlassesProtocol.System.ID_SYSTEM_SETTING_WRITE

    /** `System` 读 WiFi 配置，回包外层 field **33**。 */
    public const val ID_WIFI_CONFIG: Int = 86

    /** `Media` 媒体数量摘要，回包外层 field **13**。 */
    public const val ID_MEDIA_SUMMARY: Int = 14

    /** `Media` 媒体文件列表**请求**；眼镜用 **id=16** 回（不是 15）。 */
    public const val ID_MEDIA_LIST_REQUEST: Int = 15

    /** `Media` 媒体文件列表**回包**，外层 field **14**。 */
    public const val ID_MEDIA_LIST_RESPONSE: Int = 16

    /** `Media` 录音状态，回包外层 field **19**。 */
    public const val ID_RECORD_STATUS: Int = GlassesProtocol.Record.ID_STATUS

    /** `System` 告警状态（`AlertStatus`）。本文件不解析这条载荷。 */
    public const val ID_ALERT_STATUS: Int = GlassesProtocol.System.ID_ALERT_STATUS

    /** `System` 读佩戴状态（`GetWearStatus`），空载荷，回包 `f60 = enum`（1 戴 / 2 未戴）。 */
    public const val ID_WEAR_STATUS: Int = GlassesProtocol.System.ID_WEAR_STATUS

    /** `System` 设备主动上报电池。载荷字段仍是 [DeviceStatus.FIELD_BATTERY_STATUS]。 */
    public const val ID_BATTERY: Int = GlassesProtocol.System.ID_BATTERY
}

/** 一个查询的通用结果。 */
public sealed interface QueryResult<out T> {
    public data class Ok<T>(val value: T) : QueryResult<T>
    public data class Failed(val reason: String) : QueryResult<Nothing>
}

/** `System id=2` 设备信息。四个字段都是真机原样字符串，语义已确认。 */
public data class DeviceInfo(
    val deviceId: String,
    val firmware: String,
    val serial: String,
    val model: String,
) {
    public companion object {
        public fun parse(payload: ByteArray): DeviceInfo? {
            val inner = Pb.fields(payload)[3] ?: return null
            val f = Pb.fields(inner)
            return DeviceInfo(
                deviceId = f[1]?.utf8() ?: "",
                firmware = f[2]?.utf8() ?: "",
                serial = f[3]?.utf8() ?: "",
                model = f[4]?.utf8() ?: "",
            )
        }
    }
}

/**
 * `System id=1` 设备状态。回包 `f2{ f1{ … } }`，内层就是 `DeviceStatus.Battery`。
 *
 * 字段号与含义由参考实现的 `protobuf 消息类.writeTo` **静态确定**
 * （实测），不再是猜测：
 *
 * ```text
 * Battery { f1 capacity(uint32 百分比)  f2 chargeStatus(enum)  f3 chargeInfo{f1 state f2 timestamp} }
 * ```
 *
 * 真机实测 `74 / 2` = 74% 未充电。
 */
public data class DeviceStatus(
    /** 电量百分比，0–100。 */
    val batteryPercent: Int,
    /** [CHARGE_UNKNOWN] / [CHARGE_CHARGING] / [CHARGE_NOT_CHARGING] / [CHARGE_FULL]。 */
    val chargeStatus: Int,
    /** `充电信息`；参考实现不下发这一段，通常为 0。 */
    val chargeInfoState: Int = 0,
    /** `充电信息`，秒级时间戳。 */
    val chargeInfoTimestamp: Int = 0,
) {
    val isCharging: Boolean get() = chargeStatus == CHARGE_CHARGING
    val isFull: Boolean get() = chargeStatus == CHARGE_FULL

    public companion object {
        /** `DeviceStatus.Battery.UNKNOWN`。 */
        public const val CHARGE_UNKNOWN: Int = 0

        /** `DeviceStatus.Battery.CHARGING`。 */
        public const val CHARGE_CHARGING: Int = 1

        /** `DeviceStatus.Battery.NOT_CHARGING`。 */
        public const val CHARGE_NOT_CHARGING: Int = 2

        /** `DeviceStatus.Battery.FULL`。 */
        public const val CHARGE_FULL: Int = 3

        public fun parse(payload: ByteArray): DeviceStatus? {
            // payload = f2 { f1 { f1=capacity f2=chargeStatus [f3=chargeInfo{f1,f2}] } }
            val battery = Pb.fields(payload)[2]?.let { Pb.fields(it) }?.get(1) ?: return null
            return fromBattery(battery) ?: return null
        }

        /**
         * `System id=97` **设备主动上报**的电池状态。
         *
         * 载荷是 `f63 = Battery` 本体（不是 `f2{f1{…}}`）：
         * 参考实现的按 id 分发表里 `0x61` 走
         * `System.getBatteryStatus()` → `状态处理`，
         * 与 `id=1` 回包落实在同一个 `DeviceStatus` 槽位（实测）。
         */
        public fun parseBatteryReport(payload: ByteArray): DeviceStatus? =
            Pb.fields(payload)[FIELD_BATTERY_STATUS]?.let { fromBattery(it) }

        /** `System.f63 BATTERY_STATUS`（oneof `System.设置电池状态`）。 */
        public const val FIELD_BATTERY_STATUS: Int = 63

        private fun fromBattery(battery: ByteArray): DeviceStatus? {
            val f = Pb.fields(battery)
            val info = f[3]?.let { Pb.fields(it) }
            return DeviceStatus(
                batteryPercent = (f[1] ?: return null).varint().toInt(),
                chargeStatus = f[2]?.varint()?.toInt() ?: CHARGE_UNKNOWN,
                chargeInfoState = info?.get(1).varint().toInt(),
                chargeInfoTimestamp = info?.get(2).varint().toInt(),
            )
        }
    }
}

/** 媒体数量：⚠️ `kind` 的枚举语义未确认（实测出现 0 / 2 / 5）。 */
public data class MediaEntry(val kind: Int, val count: Int)

/** `Media id=14` 媒体数量摘要。实测 3 条：`(0,2) (2,0) (5,0)`。 */
public data class MediaSummary(val entries: List<MediaEntry>) {
    public companion object {
        public fun parse(payload: ByteArray): MediaSummary? {
            val inner = Pb.fields(payload)[13] ?: return null
            val entries = fieldsMulti(inner, 1).mapNotNull { e ->
                val f = Pb.fields(e)
                val kind = f[1] ?: return@mapNotNull null
                val count = f[2] ?: return@mapNotNull null
                MediaEntry(kind.varint().toInt(), count.varint().toInt())
            }
            return MediaSummary(entries)
        }
    }
}

/**
 * `Media id=15` 请求 → 眼镜用 **id=16** 回，外层 field 14 是文件列表。
 *
 * 实测空列表为 `72 00`（field 14，长度 0）。条目结构（`MediaFile`）：
 *
 * ```text
 * MediaFile.List { f1 = repeated MediaFile }
 * MediaFile      { f1 = Identifier{ f1 id, f2 thumbnail }, f2 type, f3 size, f4 timestamp, f5 duration }
 * ```
 *
 * [entries] 保留原始 hex（字段结构将来变了还能看），[files] 是解析后的结构化结果。
 */
public data class MediaList(
    val entries: List<String>,
    val files: List<MediaFileInfo> = emptyList(),
) {
    public companion object {
        public fun parse(payload: ByteArray): MediaList? {
            val inner = Pb.fields(payload)[14] ?: return null
            val raw = fieldsMulti(inner, 1)
            return MediaList(
                entries = raw.map { it.toHex() },
                files = raw.mapNotNull { MediaFileInfo.parse(it) },
            )
        }
    }
}

/**
 * `MediaFile`：设备上一个媒体文件。
 *
 * 真机（2026-11-11，13 条样本）里 [identifier] 形如 `IMG_20261111_…` / `AUD_…` / `VID_…`，
 * [type] 取 [MediaFormat] 常量（0 JPEG / 2 MP4 / 5 OPUS / 6 SBC / 7 MSBC …），
 * [size] 是**字节数**（音频条目常报 0，真实长度只能靠下载时的 `Content-Length`）。
 */
public data class MediaFileInfo(
    val identifier: String,
    val thumbnail: Boolean,
    val type: Int,
    val size: Long,
    val timestamp: Long,
    val duration: Long,
) {
    /** 依据 [type] 猜大类：照片 / 视频 / 音频 / 未知。 */
    val kind: MediaKind
        get() = when (type) {
            MediaFormat.JPEG, MediaFormat.PNG, MediaFormat.HEIC_META, MediaFormat.OPUS_IMAGE -> MediaKind.PHOTO
            MediaFormat.MP4, MediaFormat.QUICKTIME, MediaFormat.MP4_META, MediaFormat.YUV_META -> MediaKind.VIDEO
            MediaFormat.OPUS, MediaFormat.SBC, MediaFormat.MSBC, MediaFormat.PCM -> MediaKind.AUDIO
            else -> MediaKind.UNKNOWN
        }

    public companion object {
        public fun parse(entry: ByteArray): MediaFileInfo? {
            val f = Pb.fields(entry)
            val id = Pb.fields(f[1] ?: return null)
            val identifier = id[1]?.toString(Charsets.UTF_8) ?: return null
            return MediaFileInfo(
                identifier = identifier,
                thumbnail = (id[2]?.let { Pb.varintValue(it) } ?: 0L) != 0L,
                type = (f[2]?.let { Pb.varintValue(it) } ?: -1L).toInt(),
                size = f[3]?.let { Pb.varintValue(it) } ?: 0L,
                timestamp = f[4]?.let { Pb.varintValue(it) } ?: 0L,
                duration = f[5]?.let { Pb.varintValue(it) } ?: 0L,
            )
        }
    }
}

/** [MediaFileInfo.kind] 的粗分类。 */
public enum class MediaKind { PHOTO, VIDEO, AUDIO, UNKNOWN }

/** `MediaFile` 的 `type` 常量（参考实现 nano 里是普通 `public static final int`）。 */

/**
 * `Media id=21` / **id=22**（设备主动上报）的录音状态。
 *
 * 字段名取自参考实现（nano protobuf，名字没被混淆）：
 *
 * ```text
 * RecordStatus { f1 = duration LimitValue, f2 = size LimitValue, f3 = extraData }
 * LimitValue   { f1 = current, f2 = max }
 * ExtraData    { f1 = mediaFile, f2 = status }
 * MediaFile    { f1 = identifier{id, thumbnail}, f2 = type, f3 = size, f4 = timestamp, f5 = duration }
 * ```
 *
 * 真机 36 字节样本解出来是：`duration{current=12370, max=800}`、
 * `extraData.status=1`、`mediaFile{type=5(OPUS), size=0, timestamp=1.794e12, duration=0}` ——
 * 即「上一次/正在进行的**音频**录音」。`status` 的取值语义参考实现代码里没有可读常量，
 * **不猜**，原样透出；`mediaFile.type` 用 [MediaFormat] 判音频/视频。
 */
public data class RecordStatus(
    /**
     * `f1`：`LimitValue{current, max}`。
     *
     * **`current` 单位是秒**，但只有**录音中**（`status == 0`）才是「已录秒数」；
     * 空闲时它是设备内部那个约 +1/s 递增的计时器，跟录音长度无关
     * （真机三段观测：249→317 / 573→603 / 767→779，`实测`）。
     * 想要「本次录音时长」用 [recordingSeconds]，或停止回执的
     * `RecordResponse.duration`。
     *
     * `max` 实测恒为 800（800 s = 13 分 20 秒，像是单次录音上限）。
     */
    val duration: LimitValue?,
    /** `f2`：体积。 */
    val size: LimitValue?,
    /** `f3.f2`：状态值。真机对照：0 = 录音中、1 = 空闲（实测）。 */
    val status: Int,
    /** `f3.f1`：录音文件描述。 */
    val mediaFile: RecordMediaFile?,
    val raw: String,
) {
    /**
     * 是否正在录音：`status == 0` 且 `mediaFile.id` 非空。
     *
     * 真机对照（实测）：录音中 9 个采样点全是 `status=0`
     * 且带非空路径、`size` 单调增长；空闲的 5 个点全是 `status=1` 且路径为空。
     * 参考实现代码里没有这套常量名，所以这是**经验判据**。
     */
    val isRecording: Boolean get() = status == 0 && !mediaFile?.id.isNullOrEmpty()

    /**
     * 本次已录秒数 —— **只有录音中才有值**。
     *
     * 空闲时 `duration.current` 是那个 1 Hz 内部计时器，拿它当录音时长是错的。
     */
    val recordingSeconds: Int? get() = if (isRecording) duration?.current else null

    /** 描述的是音频录音（参考实现）。 */
    val isAudioFile: Boolean get() = mediaFile?.type == MediaFormat.OPUS

    /** 描述的是录像（`MP4` / `QUICKTIME`）。 */
    val isVideoFile: Boolean
        get() = mediaFile?.type == MediaFormat.MP4 || mediaFile?.type == MediaFormat.QUICKTIME

    public companion object {
        public fun parse(payload: ByteArray): RecordStatus? {
            val f19 = Pb.fields(payload)[com.miaiglasses.core.media.RecordIds.FIELD_RECORD_STATUS]
                ?: return null
            val outer = Pb.fields(f19)
            val extra = outer[3]?.let { Pb.fields(it) }
            val file = extra?.get(1)?.let { Pb.fields(it) }
            val ident = file?.get(1)?.let { Pb.fields(it) }
            return RecordStatus(
                duration = outer[1]?.let { parseLimit(it) },
                size = outer[2]?.let { parseLimit(it) },
                status = Pb.varintValue(extra?.get(2) ?: ByteArray(0)).toInt(),
                mediaFile = file?.let {
                    RecordMediaFile(
                        id = ident?.get(1)?.let { b -> String(b, Charsets.UTF_8) } ?: "",
                        thumbnail = Pb.varintValue(ident?.get(2) ?: ByteArray(0)) != 0L,
                        type = Pb.varintValue(it[2] ?: ByteArray(0)).toInt(),
                        size = Pb.varintValue(it[3] ?: ByteArray(0)).toInt(),
                        timestamp = Pb.varintValue(it[4] ?: ByteArray(0)),
                        duration = Pb.varintValue(it[5] ?: ByteArray(0)).toInt(),
                    )
                },
                raw = payload.toHex(),
            )
        }

        private fun parseLimit(bytes: ByteArray): LimitValue {
            val f = Pb.fields(bytes)
            return LimitValue(
                current = Pb.varintValue(f[1] ?: ByteArray(0)).toInt(),
                max = Pb.varintValue(f[2] ?: ByteArray(0)).toInt(),
            )
        }
    }
}

/** 参考实现。 */
public data class LimitValue(val current: Int, val max: Int)

/** 参考实现里我们用到的那几个字段。 */
public data class RecordMediaFile(
    val id: String,
    val thumbnail: Boolean,
    /** 容器格式，见 [MediaFormat]。 */
    val type: Int,
    val size: Int,
    /** 毫秒时间戳（真机与音频文件名的差值 < 4 分钟）。 */
    val timestamp: Long,
    val duration: Int,
)

/**
 * `System id=86` WiFi 配置。**明文**，实测回包外层 field 33：
 *
 * ```text
 * f33 { f1 { f1 { f1="GLASSCAP" f2=2 f3=2 } f2 { f1="glasscap1234" f2=1 } } }
 * ```
 *
 * SSID/PSK 与 `实测`记录的眼镜热点凭据完全一致，语义确认。
 */
public data class WifiConfig(val ssid: String, val psk: String, val raw: String) {
    public companion object {
        public fun parse(payload: ByteArray): WifiConfig? {
            val f33 = Pb.fields(payload)[33] ?: return null
            // f33 { f1 { f1{ssid} f2{psk} } f2=1 }
            val ap = Pb.fields(f33)
            val pair = ap[1]?.let { Pb.fields(it) } ?: return null
            return WifiConfig(
                ssid = pair[1]?.let { Pb.fields(it) }?.get(1)?.utf8() ?: "",
                psk = pair[2]?.let { Pb.fields(it) }?.get(1)?.utf8() ?: "",
                raw = payload.toHex(),
            )
        }
    }
}

/**
 * `System id=14` 的**请求载荷**：`System.f34 = Request{ f1 = bitmask }`。
 *
 * 位号规则：`bit = 1 shl (SystemSetting 字段号 - 1)`，即
 *
 * | bit | 段 | 本机实测 |
 * |---:|---|---|
 * | `0x10` | `f5` 指示灯 | ✅ 只回 f5 |
 * | `0x20` | `f6` AI 助手 | ✅ 只回 f6 |
 * | `0x40` | `f7` 媒体/拍摄 | ✅ 只回 f7 |
 * | `0x80` | `f8` 耳机 | ✅ 只回 f8 |
 * | `0x01`–`0x08` | `f1`–`f4` | ❌ 本机无此段（音频眼镜遗留）|
 * | `0x100` | `f9` OTA | ➖ **置不置位都回**（见下）|
 * | `0x200` | `f10` 手势 | ❌ 本机无此段 |
 *
 * **`f9` 是无条件返回的**：实测 bitmask `0xF0`（不含 `0x100`）、`0x1F0`（含）与 `0x3FF`
 * （全置）回包**逐字节相同**，都带 `f9{f1=0}`。所以读 OTA 设置不需要置位，`0x100` 是空操作。
 *
 * 空 payload 等价于 bitmask 0 ⇒ 只回 `f35{f9{f1=0}}` 7 字节 —— 这是 2026-11-10
 * 之前「读不到设置」的全部原因（实测）。
 */
public object SystemSettingRequest {
    public const val BIT_INDICATOR_LIGHT: Int = 0x10
    public const val BIT_AI_ASSISTANT: Int = 0x20
    public const val BIT_MEDIA: Int = 0x40
    public const val BIT_HEADSET: Int = 0x80

    /** 本机全部可用段（`f5`–`f8` 之和 = `0x10|0x20|0x40|0x80`）。`f9` 不需要置位。 */
    public const val ALL_SUPPORTED: Int = 0xF0

    public fun encode(bits: Int = ALL_SUPPORTED): ByteArray {
        val request = java.io.ByteArrayOutputStream()
        Pb.uint(request, 1, bits.toLong())
        val out = java.io.ByteArrayOutputStream()
        Pb.bytes(out, 34, request.toByteArray())
        return out.toByteArray()
    }
}

/**
 * `System id=15` 写设置：`System.f35{ <段号> { <整段子字段> } }`。
 *
 * **一个 setter 会重发整段**（实测）。所以底稿必须是
 * `systemSetting()` 读回来的 `groups[n]`，只改目标子字段 —— 直接构造只含一个子字段的
 * `f8` 会把其余三项清成默认值，连带把本机未识别的子字段也抹掉。
 *
 * 回执是**空 payload** 的 DATA 帧（2026-11-11 真机实测，`实测`）。
 */
public object SystemSettingWrite {
    /** 段号就是 `SystemSetting` 的字段号（5 指示灯 / 6 AI 助手 / 7 媒体 / 8 耳机 / 9 OTA）。 */
    public fun encode(segment: Int, group: ByteArray): ByteArray {
        val inner = java.io.ByteArrayOutputStream()
        Pb.bytes(inner, segment, group)
        val out = java.io.ByteArrayOutputStream()
        Pb.bytes(out, 35, inner.toByteArray())
        return out.toByteArray()
    }

    /** 把 [group] 底稿里的子字段 [field] 换成 [value]，其余子字段原样保留。 */
    public fun patch(group: ByteArray, field: Int, value: Long): ByteArray =
        Pb.replaceVarint(group, field, value)
}

/**
 * `System id=14` 读设置的**回包**（外层 `f35`）。
 *
 * 各段都是 `null` ⟺ 请求的 bitmask 没要这一段（或本机不支持）。
 * 所有值都是 proto varint 原值：`0` 常见于「不支持/未设置」，开关类多为 `1=开 / 2=关`。
 * 语义未逐一实测的字段在类 KDoc 上标了来源。
 *
 * [groups] 是每段的原始 protobuf 字节。**写回时要用它**：`id=15` 一个 setter 会重发整段，
 * 只带改动的子字段会把它余下的字段清成默认值（实测）。
 */
public data class SystemSetting(
    val indicatorLight: IndicatorLight?,
    val aiAssistant: AiAssistant?,
    val media: MediaSetting?,
    val headset: HeadsetSetting?,
    val ota: OtaSetting?,
    /** 段号 → 该段的原始 protobuf 字节，`id=15` 写回的底稿。 */
    val groups: Map<Int, ByteArray>,
    val raw: String,
) {
    /** 同 [groups]，hex 形式，便于日志对账。 */
    public val rawGroups: Map<Int, String> get() = groups.mapValues { it.value.toHex() }

    /** 按 `(段号, 子字段号)` 取原始 varint；该段或该子字段不存在时为 `null`。写后校验用。 */
    public fun value(segment: Int, field: Int): Long? =
        groups[segment]?.let { Pb.fields(it)[field] }?.let { Pb.varintValue(it) }

    /** 默认 data class 会把 `groups` 打成 `[B@1a2b3c`，日志里没用，这里去掉。 */
    override fun toString(): String =
        "SystemSetting(indicatorLight=$indicatorLight, aiAssistant=$aiAssistant, media=$media, " +
            "headset=$headset, ota=$ota, groups=${rawGroups.keys.sorted()}, raw=$raw)"

    public companion object {
        public fun parse(payload: ByteArray): SystemSetting? {
            val f35 = Pb.fields(payload)[35] ?: return null
            val groups = Pb.fields(f35)
            return SystemSetting(
                indicatorLight = groups[5]?.let { g ->
                    val f = Pb.fields(g)
                    IndicatorLight(
                        brightness = f[1].varint(),
                        lowerLevelsSupported = f[2].varint(),
                        chargingLight = f[3].varint(),
                    )
                },
                aiAssistant = groups[6]?.let { g ->
                    val f = Pb.fields(g)
                    AiAssistant(
                        voiceWakeup = f[1].varint(),
                        cameraAccess = f[2].varint(),
                        collaborationWakeup = f[3].varint(),
                        supportedTones = f[4].varint(),
                        voiceTone = f[5].varint(),
                        eyeTracking = f[6].varint(),
                    )
                },
                media = groups[7]?.let { g ->
                    val f = Pb.fields(g)
                    MediaSetting(
                        shootingMode = f[1].varint(),
                        videoDurationSec = f[2].varint(),
                        autoSync = f[3].varint(),
                        radioDirection = f[4].varint(),
                        tempControl = f[5].varint(),
                        shieldReminder = f[6].varint(),
                        hintVolume = f[7].varint(),
                        recordControl = f[8].varint(),
                        previewOnWatch = f[9].varint(),
                        livePhoto = f[10].varint(),
                        ratioQuality = f[11].varint(),
                        slowMotionDuration = f[12].varint(),
                        exerciseDelayDuration = f[13].varint(),
                    )
                },
                headset = groups[8]?.let { g ->
                    val f = Pb.fields(g)
                    HeadsetSetting(
                        wearDetection = f[1].varint(),
                        volumeAdaptive = f[2].varint(),
                        preventSoundLeak = f[3].varint(),
                        musicControl = f[4].varint(),
                    )
                },
                ota = groups[9]?.let { g ->
                    val f = Pb.fields(g)
                    val auto = f[2]?.let { Pb.fields(it) }
                    OtaSetting(
                        silentUpdate = f[1].varint(),
                        autoDownload = auto?.get(1).varint(),
                        autoUpgrade = auto?.get(2).varint(),
                    )
                },
                groups = groups,
                raw = payload.toHex(),
            )
        }
    }
}

/**
 * `SystemSetting.f5` 指示灯。
 *
 * ⚠️ 子字段语义来源是第三方实现（xiaolink `GlassesSettings`），本机只验证了**回包结构**，
 * 没有逐项改动实测。
 */
public data class IndicatorLight(
    /** 亮度：1 低 / 2 中 / 3 高 / 4 更低。 */
    val brightness: Long,
    /** 只读：支持的最低档。本机读到 30。*/
    val lowerLevelsSupported: Long,
    /** 充电指示灯：1 开 / 2 关。*/
    val chargingLight: Long,
)

/**
 * `SystemSetting.f6` AI 助手。
 *
 * ⚠️ 子字段语义来源同 [IndicatorLight]。
 */
public data class AiAssistant(
    val voiceWakeup: Long,
    val cameraAccess: Long,
    val collaborationWakeup: Long,
    /** 只读：支持的音色位。本机读到 1。*/
    val supportedTones: Long,
    /** 音色：1 Suger / 2 Pomelo。*/
    val voiceTone: Long,
    /** 眼动追踪。本机回包里该字段缺失。*/
    val eyeTracking: Long,
)

/**
 * `SystemSetting.f7` 媒体 / 拍摄。
 *
 * 字段号由 `protobuf 消息类.writeTo` **静态确定**
 * （实测），不是猜的。
 */
public data class MediaSetting(
    /** 拍摄模式：1 短按拍照长按录像 / 2 反之。*/
    val shootingMode: Long,
    /** 录像时长（秒）。本机读到 60。*/
    val videoDurationSec: Long,
    /** 自动同步：参考实现恒发 0。*/
    val autoSync: Long,
    /** 收音方向：1 全向 / 2 向前。*/
    val radioDirection: Long,
    /** 过热保护：1 开 / 2 关（有风险）。*/
    val tempControl: Long,
    /** 遮挡提醒：1 开 / 2 关。*/
    val shieldReminder: Long,
    /** 提示音音量：1 自动 / 2 高 / 3 中 / 4 低。*/
    val hintVolume: Long,
    /** 录音手势：1 双指单击 / 2 双指长按 / 3 无。*/
    val recordControl: Long,
    val previewOnWatch: Long,
    val livePhoto: Long,
    val ratioQuality: Long,
    val slowMotionDuration: Long,
    val exerciseDelayDuration: Long,
)

/**
 * `SystemSetting.f8` 耳机。
 *
 * 这四个字段与 `id=15` 的真实写帧**逐字节对齐**（实测），语义确认，不是静态推断。
 */
public data class HeadsetSetting(
    val wearDetection: Long,
    /** 自适应音量：本机读到 1。*/
    val volumeAdaptive: Long,
    val preventSoundLeak: Long,
    /** 媒体控制：1 单指单击 / 2 单指双击。*/
    val musicControl: Long,
)

/**
 * `SystemSetting.f9` 静默升级。**无论请求 bitmask 如何都会返回这一段。**
 *
 * 子字段语义来源同 [IndicatorLight]；本机只观测到 `f1=0`，`f2` 整段缺失。
 */
public data class OtaSetting(
    val silentUpdate: Long,
    val autoDownload: Long,
    val autoUpgrade: Long,
)

// ------------------------------------------------------------------ 内部

/** 取出**重复**出现的同一字段（`Pb.fields` 只保留最后一个，这里要全部）。 */
internal fun fieldsMulti(data: ByteArray, field: Int): List<ByteArray> {
    val out = mutableListOf<ByteArray>()
    var i = 0
    while (i < data.size) {
        var tag = 0L
        var j = readVarint(data, i) { tag = it }
        if (j < 0) break
        val f = (tag ushr 3).toInt()
        when ((tag and 7L).toInt()) {
            0 -> {
                var v = 0L
                j = readVarint(data, j) { v = it }
                if (j < 0) break
                if (f == field) out += minimalVarint(v)
            }
            2 -> {
                var len = 0L
                j = readVarint(data, j) { len = it }
                if (j < 0) break
                val n = len.toInt()
                if (n < 0 || j + n > data.size) break
                if (f == field) out += data.copyOfRange(j, j + n)
                j += n
            }
            1 -> j += 8
            5 -> j += 4
            else -> break
        }
        i = j
    }
    return out
}

private fun readVarint(data: ByteArray, start: Int, sink: (Long) -> Unit): Int {
    var result = 0L
    var shift = 0
    var i = start
    while (i < data.size) {
        val b = data[i].toInt() and 0xFF
        result = result or ((b and 0x7F).toLong() shl shift)
        i++
        if (b and 0x80 == 0) {
            sink(result)
            return i
        }
        shift += 7
        if (shift > 63) return -1
    }
    return -1
}

private fun minimalVarint(value: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    var v = value
    while (true) {
        if (v and 0x7FL.inv() == 0L) {
            out.write(v.toInt())
            return out.toByteArray()
        }
        out.write(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
}

internal fun ByteArray?.varint(): Long =
    if (this == null) 0L else Pb.varintValue(this)

internal fun ByteArray.utf8(): String = String(this, Charsets.UTF_8)

internal fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }
