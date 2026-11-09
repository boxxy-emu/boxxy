package com.boxxy.xboxlive

import android.util.Log
import org.json.JSONArray
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val TAG = "xemu-xboxlive"

/**
 * The emulated Xbox's DNS server, for Insignia.
 *
 * The Xbox finds Xbox Live by name (macs.xboxlive.com and a few more).
 * Insignia's own DNS server answers those with its servers' addresses, but
 * some networks block or hijack DNS to it.  insigniaDNS, Insignia's fix for
 * that (MIT, github.com/insignia-live/insigniaDNS), is a small local server
 * built on a table they publish: this is the same thing, inside Boxxy.  The
 * NAT sends every DNS query the guest makes here (SLIRP_DNS_REDIRECT).
 *
 * Insignia's names are answered from the table: each points at a name of
 * theirs (macs.insig.uk ...), looked up through this device's normal DNS.
 * Every other A query goes to Android's resolver, so private DNS and VPNs
 * work as they do for apps.  Other record types get an empty answer; the
 * Xbox only asks for A records.
 */
class InsigniaDns : Closeable {
    private val socket = DatagramSocket(0, LOOPBACK)
    private val pool = Executors.newFixedThreadPool(4)

    /** Name (lower case) -> what to answer it with: a name to look up, or an address. */
    @Volatile private var zones: Map<String, String> = DEFAULT_ZONES

    private val cache = ConcurrentHashMap<String, Pair<Long, List<ByteArray>>>()

    val port: Int get() = socket.localPort

    fun start() {
        Thread(::serve, "insignia-dns").apply { isDaemon = true }.start()
        pool.execute(::refreshZones)
    }

    override fun close() {
        socket.close()
        pool.shutdownNow()
    }

    private fun serve() {
        val buf = ByteArray(1500)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (e: SocketException) {
                break               /* closed */
            } catch (e: IOException) {
                continue
            }
            val query = packet.data.copyOf(packet.length)
            val from = packet.socketAddress
            pool.execute {
                val reply = try { answer(query) } catch (e: Exception) { null }
                if (reply != null) {
                    try { socket.send(DatagramPacket(reply, reply.size, from)) } catch (_: IOException) {}
                }
            }
        }
    }

    /** The reply to [q], or null if it isn't a query worth answering. */
    private fun answer(q: ByteArray): ByteArray? {
        if (q.size < 12 || (q[2].toInt() and 0x80) != 0) return null    /* a reply */
        if (u16(q, 4) != 1) return null                                 /* one question only */
        var i = 12
        val labels = ArrayList<String>()
        while (true) {
            if (i >= q.size) return null
            val len = q[i].toInt() and 0xff
            if (len == 0) { i++; break }
            if (len > 63 || i + 1 + len > q.size) return null
            labels += String(q, i + 1, len, Charsets.US_ASCII)
            i += 1 + len
        }
        if (i + 4 > q.size) return null
        val qtype = u16(q, i)
        val questionEnd = i + 4
        val name = labels.joinToString(".").lowercase()

        val target = zones[name]
        val addrs: List<ByteArray>? = try {
            when {
                qtype != 1 -> emptyList()       /* A only */
                target != null -> lookup(target)
                else -> lookup(name)
            }
        } catch (e: UnknownHostException) {
            null
        }
        if (target != null) Log.i(TAG, "dns: $name -> $target ${addrs?.map(::ip)}")

        /* NXDOMAIN when the name doesn't exist, and for Insignia's names a
         * failed lookup is better reported than hidden. */
        val rcode = if (addrs == null) 3 else 0
        val answers = addrs.orEmpty()
        val out = java.io.ByteArrayOutputStream()
        out.write(q, 0, 2)                                  /* id */
        out.write(0x80 or (q[2].toInt() and 0x01))          /* QR, RD copied */
        out.write(0x80 or rcode)                            /* RA */
        out.write(byteArrayOf(0, 1, 0, answers.size.toByte(), 0, 0, 0, 0))
        out.write(q, 12, questionEnd - 12)
        for (a in answers) {
            out.write(byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0x01, 0x2C, 0, 4))
            out.write(a)
        }
        return out.toByteArray()
    }

    /** IPv4 addresses for [host] (an address is its own), cached for a minute. */
    private fun lookup(host: String): List<ByteArray> {
        val now = System.currentTimeMillis()
        cache[host]?.let { (at, addrs) -> if (now - at < 60_000) return addrs }
        val addrs = InetAddress.getAllByName(host)
            .filterIsInstance<Inet4Address>()
            .map { it.address }
        if (addrs.isEmpty()) throw UnknownHostException(host)
        cache[host] = now to addrs
        if (cache.size > 512) cache.clear()
        return addrs
    }

    /** Insignia's current table; the built-in copy stays if this fails. */
    private fun refreshZones() {
        try {
            val conn = URL(ZONES_URL).openConnection().apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "Boxxy (insigniaDNS-compatible)")
            }
            val json = JSONArray(conn.getInputStream().bufferedReader().use { it.readText() })
            val fresh = HashMap<String, String>()
            for (k in 0 until json.length()) {
                val z = json.getJSONObject(k)
                if (z.optString("type") in setOf("a", "p")) {
                    fresh[z.getString("name").lowercase()] = z.getString("value")
                }
            }
            if (fresh.isNotEmpty()) {
                zones = fresh
                Log.i(TAG, "dns: ${fresh.size} names from Insignia")
            }
        } catch (e: Exception) {
            Log.w(TAG, "dns: using the built-in names (${e.message})")
        }
    }

    private fun u16(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)

    private fun ip(a: ByteArray) = a.joinToString(".") { (it.toInt() and 0xff).toString() }

    companion object {
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        const val ZONES_URL = "https://insignia.live/dns_zones.json"

        /** insignia.live/dns_zones.json as of 2026-11-08. */
        val DEFAULT_ZONES = mapOf(
            "macs.xboxlive.com" to "macs.insig.uk",
            "as.xboxlive.com" to "as.insig.uk",
            "tgs.xboxlive.com" to "tgs.insig.uk",
            "xds.xboxlive.com" to "xexds.xboxlive.com",
            "insignia.live" to "insignia.live",
        )
    }
}
