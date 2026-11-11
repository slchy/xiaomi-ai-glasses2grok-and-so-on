package com.miaiglasses.core.interconn

import com.miaiglasses.core.pb.Pb

/**
 * `Interconnection`(type=23) 的上报解析。
 *
 * ⚠️ **先纠正一处长期误标**：`id=30` 不是 `CAMERA_EVENT`。
 * `CAMERA_EVENT` 是**容器字段 30**（`Interconnection.f30`），而 `id=30` 是**命令编号**，
 * 对应 `PUBLISH_MIS_SPEC`。两套编号体系不同，不可混用（实测）。
 *
 * 真机字节证实了这一点（实测）：
 *
 * ```text
 * type=23 id=30  ->  b2 01 2e 12 2c …
 *                     ^^^^^ tag 0xB2 0x01 = varint 178 = (22 << 3) | 2  -> Interconnection.f22
 * ```
 *
 * 所以载荷是 `f22 MIS_PAYLOAD`，不是 f30。
 */
public object InterconnectionIds {
    public const val TYPE: Int = 23

    /** `PUBLISH_MIS_SPEC`：设备发布一条 MIoT spec 报文（按键动作走这条）。 */
    public const val ID_PUBLISH_MIS_SPEC: Int = 30

    /** 容器字段号：`MIS_PAYLOAD`。 */
    public const val FIELD_MIS_PAYLOAD: Int = 22
}

/**
 * 一条 MIoT spec 上报。
 *
 * @param spec `Mis.Payload.f1`，形如 `miot-spec-v2.0:…`；参考实现实机这条**不带**该字段
 * @param packet `Mis.Payload.f2` 原样字节 —— 里面是 MIoT spec 的 siid/aiid/piid 结构，
 *   **SDK 不解析**（见 KDoc 下方说明）
 * @param raw 整个 `Interconnection` 载荷的 hex，便于宿主自己进一步解
 */

public data class MisSpec(
    val spec: String,
    val packet: ByteArray,
    val raw: String,
) {
    public companion object {
        /**
         * 解析 `Interconnection id=30` 的载荷。
         *
         * **`packet` 内部不解析**：真机抓到的帧除了一个**递增计数**之外逐字节相同，
         * 也就是说**按键类型不在这条报文里**。
         *
         * 而且**条数也不固定**（实测）：
         *
         * | 动作 | `id=30` 条数 |
         * |---|---|
         * | 短按拍照 | 1 |
         * | 长按开始录像 | **2**（按下 + 长按判定）|
         * | 长按停止 | 1 |
         *
         * 所以别把「一条 id=30 = 一次按键」当规则。
         * 拍照还是录像，看 [CaptureAlert.hasRecorder]。
         */
        public fun parse(payload: ByteArray): MisSpec? {
            val misPayload = Pb.fields(payload)[InterconnectionIds.FIELD_MIS_PAYLOAD] ?: return null
            val f = Pb.fields(misPayload)
            return MisSpec(
                spec = f[1]?.toString(Charsets.UTF_8) ?: "",
                packet = f[2] ?: ByteArray(0),
                raw = payload.toHex(),
            )
        }
    }
}

/**
 * `System id=91` 的 `AlertStatus`（容器字段 **58**）里与拍摄相关的两段。
 *
 * ```text
 * AlertStatus { f6 camera{f1 status}  f8 recorder{f1 status}  … }
 * ```
 *
 * ## `相机状态` 的两个值由参考实现代码确证
 *
 * 参考实现把 `相机状态` 映射成两个 LiveEventBus 事件：
 *
 * ```java
 * 录制中 = (status == 5);          // 5 = 正在录像
 * 相机联动     = (status == 2 || 3);     // 2/3 = 「联机」态
 * ```
 *
 * 真机吻合：长按开始录像后出现 `camera=5`，停止后回到 `camera=0`；
 * 两次短按拍照全程没有出现 5。
 *
 * `camera=0` / `1` **在参考实现代码里从未与常量比较**，未命名。按**形态**推断（见下），
 * 不作为与之同级的「确证」：
 *
 * | 观测 | 形态 |
 * |---|---|
 * | 每次动作都以 `1` 开头、以 `0` 收尾 | 拍摄开始 → 结束 |
 * | 短按拍照：`1` 持续 **4.9 s / 5.3 s** | 与多曝光 LLHDR 拍摄+落盘耗时吻合 |
 * | 长按：`1` → `5`（录像中）→ 停止后 `1` → `0` | `1` 覆盖「按下到收尾」整段 |
 *
 * ⇒ 推断 `0 = 空闲`、`1 = 拍摄中（含保存）`。**这是形态推断，不是参考实现的命名**。
 *
 * ## `recorder.status` 语义未知
 *
 * 全仓静态分析扫过：`AlertStatus.status` 只有两处消费 ——
 * 直播页 ViewModel 按 `0 / 1 / 4` 分支，以及原样透传。**没有任何地方与 5 比较**，
 * 而我们真机录像时读到的是 `5` 与 `0`。所以 `recorder` 的值不解读，
 * 只作为「这条状态属不属于录像那一支」的存在性信号（见 [hasRecorder]）。
 */
