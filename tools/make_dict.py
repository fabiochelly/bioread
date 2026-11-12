#!/usr/bin/env python3
"""
Fabrique les dictionnaires hors-ligne de Bioread à partir du Wiktionnaire.

    python3 tools/make_dict.py --out dict-out en fr

Source : extractions wiktextract publiées par kaikki.org (en.wiktionary pour l'anglais,
fr.wiktionary pour le français), sous licence CC BY-SA 4.0 comme le Wiktionnaire lui-même.
Le fichier produit (bioread-dict-<langue>.bin) est à déposer dans Download/ de l'appareil :
l'app l'installe au lancement suivant. Il n'est pas embarqué dans l'APK.

Ne sont gardés que les mots de la langue (un seul mot, sans espace), leurs définitions par
catégorie grammaticale (toutes), une prononciation, leurs traductions par sens vers les autres
langues de l'app, et la table « forme fléchie → lemme » tirée des relations form-of de
l'extraction (running → run, chevaux → cheval). Ni étymologies, ni exemples.

Format du fichier (entiers little-endian) — lu par Dict.kt, à garder synchronisé :

    0   8   magie « BIODICT3 » (le chiffre est la version du format)
    8   4   nombre de blocs
    12  4   nombre de clés
    16  4   nombre d'entrées (mot + catégorie)
    20  4   nombre de renvois forme → lemme
    24  4   position des métadonnées (JSON UTF-8), 28 : leur longueur
    32  4   position de l'index, 36 : position des premières clés
    40  4   position du dictionnaire de compression, 44 : sa longueur (32 Ko au plus)
    index   par bloc : position de sa première clé (relative), position du bloc dans le
            fichier, taille décompressée ; puis une sentinelle (fin du dernier bloc)
    clés    première clé de chaque bloc : longueur sur 2 octets, puis UTF-8
    dico    dictionnaire prédéfini commun à tous les blocs (deflate, « preset dictionary »)
    blocs   flux deflate bruts, compressés avec ce dictionnaire ; chacun est une suite
            d'articles triés par clé (ordre des octets UTF-8) : clé, longueur de la valeur
            (varint), valeur

Blocs de 4 Ko : un bloc de 8 Ko se décompresse en deux fois plus de temps, et c'est l'essentiel
d'une recherche. Le dictionnaire, des extraits d'articles pris au hasard, donne d'avance au
compresseur les tournures qui reviennent dans tout le fichier (codes de langue, libellés,
« Plural of ») ; sans lui, des blocs aussi petits se compresseraient mal. Mesuré sur l'anglais :
46,4 Mo en blocs de 8 Ko sans dictionnaire, 42,6 Mo en blocs de 4 Ko avec.

Une clé est le mot normalisé (voir norm). Sa valeur :
    varint n ; n entrées : mot, prononciation, varint g ; g groupes : code de catégorie,
        libellé de catégorie, varint d ; d définitions, varint l ; l langues de traduction :
        code de langue, longueur du bloc en octets (varint, pour le sauter), puis le bloc :
        varint s ; s sens : libellé court du sens, équivalents séparés par « , »
    varint r ; r renvois : lemme, code de catégorie, libellé (« Pluriel de cheval. ») ; un libellé
        qui commence par l'octet 0x01 désigne un renvoi à montrer avant l'article du mot lui-même
        (left → leave avant left « gauche »)
Une chaîne = varint (longueur en octets) + UTF-8.
"""
import argparse
import gzip
import json
import os
import random
import re
import struct
import sys
import time
import unicodedata
import urllib.request
import zlib
from email.utils import parsedate_to_datetime

SOURCES = {
    "en": ("https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl.gz",
           "Wiktionary", "en.wiktionary.org"),
    "fr": ("https://kaikki.org/frwiktionary/Fran%C3%A7ais/kaikki.org-dictionary-Fran%C3%A7ais.jsonl.gz",
           "Wiktionnaire", "fr.wiktionary.org"),
}
LICENSE = "CC BY-SA 4.0"
BLOCK = 4096           # taille visée d'un bloc avant compression (lecture : un seul bloc décompressé)
ZDICT = 32768          # dictionnaire de compression : la fenêtre entière de deflate
MAX_GLOSS = 1200       # caractères : seules les définitions démesurées sont coupées
MAX_SENSE = 90         # libellé du sens d'une traduction
# Langues de traduction de l'app (LANGS dans Translate.kt).
TARGETS = ["fr", "en", "es", "de", "it", "pt", "nl"]
MAX_LINKS = 6

