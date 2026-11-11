package com.miaiglasses.core.crypto

import com.miaiglasses.core.config.GlassesProtocol

/**
 * 一个会话的四把密钥 + 两条签名。
 *
 * ```
 * k = HKDF-SHA256(ikm = token16, salt = appRandom‖deviceRandom, info = "miwear-auth", L = 64)
 * deviceKey = k[0:16]   appKey = k[16:32]   deviceIV = k[32:36]   appIV = k[36:40]
 *
 * deviceSign = HMAC(deviceKey, deviceRandom‖appRandom)    // 校验设备
 * appSign    = HMAC(appKey,    appRandom‖deviceRandom)    // 交给设备校验   ← 顺序相反
 * ```
 *
 * **密钥每连接重算**，不是缺陷而是前向保密；能长期复用的只有 `token`。
 * 因此本类不提供任何序列化/持久化方法 —— 结构上让「把会话密钥存下来」不可能发生。
 *
 * 用完必须 [destroy]：`keys = null` 只是丢引用，密钥数组还在堆里等 GC。
 * `XiaoLink` 的参考实现也会 `k.clear()`，两边对齐。
 */
internal class SessionKeys private constructor(
    val deviceKey: ByteArray,
    val appKey: ByteArray,
    val deviceIv: ByteArray,
    val appIv: ByteArray,
) {
    @Volatile
    private var destroyed = false
    companion object {
        const val INFO = GlassesProtocol.Crypto.HKDF_INFO
        const val LENGTH = GlassesProtocol.Crypto.HKDF_LENGTH

        fun derive(token16: ByteArray, appRandom: ByteArray, deviceRandom: ByteArray): SessionKeys {
            require(token16.size == GlassesProtocol.Crypto.TOKEN_SIZE) { "token 必须是 16 字节（hex 解码后），实际 ${token16.size}" }
            require(appRandom.size == GlassesProtocol.Crypto.RANDOM_SIZE) { "appRandom 必须是 16 字节，实际 ${appRandom.size}" }
            require(deviceRandom.size == GlassesProtocol.Crypto.RANDOM_SIZE) { "deviceRandom 必须是 16 字节，实际 ${deviceRandom.size}" }

            val k = Crypto.hkdfSha256(
                ikm = token16,
                salt = Crypto.concat(appRandom, deviceRandom),
                info = INFO.toByteArray(Charsets.UTF_8),
                length = LENGTH,
            )
            return SessionKeys(
                deviceKey = k.copyOfRange(
                    GlassesProtocol.Crypto.DEVICE_KEY_OFFSET,
                    GlassesProtocol.Crypto.DEVICE_KEY_OFFSET + GlassesProtocol.Crypto.KEY_LENGTH,
                ),
                appKey = k.copyOfRange(
                    GlassesProtocol.Crypto.APP_KEY_OFFSET,
                    GlassesProtocol.Crypto.APP_KEY_OFFSET + GlassesProtocol.Crypto.KEY_LENGTH,
                ),
                deviceIv = k.copyOfRange(
                    GlassesProtocol.Crypto.DEVICE_IV_OFFSET,
                    GlassesProtocol.Crypto.DEVICE_IV_OFFSET + GlassesProtocol.Crypto.IV_LENGTH,
                ),
                appIv = k.copyOfRange(
                    GlassesProtocol.Crypto.APP_IV_OFFSET,
                    GlassesProtocol.Crypto.APP_IV_OFFSET + GlassesProtocol.Crypto.IV_LENGTH,
                ),
            )
        }

        /** 校验设备签名：`HMAC(deviceKey, deviceRandom‖appRandom)`。 */
        fun expectedDeviceSign(deviceKey: ByteArray, deviceRandom: ByteArray, appRandom: ByteArray): ByteArray =
            Crypto.hmacSha256(deviceKey, Crypto.concat(deviceRandom, appRandom))

        /** 生成我方向设备出示的签名：`HMAC(appKey, appRandom‖deviceRandom)`。 */
        fun appSign(appKey: ByteArray, appRandom: ByteArray, deviceRandom: ByteArray): ByteArray =
            Crypto.hmacSha256(appKey, Crypto.concat(appRandom, deviceRandom))
    }

    /** 校验设备出示的 `deviceSign`。 */
    fun verifyDevice(deviceRandom: ByteArray, appRandom: ByteArray, deviceSign: ByteArray): Boolean {
        requireAlive()
        return Crypto.equal(expectedDeviceSign(deviceKey, deviceRandom, appRandom), deviceSign)
    }

    /** 本方向设备出示的 `appSign`。 */
    fun signApp(appRandom: ByteArray, deviceRandom: ByteArray): ByteArray {
        requireAlive()
        return appSign(appKey, appRandom, deviceRandom)
    }

    /** 握手期 CompanionDevice 的 nonce：`appIV ‖ 0^8`。 */
    fun companionNonce(): ByteArray {
        requireAlive()
        return Crypto.concat(appIv, ByteArray(GlassesProtocol.Crypto.CCM_NONCE_ZERO_PAD))
    }

    /** 数据帧加密（手机 → 眼镜）。 */
    fun encryptApp(plain: ByteArray): ByteArray {
        requireAlive()
        return Crypto.ctr(appKey, plain)
    }

    /** 数据帧解密（眼镜 → 手机）。 */
    fun decryptDevice(cipher: ByteArray): ByteArray {
        requireAlive()
        return Crypto.ctr(deviceKey, cipher)
    }

    /**
     * 抹掉密钥材料。幂等；此后任何加解密都抛 [IllegalStateException]。
     *
     * 为什么不用 `keys = null` 了事：那样只丢了引用，四个密钥数组仍留在堆里，
     * 直到 GC 把它们搬到别处（而且 GC 不保证清零）。密钥是每连接重算的，
     * 用一次就抹一次成本可以忽略。
     */
    fun destroy() {
        destroyed = true
        deviceKey.fill(0)
        appKey.fill(0)
        deviceIv.fill(0)
        appIv.fill(0)
    }

    val isDestroyed: Boolean get() = destroyed

    private fun requireAlive() {
        check(!destroyed) { "会话密钥已抹除，不能再用" }
    }
}
