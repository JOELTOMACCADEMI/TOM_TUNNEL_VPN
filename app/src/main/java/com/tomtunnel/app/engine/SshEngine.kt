package com.tomtunnel.app.engine

import android.content.Context
import android.util.Base64
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * SSH « SSH WebSocket » et « SSH TCP » (Direct, Payload, HTTP Proxy, SSL/SNI…).
 * Le transport est préparé ici (socket, TLS, payload, CONNECT, Upgrade WebSocket), puis JSch
 * parle SSH par-dessus et le service y branche un SOCKS5 local (SshSocksServer).
 *
 * WebSocket : après la réponse « 101 », le flux est traité comme un tunnel brut (comportement des
 * proxys SSH-over-WebSocket usuels). Les trames WebSocket RFC 6455 ne sont pas encodées.
 *
 * TLS : le certificat est vérifié (chaîne système + nom = SNI) sauf si le profil le désactive
 * (`verifyCert:false`) ou, à défaut de champ, si un SNI personnalisé différent de l'hôte est utilisé
 * (cas « SNI/CDN » où le certificat ne peut pas correspondre).
 */
class SshEngine : Engine {
    private var ssh: Session? = null
    private var socks: SshSocksServer? = null
    @Volatile private var sock: Socket? = null

    private val proxyModes = setOf("HTTP_PROXY", "HTTP_PROXY_PAYLOAD", "SSL_PROXY_SNI_PAYLOAD")
    private val sslModes = setOf("SSL_SNI", "SSL_SNI_PAYLOAD", "SSL_PROXY_SNI_PAYLOAD")

    private class Cfg(
        val isWs: Boolean, val mode: String, val host: String, val port: Int, val sshPort: Int,
        val user: String, val pass: String, val viaProxy: Boolean, val tls: Boolean, val verify: Boolean,
        val payload: String, val wsHost: String, val path: String, val sni: String,
        val dialHost: String, val dialPort: Int
    )

    private fun parse(s: JSONObject): Cfg {
        val isWs = s.optString("type") == "SSH WebSocket"
        val mode = if (isWs) "WEBSOCKET" else s.optString("mode", "DIRECT").ifEmpty { "DIRECT" }
        require(mode != "SLOW_DNS") { "Pour SlowDNS, utilise un profil « SSH SlowDNS » (NS + clé publique)." }
        val host = s.optString("host").trim()
        require(host.isNotEmpty()) { "Hôte SSH manquant" }
        val port = s.optString("port").trim().toIntOrNull() ?: if (isWs) 443 else 22
        val viaProxy = !isWs && mode in proxyModes
        val tls = if (isWs) s.optBoolean("tls", false) else mode in sslModes
        val payload = s.optString("payload")
        val wsHost = s.optString("wsHost").trim()
        val sni = s.optString("sni").trim().ifEmpty { wsHost.ifEmpty { host } }
        val dialHost = if (viaProxy) s.optString("proxyHost").trim() else host
        val dialPort = if (viaProxy) s.optString("proxyPort").trim().toIntOrNull() ?: 8080 else port
        require(dialHost.isNotEmpty()) { "Hôte du proxy HTTP manquant" }
        if (mode.endsWith("PAYLOAD")) require(payload.isNotBlank()) { "Payload manquant pour le mode $mode" }
        val verify = if (s.has("verifyCert")) s.optBoolean("verifyCert", true) else sni.equals(dialHost, ignoreCase = true)
        return Cfg(isWs, mode, host, port, s.optInt("sshPort", 22), s.optString("user").trim(), s.optString("pass"),
            viaProxy, tls, verify, payload, wsHost, s.optString("path", "/").ifEmpty { "/" }, sni, dialHost, dialPort)
    }

    override fun start(ctx: Context, s: JSONObject) {
        val c = parse(s)
        ssh = SshCommon.connect(ctx, c.host, if (c.isWs) c.sshPort else c.port, c.user, c.pass) { openTransport(ctx, c) }
        val dns = s.optString("dns", "1.1.1.1").ifEmpty { "1.1.1.1" }
        socks = SshSocksServer(ssh!!, dns, 53).also { it.start() }
    }

    /** Ouvre un transport neuf (TCP [+ TLS] + préambule HTTP éventuel) et le présente à JSch comme Proxy. */
    private fun openTransport(ctx: Context, c: Cfg): Proxy {
        try { sock?.close() } catch (_: Throwable) {}
        var socket = Socket()
        sock = socket
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(c.dialHost, c.dialPort), 15000)
        socket.soTimeout = 15000
        if (c.tls) {
            if (!c.verify) SshCommon.log(ctx, "TLS vers ${c.dialHost} : certificat NON vérifié (SNI personnalisé ou verifyCert=false)")
            socket = tlsWrap(socket, c.sni, c.dialHost, c.dialPort, c.verify).also { sock = it }
        }

        val input = PushbackInputStream(socket.getInputStream(), 16)
        val output = socket.getOutputStream()
        val vars = Vars(c.host, c.port)

