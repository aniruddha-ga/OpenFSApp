package com.openlock.filesystem.crypto

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ChunkedCipherStream {
    val MAGIC_BYTES = ObfuscatedStrings.MAGIC_HEADER
    private const val VERSION: Byte = 0x01
    private const val CHUNK_SIZE = 64 * 1024 // 64 KB
    private const val GCM_TAG_LENGTH_BITS = 128

    fun encrypt(
        inputStream: InputStream,
        outputStream: OutputStream,
        secretKey: SecretKey
    ) {
        val salt = KeyManager.generateSalt()
        val baseIv = KeyManager.generateIv()

        // Write File Header
        outputStream.write(MAGIC_BYTES)
        outputStream.write(VERSION.toInt())
        outputStream.write(salt)
        outputStream.write(baseIv)

        val buffer = ByteArray(CHUNK_SIZE)
        var chunkIndex = 0
        var bytesRead: Int

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            val chunkIv = deriveChunkIv(baseIv, chunkIndex)
            val cipher = Cipher.getInstance(ObfuscatedStrings.AES_GCM_CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, chunkIv))

            val cipherText = cipher.doFinal(buffer, 0, bytesRead)

            // Write chunk metadata: Index (4B) + Plaintext Size (4B) + Ciphertext Size (4B)
            val metaBuffer = ByteBuffer.allocate(12)
            metaBuffer.putInt(chunkIndex)
            metaBuffer.putInt(bytesRead)
            metaBuffer.putInt(cipherText.size)

            outputStream.write(metaBuffer.array())
            outputStream.write(cipherText)

            chunkIndex++
        }
        outputStream.flush()
    }

    fun decrypt(
        inputStream: InputStream,
        outputStream: OutputStream,
        secretKey: SecretKey
    ) {
        val decryptedInputStream = openDecryptedStream(inputStream, secretKey)
        val buffer = ByteArray(CHUNK_SIZE)
        var bytesRead: Int
        while (decryptedInputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
        }
        outputStream.flush()
    }

    fun openDecryptedStream(
        inputStream: InputStream,
        secretKey: SecretKey
    ): InputStream {
        // Read Magic Bytes
        val magicBuffer = ByteArray(MAGIC_BYTES.size)
        require(readFully(inputStream, magicBuffer) == MAGIC_BYTES.size && magicBuffer.contentEquals(MAGIC_BYTES)) {
            "Invalid file format: Magic bytes mismatch"
        }

        // Read Version
        val version = inputStream.read()
        require(version == VERSION.toInt()) { "Unsupported file version: $version" }

        // Read Salt
        val salt = ByteArray(16)
        require(readFully(inputStream, salt) == 16) { "Truncated header salt" }

        // Read Base IV
        val baseIv = ByteArray(12)
        require(readFully(inputStream, baseIv) == 12) { "Truncated header base IV" }

        return object : InputStream() {
            private var currentChunkBuffer: ByteArrayInputStream? = null
            private var expectedChunkIndex = 0
            private var isEof = false

            override fun read(): Int {
                val b = ByteArray(1)
                val count = read(b, 0, 1)
                return if (count <= 0) -1 else b[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (isEof) return -1

                if (currentChunkBuffer == null || currentChunkBuffer!!.available() == 0) {
                    if (!loadNextChunk()) {
                        isEof = true
                        return -1
                    }
                }

                return currentChunkBuffer!!.read(b, off, len)
            }

            private fun loadNextChunk(): Boolean {
                val metaBufferBytes = ByteArray(12)
                val readCount = readFully(inputStream, metaBufferBytes)
                if (readCount < 12) {
                    return false
                }

                val metaBuffer = ByteBuffer.wrap(metaBufferBytes)
                val chunkIndex = metaBuffer.int
                val plaintextSize = metaBuffer.int
                val ciphertextSize = metaBuffer.int

                require(chunkIndex == expectedChunkIndex) {
                    "Chunk reordering detected! Expected $expectedChunkIndex, got $chunkIndex"
                }

                val cipherText = ByteArray(ciphertextSize)
                require(readFully(inputStream, cipherText) == ciphertextSize) {
                    "Truncated chunk $chunkIndex ciphertext"
                }

                val chunkIv = deriveChunkIv(baseIv, chunkIndex)
                val cipher = Cipher.getInstance(ObfuscatedStrings.AES_GCM_CIPHER)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, chunkIv))

                val plainText = cipher.doFinal(cipherText)
                require(plainText.size == plaintextSize) {
                    "Decrypted size mismatch in chunk $chunkIndex"
                }

                currentChunkBuffer = ByteArrayInputStream(plainText)
                expectedChunkIndex++
                return true
            }
        }
    }

    fun deriveChunkIv(baseIv: ByteArray, chunkIndex: Int): ByteArray {
        val iv = baseIv.clone()
        val indexBytes = ByteBuffer.allocate(4).putInt(chunkIndex).array()
        for (i in 0 until 4) {
            iv[iv.size - 4 + i] = (iv[iv.size - 4 + i].toInt() xor indexBytes[i].toInt()).toByte()
        }
        return iv
    }

    private fun readFully(inputStream: InputStream, buffer: ByteArray): Int {
        var bytesRead = 0
        while (bytesRead < buffer.size) {
            val count = inputStream.read(buffer, bytesRead, buffer.size - bytesRead)
            if (count == -1) break
            bytesRead += count
        }
        return bytesRead
    }
}
