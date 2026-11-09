package com.boxxy.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.boxxy.emulation.GameLaunch
import com.boxxy.xboxlive.DashboardInstaller
import com.boxxy.xboxlive.XboxLive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREF_HAVE_CODE = "xboxlive_have_code"
private const val PREF_HAVE_DASHBOARD = "xboxlive_have_dashboard"
private const val PREF_REGISTERED = "xboxlive_registered"

/**
 * Xbox Live through Insignia, one step at a time.  Boxxy does what it can by
 * itself -- the connection, installing the dashboard, fetching and starting
 * the Setup Assistant -- and leaves only what happens inside the Xbox, or in
 * the user's email, to them.
 */
@Composable
fun XboxLiveSetupScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val systemReady = GameLaunch.systemFilesConfigured(context)

    /* The connection needs nothing from the user: opening this turns it on. */
    LaunchedEffect(Unit) {
        settingsViewModel.setNetworkEnabled(true)
        settingsViewModel.setNetworkDns("insignia")
    }

    /* The other steps' results can change while the emulator has the screen. */
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var haveCode by remember { mutableStateOf(prefs.getBoolean(PREF_HAVE_CODE, false)) }
    var registered by remember { mutableStateOf(prefs.getBoolean(PREF_REGISTERED, false)) }
    val dashboardInstalled = remember(refresh) {
        XboxLive.dashboardInstalled(context) || prefs.getBoolean(PREF_HAVE_DASHBOARD, false)
    }
    val installError = remember(refresh) {
        XboxLive.installError(context).takeIf { it.exists() }?.readText()
    }

    var dashboardMessage by remember { mutableStateOf<String?>(null) }
    var notFiveNineSixty by remember { mutableStateOf<Uri?>(null) }
    var setupProgress by remember { mutableStateOf<Float?>(null) }
    var setupError by remember { mutableStateOf<String?>(null) }

    fun install(uri: Uri) {
        XboxLive.installError(context).delete()
        GameLaunch.installDashboardIntent(context, uri.toString())?.let {
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(it)
        }
    }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        /* The emulator reads it from its own process, later. */
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        dashboardMessage = "Checking the zip…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DashboardInstaller.scan(context, uri) }
            }
            val contents = result.getOrElse {
                dashboardMessage = "That zip can't be used: ${it.message}."
                return@launch
            }
            dashboardMessage = null
            when {
                !contents.hasXboxLive -> dashboardMessage =
                    "That dashboard has no Xbox Live section (xodash\\xonlinedash.xbe), " +
                    "so accounts can't be made with it."
                !contents.is5960 -> notFiveNineSixty = uri
                else -> install(uri)
            }
        }
    }

    fun launchSetup() {
        val iso = XboxLive.setupIso(context)
        if (iso.exists()) {
            GameLaunch.systemDiscIntent(context, iso)?.let { context.startActivity(it) }
            return
        }
        setupError = null
        setupProgress = 0f
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    XboxLive.downloadSetup(context) { done, total ->
                        if (total > 0) setupProgress = done.toFloat() / total
                    }
                }
            }
            setupProgress = null
            result.onSuccess {
                GameLaunch.systemDiscIntent(context, iso)?.let { context.startActivity(it) }
            }.onFailure {
                setupError = "Couldn't download the Setup Assistant: ${it.message}."
            }
        }
    }

    val steps = listOf(haveCode, true, dashboardInstalled, registered)
    val current = steps.indexOfFirst { !it }.let { if (it < 0) steps.size else it }

    SettingsSubScreenScaffold("Xbox Live setup", navController) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Insignia is a free revival of the original Xbox Live. Boxxy does what it " +
                "can by itself; these are the parts only you can do.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!systemReady) {
                Text(
                    "Choose the BIOS, MCPX ROM and hard disk image under Settings › System first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SetupStep(
                number = 1,
                title = "Get a subscription code",
                done = haveCode,
                current = current == 0,
            ) {
                StepText(
                    "Register your email at insignia.live. Insignia emails you a free " +
                    "subscription code; it can take a while to arrive, so carry on meanwhile.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(XboxLive.SITE)))
                    }) { Text("Open insignia.live") }
                    if (!haveCode) {
                        TextButton(onClick = {
                            haveCode = true
                            prefs.edit().putBoolean(PREF_HAVE_CODE, true).apply()
                        }) { Text("I have my code") }
                    }
                }
            }

            SetupStep(
                number = 2,
                title = "Connection",
                done = true,
                current = false,
            ) {
                StepText(
                    "Done for you: the Xbox goes online through this device, using " +
                    "Insignia's DNS. Settings › Network has the details.")
            }

            SetupStep(
                number = 3,
                title = "Install the Xbox dashboard",
                done = dashboardInstalled,
                current = current == 2,
            ) {
                StepText(
                    "Insignia accounts are made in Microsoft's Xbox dashboard, version 5960, " +
                    "which Boxxy can't include. Choose a zip of its files (from your own Xbox) " +
                    "and Boxxy copies them onto your hard disk image. It takes about a minute.")
                installError?.let { StepText("Last try failed: $it.", error = true) }
                dashboardMessage?.let { StepText(it, error = !it.endsWith("…")) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            pickZip.launch(arrayOf(
                                "application/zip", "application/x-zip-compressed",
                                "application/octet-stream"))
                        },
                        enabled = systemReady,
                    ) { Text(if (dashboardInstalled) "Install again" else "Choose zip") }
                    if (!dashboardInstalled) {
                        TextButton(onClick = {
                            prefs.edit().putBoolean(PREF_HAVE_DASHBOARD, true).apply()
                            refresh++
                        }) { Text("It's already installed") }
                    }
                }
            }

            SetupStep(
                number = 4,
                title = "Register this Xbox",
                done = registered,
                current = current == 3,
            ) {
                StepText(
                    "Boxxy downloads Insignia's Setup Assistant and starts it. Choose " +
                    "“Register Xbox”, wait for it to say it worked, then exit from " +
                    "the menu.")
                setupProgress?.let {
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                }
                setupError?.let { StepText(it, error = true) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { launchSetup() },
                        enabled = systemReady && setupProgress == null,
                    ) { Text("Start Setup Assistant") }
                    if (!registered) {
                        TextButton(onClick = {
                            registered = true
                            prefs.edit().putBoolean(PREF_REGISTERED, true).apply()
                        }) { Text("It's registered") }
                    }
                }
            }

            SetupStep(
                number = 5,
                title = "Create your account",
                done = false,
                current = current == 4,
            ) {
                StepText("In the dashboard, open Xbox Live and make a new account:")
                StepText(
                    "• Pick your country, accept the terms and choose a gamertag.\n" +
                    "• Enter the subscription code from your email.\n" +
                    "• For the credit card, enter Insignia's test number " +
                    "4111 1111 1111 1111. Never a real one.\n" +
                    "• Use the same email you registered with.")
                StepText(
                    "Already have an account from another Xbox? Choose account recovery " +
                    "in the dashboard instead.")
                Button(
                    onClick = {
                        GameLaunch.dashboardIntent(context)?.let { context.startActivity(it) }
                    },
                    enabled = systemReady,
                ) { Text("Open Xbox Dashboard") }
            }

            Text(
                "Insignia knows this Xbox by its identity, which Boxxy keeps in its app data. " +
                "Clearing Boxxy's storage or reinstalling it gives the Xbox a new identity, " +
                "and it would need registering again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    notFiveNineSixty?.let { uri ->
        AlertDialog(
            onDismissRequest = { notFiveNineSixty = null },
            title = { Text("Not dashboard 5960?") },
            text = {
                Text("This zip doesn't look like version 5960, the one Insignia needs to " +
                     "make accounts. Install it anyway?")
            },
            confirmButton = {
                TextButton(onClick = { notFiveNineSixty = null; install(uri) }) {
                    Text("Install")
                }
            },
            dismissButton = {
                TextButton(onClick = { notFiveNineSixty = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SetupStep(
    number: Int,
    title: String,
    done: Boolean,
    current: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = if (current) {
            CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary))
        } else {
            CardDefaults.outlinedCardBorder()
        },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (done) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Done",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Text(
                        "$number",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (current) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(22.dp),
                    )
                }
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            content()
        }
    }
}

@Composable
private fun StepText(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