        when {
            c.isWs -> {
                val req = if (c.payload.isNotBlank()) expand(c.payload, vars) else
                    "GET ${c.path} HTTP/1.1\r\nHost: ${c.wsHost.ifEmpty { c.host }}\r\nUpgrade: websocket\r\n" +
                    "Connection: Upgrade\r\nSec-WebSocket-Key: ${wsKey()}\r\nSec-WebSocket-Version: 13\r\n\r\n"
                output.write(req.toByteArray(Charsets.ISO_8859_1)); output.flush()
                val status = readHeaders(input).lineSequence().firstOrNull().orEmpty()
                check(status.contains(" 101") || status.contains(" 200")) {
                    "Le serveur WebSocket a refusé l'Upgrade : ${status.ifEmpty { "réponse vide" }}"
                }
            }
            c.viaProxy -> {
                val req = if (c.payload.isNotBlank()) expand(c.payload, vars) else
                    "CONNECT ${c.host}:${c.port} HTTP/1.1\r\nHost: ${c.host}:${c.port}\r\n\r\n"
                output.write(req.toByteArray(Charsets.ISO_8859_1)); output.flush()
                val status = readHeaders(input).lineSequence().firstOrNull().orEmpty()
                check(Regex("^HTTP/\\S+\\s+2\\d\\d").containsMatchIn(status)) {
                    "Le proxy HTTP a refusé CONNECT : ${status.ifEmpty { "réponse vide" }}"
                }
            }
            c.payload.isNotBlank() -> {
                output.write(expand(c.payload, vars).toByteArray(Charsets.ISO_8859_1)); output.flush()
                skipHttpPreamble(input)
            }
        }
        socket.soTimeout = 0
        val ready = socket
        return object : Proxy {
            override fun connect(sf: com.jcraft.jsch.SocketFactory?, h: String, p: Int, timeout: Int) {}
            override fun getInputStream(): InputStream = input
            override fun getOutputStream(): OutputStream = output
            override fun getSocket(): Socket = ready
            override fun close() { try { ready.close() } catch (_: Throwable) {} }
        }
    }

    override fun stop() {
        try { socks?.stop() } catch (_: Throwable) {}
        try { ssh?.disconnect() } catch (_: Throwable) {}
        try { sock?.close() } catch (_: Throwable) {}
        socks = null; ssh = null; sock = null
    }

    override fun alive(): Boolean = ssh?.isConnected == true

    // ---- utilitaires de transport ----

    private class Vars(val host: String, val port: Int)

    private fun expand(p: String, v: Vars): String = p
        .replace("[host_port]", "${v.host}:${v.port}").replace("[host]", v.host).replace("[port]", v.port.toString())
        .replace("[protocol]", "HTTP/1.1").replace("[crlf]", "\r\n").replace("[cr]", "\r").replace("[lf]", "\n")
        .replace("\\r", "\r").replace("\\n", "\n")

    private fun wsKey(): String = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)

    private fun readHeaders(inp: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 16384) {
            val b = inp.read()
            if (b < 0) error("Connexion fermée pendant la réponse HTTP")
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n") || sb.endsWith("\n\n")) return sb.toString()
        }
        error("En-têtes HTTP trop longs")
    }

    /** Après un payload « direct » : saute une éventuelle réponse HTTP, sinon laisse passer la bannière SSH. */
    private fun skipHttpPreamble(inp: PushbackInputStream) {
        try {
            val first = ByteArray(4)
            var n = 0
            while (n < 4) { val r = inp.read(first, n, 4 - n); if (r < 0) break; n += r }
            if (n == 4 && String(first, Charsets.ISO_8859_1) == "HTTP") {
                readHeaders(inp)
            } else if (n > 0) {
                inp.unread(first, 0, n)
            }
        } catch (_: java.net.SocketTimeoutException) {
            // Aucune réponse HTTP : on continue, le serveur SSH enverra sa bannière.
        }
    }

    /**
     * TLS 1.2/1.3 avec SNI. [verify] = true : chaîne de confiance du système + nom du certificat = [sni].
     * [verify] = false : aucune vérification (SNI/CDN où le certificat ne correspond pas).
     */
    private fun tlsWrap(raw: Socket, sni: String, host: String, port: Int, verify: Boolean): Socket {
        val ctx = SSLContext.getInstance("TLS")
        if (verify) {
            ctx.init(null, null, null)
        } else {
            ctx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), SecureRandom())
        }
        val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.enabledProtocols = ssl.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        val params = ssl.sslParameters
        params.serverNames = listOf(SNIHostName(sni))
        ssl.sslParameters = params
        ssl.startHandshake()
        if (verify && !HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, ssl.session)) {
            try { ssl.close() } catch (_: Throwable) {}
            throw SSLPeerUnverifiedException("Le certificat du serveur ne correspond pas au nom « $sni ». Si c'est voulu (SNI personnalisé), ajoute verifyCert:false au profil.")
        }
        return ssl
    }
}
