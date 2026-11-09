package com.boxxy.emulation

import android.content.Context
import android.content.Intent

/**
 * The one way a game is started: the library, the scripted autolaunch and
 * frontends (ES-DE, via [com.boxxy.ExternalLaunchActivity]) all build their
 * EmulationActivity intent here, from the settings as saved.
 */
object GameLaunch {
    /** Set on launches that came from another app; exit returns there. */
    const val EXTRA_EXTERNAL = "external_launch"

    /** True once the BIOS, MCPX ROM and hard disk image are all chosen. */
    fun systemFilesConfigured(context: Context): Boolean {
        val p = prefs(context)
        return p.getString("mcpx_uri", null) != null &&
               p.getString("bios_uri", null) != null &&
               p.getString("hdd_uri", null) != null
    }

    /** The EmulationActivity intent for [isoUri], or null if the system
     *  files are not set up yet. */
    fun intentFor(context: Context, isoUri: String, quickResume: Boolean = false): Intent? {
        val p = prefs(context)
        val mcpx = p.getString("mcpx_uri", null) ?: return null
        val bios = p.getString("bios_uri", null) ?: return null
        val hdd  = p.getString("hdd_uri", null)  ?: return null
        val driverDir  = p.getString("driver_dir", "") ?: ""
        val driverName = p.getString("driver_name", "") ?: ""
        val useCustomDriver = p.getBoolean("driver_enabled", false) &&
                              driverDir.isNotEmpty() && driverName.isNotEmpty()
        return Intent(context, EmulationActivity::class.java).apply {
            putExtra("mcpx",       mcpx)
            putExtra("bios",       bios)
            putExtra("hdd",        hdd)
            putExtra("iso",        isoUri)
            putExtra("renderer",   p.getString("renderer", "VULKAN") ?: "VULKAN")
            putExtra("driverDir",  if (useCustomDriver) driverDir else "")
            putExtra("driverName", if (useCustomDriver) driverName else "")
            putExtra(EXTRA_QUICK_RESUME, quickResume)
        }
    }

    /** The Xbox dashboard, for Xbox Live setup: online, no Quick Resume. */
    fun dashboardIntent(context: Context): Intent? =
        intentFor(context, "")?.putExtra(EXTRA_SYSTEM_LAUNCH, true)

    /** Install the dashboard in the zip at [zipUri] onto the hard disk. */
    fun installDashboardIntent(context: Context, zipUri: String): Intent? =
        intentFor(context, "")?.apply {
            putExtra(EXTRA_SYSTEM_LAUNCH, true)
            putExtra(EXTRA_INSTALL_DASHBOARD, zipUri)
        }

    /** Boot the disc image [file] (Insignia's Setup Assistant), online. */
    fun systemDiscIntent(context: Context, file: java.io.File): Intent? =
        intentFor(context, android.net.Uri.fromFile(file).toString())
            ?.putExtra(EXTRA_SYSTEM_LAUNCH, true)

    private fun prefs(context: Context) =
        context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE)
}