# Catégories sans intérêt pour un lecteur qui pointe un mot dans une page.
SKIP_POS = {"prefix", "suffix", "infix", "interfix", "circumfix", "affix", "symbol", "character",
            "punct", "combining_form", "proverb", "prep_phrase", "phrase", "root", "syllable",
            "typographic variant", "unknown"}
EN_POS = {"noun": "noun", "verb": "verb", "adj": "adjective", "adv": "adverb", "name": "proper noun",
          "pron": "pronoun", "prep": "preposition", "conj": "conjunction", "det": "determiner",
          "intj": "interjection", "num": "numeral", "particle": "particle", "article": "article",
          "contraction": "contraction", "abbrev": "abbreviation", "postp": "postposition"}
# Sens relégués derrière les sens courants.
LATE = {"obsolete", "archaic", "dated", "rare", "historical", "dialectal", "nonstandard",
        "misspelling", "pronunciation-spelling", "uncommon"}
# fr.wiktionary : les marques d'usage arrivent sous forme d'étiquettes anglaises.
FR_TAGS = {"figuratively": "Figuré", "familiar": "Familier", "slang": "Argot", "dated": "Vieilli",
           "obsolete": "Désuet", "archaic": "Archaïsme", "literary": "Littéraire",
           "pejorative": "Péjoratif", "vulgar": "Vulgaire", "rare": "Rare", "broadly": "Par extension",
           "metonymically": "Par métonymie", "especially": "En particulier", "informal": "Informel",
           "colloquial": "Populaire", "analogy": "Par analogie", "ironic": "Ironique",
           "formal": "Soutenu", "poetic": "Poétique", "regional": "Régionalisme"}
FR_PRONOUNS = {"je", "j’", "j'", "tu", "il/elle/on", "nous", "vous", "ils/elles", "que", "qu’", "qu'",
               "qu’il/elle/on", "qu’ils/elles", "qu'il/elle/on", "qu'ils/elles"}
FR_TABLE_TAGS = {"plural", "singular", "masculine", "feminine"}
EN_BAD_FORM_TAGS = {"table-tags", "inflection-template", "class", "canonical", "romanization",
                    "alternative", "error-unknown-tag", "multiword-construction"}
# « Alternative spelling of X. », « Commonwealth standard spelling of color. »
ALT_OF = re.compile(r"^[A-Za-z ,()\-]*\b(?:spelling|form|variant) of ([^\s.,;:()]+)[.;]?$")


# Appels de note restés dans le texte d'une définition : « ^([note 1]) ».
NOTE = re.compile(r"\s*\^\(\[[^\]]*\]\)")


def norm(w):
    """Clé de recherche : même transformation que Dict.norm dans l'app."""
    w = unicodedata.normalize("NFC", w).replace("­", "")
    for c in "’ʼ‘":
        w = w.replace(c, "'")
    for c in "‐‑":
        w = w.replace(c, "-")
    return w.lower()


def varint(n):
    out = bytearray()
    while n >= 0x80:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    out.append(n)
    return bytes(out)


def s(text):
    b = text.encode("utf-8")
    return varint(len(b)) + b


def single(w):
    """Un seul mot, que l'on peut pointer dans une page."""
    return bool(w) and len(w) <= 60 and not any(c.isspace() for c in w) and w[0] != "-" and w[-1] != "-" \
        and any(c.isalpha() for c in w)


def clip(g):
    g = " ".join(NOTE.sub("", g).split())
    if len(g) <= MAX_GLOSS:
        return g
    cut = g.rfind(" ", 0, MAX_GLOSS)
    return g[:cut if cut > MAX_GLOSS // 2 else MAX_GLOSS].rstrip(" ,;:") + "…"


def download(lang, folder):
    url = SOURCES[lang][0]
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, lang + ".jsonl.gz")
    stamp = path + ".date"
    if not os.path.exists(path):
        print(f"[{lang}] téléchargement de {url}", flush=True)
        req = urllib.request.Request(url, headers={"User-Agent": "dict-builder/1.0"})
        with urllib.request.urlopen(req) as r, open(path + ".part", "wb") as f:
            date = r.headers.get("Last-Modified")
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)
        os.rename(path + ".part", path)
        if date:
            with open(stamp, "w") as f:
                f.write(parsedate_to_datetime(date).strftime("%Y-%m-%d"))
    if os.path.exists(stamp):
        return path, open(stamp).read().strip()
    return path, time.strftime("%Y-%m-%d", time.gmtime(os.path.getmtime(path)))


