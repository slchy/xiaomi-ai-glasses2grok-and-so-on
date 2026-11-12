package com.miaiglasses.core.session

import com.miaiglasses.core.control.ControlDecision
import com.miaiglasses.core.wear.WearPacket

/**
 * 会话状态。
 *
 * ```
 * Idle ──open()──▶ Connecting ──socket──▶ Handshaking ──DeviceConfirm──▶ Ready
 *                     │                      │                            │
 *                     │ 失败                 │ 认证失败                   │ I/O 断
 *                     ▼                      ▼                            ▼
 *                  Failed ◀──────────────────┘                    Reconnecting ──退避──▶ Connecting
 *                                                                       │ close()
 * 任意状态 ──close()──▶ Closing ──▶ Closed（吸收态）
 * ```
 *
 * **`Failed` 不是终态** —— 宿主可再次 `open()`。`Closed` 是吸收态。
 */
public enum class SessionState {
    IDLE,
    CONNECTING,
    HANDSHAKING,
    READY,
    RECONNECTING,
    CLOSING,
    CLOSED,
    FAILED,
}

/** 交给宿主的事件。所有载荷都是**副本**，宿主可任意持有。 */
public sealed interface GlassesEvent {

    public data class StateChanged(val state: SessionState, val reason: String = "") : GlassesEvent

    /**
     * 上行语音：一个 MASS 载荷块，**mSBC 原始字节，未解码**。
     *
     * 一帧的典型长度是 582 字节 = **10 × 57 字节 mSBC 帧**（7.5 ms/帧 ⇒ 75 ms）。
     * 早先这里写「57 字节/帧」，把「mSBC 帧」和「MASS 块」混成了一件事。
     *
     * MASS 头里**没有序号也没有时间戳**（见 `Mass`），所以下面两个字段是本端生成的：
     * @property seq 本会话内语音块序号，从 0 开始连续自增。跳号 = 本端漏了一次回调。
     * @property timestampMs 到达时刻（引擎时钟，毫秒；生产环境中是 `CLOCK_BOOTTIME`）。
     * @property droppedBefore 这块之前有多少块因 **RX 队列溢出**被丢（0 = 连续）。
     *   `> 0` 就说明音频流在这里有真实的空洞 —— ASR 侧应按 `timestampMs` 的间隔判断，
     *   不能假设块与块之间无缝。
     */
    public class Audio(
        public val data: ByteArray,
        public val seq: Long = 0,
        public val timestampMs: Long = 0,
        public val droppedBefore: Long = 0,
    ) : GlassesEvent {
        override fun toString(): String =
            "Audio(${data.size}B seq=$seq t=$timestampMs droppedBefore=$droppedBefore)"
    }

    /** 一张完整的 JPEG（FIRST + FOLLOW 已拼接）。 */
    public class Image(public val jpeg: ByteArray) : GlassesEvent {
        override fun toString(): String = "Image(${jpeg.size}B)"
    }

    /** 控制面命令。`decision` 说明它是被处理还是被丢弃、为什么。 */
    public class Control(public val packet: WearPacket, public val decision: ControlDecision) : GlassesEvent {
        override fun toString(): String = "Control($decision)"
    }

    /**
     * 按键动作上报：`Interconnection`(23) `PUBLISH_MIS_SPEC`(30)。
     *
     * ⚠️ **这条报文里没有「短按还是长按」** —— 真机四次按键除了递增计数外逐字节相同。
     * 拍摄类型要从 [CaptureAlert] 或 `Media id=16` 的文件列表推断，判断留给上层。
     */
    public class MisSpecEvent(public val misSpec: com.miaiglasses.core.interconn.MisSpec) : GlassesEvent {
        override fun toString(): String =
            "MisSpec(spec=${misSpec.spec}, packet=${misSpec.packet.size}B)"
    }

    /** 拍摄相关状态：`System`(2) `id=91` 的 `AlertStatus{camera, recorder}` 有变化时发出。 */
    public class CaptureAlertEvent(public val alert: com.miaiglasses.core.interconn.CaptureAlert) : GlassesEvent {
        override fun toString(): String =
            "CaptureAlert(camera=${alert.cameraStatus}, recorder=${alert.recorderStatus})"
    }

    /**
     * 佩戴状态变化：`System`(2) **id=79**，载荷 `f49{f2=state}`，
     * **1 = 佩戴中 / 2 = 未佩戴**（真机双源确证，见 [com.miaiglasses.core.interconn.WearStatus]）。
     *
     * 为什么单独抛出来：`Media id=20` 的「开始录音」在**未佩戴**时会被设备拒绝
     * （`code=5`），宿主需要这个状态来判断能不能录。
     *
     * 注意：**摘下**（`state=2`）设备还会顺带发 `Voice id=0 SYNC_INSTRUCTION_LIST` +
     * `id=6 SYNC_VOICE_ERROR{4}`（4/4 命中）；戴上时零帧。
     */
    public class WearChanged(public val status: com.miaiglasses.core.interconn.WearStatus) : GlassesEvent {
        override fun toString(): String = "WearChanged(${status})"
    }

    /**
     * 录音状态变化：`Media`(18) **id=22 `设备侧录音状态上报`**（设备主动上报）。
     *
     * **这就是「双指在眼镜上开录音」的通报**：真机实测（实测）双指按下与
     * 再按停止各来一条，带文件路径与 size；而手机自己发 `Media id=20` 时**不会有**这条。
     * 想感知设备侧录音就订阅它，想自己触发就用 `startRecording()` / `stopRecording()`。
     */
    public class RecordStatusEvent(public val status: com.miaiglasses.core.query.RecordStatus) : GlassesEvent {
        override fun toString(): String =
            "RecordStatus(type=${status.mediaFile?.type}, status=${status.status}, " +
                "duration=${status.duration?.current}ms)"
    }

