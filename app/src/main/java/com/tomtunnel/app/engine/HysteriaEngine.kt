package com.tomtunnel.app.engine

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Profil « UDP » : client Hysteria v1 (serveur compatible ZIVPN : mot de passe, obfs « zivpn »,
 * port unique ou plage de ports). Hysteria ouvre un SOCKS5 local (TCP + UDP) sur [socksPort].
 */
class HysteriaEngine(private val socksPort: Int = Engine.SOCKS_PORT, private val tag: String = "hysteria") : Engine {
    private var proc: Process? = null

    override fun start(ctx: Context, s: JSONObject) {
        val bin = EngineAssets.materialize(ctx, "hysteria")
        val host = s.optString("host").trim()
        val port = s.optString("port").trim().ifEmpty { "5667" }
        val pass = s.optString("pass")
        require(host.isNotEmpty()) { "Serveur UDP manquant" }
        require(Regex("^\\d{1,5}(-\\d{1,5})?$").matches(port)) { "Port UDP invalide : $port" }
        require(pass.isNotEmpty()) { "Mot de passe UDP manquant" }

        val rw = s.optString("recvWindow", "2").toIntOrNull()?.coerceIn(1, 8) ?: 2
        val sni = s.optString("sni").trim()
        val serverAddr = if (host.contains(":") && !host.startsWith("[")) "[$host]:$port" else "$host:$port"

        val cfg = JSONObject()
            .put("server", serverAddr)
            .put("obfs", "zivpn")
            .put("auth_str", pass)
            .put("up_mbps", UP_MBPS)
            .put("down_mbps", DOWN_MBPS)
            .put("insecure", !s.optBoolean("verifyCert", false))
            .put("recv_window_conn", 65536 * rw)
            .put("recv_window", 163840 * rw)
            .put("disable_mtu_discovery", true)
            .put("socks5", JSONObject().put("listen", "127.0.0.1:$socksPort").put("timeout", 300))
        if (sni.isNotEmpty()) cfg.put("server_name", sni)

        val cfgFile = File(ctx.filesDir, "$tag.json").apply { writeText(cfg.toString()) }
        val log = File(ctx.filesDir, "$tag-runtime.log").apply { writeText("") }

        proc = ProcessBuilder(bin.path, "--log-level", "info", "-c", cfgFile.path, "client")
            .redirectErrorStream(true).directory(ctx.filesDir).start().also { it.drainTo(log) }

        val ok = waitForPort("127.0.0.1", socksPort, 8000)
        check(ok && proc?.isAlive == true) {
            "Hysteria n'a pas démarré le SOCKS 127.0.0.1:$socksPort. ${tail(log, 1600)}"
        }
        // Le SOCKS local s'ouvre avant la poignée de main QUIC : on vérifie un vrai aller-retour via le serveur.
        check(socksProbe(socksPort, "1.1.1.1", 443)) {
            "Le serveur UDP ne répond pas (mot de passe, port ou obfs incorrects ?). ${tail(log, 1200)}"
        }
    }

    override fun stop() {
        try { proc?.destroy() } catch (_: Throwable) {}
        proc = null
    }

    override fun alive(): Boolean = proc?.isAlive == true

    companion object {
        const val UP_MBPS = 50
        const val DOWN_MBPS = 100
    }
}
