package com.miaiglasses.core.handshake

import com.miaiglasses.core.config.GlassesOptions
import com.miaiglasses.core.config.GlassesProtocol
import com.miaiglasses.core.crypto.Crypto
import com.miaiglasses.core.crypto.SessionKeys
import com.miaiglasses.core.pb.Pb
import com.miaiglasses.core.sar.SarConfig
import com.miaiglasses.core.sar.buildNegotiationConfig
import com.miaiglasses.core.wear.WearCodec
import com.miaiglasses.core.wear.WearPacket
import java.io.ByteArrayOutputStream

/**
 * 三步握手的应用层状态机（不含 I/O、不含线程）。
 *
 * ```
 * ① CMD 协商   L1START_REQ(RSP)  → isCmdExchanged
 * ② AppVerify  Account.f30{f1=appRandom}    (type=1, id=26, 明文)
 * ③ DeviceVerify ← Account.f31{f1=deviceRandom, f2=deviceSign}
 *    校验 deviceSign = HMAC(deviceKey, deviceRandom‖appRandom)
 * ④ AppConfirm → Account.f32{f1=appSign, f2=AES-CCM(CompanionDevice)}
 * ⑤ DeviceConfirm ← (type=1, id=31)  ⇒ 完成
 * ```
 *
 * <h3>为什么这里必须结构化解析</h3>
 *
 * 旧实现用 `indexOf(body, {FA, 01})` 找 DeviceVerify，再按固定偏移取 2/18/20/52 字节
 * （``  遗留）。`deviceSign` 是 32 字节随机值，**出现 `FA 01` 的概率约 0.05%**，
 * 一旦命中就会解析出错误的随机数，表现为「偶发握手失败」。
 * 本实现按 protobuf 字段号取，并在测试里用「payload 内含 `FA 01`」的对抗用例覆盖。
 */
