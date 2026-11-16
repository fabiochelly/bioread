#!/bin/sh
# Motifs de césure de TeX (projet hyph-utf8, https://github.com/hyphenation/tex-hyphen) pour le
# compositeur de paragraphes (app/src/main/java/app/bioread/Hyphen.kt) : un fichier par langue
# dans app/src/main/assets/hyph, motifs puis exceptions (mots déjà coupés). Les en-têtes
# (auteurs, licences : MIT, BSD ou équivalent) sont réunis dans LICENCES.txt.
set -e
cd "$(dirname "$0")/.."
OUT=app/src/main/assets/hyph
B=https://raw.githubusercontent.com/hyphenation/tex-hyphen/master/hyph-utf8/tex/generic/hyph-utf8/patterns
mkdir -p "$OUT"
: > "$OUT/LICENCES.txt"
for pair in en:en-us fr:fr de:de-1996 es:es it:it pt:pt nl:nl; do
    code=${pair%%:*}
    name=${pair#*:}
    curl -sfL "$B/txt/hyph-$name.pat.txt" > "$OUT/$code.txt"
    # Les exceptions n'existent pas pour toutes les langues.
    curl -sfL "$B/txt/hyph-$name.hyp.txt" >> "$OUT/$code.txt" || true
    { echo "==== hyph-$name"; curl -sfL "$B/tex/hyph-$name.tex" | sed -n '/^%/p;/^[^%]/q'; echo; } >> "$OUT/LICENCES.txt"
done
ls -l "$OUT"
