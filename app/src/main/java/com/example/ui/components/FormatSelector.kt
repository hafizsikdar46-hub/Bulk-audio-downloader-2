package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BlackBackground
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun FormatSelector(
    selectedFormat: String,
    onFormatSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "OUTPUT FORMAT",
            style = MaterialTheme.typography.labelMedium.copy(
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = TextSecondary
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = DarkSurfaceElevated,
            border = BorderStroke(1.dp, DarkBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FormatOptionPill(
                    title = "MP3",
                    subtitle = "Audio",
                    icon = Icons.Default.Audiotrack,
                    isSelected = selectedFormat == "MP3",
                    onClick = { onFormatSelected("MP3") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("format_mp3_button")
                )

                FormatOptionPill(
                    title = "MP4",
                    subtitle = "Video",
                    icon = Icons.Default.Videocam,
                    isSelected = selectedFormat == "MP4",
                    onClick = { onFormatSelected("MP4") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("format_mp4_button")
                )
            }
        }
    }
}

@Composable
private fun FormatOptionPill(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) CyanAccent else DarkSurfaceElevated,
        animationSpec = tween(durationMillis = 200),
        label = "pill_bg"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isSelected) BlackBackground else TextPrimary,
        animationSpec = tween(durationMillis = 200),
        label = "pill_content"
    )

    val subtitleColor by animateColorAsState(
        targetValue = if (isSelected) BlackBackground.copy(alpha = 0.75f) else TextSecondary,
        animationSpec = tween(durationMillis = 200),
        label = "pill_sub"
    )

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = contentColor,
                modifier = Modifier.padding(start = 8.dp)
            )

            Text(
                text = "• $subtitle",
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}
