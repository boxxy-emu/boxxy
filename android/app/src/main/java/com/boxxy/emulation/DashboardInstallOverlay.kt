package com.boxxy.emulation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Covers the screen while the dashboard installs: the machine underneath is
 * only the old dashboard serving files, nothing to look at or touch.
 * [error] non-null replaces the progress with why it stopped.
 */
@Composable
fun DashboardInstallOverlay(
    progress: Float,
    status: String,
    error: String?,
    done: Boolean,
    onClose: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
        shadowElevation = 16.dp,
        modifier = Modifier.widthIn(min = 320.dp, max = 460.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                when {
                    error != null -> "Dashboard not installed"
                    done -> "Dashboard installed"
                    else -> "Installing the Xbox dashboard"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (error != null) {
                Text(
                    error.replaceFirstChar { it.uppercase() } + ".\n\n" +
                        "Trying again from Xbox Live setup copies every file again.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (done) "Returning to Boxxy…" else status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (error != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) { DialogAction("Close", onClose) }
            }
        }
    }
}
