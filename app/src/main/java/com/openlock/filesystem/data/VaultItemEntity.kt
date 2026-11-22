package com.openlock.filesystem.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vault_items")
data class VaultItemEntity(
    @PrimaryKey
    val id: String,
    val parentFolderId: String?,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isFolder: Boolean,
    val createdAtEpochMs: Long,
    val physicalFileName: String
)
