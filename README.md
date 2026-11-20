# TOM_TUNNEL Android 1.5.6

**TOM_TUNNEL — By JOELTOM** est un client VPN Android natif (interface WebView + moteurs Kotlin et exécutables natifs).

 
## Compatibilité Android

- Version minimale configurée : **Android 6.0 (API 23)**.
- Compatible avec les versions Android plus récentes, sous réserve des restrictions propres à chaque fabricant et version.
- Architecture actuellement fournie : **ARM64 (arm64-v8a) et x86_64**.
- Les appareils Android 32 bits uniquement (armeabi-v7a / armeabi) ne sont pas encore pris en charge, car les moteurs natifs correspondants ne sont pas fournis.
- Android 5.x et antérieur ne sont pas garantis.
- La notification VPN utilise maintenant une construction compatible avec Android 6/7 et les canaux de notification uniquement sur Android 8+.

« Toutes les versions Android » ne peut donc pas être garanti avec les moteurs binaires actuels. Pour couvrir aussi les anciens téléphones 32 bits, il faudra compiler et tester séparément Xray, DNSTT, Hysteria et le moteur TUN pour ARMv7.

## Familles de moteurs

- **SSH** : TCP direct, payload HTTP, proxy HTTP CONNECT, TLS/SNI, WebSocket/WSS et SlowDNS/DNSTT.
- **Xray** : VLESS, VMess, Trojan, Shadowsocks, SOCKS5 et WireGuard ; option Xray via SlowDNS/DNSTT pour les profils TCP compatibles.
- **Transports Xray récents** : RAW/TCP, WebSocket, gRPC, HTTPUpgrade, XHTTP et mKCP, avec TLS ou REALITY lorsque la combinaison est prise en charge par Xray.
- **UDP dédié** : client Hysteria v1 avec option d'obfuscation `zivpn` ; la compatibilité doit être validée avec le serveur UDP utilisé.
- **TUN Android** : hev-socks5-tunnel raccorde l'interface VPN au proxy local.

Les modes propriétaires OSSH/Meek/Tapdance/Conjure de ZIVPN ne sont pas déclarés disponibles : l'APK de référence ne fournit pas leur code source ni leurs contrats techniques complets.

## Identité

| Élément | Valeur |
|---|---|
| Nom | TOM_TUNNEL |
| Package | `com.tomtunnel.app` |
| Développeur | By JOELTOM |
| Version | 1.5.5 |
| Canal Telegram | https://t.me/tomtunnel |

## Compilation des moteurs natifs

Depuis la racine du dépôt :

```bash
chmod +x script/bootstrap-engines.sh
./script/bootstrap-engines.sh
gradle :app:assembleDebug
```

Le bootstrap prépare Xray, hev-socks5-tunnel, DNSTT et Hysteria pour `arm64-v8a` et `x86_64`. Les binaires natifs ne sont pas inclus dans l'archive source ; ils sont téléchargés pendant cette étape.

Sur Termux ARM64, utiliser :

```bash
bash build-termux.sh
```

Prérequis : JDK 17, Android SDK API 34, Gradle 8.x, curl, unzip, git et Go.

## Import de profils

- VLESS, VMess, Trojan et Shadowsocks : liens de partage standards pris en charge par le parseur existant.
- SOCKS : `socks://` ou `socks5://`.
- WireGuard : fichier de configuration texte `[Interface]` / `[Peer]`, ou URI propre à TOM_TUNNEL `wireguard://PRIVATE_KEY@host:port?publicKey=...&address=...`.
- UDP : `udp://PASSWORD@host:port?recv=2&sni=...&verify=0`.

## Sécurité et validation

- Les secrets WireGuard/Xray sont conservés dans le profil local et la configuration temporaire Xray est supprimée à l'arrêt du moteur.
- La validation `xray run -test` est effectuée avant le démarrage.
- Un test JSON réussi ne prouve pas qu'un serveur distant est compatible. Chaque protocole/transport doit être testé avec un serveur réel correspondant.
- Le build Android n'a pas été exécuté dans l'environnement de préparation faute d'Android SDK/Gradle ; la validation finale doit être réalisée par Android Studio ou GitHub Actions.

## Fichiers conservés volontairement

`SshSocksEngine.kt` est la base partagée de SlowDNS et WebSocket ; `TProxyService.kt` est appelé par `TomVpnService`; `EngineManager.kt` distribue les profils ; `EngineAssets.kt` localise les exécutables natifs. Leur suppression casserait le fonctionnement du client.


### Modes SSH
Le formulaire SSH permet de choisir Direct, Direct + Payload, HTTP Proxy, HTTP Proxy + Payload, SSL/SNI, SSL/SNI + Payload, Slow DNS ou SSL/TLS Proxy + SNI + Payload. UDP se configure séparément. Les modes disponibles dépendent de la compatibilité du serveur distant.


## Formulaire SSH (édition 1.5.3)

Le formulaire Android propose un sélecteur unique avec les huit modes demandés : Direct, Direct + Payload, HTTP Proxy, HTTP Proxy + Payload, SSL / SNI, SSL / SNI + Payload, Slow DNS et SSL/TLS Proxy + SNI + Payload. Les champs Proxy, SNI et Payload sont affichés selon le mode. Slow DNS ouvre son formulaire dédié (NS, résolveur, clé publique DNSTT et identifiants SSH).

Les modes TCP sont reliés au moteur `SshTcpEngine` : proxy HTTP CONNECT, TLS avec SNI et injection de payload sont appliqués dans l'ordre requis. La compatibilité dépend du serveur et du proxy distants.

Le formulaire UDP conserve serveur, port/plage, mot de passe, Receive window, SNI et validation TLS. Le commutateur « Test Mode » visible dans l'application de référence n'est pas reproduit : son comportement serveur/client n'est pas défini par les éléments fournis et il ne faut pas afficher un réglage qui ne ferait rien.

`app/src/main/assets/index.html` est l'unique interface HTML embarquée dans l'APK. L'ancien `index.html` racine, qui n'était référencé par aucun code Android ni workflow, a été retiré pour éviter toute confusion.


## Test UDP réel (1.5.3)

Le formulaire UDP inclut un test de connectivité qui lance temporairement Hysteria v1/ZiVPN, négocie SOCKS5 puis vérifie un CONNECT vers `1.1.1.1:443` à travers le tunnel. Le moteur temporaire est arrêté après le test. Le contrôle ne mesure pas le débit et nécessite un accès à la destination de test.


## Identité visuelle TOM TUNNEL

L'interface utilise désormais un thème sombre fixe : bleu nuit, bleu électrique et accents orange. Le sélecteur de thème a été retiré des paramètres pour conserver une apparence cohérente. Les fonctions et moteurs VPN n'ont pas été volontairement modifiés dans cette mise à jour graphique.
