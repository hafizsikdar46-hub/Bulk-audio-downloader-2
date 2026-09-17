package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Entity(
    tableName = "downloads",
    indices = [Index(value = ["url"], unique = false)]
)
data class DownloadItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val format: String, // "MP3" or "MP4"
    val playlistTitle: String? = null,
    val fileUri: String? = null,
    val filePath: String? = null,
    val fileSize: Long = 0L,
    val bytesDownloaded: Long = 0L,
    val progress: Int = 0,
    val status: String = DownloadStatus.PENDING.name,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)
