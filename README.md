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
