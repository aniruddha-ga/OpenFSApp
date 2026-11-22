package com.openlock.filesystem.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_items WHERE (parentFolderId = :parentFolderId) OR ((parentFolderId IS NULL OR parentFolderId = '') AND (:parentFolderId IS NULL OR :parentFolderId = '')) ORDER BY isFolder DESC, name ASC")
    fun getItemsInFolder(parentFolderId: String?): Flow<List<VaultItemEntity>>

    @Query("SELECT * FROM vault_items WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchItems(query: String): Flow<List<VaultItemEntity>>

    @Query("SELECT * FROM vault_items ORDER BY isFolder DESC, name ASC")
    fun getAllItems(): Flow<List<VaultItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: VaultItemEntity)

    @Query("DELETE FROM vault_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    @Query("SELECT * FROM vault_items WHERE id = :id")
    suspend fun getItemById(id: String): VaultItemEntity?

    @Query("UPDATE vault_items SET name = :newName WHERE id = :id")
    suspend fun renameItem(id: String, newName: String)

    @Query("SELECT * FROM vault_items WHERE parentFolderId = :parentId")
    suspend fun getChildrenDirect(parentId: String): List<VaultItemEntity>

    @Query("SELECT COUNT(*) FROM vault_items WHERE isFolder = 0")
    fun getTotalFileCount(): Flow<Int>

    @Query("SELECT SUM(sizeBytes) FROM vault_items WHERE isFolder = 0")
    fun getTotalVaultSize(): Flow<Long?>
}
