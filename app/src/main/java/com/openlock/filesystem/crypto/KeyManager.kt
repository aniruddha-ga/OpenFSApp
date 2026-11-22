package com.openlock.filesystem.crypto

import android.security.keystore.KeyProperties
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object KeyManager {
    private const val KEY_SIZE = 256
    private const val ITERATION_COUNT = 10_000
    private const val SALT_SIZE = 16
    private const val GCM_IV_LENGTH = 12

    private val secureRandom = SecureRandom()

    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_SIZE)
        secureRandom.nextBytes(salt)
        return salt
    }

    fun generateIv(): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)
        return iv
    }

    fun generateRandomKey(): SecretKey {
        val keyBytes = ByteArray(KEY_SIZE / 8)
        secureRandom.nextBytes(keyBytes)
        return SecretKeySpec(keyBytes, KeyProperties.KEY_ALGORITHM_AES)
    }

    /**
     * Hashes the user PIN or Vault Password using SHA-256 + Salt for verification.
     * ONLY this salted hash is stored on ROM; NO secret keys are saved to disk.
     */
    fun hashPassword(password: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance(ObfuscatedStrings.SHA_256)
        digest.update(salt)
        return digest.digest(password.toByteArray(Charsets.UTF_8))
    }

    fun hashPin(pin: String, salt: ByteArray): ByteArray = hashPassword(pin, salt)

    /**
     * Derives the 256-bit AES File System Key directly in RAM from Password + Salt using PBKDF2.
     */
    fun deriveKeyFromPassword(password: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password, salt, ITERATION_COUNT, KEY_SIZE)
        val factory = SecretKeyFactory.getInstance(ObfuscatedStrings.PBKDF2_ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        SecurityUtils.wipeMemory(password)
        val secretKey = SecretKeySpec(keyBytes, KeyProperties.KEY_ALGORITHM_AES)
        SecurityUtils.wipeMemory(keyBytes)
        return secretKey
    }

    fun deriveKeyFromPin(pin: CharArray, salt: ByteArray): SecretKey = deriveKeyFromPassword(pin, salt)
}
