package com.miaiglasses.core.crypto

import com.miaiglasses.core.config.GlassesProtocol
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 密码学原语。移植自 `原有的 Java 实现`。
 *
 * 协议要点（`SPP-CRYPTO-SOLVED.md`）：
 * - 数据帧：**AES-CTR**，`key == iv`，每帧 counter 从 0 重置，无 tag
 * - 握手：**AES-CCM**(tag 4B)，`nonce = appIV ‖ 0x0000000000000000`
 * - `HKDF salt = appRandom ‖ deviceRandom`
 * - `deviceSign = HMAC(deviceKey, deviceRandom ‖ appRandom)`
 * - `appSign    = HMAC(appKey,    appRandom ‖ deviceRandom)` ← **顺序相反**
 *
 * > 两个 HMAC 的 message 顺序相反是最容易写错的一处，且错了只会表现为
 * > 「设备不认」，没有任何错误码。测试里两条都单独覆盖。
 */
internal object Crypto {

    /** 密码学安全随机字节。测试可注入替代实现。 */
    fun randomBytes(n: Int): ByteArray {
        val b = ByteArray(n)
        SecureRandom().nextBytes(b)
        return b
    }

    // ------------------------------------------------------------------ HMAC / HKDF

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    /**
     * HKDF-SHA256（extract + expand）。Android / SunJCE 都没有内置实现。
     *
     * @param salt 为空或 null 时按 RFC 5869 用 32 字节全零
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val effectiveSalt = if (salt == null || salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var off = 0
        var i = 1
        while (off < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - off)
            t.copyInto(out, off, 0, n)
            off += n
            i++
        }
        return out
    }

    // ------------------------------------------------------------------ AES-CTR

    /** 数据帧加解密（对称）：`key == iv`。 */
    fun ctr(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "非法 AES 密钥长度 ${key.size}" }
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        return c.doFinal(data)
    }

    // ------------------------------------------------------------------ AES-CCM

    /**
     * tag 长度固定 4 字节。
     *
     * > 为什么不用 `Cipher.getInstance("AES/CCM/NoPadding")`：SunJCE 不带 CCM，
     * > Android 的 Cipher 支持的 AES 模式也只有 CBC/CFB/CTR/CTS/ECB/GCM/OFB。
     * > 所以按 RFC 3610 用 AES/ECB 作分组原语自己拼。本项目参数固定：
     * > nonce 12 字节、tag 4 字节、AAD 为空。
     */
    const val CCM_TAG_LEN = GlassesProtocol.Crypto.CCM_TAG_LENGTH

    private fun aesEcbBlock(key: ByteArray, block: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(block)
    }

    /** `A_i = L'(1B) ‖ nonce ‖ 计数器(L 字节，大端)` */
    private fun ccmCtrBlock(nonce: ByteArray, counter: Int, l: Int): ByteArray {
        val a = ByteArray(16)
        a[0] = (l - 1).toByte()
        nonce.copyInto(a, 1, 0, nonce.size)
        for (i in 0 until l) {
            a[15 - i] = (counter ushr (8 * i)).toByte()
        }
        return a
    }

    private fun ccmMac(key: ByteArray, nonce: ByteArray, plain: ByteArray, l: Int, flags: Int): ByteArray {
        val b0 = ByteArray(16)
        b0[0] = flags.toByte()
        nonce.copyInto(b0, 1, 0, nonce.size)
        val mlen = plain.size.toLong()
        for (i in 0 until l) {
            b0[15 - i] = (mlen ushr (8 * i)).toByte()
        }

        var x = aesEcbBlock(key, b0)
        var off = 0
        while (off < plain.size) {
            val blk = ByteArray(16)
            val n = minOf(16, plain.size - off)
            plain.copyInto(blk, 0, off, off + n)      // 末块右侧补零
            for (i in 0 until 16) blk[i] = (blk[i].toInt() xor x[i].toInt()).toByte()
            x = aesEcbBlock(key, blk)
            off += 16
        }
        return x
    }

    /** 握手加密：返回 `密文 ‖ tag(4B)`。 */
    fun ccmEncrypt(key: ByteArray, nonce12: ByteArray, plain: ByteArray): ByteArray {
        val l = 15 - nonce12.size
        require(l in 2..8) { "nonce 长度须为 7..13，实际 ${nonce12.size}" }
        val flags = (((CCM_TAG_LEN - 2) / 2) shl 3) or (l - 1)

        val x = ccmMac(key, nonce12, plain, l, flags)
        val s0 = aesEcbBlock(key, ccmCtrBlock(nonce12, 0, l))

        val out = ByteArray(plain.size + CCM_TAG_LEN)
        for (i in 0 until CCM_TAG_LEN) {
            out[plain.size + i] = (x[i].toInt() xor s0[i].toInt()).toByte()
        }
        var off = 0
        var blk = 1
        while (off < plain.size) {
            val s = aesEcbBlock(key, ccmCtrBlock(nonce12, blk, l))
            val n = minOf(16, plain.size - off)
            for (i in 0 until n) {
                out[off + i] = (plain[off + i].toInt() xor s[i].toInt()).toByte()
            }
            off += 16
            blk++
        }
        return out
    }

    /** 握手解密：tag 不符抛 [IllegalStateException]。 */
    fun ccmDecrypt(key: ByteArray, nonce12: ByteArray, cipher: ByteArray): ByteArray {
        require(cipher.size >= CCM_TAG_LEN) { "密文短于 tag 长度" }
        val l = 15 - nonce12.size
        require(l in 2..8) { "nonce 长度须为 7..13，实际 ${nonce12.size}" }
        val flags = (((CCM_TAG_LEN - 2) / 2) shl 3) or (l - 1)

        val pLen = cipher.size - CCM_TAG_LEN
        val plain = ByteArray(pLen)
        var off = 0
        var blk = 1
        while (off < pLen) {
            val s = aesEcbBlock(key, ccmCtrBlock(nonce12, blk, l))
            val n = minOf(16, pLen - off)
            for (i in 0 until n) {
                plain[off + i] = (cipher[off + i].toInt() xor s[i].toInt()).toByte()
            }
            off += 16
            blk++
        }

        val x = ccmMac(key, nonce12, plain, l, flags)
        val s0 = aesEcbBlock(key, ccmCtrBlock(nonce12, 0, l))
        val want = ByteArray(CCM_TAG_LEN) { (x[it].toInt() xor s0[it].toInt()).toByte() }
        val got = cipher.copyOfRange(pLen, cipher.size)
        if (!MessageDigest.isEqual(want, got)) {
            throw IllegalStateException("AES-CCM 解密失败：tag 不匹配")
        }
        return plain
    }

    // ------------------------------------------------------------------ 工具

    /** 定长比较，防时序侧信道。 */
    fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun concat(vararg parts: ByteArray): ByteArray {
        val n = parts.sumOf { it.size }
        val out = ByteArray(n)
        var off = 0
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }
}