    /**
     * 电池状态：`System`(2) **id=97**（设备主动上报，载荷 `f63 = DeviceStatus.Battery`）。
     *
     * 参考实现从不发这条（`设置电池状态` 零命中），只会收；收到的 `Battery` 直接塞进
     * `DeviceStatus` 槽位，与 `id=1` 查询回包共用（实测）。
     * 想要「立刻问一次」用 `GlassesSession.deviceStatus()`。
     */
    public class BatteryChanged(public val status: com.miaiglasses.core.query.DeviceStatus) : GlassesEvent {
        override fun toString(): String =
            "BatteryChanged(${status.batteryPercent}%, chargeStatus=${status.chargeStatus})"
    }

    /**
     * 热点开/关的回包：`System`(2) **id=88 / id=89**，载荷
     * `f56 = WiFiAP.Result{ f1 code, f2 WiFiAP{ f1 ssid, f2 password, f3 gateway } }`。
     *
     * 宿主拿它去连眼镜 AP（**SSID 是设备名、密码每次随机**），连上后才有 `:8080`
     * 的 HTTP 文件服务。`code=0` 表示成功。
     */
    public class WifiApChanged(public val info: com.miaiglasses.core.wifi.WifiApInfo) : GlassesEvent {
        override fun toString(): String =
            "WifiApChanged(code=${info.code}, ssid=${info.ssid}, gateway=${info.gateway})"
    }

    /**
     * P2P 建组回包：`System`(2) **id=99**，载荷
     * `f66 = WiFiP2P.Result{ f1 code, f2 ipAddress, f3 taskId }`。
     *
     * 手机当 GO 自建组（静默无弹窗）→ 发 `id=99` → 眼镜作为 client 加入并报出**自己的**
     * P2P IP；拿到 IP 就能直接 `http://<ip>:8080/v1/...` 取文件（实测）。
     * 判断成功用 [com.miaiglasses.core.wifi.WifiP2pResult.isOk]（`code == 0` 且 IP 非空）。
     */
    public class WifiP2pChanged(public val result: com.miaiglasses.core.wifi.WifiP2pResult) : GlassesEvent {
        override fun toString(): String =
            "WifiP2pChanged(code=${result.code}, ip=${result.ip}, taskId=${result.taskId})"
    }

    /**
     * `L2 ch=2 MASS` 下行的**完整文件**（`Media 17/18` 取缩略图的响应）。
     *
     * 真机：`Identifier{id, thumbnail=true}` 一请求，设备就把 **PNG 100×100** 分 5 片推回来
     * （片头 6 B、首片带 identifier、末尾 4 B 尾标），组装见 `MassFileAssembler`。
     * 大文件（原图/录像）不会走这条，`实测`。
     */
    public class MassFile(public val file: com.miaiglasses.core.mass.MassFileData) : GlassesEvent {
        override fun toString(): String = "MassFile(${file.identifier}, ${file.bytes.size}B, type=${file.mediaType})"
    }

    /**
     * 基础状态上报的回包：`System`(2) **id=80**，载荷 `f51 = ReportData.Result{ f1 type, f2 status }`。
     *
     * `type`：1 = 设备日志、2 = 统计、3 = dump 日志；`status`：0 = 成功、1 = 无数据、
     * 2 = 直传 URL、3 = 忙、10 = 失败。参考实现每 1 小时自动发一次 `id=80`
     * 。
     */
    public class ReportDataReceived(public val result: com.miaiglasses.core.query.ReportDataResult) : GlassesEvent {
        override fun toString(): String = "ReportDataReceived(type=${result.type}, status=${result.status})"
    }

    /**
     * 直播状态回执：`Interconnection`(23) **id=47**，载荷
     * `f35 = LiveStreaming.Status{ f1 code, f2 rtspUrl }`。
     *
     * `code`：1 正常开启 / 2 正常停止 / 3 设备被占用 / 4 Wi-Fi 失败 / 5 RTMP 失败 /
     * 6 温度过高 / 7 电量过低 / 8 未佩戴 / 9 指示灯被挡 / 10、13 设备忙 /
     * 11 正在录音 / 12 正在通话 / 14 正在升级 / 15 固件重连中。
     * `reason` 直接给参考实现文案；`rtspUrl` 参考实现从不读，仅透传（实测）。
     */
    public class LiveStateChanged(public val state: com.miaiglasses.core.live.LiveStreaming.LiveState) : GlassesEvent {
        override fun toString(): String =
            "LiveStateChanged(code=${state.code}, ${state.reason})"
    }

    /** 错误。**不含 payload 与密钥**，只带类型/长度/原因。 */
    public class Error(
        public val code: Int,
        public val message: String,
        public val cause: Throwable? = null,
    ) : GlassesEvent {
        override fun toString(): String = "Error($code, $message)"
    }

    /** 诊断：原始帧摘要。仅在 `diagnostics = true` 时发出。 */
    public class Diagnostic(public val message: String) : GlassesEvent {
        override fun toString(): String = "Diagnostic($message)"
    }

    /** 语音链路的上行事件（`Voice` type=14 的 0/6/10）。 */
    public class Voice(public val event: com.miaiglasses.core.voice.VoiceEvent) : GlassesEvent {
        override fun toString(): String = "Voice($event)"
    }
}

/**
 * 字节管道。`:core` 不碰 Android，也不建线程 —— 线程与 socket 在 `:transport`。
 *
 * 实现方约定：`write` **同步阻塞**写完全部字节；失败抛异常（由引擎归为传输错误）。
 */
public interface ByteTransport {
    public fun write(bytes: ByteArray)
    public fun close()
}
