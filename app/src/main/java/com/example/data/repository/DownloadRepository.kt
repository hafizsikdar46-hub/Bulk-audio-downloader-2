package com.example.data.repository

import com.example.data.db.DownloadDao
import com.example.data.db.DownloadItemEntity
import com.example.data.db.DownloadStatus
import kotlinx.coroutines.flow.Flow

class DownloadRepository(private val dao: DownloadDao) {

    val allDownloads: Flow<List<DownloadItemEntity>> = dao.getAllDownloads()
    val completedDownloads: Flow<List<DownloadItemEntity>> = dao.getCompletedDownloads()
    val activeDownloads: Flow<List<DownloadItemEntity>> = dao.getActiveDownloads()
    val failedDownloads: Flow<List<DownloadItemEntity>> = dao.getFailedDownloads()

    suspend fun insert(item: DownloadItemEntity): Long = dao.insert(item)

    suspend fun insertAll(items: List<DownloadItemEntity>): List<Long> = dao.insertAll(items)

    suspend fun update(item: DownloadItemEntity) = dao.update(item)

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun clearCompleted() = dao.clearCompleted()

    suspend fun getById(id: Long): DownloadItemEntity? = dao.getById(id)

    suspend fun getByUrl(url: String): DownloadItemEntity? = dao.getByUrl(url)

    suspend fun getPendingItems(): List<DownloadItemEntity> = dao.getPendingList()

    suspend fun markAsFailed(id: Long, errorMessage: String) {
        dao.getById(id)?.let {
            dao.update(it.copy(status = DownloadStatus.FAILED.name, errorMessage = errorMessage))
        }
    }

    suspend fun retry(id: Long) {
        dao.getById(id)?.let {
            dao.update(it.copy(status = DownloadStatus.PENDING.name, errorMessage = null, progress = 0, bytesDownloaded = 0))
        }
    }

    suspend fun retryAllFailed() {
        // Find failed and reset to PENDING
        // This will be triggered from UI or VM
    }

    suspend fun cancelAllActive() = dao.cancelAllActive()
}
