package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.parser.SamplePlaylists
import com.example.ui.components.DownloadProgressSection
import com.example.ui.components.DownloadsListSection
import com.example.ui.components.FormatSelector
import com.example.ui.components.PlaylistSelectionSheet
import com.example.ui.components.UrlInputCard
import com.example.ui.theme.BlackBackground
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.RedError
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDownloaderScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val urlInput by viewModel.urlInput.collectAsState()
    val selectedFormat by viewModel.selectedFormat.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()

    val completedDownloads by viewModel.completedDownloads.collectAsState()
    val failedDownloads by viewModel.failedDownloads.collectAsState()

    val showCandidateSelector by viewModel.showCandidateSelector.collectAsState()
    val detectedPlaylistTitle by viewModel.detectedPlaylistTitle.collectAsState()
    val playlistCandidates by viewModel.playlistCandidates.collectAsState()

    val errorMessage by viewModel.errorMessage.collectAsState()
    val complianceWarning by viewModel.complianceWarning.collectAsState()
    val duplicateWarning by viewModel.duplicateWarning.collectAsState()

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        // Proceed with download after permission result
        viewModel.onDownloadClicked()
    }

    val onDownloadRequest = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.onDownloadClicked()
            }
        } else {
            viewModel.onDownloadClicked()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(BlackBackground),
        containerColor = BlackBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = CyanAccent,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    tint = BlackBackground,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Text(
                            text = "Media Downloader",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BlackBackground,
                    titleContentColor = TextPrimary
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            state = rememberLazyListState()
        ) {
            // URL Input Card
            item {
                UrlInputCard(
                    url = urlInput,
                    onUrlChange = viewModel::onUrlChanged,
                    onClearUrl = viewModel::clearUrl
                )
            }

            // Quick Samples Bar (for testing authorized feeds)
            item {
                QuickSampleFeedsRow(
                    onSelectSample = { sampleUrl, sampleFormat ->
                        viewModel.selectSample(sampleUrl, sampleFormat)
                    }
                )
            }

            // Format Selector: MP3 (Audio) / MP4 (Video)
            item {
                FormatSelector(
                    selectedFormat = selectedFormat,
                    onFormatSelected = viewModel::onFormatChanged
                )
            }

            // Primary DOWNLOAD button
            item {
                Button(
                    onClick = onDownloadRequest,
                    enabled = !isAnalyzing && urlInput.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanAccent,
                        contentColor = BlackBackground,
                        disabledContainerColor = DarkSurfaceElevated,
                        disabledContentColor = TextTertiary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("download_primary_button")
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(
                            color = BlackBackground,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "INSPECTING URL...",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "DOWNLOAD",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        )
                    }
                }
            }

            // Compliance & DRM Restriction Banner
            if (complianceWarning != null) {
                item {
                    ComplianceNoticeCard(
                        message = complianceWarning!!,
                        onDismiss = viewModel::dismissErrors
                    )
                }
            }

            // Error Banner
            if (errorMessage != null) {
                item {
                    ErrorMessageCard(
                        message = errorMessage!!,
                        onDismiss = viewModel::dismissErrors
                    )
                }
            }

            // Duplicate Notice Banner
            if (duplicateWarning != null) {
                item {
                    DuplicateNoticeCard(
                        message = duplicateWarning!!,
                        onDismiss = viewModel::dismissErrors
                    )
                }
            }

            // Active Download Progress Area
            if (batchProgress.isActive) {
                item {
                    DownloadProgressSection(
                        state = batchProgress,
                        onCancelAll = viewModel::cancelAllActive
                    )
                }
            }

            // Downloads Section (Completed & Failed)
            item {
                DownloadsListSection(
                    completedList = completedDownloads,
                    failedList = failedDownloads,
                    onOpenMedia = viewModel::openMedia,
                    onShareMedia = viewModel::shareMedia,
                    onDeleteItem = viewModel::deleteItem,
                    onRetryItem = viewModel::retryItem,
                    onRetryAllFailed = viewModel::retryAllFailed,
                    onClearCompleted = viewModel::clearAllCompleted
                )
            }

            // Spacer for bottom insets
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Playlist candidate batch selection bottom sheet
    if (showCandidateSelector && detectedPlaylistTitle != null) {
        PlaylistSelectionSheet(
            playlistTitle = detectedPlaylistTitle!!,
            candidates = playlistCandidates,
            onToggleSelectAll = viewModel::setAllCandidatesSelected,
            onToggleItem = viewModel::toggleCandidateSelection,
            onConfirmBatch = viewModel::confirmBatchDownload,
            onDismiss = viewModel::dismissCandidateSelector
        )
    }
}

@Composable
private fun QuickSampleFeedsRow(
    onSelectSample: (url: String, format: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "AUTHORIZED SAMPLE FEEDS (TEST BATCH)",
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 1.sp,
                fontSize = 10.sp
            ),
            color = TextTertiary
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SamplePlaylists.samples.forEach { sample ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DarkSurfaceVariant,
                    border = BorderStroke(1.dp, DarkBorder),
                    modifier = Modifier
                        .clickable { onSelectSample(sample.url, sample.defaultFormat) }
                        .testTag("sample_chip_${sample.label.replace(' ', '_')}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = sample.defaultFormat,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = CyanAccent
                        )
                        Text(
                            text = sample.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComplianceNoticeCard(
    message: String,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DarkSurfaceElevated,
        border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "Compliance Notice",
                tint = CyanAccent,
                modifier = Modifier.size(22.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Platform Policy Compliance",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = CyanAccent
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ErrorMessageCard(
    message: String,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DarkSurfaceElevated,
        border = BorderStroke(1.dp, RedError.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Error Notice",
                tint = RedError,
                modifier = Modifier.size(22.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Download Error",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = RedError
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun DuplicateNoticeCard(
    message: String,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DarkSurfaceElevated,
        border = BorderStroke(1.dp, DarkBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Duplicate Information",
                tint = CyanAccent,
                modifier = Modifier.size(20.dp)
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = TextTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
