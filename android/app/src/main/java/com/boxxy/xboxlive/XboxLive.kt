package com.boxxy.xboxlive

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Xbox Live through Insignia (insignia.live), the revival of the original
 * service: what the setup screen downloads, and the files it keeps.
 *
 * The steps run in different processes -- the dashboard is installed from
 * EmulationActivity's -- so their results are files here rather than
 * SharedPreferences, which a second process would not see.
 */
object XboxLive {
    const val SITE = "https://insignia.live"

    /** Insignia's Setup Assistant, the disc image they build for emulators. */
    const val SETUP_ISO_URL =
        "https://github.com/insignia-live/setup-assistant-release/releases/latest/download/Insignia-Setup.iso"

    /**
     * The 5960 dashboard's data folder.  The dashboard names it after its
     * xboxdash.xbe's certificate version (0x185ead00 for 5960), so it tells
     * the version apart without parsing the XBE.
     */
    const val DASH_5960_DATA = "xboxdashdata.185ead00"

    fun dir(context: Context) = File(context.filesDir, "xboxlive").apply { mkdirs() }

    fun setupIso(context: Context) = File(dir(context), "Insignia-Setup.iso")

    /** Written by the installer when the dashboard is on the hard disk: its version. */
    fun dashboardMarker(context: Context) = File(dir(context), "dashboard_installed")

    /** Written by the installer when it fails: why, for the setup screen to show. */
    fun installError(context: Context) = File(dir(context), "dashboard_install_error")

    /** The dashboard the installer replaced (xemu's), kept in case it is wanted back. */
    fun previousDashboard(context: Context) = File(dir(context), "xboxdash_previous.xbe")

    fun dashboardInstalled(context: Context) = dashboardMarker(context).exists()

    /**
     * Download the Setup Assistant into [setupIso].  Blocking; [progress]
     * gets bytes done and the total (0 when not known).
     */
    fun downloadSetup(context: Context, progress: (Long, Long) -> Unit) {
        val out = setupIso(context)
        val part = File(out.path + ".part")
        val conn = URL(SETUP_ISO_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.coerceAtLeast(0)
            var done = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
            if (!isXiso(part)) throw IOException("the download is not an Xbox disc image")
            if (!part.renameTo(out)) throw IOException("could not save it")
        } finally {
            conn.disconnect()
            part.delete()
        }
    }

    /** An XISO has "MICROSOFT*XBOX*MEDIA" at 0x10000. */
    private fun isXiso(f: File): Boolean = try {
        RandomAccessFile(f, "r").use { r ->
            val magic = ByteArray(20)
            r.seek(0x10000)
            r.readFully(magic)
            String(magic, Charsets.US_ASCII) == "MICROSOFT*XBOX*MEDIA"
        }
    } catch (e: IOException) {
        false
    }
}
