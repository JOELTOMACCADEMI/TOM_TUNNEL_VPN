package com.tomtunnel.app.engine

import android.content.Context
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Création de session SSH commune à SlowDNS, SSH TCP et SSH WebSocket. */
internal object SshCommon {
    fun knownHosts(ctx: Context) = File(ctx.filesDir, "known_hosts")

    /** Journal de diagnostic (ssh.log) : jamais de mot de passe, uniquement des événements de sécurité. */
    fun log(ctx: Context, msg: String) {
        try {
            val f = File(ctx.filesDir, "ssh.log")
            if (f.length() > 20000) f.writeText("")
            f.appendText("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $msg\n")
        } catch (_: Throwable) {}
    }

    /** Algorithmes anciens (vieux Dropbear/OpenSSH) : utilisés uniquement si la négociation moderne échoue. */
    private val LEGACY = mapOf(
        "kex" to "diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1,diffie-hellman-group1-sha1",
        "server_host_key" to "ssh-rsa,ssh-dss",
        "cipher.s2c" to "aes128-cbc,aes256-cbc,3des-cbc",
        "cipher.c2s" to "aes128-cbc,aes256-cbc,3des-cbc"
    )

    /**
     * Connexion SSH. [newProxy] doit ouvrir un transport NEUF à chaque appel (il est rappelé pour le second essai).
     * - Clé d'hôte : acceptée à la première connexion (TOFU), puis refusée si elle change.
     * - Algorithmes : modernes d'abord ; anciens seulement en cas d'échec de négociation.
     */
    fun connect(ctx: Context, host: String, port: Int, user: String, pass: String, newProxy: () -> Proxy): Session {
        var legacy = false
        while (true) {
            val proxy = newProxy()
            try {
                return attempt(ctx, host, port, user, pass, proxy, legacy)
            } catch (e: Throwable) {
                try { proxy.close() } catch (_: Throwable) {}
                val m = e.message.orEmpty()
                if (!legacy && e is com.jcraft.jsch.JSchException && m.contains("Algorithm negotiation fail", true)) {
                    legacy = true
                    log(ctx, "Négociation SSH moderne refusée par $host : nouvel essai avec algorithmes hérités")
                    continue
                }
                if (m.contains("HostKey has been changed", true) || m.contains("HOST IDENTIFICATION HAS CHANGED", true)) {
                    log(ctx, "ALERTE : la clé d'hôte SSH de $host a changé")
                    throw IllegalStateException(
                        "La clé d'hôte SSH de $host a changé depuis la dernière connexion (serveur réinstallé, ou interception). " +
                        "Si c'est normal : Paramètres → Réinitialiser les clés d'hôte SSH.", e)
                }
                throw e
            }
        }
    }

    private fun attempt(ctx: Context, host: String, port: Int, user: String, pass: String, proxy: Proxy, legacy: Boolean): Session {
        val jsch = JSch()
        val kh = knownHosts(ctx).apply { if (!exists()) createNewFile() }
        jsch.setKnownHosts(kh.path)
        val session = jsch.getSession(user, host, port).apply {
            setPassword(pass)
            setProxy(proxy)
            setUserInfo(object : UserInfo {
                override fun getPassphrase(): String? = null
                override fun getPassword(): String = pass
                override fun promptPassword(message: String?) = true
                override fun promptPassphrase(message: String?) = false
                override fun promptYesNo(message: String?): Boolean {
                    log(ctx, "Nouvelle clé d'hôte enregistrée pour $host (première connexion)")
                    return true
                }
                override fun showMessage(message: String?) {}
            })
            setConfig("StrictHostKeyChecking", "ask")
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
            if (legacy) {
                LEGACY.forEach { (key, extra) ->
                    val cur = JSch.getConfig(key) ?: ""
                    setConfig(key, if (cur.isEmpty()) extra else "$cur,$extra")
                }
            }
            setServerAliveInterval(15000)
            setServerAliveCountMax(3)
        }
        session.connect(30000)
        return session
    }
}

