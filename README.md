# TOM TUNNEL Android 1.0.0

Client VPN Android (interface HTML dans une WebView + moteurs natifs). Développeur : JOELTOM.

## Protocoles pris en charge

| Profil de l'interface | Moteur | Notes |
|---|---|---|
| VLESS, VMess, Trojan, Shadowsocks | Xray (V2Ray) | TCP, WS, gRPC, H2 ; TLS et Reality |
| SSH SlowDNS | DNSTT + JSch | NS + clé publique DNSTT, puis SSH |
| SSH WebSocket | JSch | TLS (WSS) optionnel, SNI, Host, Path, payload |
| SSH TCP | JSch | Direct, Direct+Payload, HTTP Proxy(+Payload), SSL/SNI(+Payload), SSL/TLS Proxy |
| UDP | Hysteria v1 | Serveur compatible ZIVPN : mot de passe, port 5667 ou plage 6000-19999 |

Non pris en charge dans cette version : WireGuard et SOCKS (le service affiche une erreur claire).

Chaîne : moteur → proxy SOCKS5 local `127.0.0.1:10808` → hev-socks5-tunnel (TUN).

## Compiler (Termux ou PC)

```bash
chmod +x build-termux.sh scripts/bootstrap-engines.sh
./build-termux.sh      # télécharge/compile les moteurs s'ils manquent, puis assemble l'APK
```

Prérequis du bootstrap : `curl unzip git go`. Il récupère Xray et l'AAR hev, et compile DNSTT et
Hysteria v1 (tag `v1.3.5`, modifiable avec `HYST_TAG=...`) pour arm64-v8a et x86_64.

## Notes techniques

- Identifiant d'application : `com.tomtunnel.app` (différent de l'ancien JephTunnel : les deux peuvent coexister).
- Pont JavaScript `JT` : connect, disconnect, testUdp, techInfo, getDeviceId, resetHostKeys, clearCache, saveState, copyText, readClipboard, shareText, openUrl, refreshIp (+ saveFile, shareFile conservés).
- Clés d'hôte SSH : acceptées à la première connexion puis vérifiées. Si la clé change, la connexion est refusée avec un message explicite ; « Réinitialiser les clés d'hôte SSH » (paramètres) efface `known_hosts`.
- Algorithmes SSH : modernes d'abord ; les anciens (diffie-hellman-group1/14-sha1, ssh-rsa/ssh-dss, CBC) ne sont activés que si le serveur refuse la négociation moderne (nouvel essai automatique, noté dans `ssh.log`).
- Reconnexion automatique : si le moteur tombe, jusqu'à 5 tentatives avec délai croissant.
- Par SSH, seul le DNS (UDP/53) est relayé en DNS-over-TCP ; UDPGW n'est pas implémenté. Le profil UDP (Hysteria) relaie lui l'UDP complet.
- SSH WebSocket : après la réponse `101`, le flux est traité comme un tunnel brut (proxys SSH-over-WebSocket usuels), sans trames RFC 6455.
- TLS (SSL/SNI, WSS) : TLS 1.2/1.3 uniquement. Le certificat est vérifié (chaîne du système + nom = SNI). Il n'est pas vérifié si le profil contient `verifyCert:false`, ou, à défaut de ce champ, si un SNI personnalisé différent de l'hôte est utilisé (cas SNI/CDN) ; chaque connexion non vérifiée est notée dans `ssh.log`.

## Diagnostics (dossier privé de l'app)

`xray.json`, `xray-runtime.log`, `dnstt.log`, `hysteria.json`, `hysteria-runtime.log`, `hysteria-test-runtime.log`, `hev-runtime.log`, `tproxy.yml`, `known_hosts`, `ssh.log`.

## Compilation automatique (GitHub Actions)

Le workflow `.github/workflows/build-apk.yml` compile l'APK à chaque push, pull request ou lancement manuel
(onglet Actions → « Build TOM TUNNEL APK » → Run workflow). Il installe JDK 17, Go, le SDK Android 34 et Gradle 8.7,
télécharge/compile les moteurs (mis en cache), puis publie `TomTunnel-<version>-debug.apk` dans les artefacts du run.

- Le dépôt doit contenir ce dossier à sa racine (`settings.gradle.kts` à côté de `.github/`).
- Un tag `v*` (ex. `v1.0.0`) publie aussi les APK dans une Release.
- APK release signé (optionnel) : ajoute les secrets `KEYSTORE_BASE64` (keystore encodé en base64), `KEYSTORE_PASSWORD`,
  `KEY_ALIAS` et `KEY_PASSWORD`. Sans eux, seul l'APK debug est produit.