class Builder:
    def __init__(self, lang, targets):
        self.lang = lang
        self.targets = set(targets) - {lang}
        # clé → {mot → [prononciation, {(code, libellé) → [listes de définitions, une par étymologie]}]}
        self.entries = {}
        # clé → {(lemme, code) → libellé}
        self.links = {}
        self.selfform = set()   # clés qui sont une forme d'elles-mêmes (run, participe passé de run)
        self.minor = set()      # (clé, catégorie) dont tous les sens sont vieillis, rares ou dialectaux…
        self.major = set()      # … du moins pour un homonyme : ceux qui ont un sens courant l'emportent

    def link(self, form, lemma, pos, label):
        if not single(form) or not single(lemma):
            return
        k = norm(form)
        if k == norm(lemma) and form == lemma:
            if label:
                self.selfform.add(k)
            return
        d = self.links.setdefault(k, {})
        key = (lemma, pos)
        if label or key not in d:
            if not d.get(key):
                d[key] = label

    def gloss(self, sense):
        if self.lang == "en":
            g = sense.get("raw_glosses") or sense.get("glosses")
            return clip(g[-1]) if g else ""
        g = sense.get("glosses")
        if not g:
            return ""
        marks = [FR_TAGS[t] for t in sense.get("tags", ()) if t in FR_TAGS]
        marks += [t for t in sense.get("raw_tags", ()) if len(t) < 30 and "\n" not in t]
        text = clip(g[-1])
        return f"({', '.join(marks[:3])}) {text}" if marks else text

    def add(self, e):
        word = e.get("word", "")
        pos = e.get("pos", "")
        if not single(word) or pos in SKIP_POS:
            return
        fr = self.lang == "fr"
        early, late = [], []
        for sense in e.get("senses", ()):
            tags = sense.get("tags", ())
            targets = [t.get("word", "") for t in sense.get("form_of", ())] + [t.get("word", "") for t in sense.get("alt_of", ())]
            g = sense.get("glosses")
            plain = clip(g[-1]) if g else ""
            if not targets and not fr and len(plain) < 90:
                m = ALT_OF.match(plain)
                if m:
                    targets = [m.group(1)]
            if targets:
                for t in targets:
                    # « run (verb) » : certaines cibles portent une précision entre parenthèses.
                    self.link(word, t.split(" (")[0].strip(), pos, plain)
                continue
            if "no-gloss" in tags:
                continue
            text = self.gloss(sense)
            if not text or text.startswith("→"):   # « → voir se louer » : simple renvoi, pas un sens
                continue
            bucket = late if LATE.intersection(tags) else early
            if text not in early and text not in late:
                bucket.append(text)
        glosses = early + late
        if not glosses:
            return
        (self.major if early else self.minor).add((norm(word), pos))

        if fr:
            # « Nom commun 2 » : le numéro d'étymologie ne dit rien au lecteur, les homonymes sont réunis.
            label = re.sub(r"\s+\d+$", "", (e.get("pos_title") or pos).lower())
            t = e.get("tags", ())
            if pos in ("noun", "name"):
                m, f = "masculine" in t, "feminine" in t
                label += " masculin et féminin" if m and f else " masculin" if m else " féminin" if f else ""
        else:
            label = EN_POS.get(pos, pos.replace("_", " "))
        ipa = ""
        for snd in e.get("sounds", ()):
            if snd.get("ipa"):
                ipa = snd["ipa"]
                break
        rec = self.entries.setdefault(norm(word), {}).setdefault(word, ["", {}, {}])
        if not rec[0]:
            rec[0] = ipa
        rec[1].setdefault((pos, label), []).append(glosses)
        self.translations(e, rec[2].setdefault((pos, label), {}))

        # Formes fléchies données par le tableau du lemme (utile quand la forme n'a pas sa page).
        for f in e.get("forms", ()):
            form = f.get("form", "")
            tags = set(f.get("tags", ()))
            if fr:
                src = f.get("source", "")
                if src.startswith("Conjugaison:"):
                    parts = form.split()
                    if len(parts) > 1 and all(p in FR_PRONOUNS for p in parts[:-1]):
                        form = parts[-1]
                    # « qu’il mange », « j’aime » : pronom élidé collé au verbe.
                    for p in ("qu’", "qu'", "j’", "j'"):
                        if form.startswith(p) and len(parts) == 1:
                            form = form[len(p):]
                elif src or not tags or not tags <= FR_TABLE_TAGS or f.get("raw_tags"):
                    continue
            elif tags & EN_BAD_FORM_TAGS or not tags:
                continue
            self.link(form, word, pos, "")

    def translations(self, e, into):
        """Traductions de l'entrée, par langue puis par sens : {langue → {sens → [équivalents]}}."""
        if not self.targets:
            return
        found = []
        # en.wiktionary : une partie des traductions est rattachée à son sens, le reste est à
        # l'entrée avec une estimation du sens visé (_dis1). On range tout dans l'ordre des sens.
        for i, sense in enumerate(e.get("senses", ())):
            found += [(i, n, tr) for n, tr in enumerate(sense.get("translations", ()))]
        for n, tr in enumerate(e.get("translations", ())):
            i = 9999
            if "sense_index" in tr:                      # fr.wiktionary
                i = tr["sense_index"]
            elif tr.get("_dis1"):
                p = [int(x) for x in tr["_dis1"].split()]
                i = p.index(max(p))
            found.append((i if isinstance(i, int) else 9999, n, tr))
        found.sort(key=lambda x: x[0])
        for _, _, tr in found:
            lang = tr.get("lang_code") or tr.get("code")
            w = " ".join((tr.get("word") or "").split())
            if lang not in self.targets or not w or len(w) > 60:
                continue
            sense = clip(tr.get("sense") or "")
            if len(sense) > MAX_SENSE:
                cut = sense.rfind(" ", 0, MAX_SENSE)
                sense = sense[:cut if cut > 40 else MAX_SENSE].rstrip(" ,;:") + "…"
            words = into.setdefault(lang, {}).setdefault(sense, [])
            if w not in words:
                words.append(w)

    def value(self, k):
        out = bytearray()
        words = self.entries.get(k, {})
        out += varint(len(words))
        n = 0
        for word, (ipa, groups, trs) in words.items():
            out += s(word) + s(ipa) + varint(len(groups))
            for (pos, label), lists in groups.items():
                # Homonymes de même catégorie : on alterne, pour que le premier sens de chacun soit en tête.
                merged = []
                for i in range(max(len(x) for x in lists)):
                    for x in lists:
                        if i < len(x) and x[i] not in merged:
                            merged.append(x[i])
                out += s(pos) + s(label) + varint(len(merged))
                for g in merged:
                    out += s(g)
                tr = trs.get((pos, label), {})
                out += varint(len(tr))
                for lang in sorted(tr):
                    block = bytearray(varint(len(tr[lang])))
                    for sense, eq in tr[lang].items():
                        block += s(sense) + s(", ".join(eq))
                    out += s(lang) + varint(len(block)) + block
                    self.n_tr += 1
                n += 1
        links = self.resolved.get(k, ())
        out += varint(len(links))
        for lemma, pos, label in links:
            out += s(lemma) + s(pos) + s(label)
        return bytes(out), n, len(links)

    def resolve(self):
        """Ne garde que les renvois qui mènent à une définition ; suit un renvoi intermédiaire."""
        self.resolved = {}
        self.minor -= self.major
        for k, d in self.links.items():
            out, seen = [], set()

            def push(lemma, pos, label):
                if (lemma, pos) not in seen and len(out) < MAX_LINKS:
                    seen.add((lemma, pos))
                    out.append((lemma, pos, label))

            for (lemma, pos), label in d.items():
                lk = norm(lemma)
                if lk in self.entries:
                    if lk != k:
                        push(lemma, pos, label)
                else:
                    # « colours » → « colour » → « color » : de préférence dans la même catégorie.
                    chain = [(l2, p2) for (l2, p2) in self.links.get(lk, {}) if norm(l2) in self.entries and norm(l2) != k]
                    for l2, p2 in [c for c in chain if c[1] == pos] or chain:
                        push(l2, p2, label)
            # Un renvoi documenté (la forme a sa page : « Pluriel de cheval. ») fait foi ; ceux
            # tirés des seuls tableaux de flexion ne servent qu'à défaut.
            documented = [x for x in out if x[2]]
            out = documented or out[:3]
            # Prétérit ou participe anglais homographe d'un autre mot (left, saw, taken) : dans un
            # texte c'est presque toujours le verbe, qui passera devant. Sauf si le mot est aussi
            # son propre participe (run, set) ou si le verbe visé est rare (rin).
            if self.lang == "en" and k not in self.selfform:
                out = [(l, p, "\x01" + lab) if p == "verb" and "past" in lab and (norm(l), p) not in self.minor else (l, p, lab)
                       for l, p, lab in out]
            if out:
                self.resolved[k] = out

    def write(self, path, meta):
        self.resolve()
        self.n_tr = 0
        keys = sorted(set(self.entries) | set(self.resolved), key=lambda x: x.encode("utf-8"))
        plain, firsts = [], []
        cur, first = bytearray(), None
        n_entries = n_links = n_keys = 0

        def flush():
            nonlocal cur, first
            if cur:
                plain.append(bytes(cur))
                firsts.append(first)
            cur, first = bytearray(), None

        for k in keys:
            kb = k.encode("utf-8")
            if len(kb) > 255:
                continue
            v, ne, nl = self.value(k)
            n_entries += ne
            n_links += nl
            n_keys += 1
            if first is None:
                first = kb
            cur += varint(len(kb)) + kb + varint(len(v)) + v
            if len(cur) >= BLOCK:
                flush()
        flush()

        # Dictionnaire de compression : 64 extraits de 512 octets, pris au hasard dans les blocs
        # (tirage fixe : deux fabrications donnent le même fichier). Essayé aussi : des débuts de
        # blocs (des clés surtout, moins utile), des articles entiers, les n-grammes les plus
        # fréquents ; les extraits pris au hasard font aussi bien ou mieux, bien plus simplement.
        rnd = random.Random(1)
        zdict = bytearray()
        for raw in rnd.sample(plain, min(64, len(plain))):
            at = rnd.randrange(max(1, len(raw) - 512))
            zdict += raw[at:at + 512]
        zdict = bytes(zdict[-ZDICT:])
        blocks = []
        for raw in plain:
            c = zlib.compressobj(9, zlib.DEFLATED, -15, zdict=zdict)
            blocks.append(c.compress(raw) + c.flush())

        meta = dict(meta, keys=n_keys, entries=n_entries, forms=n_links, translated=self.n_tr)
        mb = json.dumps(meta, ensure_ascii=False).encode("utf-8")
        pool = bytearray()
        pool_offs = []
        for f in firsts:
            pool_offs.append(len(pool))
            pool += struct.pack("<H", len(f)) + f
        meta_off = 48
        index_off = meta_off + len(mb)
        pool_off = index_off + 12 * (len(blocks) + 1)
        zdict_off = pool_off + len(pool)
        pos = zdict_off + len(zdict)
        index = bytearray()
        for i, b in enumerate(blocks):
            index += struct.pack("<III", pool_offs[i], pos, len(plain[i]))
            pos += len(b)
        index += struct.pack("<III", len(pool), pos, 0)
        with open(path + ".part", "wb") as f:
            f.write(b"BIODICT3")
            f.write(struct.pack("<IIIIIIIIII", len(blocks), n_keys, n_entries, n_links, meta_off, len(mb), index_off, pool_off,
                                zdict_off, len(zdict)))
            f.write(mb)
            f.write(index)
            f.write(pool)
            f.write(zdict)
            for b in blocks:
                f.write(b)
        os.replace(path + ".part", path)
        return meta, pos


