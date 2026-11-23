package com.openlock.filesystem.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

class ChunkedCipherStreamTest {

    @Test
    fun testObfuscatedStringsDecodedCorrectly() {
        assertEquals("AES/GCM/NoPadding", ObfuscatedStrings.AES_GCM_CIPHER)
        assertEquals("PBKDF2WithHmacSHA256", ObfuscatedStrings.PBKDF2_ALGORITHM)
        assertEquals("SHA-256", ObfuscatedStrings.SHA_256)
    }

    @Test
    fun testKeyDerivationAndHashing() {
        val pin = "1234"
        val salt = KeyManager.generateSalt()
        val hash = KeyManager.hashPin(pin, salt)
        val key = KeyManager.deriveKeyFromPin(pin.toCharArray(), salt)

        assertEquals(32, hash.size) // 256 bits = 32 bytes
        assertEquals("AES", key.algorithm)
    }

    @Test
    fun testEncryptAndDecryptSmallStream() {
        val masterKey = KeyManager.generateRandomKey()
        val originalText = "Hello OpenLock! Secure file system level encryption test string."
        val originalBytes = originalText.toByteArray(Charsets.UTF_8)

        val encryptedOutputStream = ByteArrayOutputStream()
        ChunkedCipherStream.encrypt(
            inputStream = ByteArrayInputStream(originalBytes),
            outputStream = encryptedOutputStream,
            secretKey = masterKey
        )

        val cipherBytes = encryptedOutputStream.toByteArray()

        val decryptedOutputStream = ByteArrayOutputStream()
        ChunkedCipherStream.decrypt(
            inputStream = ByteArrayInputStream(cipherBytes),
            outputStream = decryptedOutputStream,
            secretKey = masterKey
        )

        val decryptedBytes = decryptedOutputStream.toByteArray()
        assertArrayEquals(originalBytes, decryptedBytes)
        assertEquals(originalText, String(decryptedBytes, Charsets.UTF_8))
    }

    @Test
    fun testEncryptAndDecryptMultiChunkStream() {
        val masterKey = KeyManager.generateRandomKey()
        // 200 KB random payload (spans 4 x 64 KB chunks)
        val originalBytes = ByteArray(200 * 1024)
        SecureRandom().nextBytes(originalBytes)

        val encryptedOutputStream = ByteArrayOutputStream()
        ChunkedCipherStream.encrypt(
            inputStream = ByteArrayInputStream(originalBytes),
            outputStream = encryptedOutputStream,
            secretKey = masterKey
        )

        val cipherBytes = encryptedOutputStream.toByteArray()

        val decryptedOutputStream = ByteArrayOutputStream()
        ChunkedCipherStream.decrypt(
            inputStream = ByteArrayInputStream(cipherBytes),
            outputStream = decryptedOutputStream,
            secretKey = masterKey
        )

        val decryptedBytes = decryptedOutputStream.toByteArray()
        assertArrayEquals(originalBytes, decryptedBytes)
    }

    @Test
    fun testCorruptedCiphertextFails() {
        val masterKey = KeyManager.generateRandomKey()
        val originalBytes = "Top Secret Vault Data Payload".toByteArray(Charsets.UTF_8)

        val encryptedOutputStream = ByteArrayOutputStream()
        ChunkedCipherStream.encrypt(
            inputStream = ByteArrayInputStream(originalBytes),
            outputStream = encryptedOutputStream,
            secretKey = masterKey
        )

        val cipherBytes = encryptedOutputStream.toByteArray()

        // Tamper with a ciphertext byte in the first chunk payload
        cipherBytes[cipherBytes.size - 5] = (cipherBytes[cipherBytes.size - 5].toInt() xor 0xFF).toByte()

        try {
            val decryptedOutputStream = ByteArrayOutputStream()
            ChunkedCipherStream.decrypt(
                inputStream = ByteArrayInputStream(cipherBytes),
                outputStream = decryptedOutputStream,
                secretKey = masterKey
            )
            fail("Expected decryption failure due to GCM authentication tag mismatch")
        } catch (_: Exception) {
            // Success: GCM tag integrity check caught tampering
        }
    }

    @Test
    fun testRamDecryptedMediaDataSourceReadsCorrectly() {
        val masterKey = KeyManager.generateRandomKey()
        // 150 KB payload spanning multiple 64 KB chunks
        val originalBytes = ByteArray(150 * 1024)
        SecureRandom().nextBytes(originalBytes)

        val tempFile = java.io.File.createTempFile("test_enc", ".tmp")
        try {
            tempFile.outputStream().use { outStream ->
                ChunkedCipherStream.encrypt(
                    inputStream = ByteArrayInputStream(originalBytes),
                    outputStream = outStream,
                    secretKey = masterKey
                )
            }

            val dataSource = com.openlock.filesystem.ui.preview.RamDecryptedMediaDataSource(tempFile, masterKey)
            assertEquals(originalBytes.size.toLong(), dataSource.size)

            val buffer = ByteArray(originalBytes.size)
            val readBytes = dataSource.readAt(0, buffer, 0, buffer.size)

            assertEquals(originalBytes.size, readBytes)
            assertArrayEquals(originalBytes, buffer)
            dataSource.close()
        } finally {
            tempFile.delete()
        }
    }
}
