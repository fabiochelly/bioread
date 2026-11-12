#!/usr/bin/env python3
"""
Fabrique la table des mots difficiles de Bioread (traduction sous les mots difficiles) :

    uv run --with wordfreq --with openpyxl --with nltk tools/make_wordwise.py --out dict-out

Produit app/src/main/assets/wordwise-en-fr.bin. Pour chaque forme anglaise (ran, running…), la
table donne son rang de difficulté et une traduction française courte par catégorie.

RANG DE DIFFICULTÉ. Le rang est une taille de vocabulaire : un lecteur qui connaît environ N mots
anglais connaît, en gros, ceux de rang inférieur à N. Le curseur de l'app est ce N. Le classement
combine quatre mesures, par mot de base (toutes ses formes fléchies réunies) :

  1. La fréquence de la famille fléchie (run, runs, ran, running), moyenne de deux sources :
     wordfreq (livres, presse, Wikipédia, sous-titres, web) et SUBTLEX-US (sous-titres de
     films et séries, l'anglais du quotidien, celui qu'un apprenant rencontre le plus).
  2. Le taux de connaissance mesuré chez des locuteurs non natifs, environ 200 réponses par
     mot sur 61 850 mots (Brysbaert, Keuleers & Mandera 2020, « Which words do English
     non-native speakers know? »). Il est converti en fréquence équivalente (régression
     logistique sur la fréquence) et mêlé à la fréquence ; son poids décroît quand il sature,
     au-delà de 97 % (les mots connus de tous ne se départagent que par le bruit des temps de
     réponse).
  3. L'âge d'acquisition (Kuperman et al. 2012, 25 000 mots) : à fréquence égale, un mot appris
     tôt (milk, apple) est mieux connu qu'un mot appris tard (portfolio).
  4. Les dérivés transparents (unhappily ← unhappy, d'après l'étymologie du Wiktionnaire) ne
     sont jamais beaucoup plus difficiles que leur base.

Le rang se compte sur une population de vrais mots (ceux de l'étude et les mots de base
fréquents qui y manquent) : les milliers de dérivés théoriques du Wiktionnaire (antipatent) ne
gonflent pas l'échelle.

TRADUCTION. Pour chaque mot de base et catégorie : le premier équivalent français du sens
principal (Wiktionnaire anglais), sauf s'il est nettement plus rare qu'un autre ; à défaut,
les pages françaises qui traduisent vers ce mot. Quand WordNet désigne un sens nettement plus
courant que les autres (comptes du corpus annoté SemCor), c'est le tableau de traductions du
Wiktionnaire qui lui correspond qui sert, et l'équivalent que donne aussi le wordnet français
(WOLF, Open Multilingual Wordnet) passe devant. Enfin tools/wordwise-fixes-<langue>.tsv, fait
à la main d'après des romans récents, l'emporte sur tout le reste. Un mot est marqué TRANSPARENT si son sens
principal se dit presque pareil en français (melancholy → mélancolie) : l'app ne le traduit
pas. Un faux ami ne l'est jamais, puisque sa traduction ne lui ressemble pas
(library → bibliothèque, eventually → finalement).

Format (entiers little-endian), lu par WordWise.kt :

    0   8   magie « BIOWW002 » (le chiffre est la version du format)
    8   4   nombre de formes ; 12 : nombre de blocs
    16  4   position de l'index des blocs ; 20 : des blocs ; 24 : des fiches ; 28 : des gloses
    32  4   nombre de gloses ; 36 : position des métadonnées (JSON UTF-8) ; 40 : leur longueur
    44  4   formes par bloc (16)
    index   par bloc, sa position (relative au début des blocs)
    blocs   les formes dans l'ordre de leurs octets UTF-8, par blocs de 16. Chacune : octet p
            (nombre d'octets en commun avec la forme précédente, 0 pour la première d'un bloc),
            varint longueur de la suite, la suite (UTF-8), varint position de sa fiche (relative
            au début des fiches)
    fiches  varint rang, octet n (0 : mot courant sans traduction, didn't), puis n lectures :
            octet (catégorie 1 nom, 2 verbe, 3 adjectif, 4 adverbe, 5 préposition ; +8 si transparent),
            varint rang de cette lecture, varint position de sa glose (relative au début des gloses).
            Une fiche partagée par plusieurs formes (assess, assesses) n'est écrite qu'une fois.
    gloses  les chaînes (varint longueur + UTF-8) ; la première, vide, sert aux lectures sans glose

Sources et licences : Wiktionary (CC BY-SA 4.0, via kaikki.org), wordfreq (CC BY-SA 4.0),
SUBTLEX-US (Brysbaert & New 2009), L2 word knowledge (Brysbaert, Keuleers & Mandera 2020,
CC BY-NC-SA 4.0), AoA norms (Kuperman, Stadthagen-Gonzalez & Brysbaert 2012). La table produite
hérite de la clause « pas d'usage commercial » des données de l'étude L2.
"""
import argparse
import bisect
import gzip
import io
import json
import math
import os
import re
import struct
import sys
import time
import unicodedata
import urllib.request
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_dict import download, norm, varint  # noqa: E402

try:
    import openpyxl
    from wordfreq import get_frequency_dict, zipf_frequency
except ImportError:
    sys.exit("Il faut wordfreq et openpyxl : uv run --with wordfreq --with openpyxl tools/make_wordwise.py")

