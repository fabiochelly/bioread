# Bioread

Lecteur EPUB pour Android, pensé pour les liseuses à encre électronique (Onyx Boox) et les
smartphones. Rendu natif sans WebView, pages pré-rendues, animation de page adaptée à l'e-ink.

## Compiler

```
mise install          # JDK 21 (voir mise.toml), ou définir JAVA_HOME vers un JDK 21
./gradlew assembleDebug
```

Le SDK Android est attendu dans `local.properties` (`sdk.dir=…`).

Une version *release* se signe avec `keystore/keystore.properties` (hors dépôt) ; sans ce
fichier, elle est signée avec la clé de débogage.

## Polices

Le texte est composé en Bookerly, police d'Amazon qui n'est pas redistribuable et n'est donc
pas dans ce dépôt. Pour l'utiliser, placez vos propres fichiers dans
`app/src/main/assets/fonts/` :

```
Bookerly-Regular.ttf  Bookerly-Italic.ttf  Bookerly-Bold.ttf  Bookerly-BoldItalic.ttf
```

Sans eux, l'app se compile et utilise la police serif du système. Les titres et l'interface
utilisent Inter (licence OFL), fournie dans le dépôt.

## Dictionnaire

Un appui long sur un mot affiche sa définition (dictionnaire unilingue, hors-ligne) au-dessus de
sa traduction ; un lien par zone déroule tous les sens et toutes les traductions par sens du
dictionnaire. Les dictionnaires ne sont pas dans l'APK : ils se fabriquent à partir du
Wiktionnaire (extractions [kaikki.org](https://kaikki.org), licence CC BY-SA 4.0), un fichier
par langue :

```
python3 tools/make_dict.py --out dict-out en fr     # bioread-dict-en.bin, bioread-dict-fr.bin
```

Chaque fichier embarque les traductions vers les autres langues de l'app (`--targets fr,en` pour
en garder moins).

Déposez les fichiers `bioread-dict-*.bin` dans `Download/` de l'appareil : l'app les installe
au lancement suivant (puis retire l'original). Le format est décrit en tête du script.

## Bibliothèques tierces

- `com.google.mlkit:translate` : traduction hors-ligne.
- `org.lsposed.hiddenapibypass` (Apache 2.0) : utilisée uniquement sur les liseuses Onyx, pour
  appeler le rafraîchissement complet de l'écran que leur firmware masque aux applications.

## Polices

Literata (licence SIL OFL, texte dans `app/src/main/assets/fonts/licences/`) est livrée avec
l'app ; si les fichiers Bookerly sont fournis, la police se choisit dans les options. Inter sert aux titres et à l'interface.