public data class CaptureAlert(
    val cameraStatus: Int?,
    val recorderStatus: Int?,
    val raw: String,
) {
    /**
     * 是否正在录像：`相机状态 == 5`。
     *
     * 依据是参考实现的 `录制中` 事件，**不是猜的**。
     */
    val isRecording: Boolean get() = cameraStatus == CAMERA_RECORDING

    /**
     * `相机状态` 是否处于「联机」态（`2` 或 `3`）。
     *
     * 对应参考实现的 `相机联动` 事件。具体业务含义（直播？协同？）
     * 未验证 —— 只保证与参考实现判定一致。
     */
    val isJoint: Boolean get() = cameraStatus == CAMERA_JOINT_A || cameraStatus == CAMERA_JOINT_B

    /**
     * 这条状态是否与**录像**有关（`recorder` 段是否出现）。
     *
     * 真机：短按拍照 2 次都**没有** `recorder`；长按开始/停止各出现一次（值 `5` / `0`，
     * 数值语义未知）。所以它只能回答「是不是录像那一支」，不能回答「录没在录」——
     * 后者用 [isRecording]。
     */
    val hasRecorder: Boolean get() = recorderStatus != null

    /**
     * 是否在**拍摄中**（`相机状态 == 1`）。
     *
     * ⚠️ **形态推断，不是参考实现的命名** —— `参考实现` 只判 `5` 和 `2/3`，`1` 没名字。
     * 依据：每次动作都以 `1` 开头、`0` 收尾；短按两次分别持续 4.9 s / 5.3 s，
     * 与多曝光 LLHDR 拍摄+落盘耗时吻合；长按则是 `1` → `5` → `1` → `0`。
     */
    val isCapturing: Boolean get() = cameraStatus == CAMERA_CAPTURING

    /** 是否**空闲**（`相机状态 == 0`）。同为形态推断，见 [isCapturing]。 */
    val isIdle: Boolean get() = cameraStatus == CAMERA_IDLE

    public companion object {
        /** `AlertStatus` 在 `System` 里的容器字段号。 */
        public const val SYSTEM_FIELD_ALERT_STATUS: Int = 58

        /** `相机状态 = 5`，参考实现判定为「正在录像」。 */
        public const val CAMERA_RECORDING: Int = 5

        /** `相机状态 = 1`，**形态推断**为「拍摄中（含保存）」。参考实现未命名。 */
        public const val CAMERA_CAPTURING: Int = 1

        /** `相机状态 = 0`，**形态推断**为「空闲」。参考实现未命名。 */
        public const val CAMERA_IDLE: Int = 0

        /** `相机状态 = 2`，参考实现判定为「联机」。 */
        public const val CAMERA_JOINT_A: Int = 2

        /** `相机状态 = 3`，参考实现判定为「联机」。 */
        public const val CAMERA_JOINT_B: Int = 3

        public fun parse(payload: ByteArray): CaptureAlert? {
            val alert = Pb.fields(payload)[SYSTEM_FIELD_ALERT_STATUS] ?: return null
            val f = Pb.fields(alert)
            val camera = f[6]?.let { Pb.fields(it)[1] }?.let { Pb.varintValue(it).toInt() }
            val recorder = f[8]?.let { Pb.fields(it)[1] }?.let { Pb.varintValue(it).toInt() }
            if (camera == null && recorder == null) return null
            return CaptureAlert(cameraStatus = camera, recorderStatus = recorder, raw = payload.toHex())
        }
    }
}

private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }
