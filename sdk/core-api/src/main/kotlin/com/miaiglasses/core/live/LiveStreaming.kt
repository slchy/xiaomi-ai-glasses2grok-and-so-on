package com.miaiglasses.core.live

import com.miaiglasses.core.config.GlassesProtocol
import com.miaiglasses.core.pb.Pb
import com.miaiglasses.core.wear.WearCodec
import java.io.ByteArrayOutputStream

/**
 * 直播（`Interconnection`，type = 23）。
 *
 * 信封由 [WearCodec.build] 负责：`type=23` 时容器字段号 = `23 + 2 = 25`，
 * 与协议分析得到的 `interconnection(id, payload) = packet(23, id, 25, payload)` **逐字节吻合**。
 *
 * ## 证据等级（别把没实测的当实测）
 *
 * | 项 | 证据 |
 * |---|---|
 * | `id=45` 查询的**完整帧** | ✅ **真机实测**：`08 17 10 2d ca 01 07 8a 02 04 08 01 10 00`（[query] 有黄金测试）|
 * | `id=45` 回复内容 | ✅ 真机实测：`System id=86` → `f4 → f33{ f1{SSID,…}, f2{密码} }`（眼镜热点凭据）|
 * | `id=46` 起/停的字段布局 | 🔍 协议分析 + 第三方实现标注「device-verified」（480p/1080p、mode 1–3）；**我们自己尚未实测** |
 * | `liveMode=1`（眼镜开热点）| ✅ 真机端到端跑通（720p30 / AAC 48k，见 `实测`）|
 * | `liveMode=4`（蓝牙通道推流）| ⚠️ **零实测** |
 *
 * ## 用之前必须知道的网络前提
 *
 * `liveMode=1` 时**眼镜自己开 AP**（`GLASSCAP`，凭据由 `id=45` 的回复下发），
 * 它没有上行链路。所以 `url` 指向的主机**必须在这张 AP 的网络里**
 * （实测：眼镜 `192.0.2.10`、服务端 `192.0.2.20`）。
 * 填家用局域网地址一定失败，表现为固件回 `errorCode=5 / RTMP 连接失败`。
 */
public object LiveStreaming {

    /** `Interconnection` 通道号。 */
    public const val TYPE: Int = GlassesProtocol.Wear.INTERCONNECTION

    public const val ID_QUERY: Int = 45
    public const val ID_REQUEST: Int = 46

    /** 眼镜上报的直播状态回执（**眼镜 → 手机**），`Interconnection` id=47。 */
    public const val ID_STATUS: Int = 47

    /** `Interconnection.f35`（`设置直播状态` → 35）。 */
    public const val F_STATUS: Int = 35

    /** 查询与请求的载体字段号 —— **两者不同**，别合并。 */
    private const val F_QUERY = 33
    private const val F_REQUEST = 34

    /** 分辨率。取值来自 `livestream/data/model`。 */
    public enum class Resolution(public val wire: Int) {
        P480(1),
        P720(2),
        P1080(3),
        ;