DATA = {
    "subtlex": "https://www.ugent.be/pp/experimentele-psychologie/en/research/documents/subtlexus/subtlexus2.zip",
    "subtlex_pos": "https://www.ugent.be/pp/experimentele-psychologie/en/research/documents/subtlexus/subtlexus1.zip",
    "l2": "https://osf.io/download/c3m4e/",
    "aoa": "https://static-content.springer.com/esm/art%3A10.3758%2Fs13428-018-1077-9/MediaObjects/13428_2018_1077_MOESM1_ESM.xlsx",
}

# Réglages du classement (voir l'en-tête) ; les valeurs ont été choisies sur des romans.
L2_WEIGHT = 0.4        # poids maximal du taux de connaissance mesuré (participants surtout néerlandophones : atténué)
L2_FULL = 0.975        # au-delà, la mesure sature et ne compte plus
SUB_WEIGHT = 0.5       # part des sous-titres dans la fréquence
AOA_K = 0.07           # zipf gagné par année d'acquisition plus précoce que 10 ans
DERIVED = 0.3          # un dérivé transparent : au plus 0,3 zipf plus rare que sa base (unsurprisingly ← surprisingly)
MIN_RANK = 2500        # en deçà, aucun mot n'est gardé : le curseur de l'app commence à 3 000

# Mots que la table ne connaît pas (les mots d'usage vieilli, l'argot) : écartés des familles.
OLD = {"archaic", "obsolete", "dated", "dialectal", "nonstandard", "eye-dialect", "pronunciation-spelling",
       "misspelling", "rare", "uncommon", "colloquial", "slang", "informal", "Scotland", "UK-dialectal",
       "Northern-England", "Southern-US", "humorous"}
SKIP_POS = {"name", "prefix", "suffix", "infix", "affix", "symbol", "character", "punct", "phrase", "prep_phrase",
            "proverb", "abbrev", "romanization", "combining_form", "interfix", "circumfix", "root", "syllable",
            "unknown", "letter"}
POS = {"noun": 1, "verb": 2, "adj": 3, "adv": 4, "prep": 5}
FR_POS = {"noun": 1, "verb": 2, "adj": 3, "adv": 4, "prep": 5}
BAD_FORM = {"table-tags", "inflection-template", "class", "canonical", "romanization", "error-unknown-tag",
            "multiword-construction", "alternative"}
AFFIX = {"suffix", "prefix", "affix", "af", "compound", "confix", "com"}
SUFFIXES = {"ness", "ly", "less", "ful", "er", "able", "ible", "ment", "ish", "like", "hood", "ship", "y", "ity", "ist",
            "ism", "ize", "ise", "ation", "ed", "ing", "al", "ward", "wards", "dom", "ee", "ess", "fully", "lessly",
            "ily", "ingly", "edly"}
PREFIXES = {"un", "re", "dis", "non", "over", "under", "out", "mis", "pre", "self", "anti", "co", "sub", "super",
            "inter", "semi", "half", "counter", "mid", "fore", "multi", "post", "ex"}
# Terminaisons qui se correspondent d'une langue à l'autre : nation/nation, curiosity/curiosité…
ENDINGS = [("e", "er"), ("e", "ir"), ("e", "re"), ("itize", "iser"), ("ently", "emment"), ("antly", "amment"), ("ically", "iquement"), ("ally", "ellement"), ("ly", "ement"), ("ated", "e"), ("ous", "ent"), ("al", ""),
           ("y", "ier"), ("tion", "tion"), ("sion", "sion"), ("ity", "ite"), ("ty", "te"), ("ous", "eux"), ("ous", "euse"),
           ("ly", "ment"), ("ic", "ique"), ("ical", "ique"), ("ize", "iser"), ("ise", "iser"), ("ate", "er"),
           ("ive", "if"), ("ism", "isme"), ("ist", "iste"), ("or", "eur"), ("er", "eur"), ("ary", "aire"),
           ("ory", "oire"), ("y", "ie"), ("e", ""), ("", "e"), ("ance", "ance"), ("ence", "ence"), ("al", "el"),
           ("ment", "ment"), ("ude", "ude"), ("ure", "ure"), ("ed", "e"), ("ing", "er"), ("ish", "ir"), ("ct", "ct"),
           ("ble", "ble"), ("", "er"), ("", "ir"), ("", "re"), ("le", "e"), ("ain", "ain")]
MAX_GLOSS = 28


def fetch(url, path):
    if not os.path.exists(path):
        print(f"téléchargement de {url}", flush=True)
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (dict-builder)"})
        with urllib.request.urlopen(req) as r, open(path + ".part", "wb") as f:
            f.write(r.read())
        os.rename(path + ".part", path)
    return path


def word_ok(w):
    return bool(w) and w[0].isalpha() and all(c.isalpha() or c in "-'" for c in w)


def strip(s):
    return "".join(c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) != "Mn")


def lev(a, b):
    if len(a) < len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def spell(w):
    """Orthographes équivalentes d'une langue à l'autre : eradicate/éradiquer, pajama/pyjama."""
    for x, y in (("qu", "c"), ("ph", "f"), ("k", "c"), ("y", "i")):
        w = w.replace(x, y)
    return w


def close(ra, rb):
    """Deux racines presque identiques. Sur une racine courte, une seule lettre change le mot
    (groan/grogn) : l'identité est alors exigée."""
    d = lev(ra, rb)
    if min(len(ra), len(rb)) <= 5:
        return d == 0
    return d / max(len(ra), len(rb)) <= 0.2


