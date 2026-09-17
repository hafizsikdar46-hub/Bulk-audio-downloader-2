package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MediaDownloaderApp
import com.example.data.db.DownloadItemEntity
import com.example.data.db.DownloadStatus
import com.example.download.MediaDownloadService
import com.example.parser.DetectResult
import com.example.parser.MediaDetector
import com.example.parser.MediaItemCandidate
import com.example.storage.MediaStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BatchProgressState(
    val isActive: Boolean = false,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val totalCount: Int = 0,
    val overallProgress: Float = 0f,
    val currentItemTitle: String? = null,
    val currentItemProgress: Int = 0,
    val currentStatusText: String = ""
)

data class UiNotification(
    val message: String,
    val isError: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as MediaDownloaderApp).repository

    // State flows from database
    val allDownloads: StateFlow<List<DownloadItemEntity>> = repository.allDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedDownloads: StateFlow<List<DownloadItemEntity>> = repository.completedDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val failedDownloads: StateFlow<List<DownloadItemEntity>> = repository.failedDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeDownloads: StateFlow<List<DownloadItemEntity>> = repository.activeDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Input state
    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _selectedFormat = MutableStateFlow("MP3") // "MP3" or "MP4"
    val selectedFormat: StateFlow<String> = _selectedFormat.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    // Playlist candidate preview dialog / bottom sheet
    private val _detectedPlaylistTitle = MutableStateFlow<String?>(null)
    val detectedPlaylistTitle: StateFlow<String?> = _detectedPlaylistTitle.asStateFlow()

    private val _playlistCandidates = MutableStateFlow<List<MediaItemCandidate>>(emptyList())
    val playlistCandidates: StateFlow<List<MediaItemCandidate>> = _playlistCandidates.asStateFlow()

    private val _showCandidateSelector = MutableStateFlow(false)
    val showCandidateSelector: StateFlow<Boolean> = _showCandidateSelector.asStateFlow()

    // Error & Compliance alerts
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _complianceWarning = MutableStateFlow<String?>(null)
    val complianceWarning: StateFlow<String?> = _complianceWarning.asStateFlow()

    private val _duplicateWarning = MutableStateFlow<String?>(null)
    val duplicateWarning: StateFlow<String?> = _duplicateWarning.asStateFlow()

    // Derived batch progress state
    val batchProgress: StateFlow<BatchProgressState> = combine(
        activeDownloads,
        allDownloads
    ) { activeList, allList ->
        val isDownloadingOrPending = activeList.isNotEmpty()
        if (!isDownloadingOrPending) {
            BatchProgressState(isActive = false)
        } else {
            val currentlyDownloading = activeList.firstOrNull { it.status == DownloadStatus.DOWNLOADING.name }
                ?: activeList.firstOrNull()

            val activeBatchPlaylist = currentlyDownloading?.playlistTitle
            val batchItems = if (activeBatchPlaylist != null) {
                allList.filter { it.playlistTitle == activeBatchPlaylist }
            } else {
                allList.take(20)
            }

            val total = batchItems.size.coerceAtLeast(activeList.size)
            val completed = batchItems.count { it.status == DownloadStatus.COMPLETED.name }
            val failed = batchItems.count { it.status == DownloadStatus.FAILED.name }
            val completedOrFailed = completed + failed

            val currentProg = currentlyDownloading?.progress ?: 0
            val overall = if (total > 0) {
                ((completed.toFloat() + (currentProg / 100f)) / total.toFloat()).coerceIn(0f, 1f)
            } else 0f

            val currentNum = (completedOrFailed + 1).coerceAtMost(total)
            val statusText = "Downloading $currentNum of $total"

            BatchProgressState(
                isActive = true,
                completedCount = completed,
                failedCount = failed,
                totalCount = total,
                overallProgress = overall,
                currentItemTitle = currentlyDownloading?.title,
                currentItemProgress = currentProg,
                currentStatusText = statusText
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BatchProgressState())

    fun onUrlChanged(newUrl: String) {
        _urlInput.value = newUrl
        _errorMessage.value = null
        _complianceWarning.value = null
        _duplicateWarning.value = null
    }

    fun onFormatChanged(format: String) {
        _selectedFormat.value = format
    }

    fun clearUrl() {
        _urlInput.value = ""
    }

    fun dismissCandidateSelector() {
        _showCandidateSelector.value = false
        _playlistCandidates.value = emptyList()
        _detectedPlaylistTitle.value = null
    }

    fun dismissErrors() {
        _errorMessage.value = null
        _complianceWarning.value = null
        _duplicateWarning.value = null
    }

    fun selectSample(sampleUrl: String, sampleFormat: String) {
        _urlInput.value = sampleUrl
        _selectedFormat.value = sampleFormat
        _errorMessage.value = null
        _complianceWarning.value = null
        _duplicateWarning.value = null
    }

    fun onDownloadClicked() {
        val url = _urlInput.value.trim()
        if (url.isEmpty()) {
            _errorMessage.value = "Please enter or paste a media or playlist URL"
            return
        }

        viewModelScope.launch {
            _isAnalyzing.value = true
            _errorMessage.value = null
            _complianceWarning.value = null
            _duplicateWarning.value = null

            val result = MediaDetector.inspectUrl(url, _selectedFormat.value)
            _isAnalyzing.value = false

            when (result) {
                is DetectResult.Restricted -> {
                    _complianceWarning.value = result.message
                }
                is DetectResult.Error -> {
                    _errorMessage.value = result.message
                }
                is DetectResult.Single -> {
                    // Check for duplicate
                    val existing = repository.getByUrl(result.item.url)
                    if (existing != null && existing.status == DownloadStatus.COMPLETED.name) {
                        _duplicateWarning.value = "This media file has already been downloaded. Re-downloading anyway..."
                    }
                    enqueueItems(
                        items = listOf(result.item),
                        playlistName = null
                    )
                    _urlInput.value = ""
                }
                is DetectResult.Playlist -> {
                    _detectedPlaylistTitle.value = result.title
                    _playlistCandidates.value = result.items.map { it.copy(format = _selectedFormat.value, isSelected = true) }
                    _showCandidateSelector.value = true
                }
            }
        }
    }

    fun toggleCandidateSelection(index: Int) {
        val list = _playlistCandidates.value.toMutableList()
        if (index in list.indices) {
            val item = list[index]
            list[index] = item.copy(isSelected = !item.isSelected)
            _playlistCandidates.value = list
        }
    }

    fun setAllCandidatesSelected(selected: Boolean) {
        val list = _playlistCandidates.value.map { it.copy(isSelected = selected) }
        _playlistCandidates.value = list
    }

    fun confirmBatchDownload() {
        val selected = _playlistCandidates.value.filter { it.isSelected }
        if (selected.isEmpty()) {
            _errorMessage.value = "Please select at least one media item to download"
            return
        }

        val playlistName = _detectedPlaylistTitle.value ?: "Batch Download"
        enqueueItems(selected, playlistName)

        _showCandidateSelector.value = false
        _playlistCandidates.value = emptyList()
        _detectedPlaylistTitle.value = null
        _urlInput.value = ""
    }

    private fun enqueueItems(items: List<MediaItemCandidate>, playlistName: String?) {
        viewModelScope.launch {
            val entities = items.map { candidate ->
                DownloadItemEntity(
                    title = candidate.title,
                    url = candidate.url,
                    format = _selectedFormat.value,
                    playlistTitle = playlistName,
                    status = DownloadStatus.PENDING.name,
                    progress = 0
                )
            }
            repository.insertAll(entities)
            MediaDownloadService.startQueue(getApplication())
        }
    }

    fun cancelAllActive() {
        MediaDownloadService.cancelAll(getApplication())
    }

    fun retryItem(itemId: Long) {
        MediaDownloadService.retryItem(getApplication(), itemId)
    }

    fun retryAllFailed() {
        viewModelScope.launch {
            failedDownloads.value.forEach { item ->
                repository.retry(item.id)
            }
            MediaDownloadService.startQueue(getApplication())
        }
    }

    fun deleteItem(item: DownloadItemEntity) {
        viewModelScope.launch {
            MediaStorageManager.deleteFile(
                context = getApplication(),
                uriString = item.fileUri,
                filePath = item.filePath
            )
            repository.delete(item.id)
        }
    }

    fun clearAllCompleted() {
        viewModelScope.launch {
            completedDownloads.value.forEach { item ->
                MediaStorageManager.deleteFile(
                    context = getApplication(),
                    uriString = item.fileUri,
                    filePath = item.filePath
                )
            }
            repository.clearCompleted()
        }
    }

    fun openMedia(item: DownloadItemEntity) {
        MediaStorageManager.openFile(
            context = getApplication(),
            uriString = item.fileUri,
            filePath = item.filePath,
            format = item.format
        )
    }

    fun shareMedia(item: DownloadItemEntity) {
        MediaStorageManager.shareFile(
            context = getApplication(),
            uriString = item.fileUri,
            filePath = item.filePath,
            format = item.format,
            title = item.title
        )
    }
}
