> **Note de version 1.5.0 :** les évolutions Xray/SOCKS/WireGuard, transports modernes, import WireGuard et corrections du bootstrap sont décrites dans `docs/ZIVPN_ENGINE_INTEGRATION.md`. Ce fichier conserve l'historique de l'édition 1.4.0.

# Mise à niveau TOM_TUNNEL — ce qui a été fait, et son état de vérification

## ÉTAT DE VÉRIFICATION (lire en premier)
Cette mise à niveau a été écrite dans un environnement **sans accès réseau, sans SDK Android, sans Gradle ni Go**.
- ✅ Vérifié : syntaxe JavaScript de l'UI (`node --check`), YAML du workflow, shell `bash -n`, équilibre des accolades Kotlin,
  logique et format du Device ID (100 000 tirages, 0 doublon, format conforme).
- ❌ **Non vérifié** : compilation Android/Kotlin, compilation d'Hysteria, **aucune connexion réelle** (SSH, UDP, Xray).
- Conséquence : **aucun protocole n'est déclaré fonctionnel** par ce livrable. Il faut compiler (étapes ci-dessous) et tester sur un appareil.
  Le premier build peut révéler des erreurs de compilation Kotlin à corriger.

## Fait
| Demande | Réalisation | Fichiers |
|---|---|---|
| UDP (ZiVPN) | Nouveau moteur « UDP » = client **Hysteria v1** officiel (MIT) + obfuscation `zivpn` + auth par mot de passe. Profil : serveur, port (ou plage), mot de passe, receive window, SNI, vérif. certificat. Import par lien `udp://MOTDEPASSE@hôte:port?recv=2&sni=x&verify=1#nom`. | `engine/UdpEngine.kt`, `EngineAssets.kt`, `app/src/main/assets/index.html`, `script/bootstrap-engines.sh`, workflow |
| Device ID | `TOM-XXXX-XXXX-XXXX`, `SecureRandom`, chiffré AES-GCM (Android Keystore), lecture seule, copie en un clic dans Plus. Aucune donnée matérielle. | `DeviceId.kt`, `MainActivity.kt`, `app/src/main/assets/index.html` |
| SSH | Mode **proxy HTTP (CONNECT)** ajouté à SSH-Payload ; **vérification de clé d'hôte** (TOFU) à la place de `StrictHostKeyChecking=no`, avec bouton de réinitialisation. | `SshTcpEngine.kt`, `HostKeyStore.kt`, `SshSocksEngine.kt` |
| Architecture | `EngineManager` (sélection du moteur, test du port local 10808), exécuteur unique pour éviter les connexions multiples, annulation des opérations périmées. | `EngineManager.kt`, `TomVpnService.kt` |
| Reconnexion | Reconnexion automatique configurable : coupure du tunnel ou changement Wi-Fi/mobile, attentes 2/4/8/16/30 s, 5 essais. | `TomVpnService.kt`, `app/src/main/assets/index.html` |
| Sécurité | Config Xray / UDP (mots de passe) supprimée du disque après démarrage ; UDP dans `noBackupFilesDir`. | `XrayEngine.kt`, `UdpEngine.kt` |
| Paramètres | Device ID, reconnexion auto, réinit. clés SSH, vider le cache, infos techniques. L'ancien « Mon ID » est conservé (renommé « ID de partage (hérité) ») car il sert à déchiffrer les anciennes configs `JEPH`. | `app/src/main/assets/index.html` |

## Non fait / limites (volontairement non masquées)
- **Refonte Material 3 complète : non réalisée.** L'UI « Aurora » existante (clair/sombre) est conservée telle quelle.
- **Synchronisation du Device ID avec un serveur admin : non faite** (aucun backend dans le ZIP ; `docs/API.md` référencé n'existait pas).
- **Moteur SSH « DARK Tunnel » : non copié** (propriétaire). Modes réellement disponibles : direct, payload, proxy HTTP, WebSocket (WS/WSS), TLS, SNI, SlowDNS.
- **SSH + proxy + SNI/TLS** : le CONNECT est fait avant TLS/payload ; non testé contre un vrai proxy.
- **Compatibilité ZiVPN non confirmée** : elle repose sur l'hypothèse (à valider avec ton serveur) que le serveur est un dérivé de Hysteria v1 avec `obfs: "zivpn"`. Les clients ZiVPN utilisent aussi un saut de port (6000-19999 → 5667) ; ici la plage est passée au champ `server` d'Hysteria, dont la prise en charge exacte est à tester. Si la connexion échoue, consulter `filesDir/udp-runtime.log` (journal du moteur).
- **Export de configs** : non rétabli (retiré volontairement dans l'original).
- **ABI** : `arm64-v8a` et `x86_64` uniquement (comme l'original). Pas de `armeabi-v7a`.
- Fuites DNS/IPv6 : la route `::/0` et le DNS sont dirigés dans le TUN (déjà présent) ; non testé.
- Aucune valeur d'identifiant ni de mot de passe n'est dans le code source. Les accès de test fournis par l'utilisateur n'y figurent pas.

## Compiler et tester
```bash
chmod +x script/bootstrap-engines.sh && ./script/bootstrap-engines.sh   # curl unzip git go requis ; télécharge/compile Xray, hev, DNSTT, Hysteria
./gradlew :app:assembleDebug                                            # ou build-termux.sh sur Termux
```
Checklist de test sur appareil : (1) Plus → DEVICE ID visible, identique après redémarrage ; (2) ajouter un serveur UDP, connecter, vérifier l'IP publique ;
(3) couper le Wi-Fi pendant la connexion → reconnexion ; (4) SSH direct / payload / proxy ; (5) Xray (VLESS/Trojan…) ; (6) changer de protocole sans redémarrer l'app.


## Édition 1.5.2 — nettoyage de l'interface et modes SSH

- Un seul fichier HTML de référence pour l'application Android : `app/src/main/assets/index.html`.
- Suppression de l'ancien `index.html` racine, non référencé par l'application ni par la CI.
- Sélecteur SSH à huit modes avec affichage conditionnel des champs Proxy, SNI et Payload.
- Slow DNS reste un profil séparé et utilise son propre formulaire DNSTT.
- Le formulaire UDP garde les paramètres pris en charge par le moteur Hysteria v1. Le « Test Mode » de l'APK de référence n'est pas simulé, faute de spécification de son comportement.
- Aucun changement aux moteurs Xray, UDP, SSH WebSocket, DNSTT ou au service VPN natif dans cette édition de nettoyage/UI.

## 1.5.3
- Ajout d'un véritable test UDP depuis le formulaire : démarrage temporaire du client Hysteria/ZiVPN, négociation SOCKS5 et requête CONNECT vers `1.1.1.1:443` à travers le tunnel.
- Le résultat du test indique explicitement la réussite ou l'échec et le moteur temporaire est arrêté dans tous les cas.
- Le test refuse de démarrer pendant qu'une session VPN est active afin d'éviter un conflit sur le port SOCKS local.