def similar(en, fr):
    """Vrai si le mot étranger se reconnaît dans le mot anglais (même mot, à la terminaison près)."""
    a, b = strip(en), strip(fr)
    if " " in b or len(a) < 4:
        return False
    # Le s anglais devant une consonne est devenu un accent circonflexe : haste/hâte, forest/forêt.
    if "s" in a and fr != b:
        a2 = re.sub(r"s(?=[bcdfgjklmnpqtvz])", "", a)
        if a2 == b or spell(a2) == spell(b):
            return True
    if a == b or spell(a) == spell(b):
        return True
    if close(spell(a), spell(b)):
        return True
    for x, y in ENDINGS:
        if a.endswith(x) and b.endswith(y):
            ra, rb = a[:len(a) - len(x)], b[:len(b) - len(y)]
            if len(ra) >= 3 and len(rb) >= 3 and close(spell(ra), spell(rb)):
                return True
    return False


def clean(w):
    w = re.sub(r"^\((se|s')\)\s*", r"\1 ", w)       # « (se) tapir » : le pronom fait partie du verbe
    # « donner (sur) » : une particule entre parenthèses fait partie de la locution.
    w = re.sub(r"\((sur|à|de|en|dans|par|pour|avec|contre|se)\)", r"\1", w)
    w = re.sub(r"\s*\([^)]*\)", "", w)
    return " ".join(w.split()).replace("s' ", "s'")


# Marques d'un sens grossier ou blessant, en anglais comme dans le Wiktionnaire français.
RUDE = {"vulgar", "offensive", "derogatory", "pejorative", "slur", "ethnic-slur", "ethnic", "insult",
        "disparaging", "racist", "homophobic"}


# Insultes et mots grossiers que le Wiktionnaire ne marque pas sur tous leurs sens : jamais la
# traduction d'un mot anglais neutre (relevés en relisant des romans : wap → rital, flamer → pédé).
INSULTS = set("""rital pédé pédale tapette tarlouze gouine enculé enculer connard connasse con conne couillon
pute putain salope salaud bâtard nègre négro bougnoule bicot raton youpin youtre chinetoque bridé
niakoué boche fritz gogole triso sodomite bite chatte foutre merde chier niquer nique bordel cul
enfoiré fdp pétasse pouffiasse grognasse trouduc""".split())


class Lemma:
    __slots__ = ("senses", "old", "tr", "parts", "forms", "rude")

    def __init__(self):
        self.senses = 0
        self.old = 0
        self.rude = False
        self.tr = {}       # libellé du sens → équivalents, dans l'ordre des sens
        self.parts = []
        self.forms = []


def read_english(src, target):
    """Une passe sur l'extraction anglaise : lemmes, formes, traductions vers [target]."""
    lemmas, links = {}, []
    n = 0
    with gzip.open(src, "rt", encoding="utf-8") as f:
        for line in f:
            n += 1
            if n % 300000 == 0:
                print(f"[en] {n} articles lus…", flush=True)
            e = json.loads(line)
            if e.get("lang_code") != "en":
                continue
            w, pos = e.get("word", ""), e.get("pos", "")
            if not word_ok(w) or pos in SKIP_POS:
                continue
            d = lemmas.get((w, pos)) or lemmas.setdefault((w, pos), Lemma())
            senses = e.get("senses", ()) or ()
            for s in senses:
                tags = set(s.get("tags", ()) or ())
                targets = [t.get("word", "") for t in (s.get("form_of") or ())] + [t.get("word", "") for t in (s.get("alt_of") or ())]
                if targets:
                    for t in targets:
                        links.append((w, t.split(" (")[0].strip(), pos, bool(tags & OLD)))
                    continue
                if "no-gloss" in tags:
                    continue
                d.senses += 1
                if tags & OLD:
                    d.old += 1
                if tags & RUDE:
                    d.rude = True
            # Traductions rangées dans l'ordre des sens (comme make_dict.py) : celles posées à
            # l'entrée portent une estimation du sens visé (_dis1).
            found = []
            def late_sense(s):
                # Sens vieilli, rare ou d'un domaine (mathématiques, phonétique, mines…) : ses
                # traductions passent après celles des sens courants (cusp, mouthing, slack).
                return bool(set(s.get("tags", ()) or ()) & (OLD | {"rare"})) or bool(s.get("topics"))
            for i, s in enumerate(senses):
                late = late_sense(s)
                found += [(i, late, k, tr) for k, tr in enumerate(s.get("translations", ()) or ())]
            for k, tr in enumerate(e.get("translations", ()) or ()):
                i = 9999
                if tr.get("_dis1"):
                    p = [int(x) for x in tr["_dis1"].split()]
                    i = p.index(max(p))
                late = i < len(senses) and late_sense(senses[i])
                found.append((i, late, k, tr))
            found.sort(key=lambda x: (x[1], x[0], x[2]))
            for _, _, _, tr in found:
                if (tr.get("lang_code") or tr.get("code")) != target:
                    continue
                t = clean(tr.get("word") or "")
                if not t or len(t) > MAX_GLOSS:
                    continue
                eq = d.tr.setdefault(tr.get("sense") or "", [])
                if t not in eq:
                    eq.append(t)
            for t in e.get("etymology_templates", ()) or ():
                if t.get("name") in AFFIX:
                    a = t.get("args", {})
                    parts = [a[k] for k in sorted(a, key=lambda x: int(x) if x.isdigit() else 99) if k.isdigit() and k != "1"]
                    d.parts.append((t["name"], parts))
            for fm in e.get("forms", ()) or ():
                tags = set(fm.get("tags", ()) or ())
                if not tags or tags & BAD_FORM or not word_ok(fm.get("form", "")):
                    continue
                d.forms.append((fm["form"], bool(tags & OLD)))
    return lemmas, links


