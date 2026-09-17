package com.example.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.MediaDownloaderApp
import com.example.R
import com.example.data.db.DownloadItemEntity
import com.example.data.db.DownloadStatus
import com.example.data.repository.DownloadRepository
import com.example.storage.MediaStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MediaDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private lateinit var repository: DownloadRepository
    private lateinit var notificationManager: NotificationManager

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        _isServiceActive.value = true
        repository = (application as MediaDownloaderApp).repository
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_QUEUE -> {
                startForegroundNotification("Preparing downloads...", 0, 0, 0)
                processQueue()
            }
            ACTION_CANCEL_ALL -> {
                cancelCurrentQueue()
            }
            ACTION_RETRY_ITEM -> {
                val itemId = intent.getLongExtra(EXTRA_ITEM_ID, -1L)
                if (itemId != -1L) {
                    serviceScope.launch {
                        repository.retry(itemId)
                        processQueue()
                    }
                }
            }
            else -> {
                processQueue()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification(text: String, current: Int, total: Int, progress: Int) {
        val notification = buildOngoingNotification(text, current, total, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID_FOREGROUND, notification)
        }
    }

    private fun processQueue() {
        if (downloadJob?.isActive == true) return

        downloadJob = serviceScope.launch {
            var completedCount = 0
            var failedCount = 0

            while (isActive) {
                val pendingList = repository.getPendingItems()
                if (pendingList.isEmpty()) {
                    break
                }

                val totalInBatch = pendingList.size + completedCount + failedCount

                for (item in pendingList) {
                    if (!isActive) break

                    val currentDisplayIndex = completedCount + failedCount + 1
                    val success = downloadSingleItem(item, currentDisplayIndex, totalInBatch)
                    if (success) {
                        completedCount++
                    } else {
                        failedCount++
                    }
                }
            }

            // Finished all items
            showCompletionNotification(completedCount, failedCount)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun downloadSingleItem(
        item: DownloadItemEntity,
        itemIndex: Int,
        totalItems: Int
    ): Boolean {
        var tempFile: File? = null
        try {
            // Update status to DOWNLOADING
            repository.update(
                item.copy(
                    status = DownloadStatus.DOWNLOADING.name,
                    errorMessage = null,
                    progress = 0
                )
            )

            updateForegroundNotification(
                title = "Downloading $itemIndex of $totalItems",
                content = item.title,
                progress = 0,
                indeterminate = true
            )

            val extension = if (item.format.equals("MP3", ignoreCase = true)) "mp3" else "mp4"
            val sanitizedName = MediaStorageManager.sanitizeFilename(item.title, extension)
            tempFile = MediaStorageManager.createTempDownloadFile(applicationContext, sanitizedName)

            val request = Request.Builder()
                .url(item.url)
                .header("User-Agent", "Mozilla/5.0 (Android; MediaDownloader/1.0)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw Exception("Empty response body from server")
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: item.fileSize

            var downloadedBytes = 0L
            var lastUpdateTimestamp = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTimestamp > 250L || downloadedBytes == totalBytes) {
                            lastUpdateTimestamp = now
                            val progress = if (totalBytes > 0) {
                                ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }

                            repository.update(
                                item.copy(
                                    status = DownloadStatus.DOWNLOADING.name,
                                    bytesDownloaded = downloadedBytes,
                                    fileSize = totalBytes,
                                    progress = progress
                                )
                            )

                            updateForegroundNotification(
                                title = "Downloading $itemIndex of $totalItems ($progress%)",
                                content = item.title,
                                progress = progress,
                                indeterminate = totalBytes <= 0
                            )
                        }
                    }
                }
            }

            // Publish file to user-accessible Scoped MediaStore
            val result = MediaStorageManager.publishToPublicStorage(
                context = applicationContext,
                tempFile = tempFile,
                filename = sanitizedName,
                format = item.format
            )

            // Mark completed in database
            repository.update(
                item.copy(
                    status = DownloadStatus.COMPLETED.name,
                    fileUri = result.uriString,
                    filePath = result.filePath,
                    fileSize = result.fileSize,
                    bytesDownloaded = result.fileSize,
                    progress = 100,
                    completedAt = System.currentTimeMillis()
                )
            )

            // Post individual completed alert notification if app in background
            showItemCompletedNotification(item.title, item.format)
            tempFile.delete()
            return true

        } catch (e: Exception) {
            tempFile?.delete()
            val errorMsg = e.localizedMessage ?: "Download failed: Unknown network error"
            repository.markAsFailed(item.id, errorMsg)
            showItemFailedNotification(item.title, errorMsg)
            return false
        }
    }

    private fun cancelCurrentQueue() {
        downloadJob?.cancel()
        serviceScope.launch {
            repository.cancelAllActive()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildOngoingNotification(
        title: String,
        current: Int,
        total: Int,
        progress: Int
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaDownloadService::class.java).apply { action = ACTION_CANCEL_ALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, MediaDownloaderApp.CHANNEL_DOWNLOADS)
            .setContentTitle(title)
            .setContentText(if (total > 0) "Item $current of $total" else "Processing queue...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel All", cancelIntent)

        if (progress > 0) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateForegroundNotification(
        title: String,
        content: String,
        progress: Int,
        indeterminate: Boolean
    ) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaDownloadService::class.java).apply { action = ACTION_CANCEL_ALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, MediaDownloaderApp.CHANNEL_DOWNLOADS)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel All", cancelIntent)

        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, progress, false)
        }

        notificationManager.notify(NOTIFICATION_ID_FOREGROUND, builder.build())
    }

    private fun showItemCompletedNotification(title: String, format: String) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, MediaDownloaderApp.CHANNEL_ALERTS)
            .setContentTitle("Download Finished")
            .setContentText("$title ($format) saved to storage")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(title.hashCode(), notification)
    }

    private fun showItemFailedNotification(title: String, errorMsg: String) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, MediaDownloaderApp.CHANNEL_ALERTS)
            .setContentTitle("Download Failed")
            .setContentText("$title: $errorMsg")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(title.hashCode(), notification)
    }

    private fun showCompletionNotification(completedCount: Int, failedCount: Int) {
        if (completedCount == 0 && failedCount == 0) return

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val message = if (failedCount == 0) {
            "All $completedCount media files downloaded successfully"
        } else {
            "$completedCount succeeded, $failedCount failed. Tap to review."
        }

        val notification = NotificationCompat.Builder(this, MediaDownloaderApp.CHANNEL_ALERTS)
            .setContentTitle("Batch Download Complete")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_SUMMARY, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceActive.value = false
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_QUEUE = "com.example.action.START_QUEUE"
        const val ACTION_CANCEL_ALL = "com.example.action.CANCEL_ALL"
        const val ACTION_RETRY_ITEM = "com.example.action.RETRY_ITEM"
        const val EXTRA_ITEM_ID = "com.example.extra.ITEM_ID"

        private const val NOTIFICATION_ID_FOREGROUND = 1001
        private const val NOTIFICATION_ID_SUMMARY = 1002

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive = _isServiceActive.asStateFlow()

        fun startQueue(context: Context) {
            val intent = Intent(context, MediaDownloadService::class.java).apply {
                action = ACTION_START_QUEUE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelAll(context: Context) {
            val intent = Intent(context, MediaDownloadService::class.java).apply {
                action = ACTION_CANCEL_ALL
            }
            context.startService(intent)
        }

        fun retryItem(context: Context, itemId: Long) {
            val intent = Intent(context, MediaDownloadService::class.java).apply {
                action = ACTION_RETRY_ITEM
                putExtra(EXTRA_ITEM_ID, itemId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
