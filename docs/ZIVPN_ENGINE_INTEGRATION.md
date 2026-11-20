# TOM_TUNNEL — intégration des familles de moteurs inspirées de ZIVPN

## Implémenté dans cette édition

- SSH TCP / Payload / HTTP CONNECT / TLS-SNI / Dropbear-compatible : moteur Kotlin existant conservé.
- SSH WebSocket / WSS : moteur Kotlin existant conservé.
- SSH SlowDNS : DNSTT + SSH conservé.
- Xray : VLESS, VMess, Trojan, Shadowsocks, SOCKS et WireGuard.
- Xray via SlowDNS/DNSTT : le client DNSTT établit un forward TCP local puis Xray utilise ce point d’accès tout en conservant le SNI/nom distant du profil.
- Transports Xray configurables : RAW/TCP, WebSocket, gRPC, HTTPUpgrade, XHTTP et mKCP ; TLS et REALITY selon les combinaisons admises par Xray récent.
- UDP : le moteur Hysteria v1 existant est conservé et son binaire est maintenant inclus dans le bootstrap Termux et CI.
- Import : liens `socks://` / `socks5://`, lien WireGuard propre à TOM_TUNNEL et fichiers de configuration WireGuard standard `[Interface]` + `[Peer]` (un pair actif importé par profil).

## Compatibilité des transports Xray récents

La génération JSON utilise le schéma Xray actuel `streamSettings.method` (RAW, WebSocket, gRPC, HTTPUpgrade, XHTTP, mKCP). Les anciens profils qui demandent le transport HTTP/2 `h2` ou QUIC historique sont refusés explicitement : ces transports ne font plus partie des méthodes Xray actuelles. Le QUIC historique n'est donc pas présenté comme une fonction active ; utiliser le moteur UDP séparé si le serveur est compatible.

REALITY est limité aux méthodes RAW, XHTTP et gRPC ; les flows VLESS Vision sont limités à VLESS + RAW + TLS/REALITY. Les combinaisons non compatibles sont rejetées avant le lancement du moteur.

## Ce qui n'est pas prétendu comme intégré

Les transports OSSH Meek, Tapdance, Conjure et QUIC-OSSH présents dans les références DEX de l'APK ZIVPN ne sont pas reproduits ici : le fichier APK n'est pas leur code source et ne donne pas les sources/licences/contrats nécessaires pour les réimplémenter correctement. Ils ne sont donc pas affichés comme modes disponibles.

Le moteur UDP Hysteria v1 n'est pas une preuve de compatibilité universelle avec tout serveur appelé « ZiVPN UDP ». La connexion doit être validée avec un serveur compatible avec la version et l'obfuscation configurées.

## Fichiers conservés volontairement

- `SshSocksEngine.kt` : classe de base utilisée par SSH SlowDNS et SSH WebSocket.
- `TProxyService.kt` : wrapper utilisé par `TomVpnService` pour piloter le tunnel TUN.
- `EngineManager.kt` : répartiteur central.
- `EngineAssets.kt` : résolution des exécutables natifs.

Aucun de ces fichiers n'est inutile ; leur suppression casserait le démarrage ou le routage.

## Build

Depuis la racine :

```bash
chmod +x script/bootstrap-engines.sh
./script/bootstrap-engines.sh
gradle :app:assembleDebug
```

Sur Termux, utiliser `bash build-termux.sh` après installation du SDK Android et des outils requis.

## Vérifications requises avant publication

La compilation doit être exécutée avec Android SDK/Gradle. Ensuite tester un compte réel pour chaque famille et chaque transport : SSH TCP, SSH WSS, DNSTT, VLESS Reality, VMess WS, Trojan gRPC, Shadowsocks, SOCKS, WireGuard et UDP. La simple validation JSON de Xray ne remplace pas un test réseau réel.


## Sélecteur SSH — TOM_TUNNEL 1.5.2

Le formulaire propose les huit entrées visibles dans la référence ZIVPN : Direct, Direct + Payload, HTTP Proxy, HTTP Proxy + Payload, SSL / SNI, SSL / SNI + Payload, Slow DNS et SSL/TLS Proxy + SNI + Payload. Pour les six modes TCP, le profil enregistré transmet les indicateurs `proxyHost`, `proxyPort`, `tls`, `sni` et `payload` au `SshTcpEngine`. Le moteur effectue le CONNECT HTTP si un proxy est configuré, établit TLS/SNI si demandé, puis injecte le payload si demandé avant l'authentification SSH. Slow DNS est conservé comme moteur séparé DNSTT.

Le formulaire UDP expose serveur, port/plage, mot de passe, receive window, SNI et validation du certificat. Le commutateur « Test Mode » de la capture n'est pas implémenté car son effet protocolaire n'est pas documenté ; aucune commande ou option arbitraire n'est envoyée au moteur.

### Test UDP réel (1.5.3)
Le formulaire UDP propose « Tester la connexion UDP ». L'application lance temporairement le client Hysteria v1 avec les paramètres saisis, effectue la négociation SOCKS5 locale, puis demande un tunnel TCP vers `1.1.1.1:443`. Le test est réussi uniquement si la réponse SOCKS5 confirme le CONNECT. Le moteur temporaire est ensuite arrêté et le fichier de configuration temporaire est supprimé. Le test est refusé lorsqu'un VPN est déjà connecté ou en cours de connexion. Il vérifie donc le transport UDP et le passage du trafic, mais ne mesure pas le débit et dépend de l'accessibilité de la destination de test.