def read_reverse(src, target):
    """Pages de la langue cible qui traduisent vers un mot anglais : mot anglais → [(mot, catégorie)].
    Rend aussi les catégories de chaque mot de la langue cible (mot → {catégories})."""
    rev = {}
    cats = {}
    rude = {}   # mot → (sens grossiers ou blessants, sens en tout)
    with gzip.open(src, "rt", encoding="utf-8") as f:
        for line in f:
            e = json.loads(line)
            if e.get("lang_code", target) != target:
                continue
            w, pos = e.get("word", ""), e.get("pos", "")
            if pos not in FR_POS or " " in w.strip() or len(w) > MAX_GLOSS:
                continue
            cats.setdefault(w, set()).add(FR_POS[pos])
            r = rude.setdefault(w, [0, 0])
            for s in e.get("senses", ()) or ():
                t = set(s.get("tags", ()) or ()) | {x.lower() for x in (s.get("raw_tags", ()) or ())}
                r[1] += 1
                # Marques fortes seulement : « péjoratif », très répandu, écarterait banlieue ou flic.
                if t & {"vulgar", "offensive", "slur", "ethnic-slur", "insult", "racist", "homophobic",
                        "injurieux", "vulgaire", "insultant", "raciste", "xénophobe", "homophobe"}:
                    r[0] += 1
            trs = list(e.get("translations", ()) or ())
            for s in e.get("senses", ()) or ():
                trs += s.get("translations", ()) or ()
            for t in trs:
                if (t.get("lang_code") or t.get("code")) == "en" and t.get("word"):
                    lst = rev.setdefault(t["word"], [])
                    if (w, FR_POS[pos]) not in lst:
                        lst.append((w, FR_POS[pos]))
    # Mot dont tous les sens sont grossiers ou injurieux (rital, enculé) : jamais une traduction,
    # sauf d'un mot anglais lui-même grossier.
    bad = {w for w, (n, t) in rude.items() if t and n >= t} | INSULTS
    return rev, cats, bad


# Adverbe français tiré de l'adjectif (agile → agilement, lent → lentement) : gardé seulement
# s'il existe dans la langue (fréquence mesurée).
def adverb_fr(a):
    for end, rep in (("ant", "amment"), ("ent", "emment"), ("if", "ivement"), ("eux", "eusement"),
                     ("el", "ellement"), ("en", "ennement"), ("on", "onnement")):
        if a.endswith(end):
            return a[:-len(end)] + rep
    if a.endswith(("e", "é", "i", "u")):
        return a + "ment"
    return a + "ement"


# Fin d'un adverbe anglais en -ly et celle de son adjectif (nimbly → nimble, shakily → shaky).
LY = (("ically", ("ical", "ic")), ("ally", ("al",)), ("ably", ("able",)), ("ibly", ("ible",)), ("ily", ("y",)),
      ("ly", ("", "le", "e")))


STOP = set("a an the of to in on or and as at by for with from into that this which who whose what be is are "
           "being been was were it its one's someone something somebody one any some especially usually often "
           "very more most not no make made do does done have has having etc".split())


def content(text):
    """Mots pleins d'une définition, au singulier approximatif."""
    return {w.rstrip("s") for w in re.findall(r"[a-z]+", text.lower()) if w not in STOP and len(w) > 2}


WN = None


def wordnet_sense(word, pos, target):
    """Sens le plus courant d'après WordNet (comptes du corpus annoté SemCor), quand il domine
    nettement : (mots pleins de sa définition et de ses synonymes, équivalents du wordnet de la
    langue cible). None sans WordNet, ou quand aucun sens ne domine."""
    global WN
    if WN is None:
        try:
            from nltk.corpus import wordnet
            wordnet.synsets("test")
            WN = wordnet
        except Exception:
            WN = False
    if not WN:
        return None
    p = {"noun": "n", "verb": "v", "adj": "a", "adv": "r"}.get(pos)
    if p is None:
        return None
    ss = WN.synsets(word, pos=p)
    if not ss:
        return None
    def count(s):
        return sum(l.count() for l in s.lemmas() if l.name().lower() == word)
    counts = [count(s) for s in ss]
    if len(ss) > 1 and not (counts[0] >= 2 and counts[0] >= 2 * max(counts[1:])):
        return None
    s = ss[0]
    words = content(s.definition()) | {n.lower() for n in s.lemma_names() if n.lower() != word}
    lang = {"fr": "fra", "es": "spa", "it": "ita", "pt": "por", "nl": "nld"}.get(target)
    lemmas = set()
    if lang:
        try:
            lemmas = {n.replace("_", " ") for n in s.lemma_names(lang)}
        except Exception:
            pass
    return words, lemmas


