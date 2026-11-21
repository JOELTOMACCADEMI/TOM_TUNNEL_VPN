package com.tomtunnel.app.engine

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * SSH direct sur TCP :
 *   TCP (ou TLS en option) vers host:port -> session SSH (JSch) -> SOCKS5 local 127.0.0.1:SOCKS_PORT
 *
 * Sans payload : le serveur doit parler SSH dès la connexion (OpenSSH, ou SSH dans TLS si l'option TLS est activée).
 * Avec payload (style HTTP Injector) : le payload est envoyé tel quel après la connexion TCP/TLS, puis les réponses
 * HTTP éventuelles (200, 101...) sont consommées jusqu'à la bannière « SSH- », et le flux SSH continue sur la même connexion.
 * Marqueurs : [crlf] [cr] [lf] [host] [port] [host_port] [protocol] (ainsi que \r et \n).
 */
class SshTcpEngine : SshSocksEngine() {
    private lateinit var host: String
    private var port = 22
    private var tls = false
    private var sni = ""
    private var payload = ""
    private var udpgwPort = 7300
    private var proxyHost = ""
    private var proxyPort = 8080

    override fun start(ctx: Context, s: JSONObject) {
        host = s.optString("host").trim()
        port = s.optString("port", "22").trim().toIntOrNull() ?: 22
        tls = s.optBoolean("tls", false)
        sni = s.optString("sni").trim()
        payload = s.optString("payload")
        proxyHost = s.optString("proxyHost").trim()
        proxyPort = s.optString("proxyPort", "8080").trim().toIntOrNull() ?: 8080
        if (proxyHost.isNotEmpty()) require(proxyPort in 1..65535) { "Port du proxy invalide : $proxyPort" }
        udpgwPort = s.optString("udpgwPort", "7300").trim().toIntOrNull() ?: 7300
        transparentDns = s.optBoolean("transparentDns", true)
        val user = s.optString("user").trim()
        require(host.isNotEmpty()) { "Hôte du serveur manquant" }
        require(port in 1..65535) { "Port SSH invalide : $port" }
        require(udpgwPort in 1..65535) { "Port UDPGW invalide : $udpgwPort" }
        require(user.isNotEmpty()) { "Utilisateur SSH manquant" }

        // Test rapide du transport pour donner une erreur précise avant de lancer SSH.
        try { openTransport().close() } catch (e: Throwable) {
            throw IllegalStateException(explain(e), e)
        }
        // "host" JSch n'est qu'une étiquette : la vraie connexion passe par openTransport().
        connectSsh(user, host, port, s.optString("pass"), 2) { explain(it) }
        startSocks()
    }

    override fun openTransport(): Socket {
        val raw = Socket()
        if (proxyHost.isEmpty()) {
            raw.connect(InetSocketAddress(host, port), 15000)
            raw.soTimeout = 20000
        } else {
            // SSH via proxy HTTP : CONNECT host:port, puis TLS / payload / SSH sur le tunnel obtenu.
            raw.connect(InetSocketAddress(proxyHost, proxyPort), 15000)
            raw.soTimeout = 20000
            try { httpConnect(raw) } catch (e: Throwable) { try { raw.close() } catch (_: Throwable) {}; throw e }
        }
        val sock: Socket = if (!tls) raw else wrapTls(raw)
        return if (payload.isBlank()) sock else injectPayload(sock)
    }

    private fun httpConnect(sock: Socket) {
        val target = "$host:$port"
        sock.getOutputStream().apply {
            write("CONNECT $target HTTP/1.1\r\nHost: $target\r\nProxy-Connection: keep-alive\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); flush()
        }
        val inp = sock.getInputStream()
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16384) {
            val b = inp.read()
            if (b < 0) break
            buf.write(b); last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val status = buf.toString("ISO-8859-1").lineSequence().firstOrNull().orEmpty()
        val code = Regex("^HTTP/\\S+\\s+(\\d{3})").find(status)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        check(code in 200..299) { "Le proxy $proxyHost:$proxyPort a refusé CONNECT : « ${status.take(80)} »" }
    }

    private fun wrapTls(raw: Socket): Socket {
        val name = sni.ifEmpty { host }
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, name, port, true) as SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply {
            if (!Regex("^[0-9.:]+$").matches(name)) serverNames = listOf(SNIHostName(name))
        }
        ssl.startHandshake()
        return ssl
    }

    /** Port UDPGW conservé dans le profil pour compatibilité avec les configurations NPV.
     * Le transport SSH actuel relaie le DNS via direct-tcpip; le protocole BadVPN/UDPGW
     * binaire n'est pas simulé ici. Cela évite de présenter un faux support UDPGW. */
    private fun buildPayload(): String = payload
        .replace("[crlf]", "\r\n").replace("[lf]", "\n").replace("[cr]", "\r")
        .replace("[host_port]", "$host:$port").replace("[host]", host)
        .replace("[port]", port.toString()).replace("[protocol]", "HTTP/1.1")
        .replace("\\r", "\r").replace("\\n", "\n")

    /** Envoie le payload, avale les réponses HTTP, et rend la socket positionnée sur le flux SSH. */
    private fun injectPayload(sock: Socket): Socket {
        sock.getOutputStream().apply { write(buildPayload().toByteArray(Charsets.ISO_8859_1)); flush() }
        val inp = sock.getInputStream()
        val oldTimeout = sock.soTimeout
        var prefix = ByteArray(0)
        try {
            sock.soTimeout = 8000
            repeat(4) {
                val first = ByteArray(4)
                var n = 0
                while (n < 4) { val r = inp.read(first, n, 4 - n); if (r < 0) break; n += r }
                if (n < 4) { prefix = first.copyOf(n); return@repeat }
                if (String(first, Charsets.ISO_8859_1) == "SSH-") { prefix = first; return finish(sock, prefix, oldTimeout) }
                if (String(first, Charsets.ISO_8859_1) != "HTTP") {
                    throw IllegalStateException("Réponse inattendue du serveur après le payload.")
                }
                val head = readRestOfHead(inp, first)
                val status = head.lineSequence().firstOrNull().orEmpty()
                val code = Regex("^HTTP/\\S+\\s+(\\d{3})").find(status)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                check(code in 100..399) { "Le serveur a refusé le payload : « ${status.take(80)} »" }
            }
        } catch (_: SocketTimeoutException) {
            // le serveur attend peut-être le client : on poursuit, JSch lira la bannière
        }
        return finish(sock, prefix, oldTimeout)
    }

    private fun finish(sock: Socket, prefix: ByteArray, timeout: Int): Socket {
        sock.soTimeout = timeout
        return if (prefix.isEmpty()) sock else PrefixedSocket(sock, prefix)
    }

    /** Lit l'en-tête HTTP octet par octet jusqu'à la ligne vide (sans consommer le flux SSH qui suit). */
    private fun readRestOfHead(inp: InputStream, start: ByteArray): String {
        val buf = ByteArrayOutputStream().apply { write(start) }
        var last4 = start.fold(0) { a, b -> (a shl 8) or (b.toInt() and 255) }
        while (buf.size() < 16384) {
            val b = inp.read()
            if (b < 0) break
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        return buf.toString("ISO-8859-1")
    }

    /** Socket qui rejoue d'abord quelques octets déjà lus (début de la bannière SSH). */
    private class PrefixedSocket(private val s: Socket, private val prefix: ByteArray) : Socket() {
        override fun getInputStream(): InputStream = SequenceInputStream(ByteArrayInputStream(prefix), s.getInputStream())
        override fun getOutputStream() = s.getOutputStream()
        override fun close() = s.close()
        override fun isConnected() = s.isConnected
        override fun isClosed() = s.isClosed
        override fun setSoTimeout(timeout: Int) { s.soTimeout = timeout }
        override fun getSoTimeout() = s.soTimeout
        override fun setTcpNoDelay(on: Boolean) { s.tcpNoDelay = on }
        override fun getTcpNoDelay() = s.tcpNoDelay
        override fun shutdownOutput() = s.shutdownOutput()
    }

    private fun explain(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("Auth fail", true) || m.contains("auth cancel", true) ->
                "Identifiant ou mot de passe SSH incorrect (le serveur est joignable)."
            e is javax.net.ssl.SSLException -> "Échec TLS : vérifiez le SNI et le port, ou désactivez TLS. ($m)"
            e is java.net.UnknownHostException -> "Hôte introuvable : $host"
            e is java.net.ConnectException || m.contains("refused", true) ->
                "Connexion refusée par $host:$port. Vérifiez l'hôte, le port et que SSH est actif."
            m.contains("timed out", true) || m.contains("timeout", true) -> "Délai dépassé vers $host:$port."
            m.contains("Connection reset", true) || m.contains("EOF", true) || m.contains("Connection closed", true) ->
                "Le serveur a fermé la connexion pendant l'échange SSH. Vérifiez le port (22 ?), le payload et l'option TLS."
            m.contains("payload", true) -> m
            else -> m.ifEmpty { e.javaClass.simpleName }
        }
    }
}
