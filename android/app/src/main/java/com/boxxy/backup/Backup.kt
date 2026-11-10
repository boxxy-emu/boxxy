package com.boxxy.backup

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.boxxy.BuildConfig
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup and restore of everything Boxxy keeps in its private storage: the
 * Xbox's hard disk (game saves, save-state snapshots), its EEPROM (console
 * identity, Insignia registration), the BIOS/MCPX copies, save states, Quick
 * Resume, settings, artwork, shader caches.
 *
 * A backup is a plain folder, chosen by the user through the system picker,
 * with a boxxy-backup.json manifest listing every file and its size.  Not
 * included: secure_prefs.xml (encrypted with an Android Keystore key that an
 * uninstall deletes, so it could not be read back), game images (they stay
 * where they are), and the permissions on the game folders, which a reinstall
 * revokes -- the user picks those again.
 */
object Backup {
    const val MANIFEST = "boxxy-backup.json"
    private const val FORMAT = 1

    /** Under filesDir. */
    private val FILES = listOf(
        "xbox_hdd.qcow2", "eeprom.bin", "bios.bin", "mcpx.bin",
        "library_snapshot.json", "vk_pipeline_cache.bin",
    )
    private val DIRS = listOf(
        "states", "resume", "slot_thumbs", "recordings", "artwork", "driver",
        "xboxlive", "shaders", "shaders_vk",
    )
    private const val PREFS_DIR = "shared_prefs"
    private val PREFS_SKIP = setOf("secure_prefs.xml")

    data class Progress(val file: String, val done: Long, val total: Long)

    private const val CHUNK = 1 shl 20
    private const val FLUSH_EVERY = 32L shl 20
    /* Bytes per second.  Fast enough for a 6 GB disk in about half a minute,
     * slow enough for the kernel to reclaim the page cache behind it. */
    private const val MAX_RATE = 200L shl 20

