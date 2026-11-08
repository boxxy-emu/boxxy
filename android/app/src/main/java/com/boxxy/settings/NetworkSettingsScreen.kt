package com.boxxy.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.boxxy.emulation.GameLaunch

/** Insignia's DNS server: it answers for the Xbox Live names and passes the rest on. */
const val INSIGNIA_DNS = "46.101.64.175"

@Composable
fun NetworkSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val enabled by settingsViewModel.networkEnabled.collectAsState()
    val dns by settingsViewModel.networkDns.collectAsState()
    val context = LocalContext.current

    SettingsSubScreenScaffold("Network", navController) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSectionLabel("Online")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsSwitchRow(
                        label = "Connect the Xbox to the internet",
                        subtitle = "Gives the emulated Xbox a network connection through " +
                                   "this device, for online play. Takes effect on next launch.",
                        checked = enabled,
                    ) { settingsViewModel.setNetworkEnabled(it) }

                    Text(
                        "DNS",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val options = listOf(
                        "insignia" to "Insignia (Xbox Live revival)",
                        "network" to "This device's network",
                    )
                    SettingsDropdown(
                        value = options.first { it.first == dns }.second,
                        options = options.map { it.second },
                        onSelect = { label ->
                            settingsViewModel.setNetworkDns(options.first { it.second == label }.first)
                        },
                    )
                    Text(
                        "Used when the Xbox dashboard's DNS is set to automatic. " +
                        "Insignia needs its own DNS ($INSIGNIA_DNS) to reach Xbox Live " +
                        "services; it is chosen here so the dashboard can stay on automatic.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsSectionLabel("Xbox Dashboard")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Start the Xbox with no disc, to check the connection " +
                        "(Settings > Network Settings) or set up an Xbox Live account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            GameLaunch.intentFor(context, "")?.let { context.startActivity(it) }
                        },
                        enabled = GameLaunch.systemFilesConfigured(context),
                    ) { Text("Open Xbox Dashboard") }
                }
            }
        }
    }
}