class Glosser:
    def __init__(self, target, rev, lemmas, fixed, rude=frozenset()):
        self.target = target
        self.rev = rev
        self.L = lemmas
        self.fixed = fixed
        self.rude = rude
        self.cache = {}

    def freq(self, w):
        # Fréquence dans la langue cible, pénalisée pour les locutions (finalement plutôt que
        # « à la fin ») ; le pronom d'un verbe pronominal ne compte pas.
        w = re.sub(r"^(se |s')", "", w)
        k = self.cache.get(w)
        if k is None:
            k = self.cache[w] = zipf_frequency(w, self.target, "large") - 0.6 * w.count(" ")
        return k

    def gloss(self, word, pos, d):
        """(glose, transparent) pour le lemme [word] de catégorie [pos], ou (None, False)."""
        first = next((eq for eq in d.tr.values() if eq), []) if d else []
        # Pas de mot grossier ou blessant pour traduire un mot qui ne l'est pas (wap → rital, mongo).
        polite = not (d and d.rude)
        if polite and self.rude:
            first = [w for w in first if w not in self.rude]
        fixed = self.fixed.get((word, pos))
        if fixed is not None:
            # Correction faite à la main (tools/wordwise-fixes.tsv) : « - » = jamais traduit.
            return (None, False) if fixed == "-" else (fixed, False)
        wnet = wordnet_sense(word, pos, self.target)
        if wnet and d:
            # Sens principal d'après WordNet : le tableau de traductions dont le libellé lui ressemble
            # le plus, s'il y en a un qui lui ressemble vraiment (deux mots pleins en commun).
            words, lemmas = wnet
            best, score = None, 1
            for label, eq in d.tr.items():
                common = len(words & content(label))
                if eq and common > score:
                    best, score = eq, common
            if best is not None:
                first = best
            # Un équivalent que le wordnet français donne aussi pour ce sens passe devant.
            hit = [w for w in first if w in lemmas]
            if hit:
                first = hit + [w for w in first if w not in hit]
        back = [w for w, p in self.rev.get(word, ()) if p == POS[pos] and w not in first
                and not (polite and w in self.rude)]
        pool = first + back
        if not pool and pos == "adv" and word.endswith("ly"):
            # Adverbe sans traduction (nimbly, expectantly) : celui de son adjectif, s'il existe en français.
            for suf, ends in LY:
                if not word.endswith(suf):
                    continue
                for e in ends:
                    b = word[:len(word) - len(suf)] + e
                    if (b, "adj") in self.L:
                        g, _ = self.gloss(b, "adj", self.L[(b, "adj")])
                        if g and " " not in g:
                            adv = adverb_fr(g)
                            if zipf_frequency(adv, self.target, "large") >= 1.0:
                                return adv, similar(word, adv)
                break
            return None, False
        if not pool:
            return None, False
        own = first or back
        # Le premier équivalent listé, sauf s'il est nettement plus rare qu'un autre (anguleux →
        # maigre) : le plus fréquent tout court serait souvent un mot à tout faire (pain → peine).
        best = own[0]
        top = max(own, key=self.freq)
        if self.freq(top) >= self.freq(best) + 1.0:
            best = top
        # Traduction très rare (crapahuter) : une page de la langue cible en propose peut-être une plus courante.
        if self.freq(best) < 2.0 and back:
            b2 = max(back, key=self.freq)
            if self.freq(b2) > self.freq(best) + 0.3:
                best = b2
        # Le test ne porte que sur le sens principal du Wiktionnaire anglais : les pages de la
        # langue cible mêlent faux amis et vrais équivalents (librairie y traduit library).
        f0 = self.freq(best)
        trans = any(similar(word, w) and self.freq(w) >= f0 - 1.5 for w in (first or back))
        return best, trans