/** Proxy SOCKS5 local (127.0.0.1:SOCKS_PORT) qui ouvre un canal direct-tcpip SSH par connexion. */
internal class SshSocksServer(
    private val ssh: Session,
    private val dnsResolver: String,
    private val dnsPort: Int
) {
    private var server: ServerSocket? = null

    fun start() {
        val ss = ServerSocket(Engine.SOCKS_PORT, 64, InetAddress.getByName("127.0.0.1"))
        server = ss
        thread(isDaemon = true, name = "TomSshSocks") {
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (_: Throwable) { break }
                thread(isDaemon = true) { handle(c) }
            }
        }
        check(waitForPort("127.0.0.1", Engine.SOCKS_PORT, 3000)) { "SOCKS SSH indisponible" }
    }

    fun stop() { try { server?.close() } catch (_: Throwable) {}; server = null }

    private fun InputStream.full(n: Int) = ByteArray(n).also {
        var o = 0
        while (o < n) { val r = read(it, o, n - o); if (r < 0) error("EOF SOCKS"); o += r }
    }

    private fun handle(c: Socket) = try {
        val i = c.getInputStream(); val o = c.getOutputStream()
        val hello = i.full(2); i.full(hello[1].toInt() and 255)
        o.write(byteArrayOf(5, 0)); o.flush()
        val h = i.full(4)
        when (h[1].toInt() and 255) {
            1 -> {
                val host = readSocksHost(i, h[3].toInt() and 255)
                val p = i.full(2); val port = ((p[0].toInt() and 255) shl 8) or (p[1].toInt() and 255)
                val ch = try {
                    (ssh.openChannel("direct-tcpip") as ChannelDirectTCPIP).also {
                        it.setHost(host); it.setPort(port); it.connect(20000)
                    }
                } catch (e: Throwable) {
                    o.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush(); throw e
                }
                o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
                val t = thread(isDaemon = true) { pipe(ch.inputStream, o) }
                pipe(i, ch.outputStream); t.join(); ch.disconnect()
            }
            3 -> udpAssociate(c, o)
            else -> error("SOCKS commande non supportée")
        }
    } catch (_: Throwable) { } finally { try { c.close() } catch (_: Throwable) {} }

    /** Par SSH, seul le DNS (UDP/53) est relayé, en DNS-over-TCP. Les autres paquets UDP sont ignorés. */
    private fun udpAssociate(c: Socket, o: OutputStream) {
        val udp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val localPort = udp.localPort
        o.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (localPort ushr 8).toByte(), localPort.toByte())); o.flush()
        // L'association vit tant que la connexion de contrôle TCP reste ouverte.
        thread(isDaemon = true) { try { while (c.getInputStream().read() >= 0) {} } catch (_: Throwable) {}; udp.close() }
        try {
            while (!udp.isClosed && !c.isClosed) {
                val b = ByteArray(65535); val p = DatagramPacket(b, b.size); udp.receive(p)
                if (p.length < 10) continue
                val atyp = p.data[p.offset + 3].toInt() and 255
                val (_, port, headerLen) = readUdpDestination(p.data, p.offset, p.length, atyp)
                if (port != 53) continue
                val data = p.data.copyOfRange(p.offset + headerLen, p.offset + p.length)
                val peerAddr = p.address; val peerPort = p.port
                thread(isDaemon = true) {
                    try {
                        val answer = dnsOverSsh(data)
                        val out = ByteArray(10 + answer.size)
                        out[3] = 1; out[4] = 127; out[7] = 1
                        out[8] = (port ushr 8).toByte(); out[9] = port.toByte()
                        System.arraycopy(answer, 0, out, 10, answer.size)
                        udp.send(DatagramPacket(out, out.size, peerAddr, peerPort))
                    } catch (_: Throwable) {}
                }
            }
        } finally { udp.close() }
    }

    private fun readSocksHost(i: InputStream, atyp: Int): String = when (atyp) {
        1 -> InetAddress.getByAddress(i.full(4)).hostAddress!!
        3 -> { val n = i.read(); require(n >= 0); String(i.full(n)) }
        4 -> InetAddress.getByAddress(i.full(16)).hostAddress!!
        else -> error("SOCKS atyp invalide")
    }

    private fun readUdpDestination(b: ByteArray, off: Int, len: Int, atyp: Int): Triple<String, Int, Int> {
        var p = off + 4
        val host = when (atyp) {
            1 -> { val h = InetAddress.getByAddress(b.copyOfRange(p, p + 4)).hostAddress!!; p += 4; h }
            3 -> { val n = b[p].toInt() and 255; p++; val h = String(b.copyOfRange(p, p + n)); p += n; h }
            4 -> { val h = InetAddress.getByAddress(b.copyOfRange(p, p + 16)).hostAddress!!; p += 16; h }
            else -> error("SOCKS UDP atyp invalide")
        }
        val port = ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)
        return Triple(host, port, p + 2 - off)
    }

    private fun dnsOverSsh(query: ByteArray): ByteArray {
        val ch = ssh.openChannel("direct-tcpip") as ChannelDirectTCPIP
        ch.setHost(dnsResolver); ch.setPort(dnsPort); ch.connect(15000)
        return try {
            val out = ch.outputStream
            out.write((query.size ushr 8) and 255); out.write(query.size and 255); out.write(query); out.flush()
            val input = DataInputStream(ch.inputStream)
            val n = input.readUnsignedShort()
            ByteArray(n).also { input.readFully(it) }
        } finally { ch.disconnect() }
    }

    private fun pipe(a: InputStream, b: OutputStream) = try {
        val buf = ByteArray(16384)
        while (true) { val n = a.read(buf); if (n < 0) break; b.write(buf, 0, n); b.flush() }
    } catch (_: Throwable) {}
}
