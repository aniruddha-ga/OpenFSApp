package com.openlock.filesystem.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Named Encrypted File System Vault Container Manager.
 *
 * Requirements:
 * 1. Each vault is stored as an independent single-file container (`<VaultName>.ofs`) and backup (`<VaultName>.bak`).
 * 2. `getAvailableVaults()` lists all available vault container names.
 * 3. Opening a session loads `<VaultName>.ofs` into live app storage (`context.filesDir/encrypted_vault` & DB).
 * 4. Syncing updates `<VaultName>.ofs` and `<VaultName>.bak` with all DB records & encrypted payload files.
 * 5. Creating a new vault file creates an empty `<VaultName>.ofs` and `<VaultName>.bak`.
 */
class SingleFileContainerManager(private val context: Context) {
    private val containerDir = File(context.filesDir, "vault_containers").apply {
        if (!exists()) mkdirs()
    }

    val sessionWorkFile = File(context.filesDir, "session_fs_container.work")

    var activeVaultName: String = "PrimaryVault"
    var autoSyncUri: Uri? = null

    @Volatile
    var currentSaltBase64: String? = null
    @Volatile
    var currentHashBase64: String? = null

    data class ContainerMeta(val vaultName: String, val saltBase64: String, val hashBase64: String)

    fun getMasterContainerFile(vaultName: String = activeVaultName): File {
        val sanitized = sanitizeVaultName(vaultName)
        return File(containerDir, "$sanitized.ofs")
    }

    fun getBackupContainerFile(vaultName: String = activeVaultName): File {
        val sanitized = sanitizeVaultName(vaultName)
        return File(containerDir, "$sanitized.bak")
    }

    fun getAvailableVaults(): List<String> {
        val files = containerDir.listFiles { _, name -> name.endsWith(".ofs") } ?: emptyArray()
        return files.map { it.name.removeSuffix(".ofs") }.sorted()
    }

    fun sanitizeVaultName(name: String): String {
        return name.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").ifEmpty { "DefaultVault" }
    }

    @Synchronized
    fun openSession(vaultName: String = activeVaultName): Boolean {
        activeVaultName = sanitizeVaultName(vaultName)
        val master = getMasterContainerFile(activeVaultName)
        val backup = getBackupContainerFile(activeVaultName)
        val dbFile = context.getDatabasePath("openlock_vault_db")

        val targetContainer = when {
            master.exists() && master.length() > 0 -> master
            backup.exists() && backup.length() > 0 -> {
                backup.copyTo(master, overwrite = true)
                master
            }
            else -> null
        }

        if (targetContainer != null) {
            targetContainer.copyTo(sessionWorkFile, overwrite = true)
            if (!dbFile.exists() || dbFile.length() == 0L) {
                unpackVaultFromZip(sessionWorkFile)
            }
        } else {
            File(context.filesDir, "encrypted_vault").mkdirs()
            packVaultToZip(sessionWorkFile)
            sessionWorkFile.copyTo(master, overwrite = true)
            sessionWorkFile.copyTo(backup, overwrite = true)
        }
        return true
    }

    @Synchronized
    fun syncContainerState() {
        packVaultToZip(sessionWorkFile)
        val master = getMasterContainerFile(activeVaultName)
        val backup = getBackupContainerFile(activeVaultName)
        if (sessionWorkFile.exists() && sessionWorkFile.length() > 0) {
            sessionWorkFile.copyTo(master, overwrite = true)
            sessionWorkFile.copyTo(backup, overwrite = true)
            autoSyncUri?.let { uri ->
                syncToExternalStorage(context, uri)
            }
        }
    }

    @Synchronized
    fun closeSession() {
        syncContainerState()
        if (sessionWorkFile.exists()) {
            sessionWorkFile.delete()
        }
    }

