package com.boxxy.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread

/** What the update dialog and Settings > About show; written from worker threads. */
object UpdateState {
    var offer by mutableStateOf<Updater.Update?>(null)
    /** Download progress 0..1, -1 when the size is unknown, null when idle. */
    var downloading by mutableStateOf<Float?>(null)
    var error by mutableStateOf<String?>(null)
    var checking by mutableStateOf(false)
    /** The last manual check's result, for About. */
    var status by mutableStateOf<String?>(null)
}

/**
 * Look for an update.  [manual]: from the About screen -- report the result,
 * and offer a version the user skipped before.
 */
fun checkForUpdate(context: Context, manual: Boolean) {
    if (UpdateState.checking) return
    val app = context.applicationContext
    UpdateState.checking = true
    if (manual) UpdateState.status = null
    thread(name = "boxxy-update-check") {
        try {
            val u = Updater.check(app)
            when {
                u == null -> if (manual) UpdateState.status = "Boxxy is up to date."
                manual || !Updater.skipped(app, u) -> {
                    UpdateState.error = null
                    UpdateState.offer = u
                }
            }
        } catch (e: Exception) {
            if (manual) UpdateState.status = "Couldn't check for updates: ${e.message}"
        } finally {
            UpdateState.checking = false
        }
    }
}

private fun startUpdate(context: Context, u: Updater.Update) {
    if (!Updater.canInstall(context)) {
        // Once: Android asks the user to let Boxxy install apps.
        UpdateState.error = "Allow Boxxy to install updates on the next screen, " +
                            "then come back and tap Update again."
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                   Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    val app = context.applicationContext
    UpdateState.error = null
    UpdateState.downloading = 0f
    thread(name = "boxxy-update-download") {
        try {
            val apk = Updater.download(app, u) { done, total ->
                UpdateState.downloading = if (total > 0) done.toFloat() / total else -1f
            }
            Updater.install(app, apk)
            UpdateState.offer = null
        } catch (e: Exception) {
            UpdateState.error = e.message ?: "The update failed"
        } finally {
            UpdateState.downloading = null
        }
    }
}

/** "Boxxy x.y is available": shown over the library when a check finds one. */
@Composable
fun UpdateDialog() {
    val u = UpdateState.offer ?: return
    val context = LocalContext.current
    val progress = UpdateState.downloading
    AlertDialog(
        onDismissRequest = { if (progress == null) UpdateState.offer = null },
        title = { Text("Boxxy ${u.versionName} is available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (progress != null) {
                    Text("Downloading…")
                    if (progress >= 0f) {
                        LinearProgressIndicator(progress = { progress },
                                                modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    if (u.notes.isNotEmpty()) {
                        Text(
                            u.notes,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    /* Android stops an app that updates itself and won't let it
                     * reopen from the background: say so, so it isn't taken
                     * for a crash. */
                    Text(
                        "Boxxy closes to install the update; open it again afterwards. " +
                        "Your games, saves and settings stay as they are.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                UpdateState.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                         style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = { startUpdate(context, u) }, enabled = progress == null) {
                Text("Update")
            }
        },
        dismissButton = {
            if (progress == null) {
                Row {
                    TextButton(onClick = {
                        Updater.skip(context, u)
                        UpdateState.offer = null
                    }) { Text("Skip this version") }
                    TextButton(onClick = { UpdateState.offer = null }) { Text("Later") }
                }
            }
        },
    )
}
