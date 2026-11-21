package com.tomtunnel.app.engine

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Xray (VLESS/VMess/Trojan/SS/SOCKS) transporté dans un forward TCP DNSTT. */
class XraySlowDnsEngine : Engine {
    private var dns: Process? = null
    private var xray: XrayEngine? = null

    override fun start(ctx: Context, s: JSONObject) {
        val abi = if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") "x86_64" else "arm64-v8a"
        val bin = EngineAssets.materialize(ctx, "dnstt", abi, "dnstt-client")
        val resolverRaw = s.optString("dnsResolver", "8.8.8.8").trim().ifEmpty { "8.8.8.8" }
        val resolverPort = s.optString("dnsPort", "53").trim().toIntOrNull()?.coerceIn(1, 65535) ?: 53
        val dnsServer = when {
            resolverRaw.startsWith("[") -> resolverRaw
            resolverRaw.count { it == ':' } == 1 -> resolverRaw
            resolverRaw.contains(":") -> "[$resolverRaw]:$resolverPort"
            else -> "$resolverRaw:$resolverPort"
        }
        val ns = s.optString("dnsNs").trim().trimEnd('.')
        val pub = s.optString("dnsPubkey").filter { !it.isWhitespace() }
        require(ns.isNotEmpty()) { "Nameserver DNSTT (NS) manquant" }
        require(pub.matches(Regex("^[0-9a-fA-F]{64}$"))) { "Clé publique DNSTT invalide : 64 caractères hexadécimaux requis" }
        val xrayProtocol = s.optString("xrayProtocol", "VLESS").uppercase()
        require(xrayProtocol in setOf("VLESS", "VMESS", "TROJAN", "SHADOWSOCKS", "SOCKS")) {
            "WireGuard ne peut pas être encapsulé dans le forward TCP DNSTT."
        }

        try {
            ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), LOCAL_PORT)) }
        } catch (e: Throwable) {
            throw IllegalStateException("Le port local DNSTT $LOCAL_PORT est déjà utilisé.", e)
        }
        val log = File(ctx.filesDir, "xray-dnstt.log").apply { writeText("") }
        val p = ProcessBuilder(bin.path, "-udp", dnsServer, "-pubkey", pub, ns, "127.0.0.1:$LOCAL_PORT")
            .directory(ctx.filesDir).redirectErrorStream(true).start()
        dns = p
        p.drainTo(log)
        check(waitForPort("127.0.0.1", LOCAL_PORT, 15000) { p.alive() }) {
            if (!p.alive()) "DNSTT/Xray s'est arrêté : ${tail(log, 900)}"
            else "DNSTT n'a pas ouvert le port local $LOCAL_PORT. ${tail(log, 900)}"
        }

        // L'adresse de connexion devient locale, mais Xray conserve l'adresse/SNI d'origine pour TLS et REALITY.
        val xrayProfile = JSONObject(s.toString())
            .put("connectAddr", "127.0.0.1")
            .put("connectPort", LOCAL_PORT)
            .put("type", xrayProtocol)
        xray = XrayEngine()
        try {
            xray!!.start(ctx, xrayProfile)
        } catch (e: Throwable) {
            try { p.destroy() } catch (_: Throwable) {}
            dns = null
            throw IllegalStateException("Démarrage Xray sur DNSTT impossible : ${e.message}", e)
        }
    }

    override fun isAlive(): Boolean = (dns?.alive() == true) && (xray?.isAlive() == true)

    override fun stop() {
        try { xray?.stop() } catch (_: Throwable) {}
        xray = null
        try { dns?.destroy() } catch (_: Throwable) {}
        dns = null
    }

    companion object { private const val LOCAL_PORT = 2223 }
}
