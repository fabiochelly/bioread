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

## Traduction sous les mots difficiles

Dans un livre anglais, l'option « Mots difficiles » (Options › Traduction) écrit en tout petit,
sous chaque mot difficile, sa traduction française. Le curseur règle la taille du vocabulaire du
lecteur (de 3 000 à 30 000 mots) ; un aperçu montre, dans le chapitre ouvert, les mots qui
seront traduits et ceux qui sont supposés connus.

Le classement des mots est livré dans l'app (`app/src/main/assets/wordwise-en-fr.bin`) et se
refabrique avec :

```
uv run --with wordfreq --with openpyxl --with nltk tools/make_wordwise.py --check roman.txt
```

Il combine la fréquence (wordfreq, sous-titres SUBTLEX-US), le taux de connaissance mesuré chez
des locuteurs non natifs (Brysbaert, Keuleers & Mandera 2020) et l'âge d'acquisition (Kuperman
et al. 2012), par famille de mots (run, ran, running). Les mots transparents pour un francophone
(melancholy, curious) ne sont pas traduits ; les faux amis (library, eventually) le sont. Le sens
traduit est le plus courant d'après WordNet, et `tools/wordwise-fixes-fr.tsv` corrige à la main
plus de 7 500 mots relus en contexte dans une trentaine de romans récents, recoupés avec deux
dictionnaires indépendants (MUSE de Facebook, alignements des sous-titres OpenSubtitles d'OPUS). La
méthode est décrite en tête du script. Les données de l'étude sur les non-natifs sont sous
licence CC BY-NC-SA 4.0 : la table n'est utilisable qu'à des fins non commerciales.

## Bibliothèques tierces

- `com.google.mlkit:translate` : traduction hors-ligne.
- `org.lsposed.hiddenapibypass` (Apache 2.0) : utilisée uniquement sur les liseuses Onyx, pour
  appeler le rafraîchissement complet de l'écran que leur firmware masque aux applications.

## Polices

Literata (licence SIL OFL, texte dans `app/src/main/assets/fonts/licences/`) est livrée avec
l'app ; si les fichiers Bookerly sont fournis, la police se choisit dans les options. Inter sert aux titres et à l'interface.
