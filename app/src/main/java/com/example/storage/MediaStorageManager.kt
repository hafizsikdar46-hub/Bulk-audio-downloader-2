package com.example.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

object MediaStorageManager {

    fun sanitizeFilename(rawName: String, extension: String): String {
        val sanitized = rawName.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        val base = if (sanitized.isBlank()) "download_${System.currentTimeMillis()}" else sanitized
        val ext = extension.lowercase().removePrefix(".")
        return if (base.endsWith(".$ext", ignoreCase = true)) base else "$base.$ext"
    }

    fun getMimeType(format: String): String {
        return when (format.uppercase()) {
            "MP3" -> "audio/mpeg"
            "MP4" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }

    /**
     * Creates a temporary working file in the app's cache/download folder during downloading.
     */
    fun createTempDownloadFile(context: Context, filename: String): File {
        val downloadDir = File(context.filesDir, "active_downloads").apply { mkdirs() }
        return File(downloadDir, "tmp_${System.currentTimeMillis()}_$filename")
    }

    /**
     * Saves the downloaded file into user-accessible public MediaStore (Audio, Video, or Downloads)
     * using modern Android Scoped Storage APIs.
     */
    fun publishToPublicStorage(
        context: Context,
        tempFile: File,
        filename: String,
        format: String
    ): StorageResult {
        val mimeType = getMimeType(format)
        val isAudio = format.equals("MP3", ignoreCase = true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentUri = if (isAudio) {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val relativePath = if (isAudio) {
                "${Environment.DIRECTORY_MUSIC}/MediaDownloader"
            } else {
                "${Environment.DIRECTORY_MOVIES}/MediaDownloader"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(contentUri, values)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(tempFile).use { inStream ->
                            inStream.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)

                    return StorageResult(
                        uriString = uri.toString(),
                        filePath = tempFile.absolutePath,
                        fileSize = tempFile.length()
                    )
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                }
            }
        }

        // Fallback or secondary copy in app's public external files dir for guaranteed direct file access and FileProvider
        val publicDir = context.getExternalFilesDir(if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        publicDir.mkdirs()
        val destFile = File(publicDir, filename)
        tempFile.copyTo(destFile, overwrite = true)

        val fileProviderUri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destFile
            )
        }.getOrNull()

        return StorageResult(
            uriString = fileProviderUri?.toString() ?: Uri.fromFile(destFile).toString(),
            filePath = destFile.absolutePath,
            fileSize = destFile.length()
        )
    }

    fun openFile(context: Context, uriString: String?, filePath: String?, format: String) {
        try {
            val uri = resolveUri(context, uriString, filePath) ?: run {
                Toast.makeText(context, "File location not found", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(format))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                // Try generic intent
                val genericIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(genericIntent, "Open media with").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareFile(context: Context, uriString: String?, filePath: String?, format: String, title: String) {
        try {
            val uri = resolveUri(context, uriString, filePath) ?: run {
                Toast.makeText(context, "File location not found", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = getMimeType(format)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share $title").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot share file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteFile(context: Context, uriString: String?, filePath: String?): Boolean {
        var deleted = false
        if (!uriString.isNullOrBlank()) {
            runCatching {
                val uri = Uri.parse(uriString)
                if (uri.scheme == "content") {
                    val count = context.contentResolver.delete(uri, null, null)
                    if (count > 0) deleted = true
                }
            }
        }
        if (!filePath.isNullOrBlank()) {
            runCatching {
                val file = File(filePath)
                if (file.exists() && file.delete()) {
                    deleted = true
                }
            }
        }
        return deleted
    }

    private fun resolveUri(context: Context, uriString: String?, filePath: String?): Uri? {
        if (!uriString.isNullOrBlank()) {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "content") return uri
        }
        if (!filePath.isNullOrBlank()) {
            val file = File(filePath)
            if (file.exists()) {
                return runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }.getOrElse { Uri.fromFile(file) }
            }
        }
        return null
    }

    data class StorageResult(
        val uriString: String,
        val filePath: String,
        val fileSize: Long
    )
}
