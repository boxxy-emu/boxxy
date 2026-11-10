package com.boxxy.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import com.boxxy.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from GitHub releases.
 *
 * The release workflow (.github/workflows/android.yml) attaches update.json to
 * every release: version name and code, the APK's file name and its SHA-256.
 * The check reads the latest *published* release (GitHub's "latest" skips
 * drafts and pre-releases) and offers it when its version code is higher than
 * this build's.  The APK is downloaded, checked against the SHA-256, and
 * handed to Android's installer, which asks the user to confirm -- apps from
 * outside a store can't update silently.  Android also refuses an update
 * signed with a different key, so only official releases can install.
 */
object Updater {
    private const val TAG = "boxxy-update"
    private val LATEST = BuildConfig.UPDATE_URL
    private const val TAG_PREFIX = "boxxy-v"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private const val PREF_AUTO = "update_auto_check"
    private const val PREF_LAST = "update_last_check"
    private const val PREF_SKIPPED = "update_skipped_code"

    data class Update(
        val versionName: String,
        val versionCode: Int,
        val notes: String,
        val apkName: String,
        val apkUrl: String,
        val sha256: String,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE)

    fun autoCheck(context: Context) = prefs(context).getBoolean(PREF_AUTO, true)

    fun setAutoCheck(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(PREF_AUTO, on).apply()
    }

    /** At most once a day, and only when the user hasn't turned it off. */
    fun autoCheckDue(context: Context): Boolean =
        autoCheck(context) &&
        System.currentTimeMillis() - prefs(context).getLong(PREF_LAST, 0) >= DAY_MS

    fun skipped(context: Context, u: Update) =
        prefs(context).getInt(PREF_SKIPPED, 0) == u.versionCode

    fun skip(context: Context, u: Update) {
        prefs(context).edit().putInt(PREF_SKIPPED, u.versionCode).apply()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Boxxy/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    private fun getText(url: String): String? {
        val c = open(url)
        try {
            if (c.responseCode == 404) return null   // no release published yet
            if (c.responseCode != 200) throw IOException("GitHub answered ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** The newer release, or null if this build is current.  Network: not on the main thread. */
    fun check(context: Context): Update? {
        prefs(context).edit().putLong(PREF_LAST, System.currentTimeMillis()).apply()
        val release = JSONObject(getText(LATEST) ?: return null)
        val tag = release.optString("tag_name")
        if (!tag.startsWith(TAG_PREFIX)) return null
        val assets = HashMap<String, String>()
        val list = release.optJSONArray("assets")
        for (i in 0 until (list?.length() ?: 0)) {
            val a = list!!.getJSONObject(i)
            assets[a.getString("name")] = a.getString("browser_download_url")
        }
        val meta = JSONObject(getText(assets["update.json"] ?: return null) ?: return null)
        val code = meta.getInt("versionCode")
        Log.i(TAG, "latest release $tag (code $code), this build ${BuildConfig.VERSION_CODE}")
        if (code <= BuildConfig.VERSION_CODE) return null
        val apkName = meta.getString("apk")
        return Update(
            versionName = meta.getString("versionName"),
            versionCode = code,
            notes = release.optString("body").trim(),
            apkName = apkName,
            apkUrl = assets[apkName] ?: return null,
            sha256 = meta.getString("sha256").lowercase(),
        )
    }

    /** Download the APK to the cache and check it against update.json's SHA-256. */
    fun download(context: Context, u: Update, progress: (Long, Long) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, u.apkName)
        val c = open(u.apkUrl).apply { setRequestProperty("Accept", "application/octet-stream") }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (c.responseCode != 200) throw IOException("Download failed (${c.responseCode})")
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != u.sha256) {
            file.delete()
            throw IOException("The download is damaged (checksum mismatch); try again")
        }
        return file
    }

    /** Whether Android lets Boxxy install apps ("Install unknown apps"). */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Hand the APK to Android's installer; it asks the user (UpdateReceiver). */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
            }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("boxxy.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java)
            // Mutable: the installer adds its status and confirmation intent.
            val pending = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(pending.intentSender)
        }
    }
}

/** The installer's answers: show its confirmation screen, or report a failure. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // Boxxy is replaced and stopped; the user opens it again (Android
            // doesn't let an app start itself from the background).
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("boxxy-update", "install failed: $status $msg")
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(context, "Update not installed: ${msg ?: status}",
                                   Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
