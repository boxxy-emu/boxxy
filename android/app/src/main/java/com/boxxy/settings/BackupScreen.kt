package com.boxxy.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.boxxy.backup.Backup
import kotlin.concurrent.thread

private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1e9)

/** Settings > Backup: copy Boxxy's data out to a folder, or back in. */
@Composable
fun BackupScreen(navController: NavController) {
    val context = LocalContext.current
    var progress by remember { mutableStateOf<Backup.Progress?>(null) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var restoreFrom by remember { mutableStateOf<Uri?>(null) }
    var restarting by remember { mutableStateOf(false) }

    fun guard(): Boolean {
        if (Backup.emulatorRunning(context)) {
            message = "A game is still running. Exit it from its menu first, then try again."
            return false
        }
        return true
    }

    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null || !guard()) return@rememberLauncherForActivityResult
        working = true
        message = null
        thread(name = "boxxy-backup") {
            message = try {
                val r = Backup.backup(context, uri) { progress = it }
                "Backed up ${r.files} files (${gb(r.bytes)}) to \"${r.folderName}\"."
            } catch (e: Exception) {
                "Backup failed: ${e.message}"
            }
            progress = null
            working = false
        }
    }

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null && guard()) restoreFrom = uri
    }

    restoreFrom?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreFrom = null },
            title = { Text("Restore this backup?") },
            text = {
                Text("Boxxy's data on this device -- the Xbox hard disk, save states, " +
                     "Quick Resume and settings -- is replaced with the backup's. " +
                     "Boxxy restarts when it's done.")
            },
            confirmButton = {
                TextButton(onClick = {
                    restoreFrom = null
                    working = true
                    message = null
                    thread(name = "boxxy-restore") {
                        try {
                            Backup.restore(context, uri) { progress = it }
                            restarting = true
                        } catch (e: Exception) {
                            message = "Restore failed: ${e.message}. Nothing was changed " +
                                      "unless the error came while copying."
                        }
                        progress = null
                        working = false
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restoreFrom = null }) { Text("Cancel") } },
        )
    }

    if (restarting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Backup restored") },
            text = {
                Text("Boxxy restarts now. Afterwards, choose your game folder again in " +
                     "Settings > System (Android asks again after a reinstall), and " +
                     "re-enter your SteamGridDB key if you use one.")
            },
            confirmButton = {
                TextButton(onClick = {
                    // Settings are cached in memory: start over from the restored files.
                    val launch = context.packageManager
                        .getLaunchIntentForPackage(context.packageName)!!
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(launch)
                    Runtime.getRuntime().exit(0)
                }) { Text("Restart") }
            },
        )
    }

    SettingsSubScreenScaffold("Backup", navController) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "A backup holds everything Boxxy keeps on this device: the Xbox hard " +
                "disk with your game saves, the Xbox's identity (including its " +
                "Insignia registration), your BIOS and MCPX copies, save states, " +
                "Quick Resume, settings, controller mappings and cover art. Use it " +
                "before reinstalling Boxxy or to move to another device.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Not included: your game images (they stay where they are) and your " +
                "SteamGridDB key. After restoring on a new install, choose your game " +
                "folder again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Back up", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Choose where to save it. Boxxy makes a new folder there; " +
                        "it needs several GB of space.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { backupPicker.launch(null) }, enabled = !working) {
                        Text("Back up…")
                    }
                }
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Restore", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Choose the \"Boxxy backup ...\" folder itself. The backup " +
                        "is checked in full before anything here is replaced.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { restorePicker.launch(null) }, enabled = !working) {
                        Text("Restore…")
                    }
                }
            }

            progress?.let { p ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${gb(p.done)} of ${gb(p.total)} · ${p.file.substringAfterLast('/')}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
