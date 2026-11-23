package com.openlock.filesystem.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.openlock.filesystem.crypto.ChunkedCipherStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.crypto.SecretKey

data class FolderImportResult(
    val rootFolderName: String,
    val filesImported: Int,
    val foldersCreated: Int,
    val failedFiles: Int
)

class VaultRepository(
    private val context: Context,
    dao: VaultDao? = null
) {
    private val _injectedDao = dao
    private val vaultDao: VaultDao get() = _injectedDao ?: VaultDatabase.getDatabase(context).vaultDao()
    val containerManager = SingleFileContainerManager(context)

    private val vaultStorageDir = File(context.filesDir, "encrypted_vault").apply {
        if (!exists()) mkdirs()
    }

    private val tempCacheDir = File(context.cacheDir, "vault_tmp").apply {
        if (!exists()) mkdirs()
    }

    fun getItemsInFolder(parentFolderId: String?): Flow<List<VaultItemEntity>> {
        val sanitizedParentId = parentFolderId?.ifEmpty { null }
        Log.d("VaultSQL", "getItemsInFolder() called for parentFolderId: '$sanitizedParentId'")
        return vaultDao.getItemsInFolder(sanitizedParentId).onEach { items ->
            Log.d("VaultSQL", "getItemsInFolder('$sanitizedParentId') emitted ${items.size} item(s): ${items.map { it.name }}")
        }
    }

    fun getAllItems(): Flow<List<VaultItemEntity>> =
        vaultDao.getAllItems().onEach { items ->
            Log.d("VaultSQL", "getAllItems() emitted ${items.size} item(s) total: ${items.map { "${it.name} (parent: ${it.parentFolderId})" }}")
        }

    fun searchItems(query: String): Flow<List<VaultItemEntity>> =
        vaultDao.searchItems(query).onEach { items ->
            Log.d("VaultSQL", "searchItems('$query') emitted ${items.size} item(s)")
        }

    fun getTotalFileCount(): Flow<Int> = vaultDao.getTotalFileCount()

    fun getTotalVaultSize(): Flow<Long?> = vaultDao.getTotalVaultSize()

    fun getContainerFileSize(vaultName: String): Long {
        containerManager.syncContainerState()
        val master = containerManager.getMasterContainerFile(vaultName)
        val work = containerManager.sessionWorkFile
        return when {
            master.exists() && master.length() > 0 -> master.length()
            work.exists() && work.length() > 0 -> work.length()
            else -> 0L
        }
    }

    suspend fun exportVaultContainer(vaultName: String, destinationUri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            containerManager.exportBackupContainer(context, vaultName, destinationUri)
        }
    }

    suspend fun restoreVaultContainer(vaultName: String, sourceUri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            containerManager.restoreBackupContainer(context, vaultName, sourceUri)
        }
    }

    suspend fun createFolder(name: String, parentFolderId: String?) {
        withContext(Dispatchers.IO) {
            val folderEntity = VaultItemEntity(
                id = UUID.randomUUID().toString(),
                parentFolderId = parentFolderId?.ifEmpty { null },
                name = name,
                mimeType = "inode/directory",
                sizeBytes = 0L,
                isFolder = true,
                createdAtEpochMs = System.currentTimeMillis(),
                physicalFileName = ""
            )
            Log.d("VaultSQL", "Inserting folder into Room DB: $folderEntity")
            vaultDao.insertItem(folderEntity)
            Log.d("VaultSQL", "Folder inserted successfully into Room DB: ${folderEntity.id}")
            containerManager.syncContainerState()
        }
    }

    suspend fun getItemById(itemId: String): VaultItemEntity? = withContext(Dispatchers.IO) {
        vaultDao.getItemById(itemId)
    }

    suspend fun importFile(
        uri: Uri,
        parentFolderId: String?,
        masterKey: SecretKey,
        shredSource: Boolean = false
    ): VaultItemEntity {
        return withContext(Dispatchers.IO) {
            val fileName = getFileNameFromUri(uri)?.takeIf { it.isNotBlank() } ?: "imported_file_${System.currentTimeMillis()}"
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val physicalUuid = UUID.randomUUID().toString()
            val tempFile = File(vaultStorageDir, "$physicalUuid.tmp")
            val finalFile = File(vaultStorageDir, "$physicalUuid.enc")

            if (!vaultStorageDir.exists()) {
                vaultStorageDir.mkdirs()
            }

            try {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Ignore if uri does not support persistable permissions
                }

                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Unable to open input stream for file: $uri")

                inputStream.use { inStream ->
                    FileOutputStream(tempFile).use { outputStream ->
                        ChunkedCipherStream.encrypt(inStream, outputStream, masterKey)
                        outputStream.flush()
                        outputStream.fd.sync()
                    }
                }

                check(tempFile.exists() && tempFile.length() > 0) { "Encryption produced empty file (0 bytes)" }

                if (!tempFile.renameTo(finalFile)) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }

                check(finalFile.exists() && finalFile.length() > 0) { "Encrypted file missing or empty on private app storage: ${finalFile.absolutePath}" }

                val itemEntity = VaultItemEntity(
                    id = UUID.randomUUID().toString(),
                    parentFolderId = parentFolderId?.ifEmpty { null },
                    name = fileName,
                    mimeType = mimeType,
                    sizeBytes = finalFile.length(),
                    isFolder = false,
                    createdAtEpochMs = System.currentTimeMillis(),
                    physicalFileName = finalFile.name
                )
                Log.d("VaultSQL", "Inserting file into Room DB: $itemEntity")
                vaultDao.insertItem(itemEntity)
                Log.d("VaultSQL", "File inserted successfully into Room DB: ${itemEntity.id}")
                containerManager.syncContainerState()

                if (shredSource) {
                    shredAndDeleteSourceUri(context, uri)
                }

                itemEntity
            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                if (finalFile.exists()) finalFile.delete()
                Log.e("VaultRepository", "Failed to import file $uri", e)
                throw e
            }
        }
    }

    private fun shredAndDeleteSourceUri(context: Context, uri: Uri) {
        try {
            try {
                var fileSize: Long = -1L
                if (uri.scheme == "content") {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                                fileSize = cursor.getLong(sizeIndex)
                            }
                        }
                    }
                }

                if (uri.scheme == "file" && uri.path != null) {
                    val file = File(uri.path!!)
                    if (file.exists() && file.isFile) {
                        fileSize = file.length()
                        java.io.RandomAccessFile(file, "rws").use { raf ->
                            val buffer = ByteArray(64 * 1024)
                            var remaining = fileSize
                            while (remaining > 0) {
                                val toWrite = minOf(buffer.size.toLong(), remaining).toInt()
                                raf.write(buffer, 0, toWrite)
                                remaining -= toWrite
                            }
                            raf.fd.sync()
                        }
                        file.delete()
                        return
                    }
                } else {
                    context.contentResolver.openOutputStream(uri, "rwt")?.use { outStream ->
                        val buffer = ByteArray(64 * 1024)
                        if (fileSize > 0) {
                            var remaining = fileSize
                            while (remaining > 0) {
                                val toWrite = minOf(buffer.size.toLong(), remaining).toInt()
                                outStream.write(buffer, 0, toWrite)
                                remaining -= toWrite
                            }
                        } else {
                            outStream.write(buffer)
                        }
                        outStream.flush()
                    }
                }
            } catch (e: Exception) {
                Log.w("VaultRepository", "Could not overwrite source file before deletion: ${e.message}")
            }

            var deleted = false
            if (DocumentFile.isDocumentUri(context, uri)) {
                deleted = android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
            }
            if (!deleted) {
                val docFile = DocumentFile.fromSingleUri(context, uri)
                if (docFile != null && docFile.exists()) {
                    deleted = docFile.delete()
                }
            }
            if (!deleted) {
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (_: Exception) {}
            }
            Log.d("VaultRepository", "Shred source URI result for $uri: deleted=$deleted")
        } catch (e: Exception) {
            Log.e("VaultRepository", "Failed to shred/delete source file: $uri", e)
        }
    }

    suspend fun importFiles(
        uris: List<Uri>,
        parentFolderId: String?,
        masterKey: SecretKey,
        shredSource: Boolean = false
    ): Pair<Int, Int> {
        var successCount = 0
        var failCount = 0
        for (uri in uris) {
            try {
                importFile(uri, parentFolderId, masterKey, shredSource = shredSource)
                successCount++
            } catch (e: Exception) {
                e.printStackTrace()
                failCount++
            }
        }
        return Pair(successCount, failCount)
    }

    suspend fun importFolder(
        treeUri: Uri,
        targetParentFolderId: String?,
        masterKey: SecretKey,
        onProgressUpdate: ((currentFile: String, filesImported: Int) -> Unit)? = null
    ): FolderImportResult {
        return withContext(Dispatchers.IO) {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IOException("Unable to access directory at URI: $treeUri")

            val rootName = rootDoc.name?.takeIf { it.isNotBlank() } ?: "ImportedFolder"

            var filesImportedCount = 0
            var foldersCreatedCount = 0
            var failedFilesCount = 0

            suspend fun processDirectoryContents(dir: DocumentFile, currentVaultParentId: String?) {
                val children = dir.listFiles()
                for (child in children) {
                    if (child.isDirectory) {
                        val subFolderName = child.name?.takeIf { it.isNotBlank() } ?: "Folder"
                        val subFolderEntity = VaultItemEntity(
                            id = UUID.randomUUID().toString(),
                            parentFolderId = currentVaultParentId?.ifEmpty { null },
                            name = subFolderName,
                            mimeType = "inode/directory",
                            sizeBytes = 0L,
                            isFolder = true,
                            createdAtEpochMs = System.currentTimeMillis(),
                            physicalFileName = ""
                        )
                        vaultDao.insertItem(subFolderEntity)
                        foldersCreatedCount++
                        processDirectoryContents(child, subFolderEntity.id)
                    } else if (child.isFile) {
                        try {
                            val fileName = child.name ?: "imported_file_${System.currentTimeMillis()}"
                            onProgressUpdate?.invoke(fileName, filesImportedCount)
                            importFile(child.uri, currentVaultParentId, masterKey)
                            filesImportedCount++
                        } catch (e: Exception) {
                            Log.e("VaultRepository", "Failed to import file in folder: ${child.uri}", e)
                            failedFilesCount++
                        }
                    }
                }
            }

            // Create root folder entry in vault Room DB
            val rootFolderEntity = VaultItemEntity(
                id = UUID.randomUUID().toString(),
                parentFolderId = targetParentFolderId?.ifEmpty { null },
                name = rootName,
                mimeType = "inode/directory",
                sizeBytes = 0L,
                isFolder = true,
                createdAtEpochMs = System.currentTimeMillis(),
                physicalFileName = ""
            )
            vaultDao.insertItem(rootFolderEntity)
            foldersCreatedCount++

            // Recursively import contents of selected folder into rootFolderEntity
            processDirectoryContents(rootDoc, rootFolderEntity.id)

            containerManager.syncContainerState()

            FolderImportResult(
                rootFolderName = rootName,
                filesImported = filesImportedCount,
                foldersCreated = foldersCreatedCount,
                failedFiles = failedFilesCount
            )
        }
    }

    suspend fun exportFile(itemId: String, destinationUri: Uri, masterKey: SecretKey) {
        withContext(Dispatchers.IO) {
            val item = vaultDao.getItemById(itemId) ?: return@withContext
            if (item.isFolder) return@withContext

            val physicalFile = File(vaultStorageDir, item.physicalFileName)
            if (!physicalFile.exists()) return@withContext

            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                physicalFile.inputStream().use { inputStream ->
                    ChunkedCipherStream.decrypt(inputStream, outputStream, masterKey)
                }
            }
        }
    }

    suspend fun openDecryptedStream(itemId: String, masterKey: SecretKey): InputStream? {
        return withContext(Dispatchers.IO) {
            val item = vaultDao.getItemById(itemId) ?: return@withContext null
            if (item.isFolder) return@withContext null

            val physicalFile = File(vaultStorageDir, item.physicalFileName)
            if (!physicalFile.exists()) {
                Log.e("VaultRepository", "ENOENT Error: Encrypted file missing in private storage at ${physicalFile.absolutePath}")
                return@withContext null
            }

            ChunkedCipherStream.openDecryptedStream(physicalFile.inputStream(), masterKey)
        }
    }

    suspend fun getPhysicalFile(itemId: String): File? = withContext(Dispatchers.IO) {
        val item = vaultDao.getItemById(itemId) ?: return@withContext null
        if (item.isFolder) return@withContext null
        val physicalFile = File(vaultStorageDir, item.physicalFileName)
        if (!physicalFile.exists()) {
            Log.e("VaultRepository", "Physical file missing: ${physicalFile.absolutePath}")
            return@withContext null
        }
        physicalFile
    }

    /**
     * Purges all temporary preview cache files upon vault lock, exit, or self-destruct wipe.
     */
    fun clearTempPreviewCache() {
        if (tempCacheDir.exists()) {
            tempCacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    suspend fun renameItem(itemId: String, newName: String) {
        withContext(Dispatchers.IO) {
            vaultDao.renameItem(itemId, newName)
            containerManager.syncContainerState()
        }
    }

    suspend fun deleteItem(itemId: String) {
        withContext(Dispatchers.IO) {
            val item = vaultDao.getItemById(itemId) ?: return@withContext
            if (item.isFolder) {
                deleteFolderRecursively(item.id)
            } else {
                deleteSingleFile(item)
            }
            containerManager.syncContainerState()
        }
    }

    private suspend fun deleteFolderRecursively(folderId: String) {
        val children = vaultDao.getChildrenDirect(folderId)
        for (child in children) {
            if (child.isFolder) {
                deleteFolderRecursively(child.id)
            } else {
                deleteSingleFile(child)
            }
        }
        vaultDao.deleteItem(folderId)
    }

    private suspend fun deleteSingleFile(item: VaultItemEntity) {
        val physicalFile = File(vaultStorageDir, item.physicalFileName)
        if (physicalFile.exists()) {
            physicalFile.delete()
        }
        vaultDao.deleteItem(item.id)
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            name = cursor.getString(index)?.takeIf { it.isNotBlank() }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VaultRepository", "Failed to query DISPLAY_NAME for uri: $uri", e)
            }
        }
        if (name.isNullOrBlank()) {
            val lastSegment = uri.lastPathSegment
            if (!lastSegment.isNullOrBlank()) {
                val decoded = Uri.decode(lastSegment)
                val cut = decoded.lastIndexOf('/')
                var rawName = if (cut != -1) decoded.substring(cut + 1) else decoded
                val colonCut = rawName.lastIndexOf(':')
                if (colonCut != -1) {
                    rawName = rawName.substring(colonCut + 1)
                }
                name = rawName.takeIf { it.isNotBlank() }
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.path?.let { Uri.decode(it) }?.takeIf { it.isNotBlank() }?.let { path ->
                val cut = path.lastIndexOf('/')
                val rawName = if (cut != -1) path.substring(cut + 1) else path
                val colonCut = rawName.lastIndexOf(':')
                if (colonCut != -1) rawName.substring(colonCut + 1) else rawName
            }
        }
        return name?.takeIf { it.isNotBlank() }
    }
}
