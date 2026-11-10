package com.boxxy.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.boxxy.BuildConfig

private const val REPO_URL = "https://github.com/boxxy-emu/boxxy"

/** The app's version (from git, see build.gradle.kts), links and credits. */
@Composable
fun AboutScreen(navController: NavController) {
    val context = LocalContext.current
    fun open(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    SettingsSubScreenScaffold("About", navController) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Boxxy", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "An original Xbox emulator for Android, based on xemu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSectionLabel("Build")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AboutRow("Version", BuildConfig.VERSION_NAME)
                    AboutRow("Build", BuildConfig.VERSION_CODE.toString())
                    AboutRow("Commit", BuildConfig.GIT_COMMIT)
                    AboutRow("Date", BuildConfig.COMMIT_DATE)
                    AboutRow("Device", deviceName())
                    AboutRow("Android", android.os.Build.VERSION.RELEASE)
                    AboutRow("ABI", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "—")
                }
            }

            SettingsSectionLabel("Links")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { open(REPO_URL) }) { Text("Source code") }
                OutlinedButton(onClick = { open("$REPO_URL/issues/new/choose") }) {
                    Text("Report a problem")
                }
            }

            SettingsSectionLabel("Credits")
            Text(
                "Boxxy is built on xemu (xemu.app) and QEMU, and is licensed under " +
                "the GNU General Public License, version 2. Online play uses " +
                "Insignia (insignia.live).\n\n" +
                "Boxxy is an independent project, not affiliated with or endorsed " +
                "by the xemu project, Insignia or Microsoft. Xbox and Xbox Live are " +
                "trademarks of Microsoft.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { open("$REPO_URL/blob/android-port/LICENSE") }) {
                Text("License")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** "AYN Thor", not "AYN AYN Thor": some models already carry the maker. */
private fun deviceName(): String {
    val maker = android.os.Build.MANUFACTURER
    val model = android.os.Build.MODEL
    return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