def build(a):
    t0 = time.time()
    data = os.path.join(a.out, "wordwise-data")
    os.makedirs(data, exist_ok=True)
    src_en, date = download("en", os.path.join(a.out, "dl-en"))
    lemmas, links = read_english(src_en, a.target)
    rev, frcats, rude_fr = {}, {}, set()
    if a.target == "fr":
        src_fr, _ = download("fr", os.path.join(a.out, "dl-fr"))
        rev, frcats, rude_fr = read_reverse(src_fr, "fr")
        print(f"{len(rude_fr)} mots grossiers ou blessants écartés des traductions", flush=True)
    print(f"lu en {time.time() - t0:.0f} s : {len(lemmas)} entrées, {len(links)} renvois, {len(rev)} traductions inverses", flush=True)

    # ---- lemmes et familles fléchies (sans les formes vieillies : spake n'hérite pas de speak)
    L = {k: d for k, d in lemmas.items() if d.senses > 0 and k[0][0].islower()}
    words = {w for w, _ in L}
    fam = {}
    of = {}
    def add(form, lemma, pos):
        fam.setdefault(lemma, set()).add(form)
        of.setdefault(norm(form), set()).add((lemma, pos))
    for (w, pos) in L:
        add(w, w, pos)
    for form, lemma, pos, old in links:
        if not old and word_ok(form) and form[0].islower() and lemma in words:
            add(form, lemma, pos)
    for (w, pos), d in L.items():
        for f, old in d.forms:
            if not old and f[0].islower():
                add(f, w, pos)

    # ---- fréquences : wordfreq et sous-titres, par famille
    wf = get_frequency_dict("en", "large")
    zsub = io.TextIOWrapper(zipfile.ZipFile(fetch(DATA["subtlex"], os.path.join(data, "subtlexus2.zip"))).open("SUBTLEXus74286wordstextversion.txt"), encoding="latin-1")
    sub = {}
    for line in list(zsub)[1:]:
        c = line.split("\t")
        sub[c[0].lower()] = sub.get(c[0].lower(), 0.0) + float(c[5])           # par million de mots
    # Catégories d'une forme dans les sous-titres (Brysbaert, New & Keuleers 2012) : pink est un
    # adjectif à 95 %, till une conjonction ; saw, un verbe.
    SUBPOS = {"Noun": 1, "Verb": 2, "Adjective": 3, "Adverb": 4, "Preposition": 5}
    zp = zipfile.ZipFile(fetch(DATA["subtlex_pos"], os.path.join(data, "subtlexus1.zip")))
    wb = openpyxl.load_workbook(io.BytesIO(zp.read(zp.namelist()[0])), read_only=True)
    shares = {}
    for r in wb.worksheets[0].iter_rows(min_row=2, values_only=True):
        if not isinstance(r[0], str) or not isinstance(r[12], str):
            continue
        cats = r[12].split(".")
        counts = [int(x) for x in str(r[13]).split(".") if x.isdigit()]
        if len(counts) != len(cats) or not sum(counts):
            continue
        t = float(sum(counts))
        sh = [0.0] * 6
        for c, n in zip(cats, counts):
            sh[SUBPOS.get(c, 0)] += n / t
        shares[r[0].lower()] = sh

    famz = {}
    for w, forms in fam.items():
        f = sum(wf.get(x.lower(), 0.0) for x in forms)
        z = math.log10(f * 1e9) if f > 0 else 0.0
        fs = sum(sub.get(x.lower(), 0.0) for x in forms)
        if fs > 0:
            z = (1 - SUB_WEIGHT) * z + SUB_WEIGHT * (math.log10(fs) + 3)
        famz[w] = z

    # ---- dérivés transparents, d'après l'étymologie
    derived = {}
    for w in famz:
        best = None
        for p in POS:
            d = L.get((w, p))
            if not d:
                continue
            for kind, parts in d.parts:
                parts = [x.strip() for x in parts if x and "<" not in x and "=" not in x]
                if len(parts) != 2:
                    continue
                x, y = parts
                if kind in ("suffix", "af", "affix") and y.lstrip("-") in SUFFIXES and x.lower() in famz:
                    c = x.lower()
                elif kind in ("prefix", "af", "affix") and x.rstrip("-") in PREFIXES and y.lower() in famz:
                    c = y.lower()
                else:
                    continue
                if best is None or famz[c] > famz[best]:
                    best = c
        if best and famz[best] - DERIVED > famz[w]:
            derived[w] = best

    # ---- mesures sur les apprenants et âge d'acquisition
    acc = {}
    wb = openpyxl.load_workbook(fetch(DATA["l2"], os.path.join(data, "l2-rankings.xlsx")), read_only=True)
    for r in wb["Words"].iter_rows(min_row=2, values_only=True):
        if isinstance(r[0], str) and isinstance(r[3], (int, float)) and r[0].strip().islower():
            acc[r[0].strip()] = float(r[3])
    aoa = {}
    wb = openpyxl.load_workbook(fetch(DATA["aoa"], os.path.join(data, "prevalence-aoa.xlsx")), read_only=True)
    for r in wb["Sheet1"].iter_rows(min_row=2, values_only=True):
        if isinstance(r[0], str) and isinstance(r[11], (int, float)):
            aoa[r[0].strip().lower()] = float(r[11])

    def logit(p):
        p = min(max(p, 0.02), 0.985)
        return math.log(p / (1 - p))
    xs, ys = [], []
    for w, p in acc.items():
        if famz.get(w) and 0.15 < p < 0.96:
            xs.append(famz[w]); ys.append(logit(p))
    mx, my = sum(xs) / len(xs), sum(ys) / len(ys)
    slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
    inter = my - slope * mx
    print(f"connaissance L2 : logit(p) = {inter:.2f} + {slope:.2f}·zipf ({len(xs)} mots)", flush=True)

    def score(w):
        z = famz.get(w, 0.0)
        if w in aoa:
            z += AOA_K * (10.0 - aoa[w])
        p = acc.get(w)
        if p is None:
            return z
        k = L2_WEIGHT * min(1.0, max(0.0, (L2_FULL - p) / 0.06))
        return k * (logit(p) - inter) / slope + (1 - k) * z

    sc = {w: score(w) for w in set(famz) | set(acc)}
    for w, base in derived.items():
        if w not in acc and base in sc:
            sc[w] = max(sc[w], sc[base] - DERIVED)
    inflected = {f for f, lem in of.items() if any(l != f for l, _ in lem)}
    pop = sorted((s for w, s in sc.items() if s > 0 and (w in acc or (famz.get(w, 0) >= 3.0 and w not in derived and w not in inflected))), reverse=True)
    neg = [-s for s in pop]
    def rank(s):
        return min(65000, bisect.bisect_right(neg, -s) + 1 if s > 0 else 65000)
    lrank = {w: rank(s) for w, s in sc.items()}

    # ---- gloses et fiches par forme
    # Corrections faites à la main : forme, catégorie, traduction (ou « - » : jamais traduit). Une
    # correction notée sur une forme (assessed) vaut pour son lemme, donc pour toutes ses formes.
    fixed, by_form = {}, {}
    fx = os.path.join(os.path.dirname(os.path.abspath(__file__)), f"wordwise-fixes-{a.target}.tsv")
    if os.path.exists(fx):
        for line in open(fx, encoding="utf-8"):
            c = line.rstrip("\n").split("\t")
            if len(c) < 3 or line.startswith("#") or c[1] not in POS:
                continue
            f, p, g = norm(c[0].strip()), c[1], c[2].strip()
            by_form[(f, p)] = g
            lems = [l for l, q in of.get(f, ()) if q == p] or [f]
            for l in lems:
                fixed[(l, p)] = g
        print(f"{len(by_form)} corrections faites à la main", flush=True)
    os.environ.setdefault("NLTK_DATA", os.path.join(data, "nltk"))
    try:
        import nltk
        for pkg in ("wordnet", "omw-2.0"):
            nltk.download(pkg, download_dir=os.environ["NLTK_DATA"], quiet=True)
    except ImportError:
        print("sans nltk : le sens principal n'est pas choisi d'après WordNet", flush=True)
    gl = Glosser(a.target, rev, L, fixed, rude_fr)
    glosses, gid = [], {}
    def gloss_id(g):
        if g not in gid:
            gid[g] = len(glosses)
            glosses.append(g)
        return gid[g]
    gloss_id("")    # glose n° 0 : aucune (lecture transparente, jamais montrée)
    records = {}
    for f, lem in of.items():
        sh = shares.get(f)
        # Surtout un mot grammatical dans l'usage (till, still) : jamais traduit.
        if sh and sum(sh[1:]) < 0.35:
            continue
        reads = {}
        for (l, p) in lem:
            if p not in POS:
                continue
            g, trans = gl.gloss(l, p, L.get((l, p)))
            if g:
                share = sh[POS[p]] if sh else 0.0
                old = reads.get(POS[p])
                rd = (lrank.get(l, 65000), POS[p] | (8 if trans else 0), 0 if trans else gloss_id(g), share, L[(l, p)].senses if (l, p) in L else 0)
                # Une même catégorie par deux lemmes (left : leave, left) : le plus facile.
                if old is None or rd[0] < old[0]:
                    reads[POS[p]] = rd
        if not reads:
            continue
        reads = list(reads.values())
        r = min(x[0] for x in reads)
        if f in lrank:
            r = min(r, lrank[f])
        # Mots toujours connus (le curseur ne descend pas sous MIN_RANK : il faut déjà lire l'anglais
        # correctement) ou toujours transparents : inutiles.
        if r < MIN_RANK or all(x[1] & 8 for x in reads):
            continue
        # Lectures : la catégorie la plus courante de la forme d'abord, puis la plus facile, puis
        # celle qui a le plus de sens.
        reads.sort(key=lambda x: (-x[3], x[0], -x[4]))
        records[f.encode("utf-8")] = (r, reads)
    # Mots courants sans traduction (didn't, o'clock, mots grammaticaux) : une fiche sans lecture,
    # avec leur rang. L'app sait ainsi qu'ils sont connus et ne leur cherche pas de définition
    # anglaise de repli, réservée aux mots vraiment rares.
    bare = 0
    for wd, fq in wf.items():
        k = norm(wd).encode("utf-8")
        if fq < 3e-7 or k in records or not wd[:1].isalpha():
            continue
        r = min([lrank.get(l, 65000) for l, _ in of.get(norm(wd), ())] + [rank(math.log10(fq * 1e9))])
        records[k] = (r, [])
        bare += 1
    print(f"{bare} mots courants sans traduction", flush=True)

    # Mots de WordNet absents de la table : des mots rares (sinon ils y seraient déjà), traduits
    # au rang maximal. Traduction : l'équivalent courant du wordnet de la langue cible pour le sens
    # principal, sinon la courte définition anglaise de WordNet (marquée \u0001, tracée en italique).
    added = 0
    if WN:
        lang = {"fr": "fra", "es": "spa", "it": "ita", "pt": "por", "nl": "nld"}.get(a.target)
        WNPOS = {"n": 1, "v": 2, "a": 3, "s": 3, "r": 4}
        CODE = {"noun": 1, "verb": 2, "adj": 3, "adv": 4}
        # Lemmes de WordNet, puis formes fléchies du Wiktionnaire restées sans traduction (gulped :
        # le verbe gulp n'a pas de traduction, le nom en a une) : le sens WordNet de leur lemme, à
        # son rang.
        todo = [(name, name, None, 65000) for name in WN.all_lemma_names()]
        for f, lem in of.items():
            if f.encode("utf-8") not in records:
                for l, p in lem:
                    if p in CODE and lrank.get(l, 65000) >= MIN_RANK and (l, p) not in fixed:
                        todo.append((f, l, CODE[p], lrank.get(l, 65000)))
        for name, lemma, only, rk in todo:
            if not name.isascii() or not name[:1].isalpha() or "_" in name or name.lower() != name:
                continue
            k = norm(name).encode("utf-8")
            if k in records:
                continue
            reads, seen = [], set()
            for syn in WN.synsets(lemma):
                pc = WNPOS.get(syn.pos())
                if pc is None or pc in seen or (only is not None and pc != only):
                    continue
                if lemma not in [n.lower() for n in syn.lemma_names()]:
                    continue
                seen.add(pc)
                fr = []
                if lang:
                    try:
                        fr = [n.replace("_", " ") for n in syn.lemma_names(lang)]
                    except Exception:
                        fr = []
                # Un mot de la même catégorie (le verbe rocket n'est pas « fusée »), et d'usage courant.
                fr = [w for w in fr if " " not in w and zipf_frequency(w, a.target, "large") >= 2.0
                      and (not frcats or pc in frcats.get(w, ())) and w not in rude_fr]
                if fr:
                    g = max(fr, key=lambda w: zipf_frequency(w, a.target, "large"))
                    if any(similar(lemma, w) for w in fr):
                        reads.append((rk, pc | 8, 0, 0.0, 0))
                        continue
                else:
                    d = re.split(r"[;(]", syn.definition())[0].strip()
                    if not d or len(d) > 32:
                        continue
                    g = "\u0001" + d
                reads.append((rk, pc, gloss_id(g), 0.0, 0))
            if reads and not all(x[1] & 8 for x in reads):
                records[k] = (rk, reads[:4])
                added += 1
    print(f"{added} mots rares ajoutés d'après WordNet", flush=True)

    # Les corrections l'emportent aussi sur les formes venues d'ailleurs (WordNet, mots rares).
    for (f, p), g in by_form.items():
        k = f.encode("utf-8")
        r, reads = records.get(k, (lrank.get(f, 65000), []))
        # « - » : la forme n'est jamais traduite, quelle que soit sa catégorie.
        reads = [] if g == "-" else [x for x in reads if x[1] & 7 != POS[p]]
        if g != "-":
            reads.insert(0, (r, POS[p], gloss_id(g), 1.0, 0))
        records[k] = (r, reads)
    print(f"{len(records)} formes, {len(glosses)} gloses, {sum(1 for r in records.values() if r[1] and r[1][0][1] & 8)} transparentes", flush=True)

    # ---- écriture
    meta = {"from": "en", "to": a.target, "date": date, "forms": len(records), "glosses": len(glosses),
            "sources": "Wiktionary (CC BY-SA 4.0, kaikki.org) · wordfreq (CC BY-SA 4.0) · SUBTLEX-US · "
                       "Brysbaert, Keuleers & Mandera 2020 (CC BY-NC-SA 4.0) · Kuperman et al. 2012"}
    out = a.asset.format(target=a.target)
    write(out, records, glosses, meta)
    print(f"{out} : {os.path.getsize(out) / 1e6:.2f} Mo, {time.time() - t0:.0f} s", flush=True)

    if a.dump:
        with open(a.dump, "w", encoding="utf-8") as f:
            for k in sorted(records):
                r, reads = records[k]
                f.write(k.decode("utf-8") + "\t" + str(r) + "\t" +
                        ";".join(f"{x[1]}:{x[0]}:{glosses[x[2]]}" for x in reads) + "\n")
    if a.check:
        check(a.check, records, glosses)