        public companion object {
            public fun of(wire: Int): Resolution? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** 帧率。协议只接受这四档。 */
    public enum class FrameRate(public val wire: Int) {
        FPS15(15),
        FPS24(24),
        FPS25(25),
        FPS30(30),
        ;

        public companion object {
            public fun of(wire: Int): FrameRate? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** 码率策略（协议里字段名叫 `bps`，实际传的是 `StreamMode`）。 */
    public enum class StreamMode(public val wire: Int) {
        AUTO(1),
        FRAME_RATE_FIRST(2),
        QUALITY_FIRST(3),
        ;

        public companion object {
            public fun of(wire: Int): StreamMode? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** 推流通道。`BLUETOOTH` **尚未实测**，别默认用它。 */
    public enum class Mode(public val wire: Int) {
        /** 眼镜开热点，手机/PC 加入（✅ 实测通） */
        HOTSPOT(1),

        /** 有线（⚠️ 未证） */
        WIRED(2),

        /** 走蓝牙通道（⚠️ 未证；若成立，SPP 上会出现持续大帧） */
        BLUETOOTH(4),
        ;

        public companion object {
            public fun of(wire: Int): Mode? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** `直播平台`：只有 RTMP 会用调用方给的 URL。 */
    private const val PLATFORM_RTMP = 0

    private const val CMD_START = 1
    private const val CMD_STOP = 2

    /**
     * 查询直播状态（`id=45`）。
     *
     * payload 是 `f33 { f1 = 1, f2 = 0 }` —— 这两个值取自**参考实现的真机抓包**，
     * 语义未确证，所以按原样发送、不做「优化」。
     * （第三方实现发的是**空** payload 也能拿到回复；两种都留记录。）
     */
    public fun query(): ByteArray = WearCodec.build(TYPE, ID_QUERY, queryPayload())

    /** `id=45` 的**载荷**（不含 WearPacket 信封），供 `SessionEngine.sendWearPacket` 使用。 */
    public fun queryPayload(): ByteArray {
        val payload = ByteArrayOutputStream(8)
        val inner = ByteArrayOutputStream(8)
        Pb.uint(inner, 1, 1L)
        Pb.uint(inner, 2, 0L)
        val innerBytes = inner.toByteArray()
        Pb.tag(payload, F_QUERY, 2)
        Pb.varint(payload, innerBytes.size.toLong())
        payload.write(innerBytes, 0, innerBytes.size)
        return payload.toByteArray()
    }

    /**
     * 开始推流（`id=46`）。
     *
     * payload = `f34 { f1 = 1(开始), f2 = { f1 resolution, f2 fps, f3 streamMode,
     * f4 直播平台(=0 RTMP), f5 liveMode, f6 url } }`。
     *
     * `直播平台` 显式写 0：proto3 可省略零值，但第三方实现是显式写的，
     * 这里保持一致，免得踩「省略即不同语义」的坑（`WearPacket` 已因同类问题吃过亏）。
     */
    public fun start(
        url: String,
        resolution: Resolution = Resolution.P720,
        fps: FrameRate = FrameRate.FPS30,
        streamMode: StreamMode = StreamMode.AUTO,
        mode: Mode = Mode.HOTSPOT,
    ): ByteArray = WearCodec.build(TYPE, ID_REQUEST, startPayload(url, resolution, fps, streamMode, mode))

    /** `id=46` 起播的**载荷**。 */
    public fun startPayload(
        url: String,
        resolution: Resolution = Resolution.P720,
        fps: FrameRate = FrameRate.FPS30,
        streamMode: StreamMode = StreamMode.AUTO,
        mode: Mode = Mode.HOTSPOT,
    ): ByteArray = request { body ->
        Pb.uint(body, 1, CMD_START.toLong())
        val param = ByteArrayOutputStream(32)
        Pb.uint(param, 1, resolution.wire.toLong())
        Pb.uint(param, 2, fps.wire.toLong())
        Pb.uint(param, 3, streamMode.wire.toLong())
        Pb.uint(param, 4, PLATFORM_RTMP.toLong())
        Pb.uint(param, 5, mode.wire.toLong())
        Pb.string(param, 6, url)
        Pb.bytes(body, 2, param.toByteArray())
    }

    /** 停止推流（`id=46`，`f34 { f1 = 2 }`）。 */
    public fun stop(): ByteArray = WearCodec.build(TYPE, ID_REQUEST, stopPayload())

    /** `id=46` 停播的**载荷**。 */
    public fun stopPayload(): ByteArray = request { body -> Pb.uint(body, 1, CMD_STOP.toLong()) }

    private inline fun request(build: (ByteArrayOutputStream) -> Unit): ByteArray {
        val inner = ByteArrayOutputStream(32)
        build(inner)
        val innerBytes = inner.toByteArray()
        val payload = ByteArrayOutputStream(innerBytes.size + 4)
        Pb.tag(payload, F_REQUEST, 2)
        Pb.varint(payload, innerBytes.size.toLong())
        payload.write(innerBytes, 0, innerBytes.size)
        return payload.toByteArray()
    }

    /**
     * `id=45` 的回复内容。
     *
     * 真机看到的是 `System(2) id=86` 的
     * `f4 → f33 { f1{ f1{SSID, f2, f3}, f2{密码} }, f2 = 1 }`。
     * 这里**容器位置两种都认**（顶层 `f33`，或 `f4 → f33`），
     * 并按「`f1`/`f2` 是 varint 枚举」与「`f1` 是嵌套配置消息」两种形态分别解读。
     */
    public data class Status(
        val supportedMode: Int?,
        val currentMode: Int?,
        val ssid: String?,
        val password: String?,
    )

    /**
     * 直播状态回执：`Interconnection` **id=47**，载荷 `f35 = LiveStreaming.Status{ f1 code, f2 rtspUrl }`。
     *
     * `code` 的取值来自参考实现的 switch
     * （`switch 分支表` + 内层 `0x3` 表，2026-11-11 逐个 pswitch 块读出）：
     *
     * | code | 含义（参考实现文案） |
     * | ---: | --- |
     * | 1 | 正常开启直播 |
     * | 2 | 正常停止直播 |
     * | 3 | 设备被占用，无法开始直播 |
     * | 4 | Wi-Fi 连接失败 |
     * | 5 | RTMP 连接失败 |
     * | 6 | 设备温度过高 |
     * | 7 | 电量过低 |
     * | 8 | 请先佩戴眼镜 |
     * | 9 | 相机指示灯被遮挡 |
     * | 10 | 设备忙 |
     * | 11 | 设备正在录音 |
     * | 12 | 设备正在通话 |
     * | 13 | 设备忙 |
     * | 14 | 设备正在升级 |
     * | 15 | 固件自动重连中 |
     *
     * 其它值参考实现走「收到未知固件报错」。
     *
     * `rtspUrl`：**参考实现从不读**（`取推流地址` 全仓零命中），这里只透传，不做任何推导
     * （实测）。
     */
    public data class LiveState(val code: Int, val rtspUrl: String) {

        /** `1`（开启）/ `2`（停止）都算正常。 */
        val isOk: Boolean get() = code == CODE_STARTED || code == CODE_STOPPED

        /** 参考实现文案（未知码给「未知固件报错（code=N）」）。 */
        val reason: String
            get() = when (code) {
                CODE_STARTED -> "正常开启直播"
                CODE_STOPPED -> "正常停止直播"
                3 -> "设备被占用，无法开始直播"
                4 -> "Wi-Fi 连接失败"
                5 -> "RTMP 连接失败"
                6 -> "设备温度过高"
                7 -> "电量过低"
                8 -> "请先佩戴眼镜"
                9 -> "相机指示灯被遮挡"
                10, 13 -> "设备忙"
                11 -> "设备正在录音"
                12 -> "设备正在通话"
                14 -> "设备正在升级"
                15 -> "固件自动重连中"
                else -> "未知固件报错（code=$code）"
            }

        public companion object {
            public const val CODE_STARTED: Int = 1
            public const val CODE_STOPPED: Int = 2
        }
    }

    /** 解析 `Interconnection{ f35 = LiveStreaming.Status{ f1 code, f2 rtspUrl } }`。 */
    public fun parseLiveState(payload: ByteArray): LiveState? {
        val status = Pb.fields(payload)[F_STATUS] ?: return null
        val f = Pb.fields(status)
        return LiveState(
            code = (f[1]?.let { Pb.varintValue(it) } ?: 0L).toInt(),
            rtspUrl = f[2]?.decodeToString() ?: "",
        )
    }

    public fun parseStatus(payload: ByteArray): Status? {
        val outer = Pb.fields(payload) ?: return null
        val carrier = outer[F_QUERY] ?: outer[4]?.let { Pb.fields(it)?.get(F_QUERY) } ?: return null
        val inner = Pb.fields(carrier) ?: return null

        fun asFields(raw: ByteArray?): Map<Int, ByteArray>? =
            raw?.let { runCatching { Pb.fields(it) }.getOrNull() }

        fun asVarint(raw: ByteArray?): Int? =
            raw?.let { runCatching { Pb.varintValue(it).toInt() }.getOrNull() }

        fun asString(raw: ByteArray?): String? =
            raw?.let { runCatching { it.decodeToString() }.getOrNull() }

        val f1 = inner[1]
        val f2 = inner[2]
        val cfg = asFields(f1) // 真机形态：f1 是「SSID + 密码」的配置消息
        val ssid = asString(cfg?.get(1)?.let { asFields(it)?.get(1) } ?: cfg?.get(1))
        val password = asString(cfg?.get(2)?.let { asFields(it)?.get(1) } ?: cfg?.get(2))

        return Status(
            supportedMode = if (cfg == null) asVarint(f1) else null,
            currentMode = if (asFields(f2) == null) asVarint(f2) else null,
            ssid = ssid,
            password = password,
        )
    }
}
