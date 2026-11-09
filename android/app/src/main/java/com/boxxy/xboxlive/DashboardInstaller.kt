package com.boxxy.xboxlive

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.ZipInputStream

private const val TAG = "xemu-xboxlive"

/**
 * Installs the Xbox dashboard from a zip of its files onto drive C: of the
 * hard disk image.
 *
 * The image is a qcow2 file holding an Xbox-format (FATX) disk, so rather
 * than writing that format here, the files go in through the guest: the
 * machine boots the dashboard already on the disk -- xemu's, which runs an
 * FTP server -- and this uploads to it over a port forwarded from this
 * device's loopback only.  The guest's own file system does the writing.
 */
object DashboardInstaller {
    const val XBE = "xboxdash.xbe"
    private const val ONLINE_XBE = "xodash/xonlinedash.xbe"

    /** What a zip holds: where the dashboard is in it, and how much. */
    class Contents(
        val prefix: String,
        val files: List<Pair<String, Long>>,
        val hasXboxLive: Boolean,
        val is5960: Boolean,
    ) {
        val totalBytes get() = files.sumOf { it.second.coerceAtLeast(0) }
    }

    /** Read [uri]'s entries.  Throws IOException when it has no dashboard. */
    fun scan(context: Context, uri: Uri): Contents {
        val names = ArrayList<Pair<String, Long>>()
        open(context, uri).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                if (!e.isDirectory) names += e.name.replace('\\', '/') to e.size
            }
        }
        /* The dashboard may sit in a folder inside the zip: the shallowest
         * xboxdash.xbe marks it. */
        val prefix = names.map { it.first }
            .filter { it.substringAfterLast('/').equals(XBE, ignoreCase = true) }
            .minByOrNull { it.length }
            ?.dropLast(XBE.length)
            ?: throw IOException("there is no $XBE in this zip")
        val files = names.filter { it.first.startsWith(prefix, ignoreCase = true) }
            .map { it.first.substring(prefix.length) to it.second }
            .filter { (name, _) -> name.split('/').none { it == ".." } }
        return Contents(
            prefix = prefix,
            files = files,
            hasXboxLive = files.any { it.first.equals(ONLINE_XBE, ignoreCase = true) },
            is5960 = files.any {
                it.first.substringBefore('/').equals(XboxLive.DASH_5960_DATA, ignoreCase = true)
            },
        )
    }

    /**
     * Upload the dashboard in [uri] to the guest's FTP server on 127.0.0.1:[port].
     * Blocking.  [progress] gets the fraction done and what is happening.
     * Returns null on success, else why it failed.
     */
    fun install(context: Context, uri: Uri, port: Int,
                progress: (Float, String) -> Unit): String? {
        return try {
            val contents = scan(context, uri)
            progress(0f, "Waiting for the Xbox to start…")
            connect(port).use { ftp ->
                ftp.login()
                progress(0f, "Copying the dashboard…")
                backUpPrevious(context, ftp)
                upload(context, uri, contents, ftp, progress)
            }
            XboxLive.dashboardMarker(context).writeText(
                if (contents.is5960) "5960" else "other")
            XboxLive.installError(context).delete()
            Log.i(TAG, "dashboard installed: ${contents.files.size} files")
            null
        } catch (e: IOException) {
            Log.w(TAG, "dashboard install failed", e)
            val why = e.message ?: e.toString()
            XboxLive.installError(context).writeText(why)
            why
        }
    }

    /** The guest takes a while to boot and start its server: keep trying. */
    private fun connect(port: Int): Ftp {
        val deadline = System.currentTimeMillis() + 120_000
        var last: IOException? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                return Ftp(port)
            } catch (e: IOException) {
                if (e.message != last?.message) Log.i(TAG, "waiting for the FTP server: ${e.message}")
                last = e
                Thread.sleep(2000)
            }
        }
        throw IOException(
            "the hard disk's dashboard did not answer. Installing needs the dashboard " +
            "from xemu's hard disk image, which has a file server (${last?.message})")
    }

    /** Keep the dashboard being replaced, once: the first one is the original. */
    private fun backUpPrevious(context: Context, ftp: Ftp) {
        val keep = XboxLive.previousDashboard(context)
        if (keep.exists()) return
        val part = File(keep.path + ".part")
        try {
            part.outputStream().use { ftp.retrieve("/C/$XBE", it) }
            part.renameTo(keep)
        } catch (e: IOException) {
            Log.w(TAG, "could not keep the previous dashboard: ${e.message}")
        } finally {
            part.delete()
        }
    }

    private fun upload(context: Context, uri: Uri, contents: Contents, ftp: Ftp,
                       progress: (Float, String) -> Unit) {
        val total = contents.totalBytes.coerceAtLeast(1)
        val wanted = contents.files.associate { it.first.lowercase() to it.second }
        val made = HashSet<String>()
        var done = 0L
        open(context, uri).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                if (e.isDirectory) return@forEach
                val name = e.name.replace('\\', '/')
                if (!name.startsWith(contents.prefix, ignoreCase = true)) return@forEach
                val rel = name.substring(contents.prefix.length)
                if (rel.lowercase() !in wanted) return@forEach

                /* Each folder on the way, once; one already there is fine. */
                val parts = rel.split('/')
                for (i in 1 until parts.size) {
                    val dir = "/C/" + parts.take(i).joinToString("/")
                    if (made.add(dir.lowercase())) ftp.makeDir(dir)
                }

                val path = "/C/$rel"
                progress(done.toFloat() / total, rel)
                val sent = ftp.store(path, zip) { n ->
                    done += n
                    progress(done.toFloat() / total, rel)
                }
                val size = ftp.size(path)
                if (size != sent) {
                    throw IOException("$rel was not written whole ($size of $sent bytes)")
                }
            }
        }
        progress(1f, "Done")
    }

    private fun open(context: Context, uri: Uri) =
        ZipInputStream(context.contentResolver.openInputStream(uri)
            ?: throw IOException("could not open the zip"))
}