def main():
    ap = argparse.ArgumentParser(description="Fabrique bioread-dict-<langue>.bin à partir du Wiktionnaire (kaikki.org).")
    ap.add_argument("langs", nargs="*", default=["en", "fr"], help="langues : en, fr")
    ap.add_argument("--out", default="dict-out", help="dossier de sortie (téléchargements compris)")
    ap.add_argument("--targets", default=",".join(TARGETS),
                    help="langues des traductions embarquées, séparées par des virgules (vide : aucune)")
    ap.add_argument("--name", default="bioread-dict-{lang}.bin", help="nom du fichier produit")
    a = ap.parse_args()
    for lang in a.langs:
        if lang not in SOURCES:
            sys.exit(f"langue inconnue : {lang}")
        src, date = download(lang, os.path.join(a.out, "dl-" + lang))
        t = time.time()
        b = Builder(lang, [x for x in a.targets.split(",") if x])
        n = 0
        with gzip.open(src, "rt", encoding="utf-8") as f:
            for line in f:
                e = json.loads(line)
                if e.get("lang_code", lang) == lang:
                    b.add(e)
                n += 1
                if n % 200000 == 0:
                    print(f"[{lang}] {n} articles lus…", flush=True)
        name, site = SOURCES[lang][1], SOURCES[lang][2]
        out = os.path.join(a.out, a.name.format(lang=lang))
        meta, size = b.write(out, {"lang": lang, "source": name, "site": site, "via": "kaikki.org (wiktextract)",
                                   "license": LICENSE, "date": date,
                                   "targets": sorted(b.targets)})
        print(f"[{lang}] {out} : {size / 1e6:.1f} Mo, {meta['keys']} clés, {meta['entries']} entrées, "
              f"{meta['forms']} renvois de formes, {meta['translated']} blocs de traductions "
              f"({','.join(meta['targets']) or 'aucune'}), {time.time() - t:.0f} s", flush=True)


if __name__ == "__main__":
    main()
