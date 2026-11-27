package com.tomtunnel.app.engine

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject

/** Un moteur ouvre un proxy SOCKS5 sur 127.0.0.1:SOCKS_PORT puis le service y branche l'interface TUN. */
interface Engine {
    fun start(ctx: Context, s: JSONObject)
    fun stop()
    /** false = le moteur est tombé (processus mort, session SSH coupée) : le service peut reconnecter. */
    fun alive(): Boolean = true
    companion object { const val SOCKS_PORT = 10808 }
}

internal fun Process.drainTo(file: File): Thread = Thread {
    try {
        file.parentFile?.mkdirs()
        inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line -> file.appendText(line + "\n") }
        }
    } catch (_: Throwable) {}
}.apply { isDaemon = true; start() }

internal fun waitForPort(host: String, port: Int, timeoutMs: Long = 6000): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), 300) }
            return true
        } catch (_: Throwable) { Thread.sleep(100) }
    }
    return false
}

internal fun tail(file: File, max: Int = 1200): String = try {
    val s = if (file.exists()) file.readText() else ""
    s.takeLast(max)
} catch (_: Throwable) { "" }

private fun InputStream.readFullyInto(buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
        val n = read(buf, off, buf.size - off)
        if (n < 0) error("EOF SOCKS")
        off += n
    }
}

/** Ouvre une vraie connexion TCP vers host:port à travers le proxy SOCKS5 local (sans authentification). */
internal fun socksProbe(socksPort: Int, host: String, port: Int): Boolean = try {
    Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", socksPort), 3000)
        socket.soTimeout = 8000
        val out = socket.getOutputStream(); val input = socket.getInputStream()
        out.write(byteArrayOf(5, 1, 0)); out.flush()
        val hello = ByteArray(2); input.readFullyInto(hello)
        check(hello[0].toInt() == 5 && hello[1].toInt() == 0)
        val hb = host.toByteArray(Charsets.UTF_8)
        require(hb.size <= 255)
        out.write(byteArrayOf(5, 1, 0, 3, hb.size.toByte())); out.write(hb)
        out.write(byteArrayOf((port ushr 8).toByte(), port.toByte())); out.flush()
        val h = ByteArray(4); input.readFullyInto(h)
        if (h[1].toInt() != 0) {
            false
        } else {
            val rest = when (h[3].toInt() and 255) {
                1 -> 4
                3 -> input.read().also { check(it >= 0) }
                4 -> 16
                else -> error("ATYP SOCKS invalide")
            }
            input.readFullyInto(ByteArray(rest))
            input.readFullyInto(ByteArray(2))
            true
        }
    }
} catch (_: Throwable) { false }
