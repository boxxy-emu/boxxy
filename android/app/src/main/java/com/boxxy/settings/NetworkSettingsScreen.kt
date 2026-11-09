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
            SettingsSectionLabel("Online play")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Play online through Insignia, a community revival of the " +
                        "original Xbox Live. Setup walks you through it and does most of " +
                        "it for you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { navController.navigate("settings/xboxlive") }) {
                        Text("Set up online play")
                    }
                }
            }

            SettingsSectionLabel("Connection")
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
                        "insignia" to "Insignia",
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
                        "With Insignia, Boxxy answers the Xbox's DNS itself, sending Xbox " +
                        "Live's names to Insignia's servers and everything else through " +
                        "this device. It works even on networks that block Insignia's DNS " +
                        "server, and whatever DNS the Xbox's own settings name.",
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
                        "(Settings > Network Settings) or create your Insignia account.",
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