    fun exportBackupContainer(context: Context, vaultName: String, destinationUri: Uri): Boolean {
        syncContainerState()
        val backup = getBackupContainerFile(vaultName)
        val master = getMasterContainerFile(vaultName)
        val sourceFile = if (backup.exists() && backup.length() > 0) backup else master

        if (!sourceFile.exists() || sourceFile.length() == 0L) return false

        return try {
            context.contentResolver.openFileDescriptor(destinationUri, "rwt")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { outputStream ->
                    outputStream.channel.truncate(0)
                    sourceFile.inputStream().buffered().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    outputStream.flush()
                    outputStream.fd.sync()
                }
            }
            true
        } catch (e: Exception) {
            Log.e("ContainerManager", "Failed to export backup container", e)
            false
        }
    }

    fun restoreBackupContainer(context: Context, vaultName: String, sourceUri: Uri): Boolean {
        val master = getMasterContainerFile(vaultName)
        val backup = getBackupContainerFile(vaultName)

        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                master.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            if (master.exists() && master.length() > 0) {
                master.copyTo(backup, overwrite = true)
                master.copyTo(sessionWorkFile, overwrite = true)
                unpackVaultFromZip(sessionWorkFile)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("ContainerManager", "Failed to restore backup container", e)
            false
        }
    }

    fun syncToExternalStorage(context: Context, destinationUri: Uri): Boolean {
        val master = getMasterContainerFile(activeVaultName)
        val sourceFile = if (master.exists() && master.length() > 0) master else sessionWorkFile

        if (!sourceFile.exists() || sourceFile.length() == 0L) return false

        return try {
            context.contentResolver.openFileDescriptor(destinationUri, "rwt")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { outputStream ->
                    outputStream.channel.truncate(0)
                    sourceFile.inputStream().buffered().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    outputStream.flush()
                    outputStream.fd.sync()
                }
            }
            true
        } catch (e: Exception) {
            Log.e("ContainerManager", "Failed to sync to external storage URI: $destinationUri", e)
            false
        }
    }

    fun computeFileHash(file: File): String? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("ContainerManager", "Failed to compute file hash", e)
            null
        }
    }

    fun computeUriHash(context: Context, uri: Uri): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            } ?: return null
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("ContainerManager", "Failed to compute uri hash", e)
            null
        }
    }

    fun readVaultMetadataFromZip(sourceZipFile: File): ContainerMeta? {
        if (!sourceZipFile.exists() || sourceZipFile.length() == 0L) return null
        return try {
            ZipInputStream(FileInputStream(sourceZipFile).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "db/vault_meta.json") {
                        val bytes = zis.readBytes()
                        val json = String(bytes, Charsets.UTF_8)
                        val vaultName = Regex("\"vaultName\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
                        val saltBase64 = Regex("\"saltBase64\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
                        val hashBase64 = Regex("\"hashBase64\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
                        if (saltBase64.isNotEmpty() && hashBase64.isNotEmpty()) {
                            return ContainerMeta(vaultName, saltBase64, hashBase64)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            null
        } catch (e: Exception) {
            Log.e("ContainerManager", "Failed to read vault metadata from zip", e)
            null
        }
    }

    fun readVaultMetadataFromUri(context: Context, uri: Uri): ContainerMeta? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "db/vault_meta.json") {
                            val bytes = zis.readBytes()
                            val json = String(bytes, Charsets.UTF_8)
                            val vaultName = Regex("\"vaultName\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
                            val saltBase64 = Regex("\"saltBase64\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
                            val hashBase64 = Regex("\"hashBase64\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
                            if (saltBase64.isNotEmpty() && hashBase64.isNotEmpty()) {
                                return ContainerMeta(vaultName, saltBase64, hashBase64)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e("ContainerManager", "Failed to read vault metadata from uri", e)
            null
        }
    }

    @Synchronized
    fun wipeContainers() {
        if (containerDir.exists()) {
            containerDir.deleteRecursively()
        }
        if (sessionWorkFile.exists()) {
            sessionWorkFile.delete()
        }
        val vaultStorageDir = File(context.filesDir, "encrypted_vault")
        if (vaultStorageDir.exists()) {
            vaultStorageDir.deleteRecursively()
        }
    }

    private fun packVaultToZip(targetZipFile: File) {
        VaultDatabase.checkpointDatabase(context)

        val dbFile = context.getDatabasePath("openlock_vault_db")
        val vaultStorageDir = File(context.filesDir, "encrypted_vault")

        val tempZip = File(containerDir, "${UUID.randomUUID()}.tmp")
        try {
            ZipOutputStream(FileOutputStream(tempZip).buffered()).use { zos ->
                if (!currentSaltBase64.isNullOrEmpty() && !currentHashBase64.isNullOrEmpty()) {
                    zos.putNextEntry(ZipEntry("db/vault_meta.json"))
                    val json = "{\"vaultName\":\"$activeVaultName\",\"saltBase64\":\"$currentSaltBase64\",\"hashBase64\":\"$currentHashBase64\"}"
                    zos.write(json.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                if (dbFile.exists()) {
                    zos.putNextEntry(ZipEntry("db/openlock_vault_db"))
                    dbFile.inputStream().buffered().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                if (vaultStorageDir.exists()) {
                    vaultStorageDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.name.endsWith(".enc")) {
                            zos.putNextEntry(ZipEntry("encrypted_vault/${file.name}"))
                            file.inputStream().buffered().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
            if (targetZipFile.exists()) targetZipFile.delete()
            if (!tempZip.renameTo(targetZipFile)) {
                tempZip.copyTo(targetZipFile, overwrite = true)
                tempZip.delete()
            }
        } catch (e: Exception) {
            Log.e("ContainerManager", "Failed to pack vault to zip", e)
            if (tempZip.exists()) tempZip.delete()
        }
    }

    private fun unpackVaultFromZip(sourceZipFile: File) {
        if (!sourceZipFile.exists() || sourceZipFile.length() == 0L) return

        VaultDatabase.closeDatabase()

        val dbFile = context.getDatabasePath("openlock_vault_db")
        val dbWalFile = File(dbFile.path + "-wal")
        val dbShmFile = File(dbFile.path + "-shm")
        val vaultStorageDir = File(context.filesDir, "encrypted_vault").apply {
            if (!exists()) mkdirs()
        }

        if (dbFile.exists()) dbFile.delete()
        if (dbWalFile.exists()) dbWalFile.delete()
        if (dbShmFile.exists()) dbShmFile.delete()
        if (vaultStorageDir.exists()) vaultStorageDir.deleteRecursively()
        vaultStorageDir.mkdirs()

        try {
            ZipInputStream(FileInputStream(sourceZipFile).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    if (!entry.isDirectory) {
                        if (entryName.startsWith("db/")) {
                            val targetFile = File(dbFile.parentFile, entryName.removePrefix("db/"))
                            targetFile.parentFile?.mkdirs()
                            FileOutputStream(targetFile).buffered().use { out -> zis.copyTo(out) }
                        } else if (entryName.startsWith("encrypted_vault/")) {
                            val fileName = entryName.removePrefix("encrypted_vault/")
                            val targetFile = File(vaultStorageDir, fileName)
                            FileOutputStream(targetFile).buffered().use { out -> zis.copyTo(out) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e("ContainerManager", "Failed to unpack vault from zip", e)
        }
    }
}
