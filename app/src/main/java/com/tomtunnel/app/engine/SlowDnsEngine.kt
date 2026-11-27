package com.tomtunnel.app.engine

import android.content.Context
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** SSH sur DNSTT/SlowDNS. DNSTT expose un forward TCP local brut (pas un proxy SOCKS). */
class SlowDnsEngine : Engine {
    private var dns: Process? = null
    private var ssh: Session? = null
    private var socks: SshSocksServer? = null

    override fun start(ctx: Context, s: JSONObject) {
        val bin = EngineAssets.materialize(ctx, "dnstt")
        val resolver = s.optString("resolver", "1.1.1.1").trim().ifEmpty { "1.1.1.1" }
        val dnsPort = s.optInt("dnsPort", 53).coerceIn(1, 65535)
        val dnsServer = if (resolver.contains(":")) resolver else "$resolver:$dnsPort"
        val ns = s.optString("ns").trim()
        val pub = s.optString("pubkey").trim()
        require(ns.isNotEmpty()) { "Nameserver DNSTT manquant" }
        require(pub.isNotEmpty()) { "Clé publique DNSTT manquante" }

        val log = ctx.getFileStreamPath("dnstt.log").apply { writeText("") }
        dns = ProcessBuilder(bin.path, "-udp", dnsServer, "-pubkey", pub, ns, "127.0.0.1:$LOCAL")
            .redirectErrorStream(true).start().also { it.drainTo(log) }
        check(waitForPort("127.0.0.1", LOCAL, 12000)) {
            "DNSTT n'a pas ouvert le tunnel TCP $LOCAL. ${tail(log, 2200)}"
        }

        fun newProxy(): Proxy = object : Proxy {
            private var socket: Socket? = null
            private var input: InputStream? = null
            private var output: OutputStream? = null
            override fun connect(sf: com.jcraft.jsch.SocketFactory?, host: String, port: Int, timeout: Int) {
                socket = if (sf != null) sf.createSocket("127.0.0.1", LOCAL) else Socket("127.0.0.1", LOCAL)
                socket!!.soTimeout = timeout.coerceAtLeast(1000)
                input = socket!!.getInputStream(); output = socket!!.getOutputStream()
            }
            override fun getInputStream(): InputStream = input ?: error("DNSTT proxy input indisponible")
            override fun getOutputStream(): OutputStream = output ?: error("DNSTT proxy output indisponible")
            override fun getSocket(): Socket = socket ?: error("DNSTT proxy socket indisponible")
            override fun close() { try { socket?.close() } catch (_: Throwable) {} }
        }

        ssh = SshCommon.connect(ctx, s.optString("host"), s.optInt("sshPort", 22), s.optString("user"), s.optString("pass")) { newProxy() }

        socks = SshSocksServer(ssh!!, resolver.substringBefore(":"), dnsPort).also { it.start() }
    }

    override fun stop() {
        try { socks?.stop() } catch (_: Throwable) {}
        try { ssh?.disconnect() } catch (_: Throwable) {}
        try { dns?.destroy() } catch (_: Throwable) {}
        socks = null; ssh = null; dns = null
    }

    override fun alive(): Boolean = ssh?.isConnected == true && dns?.isAlive == true

    companion object { const val LOCAL = 2222 }
}
