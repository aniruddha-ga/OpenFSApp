package com.openlock.filesystem.ui.preview

import android.media.MediaDataSource
import android.util.Log
import com.openlock.filesystem.crypto.ChunkedCipherStream
import com.openlock.filesystem.crypto.ObfuscatedStrings
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 100% In-RAM On-Demand Decrypting MediaDataSource.
 * - ZERO unencrypted bytes are ever written to ROM / disk storage.
 * - Decrypts 64 KB chunks directly in RAM on demand.
 * - Caches up to 32 recently decrypted chunks in an in-RAM LRU Cache (~2 MB RAM max).
 * - Provides direct O(1) random-access seeking for Android MediaPlayer / SurfaceView / Audio.
 */
class RamDecryptedMediaDataSource(
    private val encryptedFile: File,
    private val secretKey: SecretKey
) : MediaDataSource() {

    private val randomAccessFile = RandomAccessFile(encryptedFile, "r")
    private val baseIv: ByteArray
    private val totalPlaintextSize: Long

    // In-RAM LRU Cache: Stores up to 32 decrypted 64 KB chunks in RAM (~2 MB RAM max)
    private val ramChunkCache = object : LinkedHashMap<Int, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean {
            return size > 32
        }
    }

    init {
        val header = ByteArray(33)
        randomAccessFile.readFully(header)
        val magic = header.copyOfRange(0, 4)
        require(magic.contentEquals(ObfuscatedStrings.MAGIC_HEADER)) { "Invalid file header magic" }

        baseIv = header.copyOfRange(21, 33)

        val fileLength = encryptedFile.length()
        val payloadLength = fileLength - 33
        if (payloadLength <= 0) {
            totalPlaintextSize = 0L
        } else {
            val fullChunks = payloadLength / 65564L
            val remainder = payloadLength % 65564L
            if (remainder == 0L) {
                totalPlaintextSize = fullChunks * 65536L
            } else {
                val lastPlaintext = (remainder - 28L).coerceAtLeast(0L)
                totalPlaintextSize = fullChunks * 65536L + lastPlaintext
            }
        }
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= totalPlaintextSize || size <= 0) return -1

        val bytesToRead = minOf(size.toLong(), totalPlaintextSize - position).toInt()
        var bytesCopied = 0

        while (bytesCopied < bytesToRead) {
            val currentPos = position + bytesCopied
            val chunkIndex = (currentPos / 65536L).toInt()
            val offsetInChunk = (currentPos % 65536L).toInt()

            val plainTextChunk = getDecryptedChunkFromRam(chunkIndex) ?: break
            val bytesAvailableInChunk = plainTextChunk.size - offsetInChunk
            if (bytesAvailableInChunk <= 0) break

            val copyCount = minOf(bytesToRead - bytesCopied, bytesAvailableInChunk)
            System.arraycopy(plainTextChunk, offsetInChunk, buffer, offset + bytesCopied, copyCount)
            bytesCopied += copyCount
        }

        return if (bytesCopied > 0) bytesCopied else -1
    }

    @Synchronized
    private fun getDecryptedChunkFromRam(chunkIndex: Int): ByteArray? {
        // 1. Return from RAM LRU Cache if present
        ramChunkCache[chunkIndex]?.let { return it }

        // 2. Direct O(1) Random Access Seek on disk
        val diskOffset = 33L + chunkIndex * 65564L
        if (diskOffset >= encryptedFile.length()) return null

        return try {
            randomAccessFile.seek(diskOffset)

            val meta = ByteArray(12)
            randomAccessFile.readFully(meta)
            val metaBuffer = ByteBuffer.wrap(meta)
            val index = metaBuffer.int
            val plaintextSize = metaBuffer.int
            val ciphertextSize = metaBuffer.int

            val cipherText = ByteArray(ciphertextSize)
            randomAccessFile.readFully(cipherText)

            val chunkIv = ChunkedCipherStream.deriveChunkIv(baseIv, index)
            val cipher = Cipher.getInstance(ObfuscatedStrings.AES_GCM_CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, chunkIv))
            val plainText = cipher.doFinal(cipherText)
            require(plainText.size == plaintextSize) {
                "Decrypted size mismatch in chunk $index: expected $plaintextSize, got ${plainText.size}"
            }

            // Cache in RAM
            ramChunkCache[chunkIndex] = plainText
            plainText
        } catch (e: Exception) {
            Log.e("RamMediaDataSource", "Failed to decrypt chunk $chunkIndex in RAM", e)
            null
        }
    }

    override fun getSize(): Long = totalPlaintextSize

    @Synchronized
    override fun close() {
        ramChunkCache.clear()
        try {
            randomAccessFile.close()
        } catch (_: Exception) {}
    }
}
