package com.tomtunnel.app.engine

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Xray core : VLESS, VMess, Trojan, Shadowsocks, SOCKS et WireGuard. */
class XrayEngine : Engine {
    private var proc: Process? = null
    private var cfgFile: File? = null

    override fun start(ctx: Context, s: JSONObject) {
        val bin = EngineAssets.materialize(ctx, "xray", abi(), "xray")
        bin.setExecutable(true, true)
        val cfg = File(ctx.filesDir, "xray.json").apply { writeText(buildConfig(s).toString()) }
        cfgFile = cfg

        val test = ProcessBuilder(bin.path, "run", "-test", "-c", cfg.path)
            .redirectErrorStream(true).directory(ctx.filesDir).start()
        val testOut = test.inputStream.bufferedReader().readText()
        check(test.waitFor() == 0) { "Configuration Xray invalide : ${testOut.takeLast(1200)}" }

        proc = ProcessBuilder(bin.path, "run", "-c", cfg.path)
            .redirectErrorStream(true).directory(ctx.filesDir).apply {
                environment()["XRAY_LOCATION_ASSET"] = ctx.filesDir.path
            }.start().also { p ->
                Thread {
                    try {
                        File(ctx.filesDir, "xray-runtime.log").outputStream().bufferedWriter().use { out ->
                            p.inputStream.bufferedReader().forEachLine { out.appendLine(it); out.flush() }
                        }
                    } catch (_: Throwable) {}
                }.apply { isDaemon = true; start() }
            }

        val running = proc!!
        check(waitForPort("127.0.0.1", Engine.SOCKS_PORT, 8000) { running.alive() }) {
            val log = File(ctx.filesDir, "xray-runtime.log")
            val err = File(ctx.filesDir, "xray-error.log")
            "Xray n'a pas ouvert le SOCKS 127.0.0.1:${Engine.SOCKS_PORT}. ${tail(log, 900)} ${tail(err, 700)}".trim()
        }
    }

    private fun abi(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "x86_64" -> "x86_64"
        else -> "arm64-v8a"
    }

    override fun isAlive(): Boolean = proc?.alive() == true

    override fun stop() {
        try { proc?.destroy() } catch (_: Throwable) {}
        proc = null
        try { cfgFile?.delete() } catch (_: Throwable) {}
        cfgFile = null
    }

