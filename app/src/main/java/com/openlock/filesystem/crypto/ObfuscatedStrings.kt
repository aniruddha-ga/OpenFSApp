package com.openlock.filesystem.crypto

import kotlin.experimental.xor

/**
 * Dynamic string decoder that prevents static analysis / bytecode decompilers (e.g. JADX)
 * from finding plaintext cryptography algorithm strings or header signatures.
 */
object ObfuscatedStrings {
    private fun decode(encoded: ByteArray, key: Byte): String {
        val bytes = ByteArray(encoded.size) { i -> encoded[i] xor key }
        return String(bytes, Charsets.US_ASCII)
    }

    private fun encode(plain: String, key: Byte): ByteArray {
        val bytes = plain.toByteArray(Charsets.US_ASCII)
        return ByteArray(bytes.size) { i -> bytes[i] xor key }
    }

    private val aesGcmEncrypted = encode("AES/GCM/NoPadding", 0x5A.toByte())
    private val pbkdf2Encrypted = encode("PBKDF2WithHmacSHA256", 0x37.toByte())
    private val sha256Encrypted = encode("SHA-256", 0x24.toByte())

    val AES_GCM_CIPHER: String get() = decode(aesGcmEncrypted, 0x5A.toByte())
    val PBKDF2_ALGORITHM: String get() = decode(pbkdf2Encrypted, 0x37.toByte())
    val SHA_256: String get() = decode(sha256Encrypted, 0x24.toByte())

    val MAGIC_HEADER: ByteArray get() = byteArrayOf(0x4F.toByte(), 0x50.toByte(), 0x4C.toByte(), 0x4B.toByte())
}
