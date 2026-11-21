package com.tomtunnel.app.engine

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Moteur « UDP » : client Hysteria v1 officiel (apernet/hysteria, licence MIT) avec l'obfuscation « zivpn »,
 * qui correspond au serveur UDP de type ZiVPN (auth par mot de passe). Le binaire natif (libhysteria.so) est
 * compilé par script/bootstrap-engines.sh. Ouvre un SOCKS5 local sur Engine.SOCKS_PORT.
 *
 * Profil JSON : {type:"UDP", host, port ("5667" ou plage "6000-19999"), pass, recvWindow (1..8),
 *                sni, verifyCert (bool, défaut false : les serveurs ZiVPN utilisent un certificat auto-signé),
 *                upMbps, downMbps}
 */
class UdpEngine : Engine {
    private var proc: Process? = null
    private var cfgFile: File? = null

    override fun start(ctx: Context, s: JSONObject) {
        val host = s.optString("host").trim()
        val portSpec = s.optString("port", "5667").trim().ifEmpty { "5667" }
        val pass = s.optString("pass")
        require(host.isNotEmpty()) { "Adresse du serveur UDP manquante" }
        require(Regex("^[A-Za-z0-9.\\-:_\\[\\]]+$").matches(host)) { "Adresse du serveur UDP invalide" }
        require(Regex("^\\d{1,5}(-\\d{1,5})?$").matches(portSpec) &&
            portSpec.split("-").all { it.toInt() in 1..65535 }) { "Port UDP invalide : $portSpec (ex. 5667 ou 6000-19999)" }
        require(pass.isNotEmpty()) { "Mot de passe UDP manquant" }
        val recv = s.optString("recvWindow", "2").toIntOrNull()?.coerceIn(1, 8) ?: 2
        val up = s.optString("upMbps", "50").toIntOrNull()?.coerceIn(1, 1000) ?: 50
        val down = s.optString("downMbps", "100").toIntOrNull()?.coerceIn(1, 1000) ?: 100

        val bin = EngineAssets.materialize(ctx, "hysteria", abi(), "hysteria")
        // Fenêtre de réception : 2 = valeur par défaut de Hysteria (15 Mo par connexion), proportionnelle ensuite.
        val conn = 7_864_320L * recv
        val cfg = JSONObject().apply {
            put("server", "$host:$portSpec")
            put("obfs", "zivpn")
            put("auth_str", pass)
            put("up_mbps", up); put("down_mbps", down)
            put("recv_window_conn", conn); put("recv_window", conn * 4)
            put("retry", -1); put("retry_interval", 3)
            put("insecure", !s.optBoolean("verifyCert", false))
            s.optString("sni").trim().takeIf { it.isNotEmpty() }?.let { put("server_name", it) }
            put("socks5", JSONObject().put("listen", "127.0.0.1:${Engine.SOCKS_PORT}").put("timeout", 300))
        }
        // Le fichier contient le mot de passe : stockage hors sauvegarde, supprimé dès que le moteur a démarré.
        val f = File(ctx.noBackupFilesDir, "udp.json").apply { writeText(cfg.toString()) }
        cfgFile = f
        val log = File(ctx.filesDir, "udp-runtime.log").apply { delete() }
        val p = ProcessBuilder(bin.path, "-c", f.path, "client")
            .redirectErrorStream(true).directory(ctx.filesDir).start()
        proc = p
        p.drainTo(log)
        val ok = waitForPort("127.0.0.1", Engine.SOCKS_PORT, 20000) { p.alive() }
        try { f.delete() } catch (_: Throwable) {}
        check(ok) {
            val t = tail(log, 900)
            when {
                !p.alive() -> "Le moteur UDP s'est arrêté. $t"
                else -> "Impossible d'établir la session UDP avec $host:$portSpec (serveur injoignable, port filtré ou mot de passe refusé). $t"
            }.trim()
        }
    }

    /** Lance temporairement le client puis vérifie un vrai CONNECT SOCKS5 à travers le tunnel UDP. */
    fun testConnection(ctx: Context, profile: JSONObject): String {
        try {
            start(ctx, profile)
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", Engine.SOCKS_PORT), 3000)
                socket.soTimeout = 8000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                val greeting = ByteArray(2)
                input.readFully(greeting)
                check(greeting[0].toInt() == 5 && greeting[1].toInt() == 0) {
                    "Le proxy SOCKS5 local n'accepte pas la méthode sans authentification."
                }
                // CONNECT vers Cloudflare HTTPS : le succès du code REP confirme le passage par le tunnel.
                output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 1, 1, 1, 1, 0x01, 0xBB.toByte()))
                output.flush()
                val head = ByteArray(4)
                input.readFully(head)
                check(head[0].toInt() == 5) { "Réponse SOCKS5 invalide." }
                val rep = head[1].toInt() and 0xff
                when (head[3].toInt() and 0xff) {
                    1 -> input.skipBytes(4)
                    3 -> { val n = input.readUnsignedByte(); input.skipBytes(n) }
                    4 -> input.skipBytes(16)
                    else -> error("Type d'adresse SOCKS5 inconnu.")
                }
                input.skipBytes(2)
                check(rep == 0) { "Le serveur UDP a répondu avec le code SOCKS5 $rep : le trafic ne passe pas." }
            }
            return "Connexion UDP et trafic de test confirmés."
        } finally {
            stop()
        }
    }

    override fun isAlive(): Boolean = proc?.alive() == true

    override fun stop() {
        try { proc?.destroy() } catch (_: Throwable) {}
        proc = null
        try { cfgFile?.delete() } catch (_: Throwable) {}
        cfgFile = null
    }

    private fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
}
