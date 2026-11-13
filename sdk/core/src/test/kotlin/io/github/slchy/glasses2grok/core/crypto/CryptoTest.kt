package io.github.slchy.glasses2grok.core.crypto

import io.github.slchy.glasses2grok.core.Hex
import io.github.slchy.glasses2grok.core.l1.GoldenVectors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 密码学原语对账。三条向量来自 `tests/golden/vectors.txt`，
 * 由 `tests/golden/gen.py` 用独立实现（Python `hashlib` / `cryptography`）产出。
 */
class CryptoTest {

    @Test
    fun `HKDF-SHA256 与独立实现一致`() {
        val rows = GoldenVectors.primitiveRows("hkdf")
        assertEquals(listOf("hkdf64"), rows.map { it.name })

        for (row in rows) {
            val p = row.script.split("|")
            val ikm = Hex.decode(p[0])
            val salt = Hex.decode(p[1])
            val info = p[2].toByteArray(Charsets.UTF_8)
            val len = p[3].toInt()
            assertEquals(row.expectedHex, Hex.encode(Crypto.hkdfSha256(ikm, salt, info, len)))
        }
    }

    @Test
    fun `AES-CTR 与独立实现一致 - key 等于 iv`() {
        val rows = GoldenVectors.primitiveRows("ctr")
        assertEquals(listOf("ctr"), rows.map { it.name })

        for (row in rows) {
            val p = row.script.split("|")
            val key = Hex.decode(p[0])
            val plain = Hex.decode(p[1])
            assertEquals(row.expectedHex, Hex.encode(Crypto.ctr(key, plain)), "CTR 加密")
            assertEquals(Hex.encode(plain), Hex.encode(Crypto.ctr(key, Hex.decode(row.expectedHex))), "CTR 解密")
        }
    }

    @Test
    fun `AES-CCM tag4 与独立实现一致`() {
        val rows = GoldenVectors.primitiveRows("ccm")
        assertEquals(listOf("ccm24"), rows.map { it.name })

        for (row in rows) {
            val p = row.script.split("|")
            val key = Hex.decode(p[0])
            val nonce = Hex.decode(p[1])
            val plain = Hex.decode(p[2])
            val enc = Crypto.ccmEncrypt(key, nonce, plain)
            assertEquals(row.expectedHex, Hex.encode(enc), "CCM 加密")
            assertEquals(Hex.encode(plain), Hex.encode(Crypto.ccmDecrypt(key, nonce, enc)), "CCM 解密")
        }
    }

    @Test
    fun `CCM tag 不符必须抛异常`() {
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { 0x11 }
        val enc = Crypto.ccmEncrypt(key, nonce, "hello".toByteArray())
        enc[enc.size - 1] = (enc[enc.size - 1].toInt() xor 0xFF).toByte()
        assertFailsWith<IllegalStateException> { Crypto.ccmDecrypt(key, nonce, enc) }
    }

    @Test
    fun `CCM 跨块边界仍然正确`() {
        val key = ByteArray(16) { (it * 7).toByte() }
        val nonce = ByteArray(12) { (it * 3).toByte() }
        for (n in listOf(0, 1, 15, 16, 17, 32, 33, 100)) {
            val plain = ByteArray(n) { (it and 0xFF).toByte() }
            val enc = Crypto.ccmEncrypt(key, nonce, plain)
            assertEquals(n + Crypto.CCM_TAG_LEN, enc.size)
            assertTrue(plain.contentEquals(Crypto.ccmDecrypt(key, nonce, enc)), "长度 $n")
        }
    }

    @Test
    fun `HKDF 长度不是 32 的整数倍时也正确`() {
        val ikm = ByteArray(16) { it.toByte() }
        val salt = ByteArray(32) { 0x5A }
        val info = "miwear-auth".toByteArray()
        for (len in listOf(1, 16, 40, 64, 100)) {
            assertEquals(len, Crypto.hkdfSha256(ikm, salt, info, len).size)
        }
        // 前缀性：短长度必须是长长度的前缀（HKDF 的结构性质）
        val a = Crypto.hkdfSha256(ikm, salt, info, 16)
        val b = Crypto.hkdfSha256(ikm, salt, info, 64)
        assertTrue(b.copyOfRange(0, 16).contentEquals(a))
    }

    @Test
    fun `salt 为空时按 RFC 5869 用 32 字节零`() {
        val ikm = ByteArray(16) { 1 }
        val info = ByteArray(0)
        assertTrue(
            Crypto.hkdfSha256(ikm, null, info, 32)
                .contentEquals(Crypto.hkdfSha256(ikm, ByteArray(32), info, 32)),
        )
    }
}