    /*
     * Copy, keeping gigabytes out of the page cache.  A plain stream copy of
     * the 6 GB disk image left it all as dirty cache faster than storage took
     * it: the low-memory killer then stopped the storage provider, and Boxxy
     * with it (2026-11-09).  Syncing every FLUSH_EVERY bytes keeps the dirty
     * part small.  That was not enough: reading the image and writing it
     * through FUSE filled the cache with clean pages at ~1.1 GB/s, faster than
     * reclaim, and free memory fell until lmkd killed apps (measured).  Apps
     * cannot drop the cache themselves, so the copy is paced to MAX_RATE.
     */
    private fun copy(input: FileInputStream, output: FileOutputStream, onBytes: (Int) -> Unit) {
        val buf = ByteArray(CHUNK)
        var sinceFlush = 0L
        var copied = 0L
        val start = System.nanoTime()
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            onBytes(n)
            copied += n
            /* Ahead of MAX_RATE: wait until the schedule catches up. */
            val aheadMs = copied * 1000 / MAX_RATE - (System.nanoTime() - start) / 1_000_000
            if (aheadMs > 0) Thread.sleep(aheadMs)
            sinceFlush += n
            if (sinceFlush >= FLUSH_EVERY) {
                Os.fsync(output.fd)
                sinceFlush = 0
            }
        }
        Os.fsync(output.fd)
    }

    class Result(val folderName: String, val files: Int, val bytes: Long)

    /** The emulator writes the disk image while it runs: back up only when it doesn't. */
    fun emulatorRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val name = "${context.packageName}:EmulationProcess"
        return am.runningAppProcesses?.any { it.processName == name } == true
    }

    /** Paths relative to dataDir ("files/...", "shared_prefs/..."), with their files. */
    private fun entries(context: Context): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        val files = context.filesDir
        for (name in FILES) {
            val f = File(files, name)
            if (f.isFile) out += "files/$name" to f
        }
        for (dir in DIRS) {
            val root = File(files, dir)
            root.walkTopDown().filter { it.isFile }.forEach {
                out += "files/$dir/${it.relativeTo(root).path}" to it
            }
        }
        File(context.dataDir, PREFS_DIR).listFiles()?.filter {
            it.isFile && it.name.endsWith(".xml") && it.name !in PREFS_SKIP
        }?.forEach { out += "$PREFS_DIR/${it.name}" to it }
        return out
    }

    private fun subdir(parent: DocumentFile, name: String, cache: MutableMap<String, DocumentFile>,
                       key: String): DocumentFile =
        cache.getOrPut(key) {
            parent.findFile(name)?.takeIf { it.isDirectory }
                ?: parent.createDirectory(name)
                ?: throw IOException("Could not create folder $name")
        }

    /** Copy everything into a new folder inside [treeUri]. */
    fun backup(context: Context, treeUri: Uri, progress: (Progress) -> Unit): Result {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("That folder can't be written")
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())
        val folderName = "Boxxy backup $stamp"
        /* Named as incomplete until the last file and the manifest are in:
         * a backup cut short (the app killed) can't pass for a good one. */
        val top = root.createDirectory("$folderName (incomplete)")
            ?: throw IOException("Could not create a folder there")
        val items = entries(context)
        val total = items.sumOf { it.second.length() }
        val dirs = HashMap<String, DocumentFile>()
        val manifest = JSONArray()
        var done = 0L
        try {
            for ((path, file) in items) {
                val parts = path.split('/')
                var parent = top
                var key = ""
                for (p in parts.dropLast(1)) {
                    key += "/$p"
                    parent = subdir(parent, p, dirs, key)
                }
                val out = parent.createFile("application/octet-stream", parts.last())
                    ?: throw IOException("Could not create $path")
                context.contentResolver.openFileDescriptor(out.uri, "w")!!.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { o ->
                        FileInputStream(file).use { i ->
                            copy(i, o) { n ->
                                done += n
                                progress(Progress(path, done, total))
                            }
                        }
                    }
                }
                manifest.put(JSONObject().put("path", path).put("size", file.length()))
            }
            val m = top.createFile("application/json", MANIFEST.removeSuffix(".json"))
                ?: throw IOException("Could not write the manifest")
            context.contentResolver.openOutputStream(m.uri, "w")!!.use {
                it.write(JSONObject()
                    .put("format", FORMAT)
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE)
                    .put("created", stamp)
                    .put("files", manifest)
                    .toString(1).toByteArray())
            }
            if (!top.renameTo(folderName)) throw IOException("Could not name the backup folder")
        } catch (e: Exception) {
            top.delete()                // a partial backup must not look complete
            throw e
        }
        return Result(folderName, items.size, total)
    }

    /** Every file under [dir], by path relative to it. */
    private fun listTree(dir: DocumentFile, prefix: String = "", out: MutableMap<String, DocumentFile> = HashMap()):
            MutableMap<String, DocumentFile> {
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            if (f.isDirectory) listTree(f, "$prefix$name/", out) else out["$prefix$name"] = f
        }
        return out
    }

    /**
     * Replace this device's Boxxy data with the backup in [folderUri] (the
     * backup folder itself).  Checks the whole backup against its manifest
     * before changing anything.  The app must restart afterwards: settings
     * are cached in memory.
     */
    fun restore(context: Context, folderUri: Uri, progress: (Progress) -> Unit): Result {
        val top = DocumentFile.fromTreeUri(context, folderUri)
            ?: throw IOException("That folder can't be read")
        val tree = listTree(top)
        val manifestFile = tree[MANIFEST] ?: throw IOException(
            "That folder isn't a Boxxy backup (no $MANIFEST). Choose the " +
            "\"Boxxy backup ...\" folder itself.")
        val manifest = context.contentResolver.openInputStream(manifestFile.uri)!!.use {
            JSONObject(it.readBytes().decodeToString())
        }
        if (manifest.optInt("format") != FORMAT) {
            throw IOException("This backup was made by a newer Boxxy")
        }
        val list = manifest.getJSONArray("files")
        val wanted = (0 until list.length()).map {
            val o = list.getJSONObject(it)
            o.getString("path") to o.getLong("size")
        }
        for ((path, size) in wanted) {
            val f = tree[path] ?: throw IOException("The backup is incomplete: $path is missing")
            if (f.length() != size) throw IOException("The backup is damaged: $path has the wrong size")
            if (path.contains("..") || !(path.startsWith("files/") || path.startsWith("$PREFS_DIR/"))) {
                throw IOException("The backup lists an unexpected file: $path")
            }
        }

        // The backup is complete: replace what it covers.
        for (dir in DIRS) File(context.filesDir, dir).deleteRecursively()
        val total = wanted.sumOf { it.second }
        var done = 0L
        for ((path, _) in wanted) {
            val dest = File(context.dataDir, path)
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".restoring")
            context.contentResolver.openFileDescriptor(tree.getValue(path).uri, "r")!!.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { i ->
                    FileOutputStream(tmp).use { o ->
                        copy(i, o) { n ->
                            done += n
                            progress(Progress(path, done, total))
                        }
                    }
                }
            }
            if (!tmp.renameTo(dest)) throw IOException("Could not restore $path")
        }
        return Result(top.name ?: "backup", wanted.size, total)
    }
}
