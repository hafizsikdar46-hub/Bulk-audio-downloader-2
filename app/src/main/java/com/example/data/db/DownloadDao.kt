package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<DownloadItemEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
    fun getCompletedDownloads(): Flow<List<DownloadItemEntity>>

    @Query("SELECT * FROM downloads WHERE status IN ('PENDING', 'DOWNLOADING') ORDER BY createdAt ASC")
    fun getActiveDownloads(): Flow<List<DownloadItemEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'FAILED' ORDER BY createdAt DESC")
    fun getFailedDownloads(): Flow<List<DownloadItemEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingList(): List<DownloadItemEntity>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadItemEntity?

    @Query("SELECT * FROM downloads WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): DownloadItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: DownloadItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DownloadItemEntity>): List<Long>

    @Update
    suspend fun update(item: DownloadItemEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM downloads WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()

    @Query("UPDATE downloads SET status = 'CANCELLED' WHERE status IN ('PENDING', 'DOWNLOADING')")
    suspend fun cancelAllActive()
}