BLOCK = 16     # formes par bloc (voir le format en tête du fichier)


def write(out, records, glosses, meta):
    """Écrit la table au format décrit en tête du fichier. records : forme (UTF-8) → (rang, lectures)."""
    # Gloses : désignées dans les fiches par leur position dans le réservoir (la glose n° 0, vide, en tête).
    gbody, gpos = bytearray(), []
    for g in glosses:
        b = g.encode("utf-8")
        gpos.append(len(gbody))
        gbody += varint(len(b)) + b
    # Fiches : une seule fois chacune, quel que soit le nombre de formes qui la partagent.
    recs, rpos = bytearray(), {}
    keys = sorted(records)
    body, index, prev = bytearray(), bytearray(), b""
    for i, k in enumerate(keys):
        r, reads = records[k]
        v = varint(r) + bytes([len(reads)])
        for rr, pos, g, _, _ in reads:
            v += bytes([pos]) + varint(rr) + varint(gpos[g])
        if v not in rpos:
            rpos[v] = len(recs)
            recs += v
        # Formes : la première d'un bloc en entier, les suivantes par ce qui les distingue de la précédente.
        p = 0
        if i % BLOCK:
            while p < min(len(k), len(prev)) and k[p] == prev[p]:
                p += 1
        else:
            index += struct.pack("<I", len(body))
        assert p < 256
        body += bytes([p]) + varint(len(k) - p) + k[p:] + varint(rpos[v])
        prev = k
    mb = json.dumps(meta, ensure_ascii=False).encode("utf-8")
    p_index = 48
    p_body = p_index + len(index)
    p_recs = p_body + len(body)
    p_gbody = p_recs + len(recs)
    p_meta = p_gbody + len(gbody)
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with open(out + ".part", "wb") as f:
        f.write(b"BIOWW002")
        f.write(struct.pack("<IIIIIIIIII", len(keys), len(index) // 4, p_index, p_body, p_recs, p_gbody, len(glosses),
                            p_meta, len(mb), BLOCK))
        f.write(index); f.write(body); f.write(recs); f.write(gbody); f.write(mb)
    os.replace(out + ".part", out)