    companion object {
        fun buildConfig(s: JSONObject): JSONObject {
            val type = s.optString("type").trim().uppercase()
            val addr = s.optString("addr").trim()
            val port = s.optString("port").trim().toIntOrNull() ?: error("Port Xray invalide")
            val connectAddr = s.optString("connectAddr").trim().ifEmpty { addr }
            val connectPort = s.optInt("connectPort", port).takeIf { it in 1..65535 } ?: port
            require(addr.isNotEmpty()) { "Adresse du serveur Xray vide" }
            require(port in 1..65535) { "Port Xray hors limites" }

            val protocol = when (type) {
                "VLESS" -> "vless"
                "VMESS" -> "vmess"
                "TROJAN" -> "trojan"
                "SHADOWSOCKS" -> "shadowsocks"
                "SOCKS", "SOCKS5" -> "socks"
                "WIREGUARD", "WG" -> "wireguard"
                else -> error("Protocole Xray non pris en charge : $type")
            }

            val settings = when (protocol) {
                "vless" -> {
                    val uuid = s.optString("uuid").trim()
                    require(uuid.isNotEmpty()) { "UUID VLESS manquant" }
                    JSONObject().put("vnext", JSONArray().put(JSONObject()
                        .put("address", connectAddr).put("port", connectPort)
                        .put("users", JSONArray().put(JSONObject()
                            .put("id", uuid).put("encryption", "none")
                            .apply { s.optString("flow").trim().takeIf { it.isNotEmpty() }?.let { put("flow", it) } }))))
                }
                "vmess" -> {
                    val uuid = s.optString("uuid").trim()
                    require(uuid.isNotEmpty()) { "UUID VMess manquant" }
                    JSONObject().put("vnext", JSONArray().put(JSONObject()
                        .put("address", connectAddr).put("port", connectPort)
                        .put("users", JSONArray().put(JSONObject()
                            .put("id", uuid).put("alterId", s.optInt("aid", 0).coerceAtLeast(0))
                            .put("security", s.optString("cipher", "auto").ifEmpty { "auto" })))) )
                }
                "trojan" -> {
                    val pass = s.optString("pass")
                    require(pass.isNotEmpty()) { "Mot de passe Trojan manquant" }
                    JSONObject().put("servers", JSONArray().put(JSONObject()
                        .put("address", connectAddr).put("port", connectPort).put("password", pass)))
                }
                "shadowsocks" -> {
                    val pass = s.optString("pass")
                    require(pass.isNotEmpty()) { "Mot de passe Shadowsocks manquant" }
                    JSONObject().put("servers", JSONArray().put(JSONObject()
                        .put("address", connectAddr).put("port", connectPort)
                        .put("method", s.optString("method", "aes-256-gcm"))
                        .put("password", pass)))
                }
                "socks" -> {
                    val server = JSONObject().put("address", connectAddr).put("port", connectPort)
                    val user = s.optString("user").trim()
                    val pass = s.optString("pass")
                    if (user.isNotEmpty()) {
                        require(pass.isNotEmpty()) { "Mot de passe SOCKS manquant pour cet utilisateur" }
                        server.put("users", JSONArray().put(JSONObject().put("user", user).put("pass", pass)))
                    }
                    JSONObject().put("servers", JSONArray().put(server))
                }
                "wireguard" -> {
                    val secret = s.optString("secretKey").trim()
                    val public = s.optString("publicKey").trim()
                    val localAddress = s.optString("wgAddress").trim()
                    require(secret.isNotEmpty()) { "Clé privée WireGuard manquante" }
                    require(public.isNotEmpty()) { "Clé publique du pair WireGuard manquante" }
                    require(localAddress.isNotEmpty()) { "Adresse locale WireGuard manquante" }
                    val allowed = splitCsv(s.optString("allowedIPs").ifEmpty { "0.0.0.0/0,::/0" })
                    val peer = JSONObject().put("endpoint", endpoint(addr, port))
                        .put("publicKey", public).put("allowedIPs", JSONArray(allowed))
                        .put("keepAlive", s.optInt("keepAlive", 0).coerceIn(0, 65535))
                    s.optString("preSharedKey").trim().takeIf { it.isNotEmpty() }?.let { peer.put("preSharedKey", it) }
                    val addresses = JSONArray(splitCsv(localAddress).map { it.substringBefore('/') })
                    val wg = JSONObject().put("secretKey", secret).put("address", addresses)
                        .put("peers", JSONArray().put(peer))
                        .put("mtu", s.optInt("mtu", 1420).coerceIn(576, 9000))
                    val reserved = splitCsv(s.optString("reserved")).mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
                    if (reserved.isNotEmpty()) {
                        require(reserved.size == 3) { "WireGuard reserved doit contenir exactement trois octets (0-255)" }
                        wg.put("reserved", JSONArray(reserved))
                    }
                    s.optString("remoteDNS").trim().takeIf { it.isNotEmpty() }?.let { wg.put("remoteDNS", JSONArray(splitCsv(it))) }
                    wg.put("noKernelTun", true)
                }
                else -> error("Moteur Xray non disponible")
            }

            val outbound = JSONObject().put("tag", "proxy").put("protocol", protocol).put("settings", settings)
            if (protocol !in setOf("wireguard", "shadowsocks")) outbound.put("streamSettings", stream(s, protocol, protocol == "trojan"))

            return JSONObject()
                .put("log", JSONObject().put("loglevel", "warning").put("access", "xray-access.log").put("error", "xray-error.log"))
                .put("inbounds", JSONArray().put(JSONObject()
                    .put("tag", "socks").put("listen", "127.0.0.1").put("port", Engine.SOCKS_PORT)
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
                    .put("sniffing", JSONObject().put("enabled", true)
                        .put("destOverride", JSONArray().put("http").put("tls").put("quic")))))
                .put("outbounds", JSONArray().put(outbound)
                    .put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
                .put("routing", JSONObject().put("domainStrategy", "AsIs")
                    .put("rules", JSONArray().put(JSONObject().put("type", "field")
                        .put("inboundTag", JSONArray().put("socks")).put("outboundTag", "proxy"))))
        }

        private fun splitCsv(value: String): List<String> = value.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
        private fun endpoint(host: String, port: Int): String = if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"

        private fun stream(s: JSONObject, protocol: String, forceTls: Boolean): JSONObject {
            val requestedNet = s.optString("network", "raw").trim().lowercase().ifEmpty { "raw" }
            val method = when (requestedNet) {
                "tcp", "raw" -> "raw"
                "ws", "websocket" -> "websocket"
                "grpc" -> "grpc"
                "httpupgrade", "http-upgrade" -> "httpupgrade"
                "xhttp", "splithttp", "split-http" -> "xhttp"
                "kcp", "mkcp" -> "mkcp"
                "h2", "http", "http2", "http/2" -> error("Le transport HTTP/2 historique n'est plus pris en charge par Xray récent. Utilisez XHTTP, HTTPUpgrade ou RAW selon votre serveur.")
                "quic" -> error("Le transport QUIC historique a été retiré de Xray. Utilisez le moteur UDP dédié ou un transport Xray compatible.")
                else -> error("Transport Xray non pris en charge : $requestedNet")
            }
            val requestedSec = s.optString("security").trim().lowercase()
            val security = when {
                forceTls -> "tls"
                requestedSec == "reality" -> "reality"
                requestedSec == "tls" || s.optBoolean("tls", false) -> "tls"
                requestedSec.isEmpty() || requestedSec == "none" -> "none"
                else -> error("Sécurité Xray non prise en charge : $requestedSec")
            }
            if (security == "reality") require(method in setOf("raw", "xhttp", "grpc")) {
                "REALITY est compatible avec RAW, XHTTP et gRPC, pas avec $method"
            }
            val flow = s.optString("flow").trim()
            if (flow.isNotEmpty()) require(protocol == "vless" && method == "raw" && security != "none") {
                "Le flow VLESS Vision exige VLESS sur RAW avec TLS ou REALITY."
            }
            val st = JSONObject().put("method", method).put("security", security)
            if (security == "tls") {
                st.put("tlsSettings", JSONObject()
                    .put("serverName", s.optString("sni").ifEmpty { s.optString("addr") })
                    .put("fingerprint", s.optString("fp").ifEmpty { "chrome" })
                    .apply { s.optString("alpn").takeIf { it.isNotBlank() }?.let { put("alpn", JSONArray(splitCsv(it))) } })
            } else if (security == "reality") {
                val pbk = s.optString("pbk").trim()
                require(pbk.isNotEmpty()) { "Clé publique Reality (pbk) manquante" }
                st.put("realitySettings", JSONObject()
                    .put("serverName", s.optString("sni").ifEmpty { s.optString("addr") })
                    .put("fingerprint", s.optString("fp").ifEmpty { "chrome" })
                    .put("publicKey", pbk).put("shortId", s.optString("sid"))
                    .put("spiderX", s.optString("spx", "/").ifEmpty { "/" }))
            }
            val path = s.optString("path", "/").ifEmpty { "/" }
            val host = s.optString("host").ifEmpty { s.optString("sni") }
            when (method) {
                "websocket" -> st.put("wsSettings", JSONObject().put("path", path)
                    .put("headers", JSONObject().apply { if (host.isNotEmpty()) put("Host", host) }))
                "grpc" -> st.put("grpcSettings", JSONObject().put("serviceName", s.optString("path"))
                    .put("multiMode", s.optBoolean("multiMode", false))
                    .apply { if (host.isNotEmpty()) put("authority", host) })
                "httpupgrade" -> st.put("httpupgradeSettings", JSONObject().put("path", path).put("host", host))
                "xhttp" -> st.put("xhttpSettings", JSONObject().put("path", path).put("host", host)
                    .put("mode", s.optString("mode", "auto").ifEmpty { "auto" }))
                "mkcp" -> st.put("kcpSettings", JSONObject()
                    .put("mtu", s.optInt("kcpMtu", 1350).coerceIn(576, 1460))
                    .put("tti", s.optInt("kcpTti", 50).coerceIn(10, 100))
                    .put("uplinkCapacity", s.optInt("uplinkCapacity", 12).coerceIn(1, 1000))
                    .put("downlinkCapacity", s.optInt("downlinkCapacity", 100).coerceIn(1, 1000))
                    .put("congestion", s.optBoolean("kcpCongestion", false))
                    .put("readBufferSize", s.optInt("readBufferSize", 2).coerceIn(1, 64))
                    .put("writeBufferSize", s.optInt("writeBufferSize", 2).coerceIn(1, 64))
                    .put("header", JSONObject().put("type", s.optString("headerType", "none"))))
            }
            return st
        }
    }
}
