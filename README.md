# Bulle pour Android

L'appli Android de [Bulle](https://github.com/kermess14/bulle), le lecteur de BD maison.

Elle se connecte à **votre** serveur Bulle (sur le Wi-Fi de la maison, ou à distance par VPN) et affiche votre
bibliothèque comme une vraie appli : plein écran pendant la lecture, écran qui reste allumé, bouton retour
d'Android. Les BD restent sur votre serveur : l'appli ne contient ni ne fournit aucun contenu.

- **Premier lancement** : « Chercher » trouve Bulle tout seul sur le Wi-Fi (port 2205), ou on tape son adresse
  (par exemple `192.168.0.213:2205`).
- **Changer de serveur** : appui long sur l'icône de l'appli › « Changer de serveur » (ou depuis l'écran
  « Impossible de joindre Bulle »).
- **Lecture** : plein écran, écran toujours allumé ; un glissement depuis le bord fait revenir les barres
  d'Android.
- **Lire hors connexion** : dans la fiche d'un album (bouton « i »), « Garder hors connexion » copie ses pages
  sur le téléphone (à la taille de l'écran, environ 1 Mo par tranche de 8 pages en SD, davantage en HD). Sans
  réseau, l'écran « Impossible de joindre Bulle » propose « Lire mes BD hors connexion » ; on y arrive aussi
  par un appui long sur l'icône › « BD hors connexion », ou par la rubrique « Hors connexion » du menu de Bulle.
  On lit avec le lecteur habituel (case par case compris) ; la page atteinte est renvoyée au serveur au retour
  du réseau. Un album gardé se lit toujours depuis le téléphone, même en ligne : c'est plus rapide.
  (Nécessite Bulle 0.25 ou plus récent sur le serveur.)
- **Télécharger un album** (bouton de la fiche) : le fichier d'origine arrive dans « Téléchargements ».
- Les liens vers d'autres sites (Bédéthèque…) s'ouvrent dans le navigateur.

## Essayer sur son téléphone

Chaque modification fabrique automatiquement l'appli (onglet *Actions*). La dernière version est publiée sur la
page [Releases](../../releases/tag/essai) : depuis le téléphone, touchez le fichier `.apk` pour le télécharger,
puis ouvrez-le. Android demande d'autoriser l'installation depuis le navigateur la première fois.

Signature : tant qu'aucune clé n'est enregistrée dans les *secrets* du dépôt (`BULLE_KEYSTORE_B64`,
`BULLE_KEYSTORE_PASSWORD`, `BULLE_KEY_ALIAS`, `BULLE_KEY_PASSWORD`), chaque version est signée avec une clé de
débogage différente : il faut désinstaller l'ancienne avant d'installer la nouvelle. Avec la clé, les mises à jour
s'installent par-dessus ; c'est aussi elle qui servira pour le Play Store. Elle ne doit jamais être mise dans le code.

## Technique

Java, sans bibliothèque externe ; Android 8 et plus (minSdk 26), cible Android 16 (API 36). Fabrication :
GitHub Actions (`.github/workflows/build.yml`), Gradle 8.13, Android Gradle Plugin 8.10.