internal class HandshakeDriver(
    private val token16: ByteArray,
    private val model: String,
    private val locale: String,
    private val sendCmd: (cmd: Int, body: ByteArray) -> Unit,
    private val sendWear: (type: Int, id: Int, body: ByteArray, encrypted: Boolean) -> Unit,
    private val now: () -> Long,
    private val random: (Int) -> ByteArray = { com.miaiglasses.core.crypto.Crypto.randomBytes(it) },
    private val negotiateTimeoutMs: Long = GlassesOptions.NEGOTIATE_TIMEOUT_MS,
    private val verifyTimeoutMs: Long = GlassesOptions.VERIFY_TIMEOUT_MS,
    private val confirmTimeoutMs: Long = GlassesOptions.CONFIRM_TIMEOUT_MS,
) {
    enum class State { IDLE, NEGOTIATING, VERIFYING, CONFIRMING, DONE, FAILED }

    /** 失败原因。**不含任何密钥/token 片段** —— 错误信息会被日志带走。 */
    sealed interface Failure {
        data object NegotiateTimeout : Failure
        data object VerifyTimeout : Failure
        data object ConfirmTimeout : Failure

        /** 未收到或解析不出 DeviceVerify。 */
        data object NoDeviceVerify : Failure

        /** `deviceSign` 校验失败 ⇒ token 不匹配（或设备侧用别的 token）。 */
        data object BadDeviceSign : Failure

        data class Error(val message: String) : Failure
    }

    var state: State = State.IDLE
        private set

    var failure: Failure? = null
        private set

    /** 成功后才有值。 */
    var keys: SessionKeys? = null
        private set

    var appRandom: ByteArray = ByteArray(0)
        private set
    var deviceRandom: ByteArray = ByteArray(0)
        private set

    private var deadlineMs: Long = 0

    val isDone: Boolean get() = state == State.DONE
    val isFailed: Boolean get() = state == State.FAILED

    /** ① 发起协商。 */
    fun start() {
        if (state != State.IDLE) return
        state = State.NEGOTIATING
        deadlineMs = now() + negotiateTimeoutMs
        sendCmd(SarConfig.CMD_L1START_REQ, buildConfig())
    }

    /**
     * 回到初始态，清掉上一次会话的全部中间状态。
     *
     * **重连必须调用** —— 否则 `start()` 会因为 `state != IDLE` 直接返回，
     * 表现为「重连成功但一个字节都没发出去」。
     */
    fun reset() {
        state = State.IDLE
        failure = null
        keys = null
        appRandom = ByteArray(0)
        deviceRandom = ByteArray(0)
        deadlineMs = 0
    }

    /** 协商完成（`isCmdExchanged == true`）后调用，进入 ②。 */
    fun onNegotiated() {
        if (state != State.NEGOTIATING) return
        appRandom = random(GlassesProtocol.Crypto.RANDOM_SIZE)
        state = State.VERIFYING
        deadlineMs = now() + verifyTimeoutMs
        sendWear(ACCOUNT_TYPE, ID_APP_VERIFY, buildAppVerify(appRandom), false)
    }

    /** 收到一条已解密的 WearPacket。 */
    fun onPacket(p: WearPacket) {
        when {
            state == State.VERIFYING && p.type == ACCOUNT_TYPE && p.id == ID_DEVICE_VERIFY ->
                handleDeviceVerify(p.payload)

            state == State.CONFIRMING && p.type == ACCOUNT_TYPE && p.id == ID_DEVICE_CONFIRM ->
                state = State.DONE

            else -> Unit        // 其它报文不在这里处理（控制面另有路由）
        }
    }

    /** 超时检查。由引擎线程周期性调用。 */
    fun tick(nowMs: Long = now()) {
        if (state == State.DONE || state == State.FAILED || state == State.IDLE) return
        if (nowMs < deadlineMs) return
        fail(
            when (state) {
                State.NEGOTIATING -> Failure.NegotiateTimeout
                State.VERIFYING -> Failure.VerifyTimeout
                else -> Failure.ConfirmTimeout
            },
        )
    }

    private fun handleDeviceVerify(payload: ByteArray) {
        // Account(1) → field 31 → { f1 = deviceRandom, f2 = deviceSign }
        val account = Pb.fields(payload)
        val deviceVerify = account[GlassesProtocol.Handshake.FIELD_DEVICE_VERIFY] ?: return fail(Failure.NoDeviceVerify)
        val f = Pb.fields(deviceVerify)
        val dr = f[1] ?: return fail(Failure.NoDeviceVerify)
        val ds = f[2] ?: return fail(Failure.NoDeviceVerify)
        if (dr.size != GlassesProtocol.Crypto.RANDOM_SIZE || ds.size != 32) return fail(Failure.NoDeviceVerify)

        deviceRandom = dr

        val k = try {
            SessionKeys.derive(token16, appRandom, deviceRandom)
        } catch (e: IllegalArgumentException) {
            return fail(Failure.Error(e.message ?: "密钥派生失败"))
        }

        if (!k.verifyDevice(deviceRandom, appRandom, deviceSign = ds)) {
            return fail(Failure.BadDeviceSign)
        }

        keys = k
        state = State.CONFIRMING
        deadlineMs = now() + confirmTimeoutMs
        val appSign = k.signApp(appRandom, deviceRandom)
        val companion = Crypto.ccmEncrypt(k.appKey, k.companionNonce(), buildCompanion(model, locale))
        sendWear(ACCOUNT_TYPE, ID_APP_CONFIRM, buildAppConfirm(appSign, companion), false)
    }

    private fun fail(f: Failure) {
        failure = f
        state = State.FAILED
        // 失败路径先抹掉已派生的密钥，避免残留可用的会话密钥
        keys = null
    }

    // ------------------------------------------------------------------ 报文构造

    private fun buildConfig(): ByteArray = buildNegotiationConfig()

    companion object {
        const val ACCOUNT_TYPE = GlassesProtocol.Handshake.ACCOUNT_TYPE
        const val ID_APP_VERIFY = GlassesProtocol.Handshake.ID_APP_VERIFY
        const val ID_DEVICE_VERIFY = GlassesProtocol.Handshake.ID_DEVICE_VERIFY
        const val ID_APP_CONFIRM = GlassesProtocol.Handshake.ID_APP_CONFIRM

        /**
         * 设备的 `DeviceConfirm` **与 `AppConfirm` 用同一个 id（27）**。
         *
         * 曾错写成 31（凭「序号递增」推测）⇒ 真机三步握手卡在
         * `等待 DeviceConfirm 超时`：设备确实在 `type=1 id=27` 上回了确认，
         * 驱动只认 31 所以视而不见。
         * 依据：真机抓包里双向只有 `(1,26)` 与 `(1,27)`，**不存在 id=31**；
         * 与 `DeviceVerify` 复用 `AppVerify` 的 26 完全对称。
         */
        const val ID_DEVICE_CONFIRM = GlassesProtocol.Handshake.ID_DEVICE_CONFIRM

        /** `Account.f30 = AppVerify{f1 = appRandom}` → WearPacket(1, 26)。 */
        fun buildAppVerify(appRandom16: ByteArray): ByteArray {
            val inner = ByteArrayOutputStream().also { Pb.bytes(it, 1, appRandom16) }.toByteArray()
            return ByteArrayOutputStream().also { Pb.bytes(it, GlassesProtocol.Handshake.FIELD_APP_VERIFY, inner) }.toByteArray()
        }

        /** `Account.f32 = AppConfirm{f1 = appSign, f2 = 加密后的 CompanionDevice}` → WearPacket(1, 27)。 */
        fun buildAppConfirm(appSign32: ByteArray, encryptedCompanion: ByteArray): ByteArray {
            val inner = ByteArrayOutputStream().also {
                Pb.bytes(it, 1, appSign32)
                Pb.bytes(it, 2, encryptedCompanion)
            }.toByteArray()
            return ByteArrayOutputStream().also { Pb.bytes(it, GlassesProtocol.Handshake.FIELD_APP_CONFIRM, inner) }.toByteArray()
        }

        /** CompanionDevice：`f1 = 0`、`f2 = 31.0f`、`f3 = model`、`f5 = locale`。 */
        fun buildCompanion(model: String, locale: String): ByteArray =
            ByteArrayOutputStream().also {
                Pb.uint(it, GlassesProtocol.Handshake.COMPANION_FIELD_DEVICE_TYPE, GlassesProtocol.Handshake.COMPANION_DEVICE_TYPE.toLong())
                Pb.float32(it, GlassesProtocol.Handshake.COMPANION_FIELD_SYSTEM_VERSION, GlassesProtocol.Handshake.COMPANION_SYSTEM_VERSION)
                Pb.string(it, GlassesProtocol.Handshake.COMPANION_FIELD_MODEL, model)
                Pb.string(it, GlassesProtocol.Handshake.COMPANION_FIELD_LOCALE, locale)
            }.toByteArray()

        /** 构造 DeviceVerify 报文 —— **仅测试与诊断用**（真实报文由设备产生）。 */
        fun buildDeviceVerifyForTest(deviceRandom16: ByteArray, deviceSign32: ByteArray): ByteArray {
            val inner = ByteArrayOutputStream().also {
                Pb.bytes(it, 1, deviceRandom16)
                Pb.bytes(it, 2, deviceSign32)
            }.toByteArray()
            val account = ByteArrayOutputStream().also { Pb.bytes(it, GlassesProtocol.Handshake.FIELD_DEVICE_VERIFY, inner) }.toByteArray()
            return WearCodec.build(ACCOUNT_TYPE, ID_DEVICE_VERIFY, account)
        }
    }
}
