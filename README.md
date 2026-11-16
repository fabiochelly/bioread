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

## Composition des paragraphes

Le texte justifié est composé par l'algorithme de Knuth et Plass (celui de TeX) : les coupures de
ligne sont choisies sur le paragraphe entier, pour des blancs réguliers et peu de mots coupés. La
césure suit les motifs de TeX (projet [hyph-utf8](https://github.com/hyphenation/tex-hyphen),
licences MIT et BSD, rappelées dans `app/src/main/assets/hyph/LICENCES.txt`), identiques sur tous
les appareils ; `tools/fetch_hyph.sh` les retélécharge. En bout de ligne, le trait d'union et la
ponctuation débordent légèrement dans la marge (marge optique). Les titres, les textes centrés,
les vers et les écritures non latines restent composés par le système.

Pour comparer avec la composition du système : `adb shell setprop debug.bioread.kp 0`, puis
relancer l'app.

## Surlignages et notes

Appui long sur un mot, puis « Surligner » : le passage part de ce mot, et toucher un autre mot de
la page l'étend jusqu'à lui (ou le raccourcit) ; « Phrase » l'arrondit aux phrases entières,
« Note » y attache un texte. La liste des surlignages du livre s'ouvre par le crayon du panneau de
navigation (pied de page). Une copie en Markdown est tenue à jour dans
`Download/bioread-notes/<titre>.md`.

## Synchronisation de la position

Options › Synchronisation : un compte sur un serveur de synchronisation de KOReader (kosync),
`https://sync.koreader.rocks` ou le vôtre. La position part quand on quitte le livre ; à
l'ouverture, si un autre appareil est allé plus loin, l'app propose de l'y rejoindre. Compatible
avec KOReader pour un même fichier EPUB. Sans clavier : déposez `Download/bioread-sync.txt`
(trois lignes : serveur, identifiant, mot de passe), lu une fois puis supprimé.

Serveur à soi, sans KOReader : `server/bioread-sync/index.php`, une seule page PHP à déposer
dans un dossier de son site, accessible en écriture. Comptes et positions sont gardés en JSON
dans `bioread-data.php`, à côté (protégé de la lecture depuis le web par sa première ligne).
Dans l'app, l'adresse du dossier à la place de celle du serveur KOReader : Bioread reconnaît
seul une page Bioread. Le premier compte se crée depuis l'app (« Créer le compte ») ; les
suivants s'ajoutent dans le fichier, mot de passe en clair, remplacé par son empreinte à la
première connexion.

## Catalogues en ligne (OPDS)

L'icône du nuage, à l'accueil, ouvre les catalogues OPDS : projet Gutenberg et Ebooks libres et
gratuits d'origine, et les vôtres (Calibre : `http://adresse-du-pc:8080/opds`, Calibre-Web, Kavita,
Komga…), avec identifiant et mot de passe s'il en faut. Un toucher sur un livre le télécharge dans
la bibliothèque.

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
lecteur (de 3 000 à 30 000 mots).

Les expressions sont traduites d'un bloc, sous tous leurs mots : *gave up* → abandonner, *put up
with* → supporter, *shrugged it off* → ignorer. Les quelque 1 400 expressions
(`tools/wordwise-phrases-fr.tsv`) viennent du Wiktionnaire et ne sont gardées que si leur sens
figuré l'emporte dans les romans relus.

Un appui long sur un mot propose, dans sa fiche, de ne plus le traduire (« Je connais… ») ou, pour
un mot que le curseur laisse sans traduction, de toujours le traduire ; le choix vaut pour toutes
les formes du mot et se défait de la même façon.

Le classement des mots est livré dans l'app (`app/src/main/assets/wordwise-en-fr.bin`) et se
refabrique avec :

```
uv run --with wordfreq --with openpyxl --with nltk tools/make_wordwise.py --check roman.txt
```

Il combine la fréquence (wordfreq, sous-titres SUBTLEX-US), le taux de connaissance mesuré chez
des locuteurs non natifs (Brysbaert, Keuleers & Mandera 2020) et l'âge d'acquisition (Kuperman
et al. 2012), par famille de mots (run, ran, running) ; une catégorie rare dans sa famille a son
propre rang (le verbe de tower, « dominer », est bien plus rare que le nom). Les mots transparents
pour un francophone (melancholy, curious) ne sont pas traduits ; les faux amis (library, agony,
succeed) le sont. Le sens
traduit est le plus courant d'après WordNet, et `tools/wordwise-fixes-fr.tsv` corrige à la main
plus de 10 000 mots : relus en contexte dans une trentaine de romans récents, recoupés avec deux
dictionnaires indépendants (MUSE de Facebook, alignements des sous-titres OpenSubtitles d'OPUS),
puis tous les mots de la table rencontrés dans ces romans et jamais relus. La
méthode est décrite en tête du script. Les données de l'étude sur les non-natifs sont sous
licence CC BY-NC-SA 4.0 : la table n'est utilisable qu'à des fins non commerciales.

## Traduction de la page

L'icône du pied de page traduit la page entière. Avec une clé DeepL (Options › Traduction), la
traduction passe par DeepL ; sinon, ou si le réseau manque, elle se fait sur l'appareil avec le
moteur de Firefox Translations ([bergamot-translator](https://github.com/browsermt/bergamot-translator),
Marian) et les modèles de Mozilla. Anglais → français est livré dans l'app et lu directement dans
l'APK ; les autres sens se téléchargent une fois, au premier besoin, depuis les serveurs de
Firefox, et deux langues autres que l'anglais passent par lui.

Ni le modèle ni le moteur ne sont dans le dépôt ; avant de compiler l'app :

```
python3 tools/fetch_mt_model.py      # modèle anglais → français (36 Mo) dans app/src/main/assets/mt/enfr
tools/build_bergamot.sh              # moteur (libbergamot.so) dans app/src/main/jniLibs, avec le NDK et CMake du SDK Android
```

Sans le modèle, l'app le télécharge au premier usage ; sans le moteur, la traduction hors-ligne
est indisponible (DeepL reste possible).

## Bibliothèques tierces

- bergamot-translator (MPL 2.0) et Marian (MIT), avec SentencePiece et ruy (Apache 2.0),
  yaml-cpp (MIT), PCRE2 (BSD) et zlib : traduction hors-ligne. Modèles de traduction de Mozilla
  (Firefox Translations, MPL 2.0).
- `org.lsposed.hiddenapibypass` (Apache 2.0) : utilisée uniquement sur les liseuses Onyx, pour
  appeler le rafraîchissement complet de l'écran que leur firmware masque aux applications.

## Polices

Literata (licence SIL OFL, texte dans `app/src/main/assets/fonts/licences/`) est livrée avec
l'app ; si les fichiers Bookerly sont fournis, la police se choisit dans les options. Inter sert aux titres et à l'interface.
