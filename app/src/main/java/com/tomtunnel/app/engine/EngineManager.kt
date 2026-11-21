package com.tomtunnel.app.engine

import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket

/** Répartiteur explicite : aucun type inconnu n'est envoyé silencieusement vers Xray. */
object EngineManager {
    private fun normalized(s: JSONObject): String = s.optString("type").trim().uppercase().replace('_', '-').replace(' ', '-')

    fun create(s: JSONObject): Engine = when (normalized(s)) {
        "SSH-SLOWDNS", "SLOWDNS", "DNSTT", "SSH-DNSTT" -> SlowDnsEngine()
        "SSH-WEBSOCKET", "SSH-WS", "SSH-WSS", "WEBSOCKET-SSH" -> SshWebSocketEngine()
        "SSH-TCP", "SSH-PAYLOAD", "SSH-DIRECT", "SSH-SSL", "SSH-STUNNEL", "SSH-PROXY", "SSH-HTTP-PROXY", "SSH-DROPBEAR", "DROPBEAR" -> SshTcpEngine()
        "UDP", "ZIVPN-UDP", "HYSTERIA" -> UdpEngine()
        "XRAY-SLOWDNS", "V2RAY-SLOWDNS", "V2RAY-DNS" -> XraySlowDnsEngine()
        "VLESS", "VMESS", "TROJAN", "SHADOWSOCKS", "SOCKS", "SOCKS5", "WIREGUARD", "WG", "XRAY", "V2RAY" -> XrayEngine()
        else -> error("Type de serveur inconnu : ${s.optString("type")}. Importez un profil pris en charge.")
    }

    fun family(s: JSONObject): String = when (normalized(s)) {
        "SSH-SLOWDNS", "SLOWDNS", "DNSTT", "SSH-DNSTT",
        "SSH-WEBSOCKET", "SSH-WS", "SSH-WSS", "WEBSOCKET-SSH",
        "SSH-TCP", "SSH-PAYLOAD", "SSH-DIRECT", "SSH-SSL", "SSH-STUNNEL", "SSH-PROXY", "SSH-HTTP-PROXY", "SSH-DROPBEAR", "DROPBEAR" -> "SSH"
        "UDP", "ZIVPN-UDP", "HYSTERIA" -> "UDP"
        "XRAY-SLOWDNS", "V2RAY-SLOWDNS", "V2RAY-DNS" -> "XRAY"
        "VLESS", "VMESS", "TROJAN", "SHADOWSOCKS", "SOCKS", "SOCKS5", "WIREGUARD", "WG", "XRAY", "V2RAY" -> "XRAY"
        else -> error("Famille de protocole inconnue : ${s.optString("type")}")
    }

    /** Échoue si le port SOCKS local est déjà pris (ancien processus, autre application). */
    fun ensureLocalPortFree(port: Int = Engine.SOCKS_PORT) {
        try { ServerSocket().use { it.reuseAddress = true; it.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)) } }
        catch (e: Throwable) { throw IllegalStateException("Le port local $port est déjà utilisé. Fermez l'application qui l'occupe puis réessayez.", e) }
    }
}
