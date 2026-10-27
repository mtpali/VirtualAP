package com.virtualap.app.ui.util

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class LoadingSize(val size: Dp, val strokeWidth: Dp) {
    Small(16.dp, 2.dp), Medium(24.dp, 3.dp), Large(48.dp, 4.dp)
}

// A regular progress ring avoids the polygon morphing code and its graphics dependency.
@Composable
fun LoadingIndicator(
    size: LoadingSize = LoadingSize.Medium,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    CircularProgressIndicator(modifier = modifier.size(size.size), color = color, strokeWidth = size.strokeWidth)
}

@Composable
fun LoadingIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    CircularProgressIndicator(progress = progress().coerceIn(0f, 1f), modifier = modifier, color = color, strokeWidth = 3.dp)
}

@Composable
fun FullScreenLoading(message: String? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LoadingIndicator(size = LoadingSize.Large)
            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