/**
 * Just enough FTP for the installer, against xemu's dashboard server.
 *
 * Data connections are active (PORT): the guest connects out to the host's
 * address on the NAT, 10.0.2.2, which the NAT turns into this device's
 * loopback.  Passive mode would need a second forwarded port, for a port the
 * server only picks at transfer time.
 */
private class Ftp(port: Int) : Closeable {
    private val socket = Socket(LOOPBACK, port).apply {
        soTimeout = 30_000
    }
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
    private val writer = socket.getOutputStream()

    init {
        try {
            /* The forward accepts at once, even before the guest listens;
             * only a greeting shows the server is really there. */
            val (code, text) = reply()
            if (code != 220) throw IOException("unexpected greeting: $code $text")
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    fun login() {
        command("USER xbox", 230, 331).let { (code, _) ->
            if (code == 331) command("PASS xbox", 230)
        }
        command("TYPE I", 200)
    }

    fun makeDir(path: String) {
        /* 550: there already. */
        command("MKD $path", 257, 550)
    }

    fun size(path: String): Long =
        command("SIZE $path", 213).second.trim().toLongOrNull() ?: -1

    /** Upload [input] (to its end) to [path]; returns the bytes sent. */
    fun store(path: String, input: InputStream, onBytes: (Int) -> Unit): Long =
        transfer("STOR $path") { data ->
            val out = data.getOutputStream()
            val buf = ByteArray(1 shl 16)
            var sent = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                sent += n
                onBytes(n)
            }
            out.flush()
            sent
        }

    fun retrieve(path: String, out: OutputStream): Long =
        transfer("RETR $path") { data -> data.getInputStream().copyTo(out) }

    private fun <T> transfer(cmd: String, body: (Socket) -> T): T {
        ServerSocket(0, 1, LOOPBACK).use { server ->
            server.soTimeout = 30_000
            val p = server.localPort
            command("PORT 10,0,2,2,${p shr 8},${p and 0xff}", 200)
            command(cmd, 150, 125)
            val result = server.accept().use { data ->
                data.soTimeout = 30_000
                body(data)
            }
            expect(reply(), cmd, 226, 250)
            return result
        }
    }

    private fun command(line: String, vararg ok: Int): Pair<Int, String> {
        writer.write("$line\r\n".toByteArray(Charsets.ISO_8859_1))
        writer.flush()
        return expect(reply(), line, *ok)
    }

    private fun expect(r: Pair<Int, String>, what: String, vararg ok: Int): Pair<Int, String> {
        if (r.first !in ok) {
            throw IOException("${what.substringBefore(' ')} ${what.substringAfter(' ', "")}: ${r.first} ${r.second}".trim())
        }
        return r
    }

    /** One reply; a multi-line one ("123-" ... "123 ") reads to its last line. */
    private fun reply(): Pair<Int, String> {
        var line = reader.readLine() ?: throw IOException("the connection closed")
        val code = line.take(3).toIntOrNull() ?: throw IOException("bad reply: $line")
        if (line.getOrNull(3) == '-') {
            while (true) {
                line = reader.readLine() ?: throw IOException("the connection closed")
                if (line.startsWith("$code ")) break
            }
        }
        return code to line.drop(4)
    }

    companion object {
        /* IPv4: the forward listens there, and the guest's data connections
         * arrive there.  getLoopbackAddress() is ::1 on Android. */
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }

    override fun close() {
        try {
            writer.write("QUIT\r\n".toByteArray(Charsets.ISO_8859_1))
            writer.flush()
        } catch (_: IOException) {}
        socket.close()
    }
}