def check(path, records, glosses):
    """Essai sur un texte : mots traduits à divers seuils (ce que montrerait l'app)."""
    text = open(path, encoding="utf-8").read()
    tot, seen = 0, {}
    prev = "."
    for m in re.finditer(r"([A-Za-z][A-Za-z'’]*[A-Za-z]|[A-Za-z])|([.!?“\"])", text):
        if m.group(2):
            prev = "."
            continue
        w = m.group(1)
        cap = w[0].isupper() and prev != "."
        prev = "w"
        if cap:
            continue
        tot += 1
        k = norm(w)
        if k.endswith("'s"):
            k = k[:-2]
        rec = records.get(k.encode("utf-8"))
        if rec:
            seen.setdefault(k, [rec, 0])[1] += 1
    for th in (3000, 5000, 8000, 12000, 20000):
        hard = sorted((v[0][0], w) for w, v in seen.items() if v[0][0] > th and v[0][1] and not v[0][1][0][1] & 8)
        n = sum(seen[w][1] for _, w in hard)
        print(f"\n== {th} mots : ~{n / (tot / 250):.1f} traductions par page de 250 mots")
        print("   " + ", ".join(f"{w} → {glosses[seen[w][0][1][0][2]]}" for _, w in hard[:24]))


def main():
    ap = argparse.ArgumentParser(description="Fabrique la table des mots difficiles (anglais → langue cible).")
    ap.add_argument("--out", default="dict-out", help="dossier des téléchargements (partagé avec make_dict.py)")
    ap.add_argument("--target", default="fr", help="langue des traductions")
    ap.add_argument("--asset", default="app/src/main/assets/wordwise-en-{target}.bin", help="fichier produit")
    ap.add_argument("--check", help="texte anglais sur lequel montrer le résultat")
    ap.add_argument("--dump", help="écrit aussi la table en texte (forme, rang, lectures) pour les essais")
    build(ap.parse_args())


if __name__ == "__main__":
    main()
