#!/usr/bin/env python3
"""
Bioreader : Newsreader retravaillée pour la lecture sur liseuse.

Part des polices variables de Newsreader (app/src/main/assets/fonts/Newsreader*.ttf) et écrit
quatre polices statiques Bioreader-{Regular,Italic,Bold,BoldItalic}.ttf :

  - optique figée au corps 12 (axe opsz), le dessin prévu pour le texte courant ;
  - italique resserré de [SERRE] dans le sens de la pente (la pente reste celle d'origine),
    après avoir été graissé sur l'axe wght d'autant que le resserrement amincit ses fûts ;
  - trait épaissi par une plume elliptique : [GRAISSE] crans du réglage « Graisse » de l'app
    (0,2 % du corps par cran) à l'horizontale, [VERT] à la verticale : les déliés
    et attaches (arcs du n, panse du a, boucle du g) passent au-dessus de 1,5 pixel au corps
    de lecture sur la Boox (25 sp à 300 ppp, 47 px par cadratin), et le contraste se
    rapproche de celui de Bookerly ;
  - gris équilibré : l'italique aussi foncé que le romain, le gras un peu allégé ;
  - typographie française : espaces fines (U+202F, U+2009) et toute la série U+2000-200A,
    lettres supérieures (ᵉ ʳ ᵈ ˢ ᵐ ˡ ⁿ ᵒ… pour XIXᵉ, 1ᵉʳ, Mˡˡᵉ) tirées d'une graisse plus forte ;
  - hinting ttfautohint (vertical seulement sous Android : hauteur d'x et ligne de base calées
    sur le pixel, hauteur d'x arrondie vers le haut), utilisé seulement si l'app le demande ;
  - dessin agrandi de [ECHELLE] dans le cadratin (le cadratin passe de 2000 à 1878 unités) :
    à réglage « Taille » égal, hauteur d'x de Bookerly (0,511 en romain, 0,522 en italique,
    contre 0,479 et 0,491 sans agrandissement), au lieu d'un ou deux crans de plus ;
  - pieds posés exactement sur la ligne de base (le trait ajouté dessous est compensé) ;
  - approches inchangées (comme le réglage « Graisse », le trait ne s'ajoute pas aux chasses).

  python3 -m venv /tmp/ft && /tmp/ft/bin/pip install fonttools skia-pathops ttfautohint-py
  /tmp/ft/bin/python tools/make_bioreader.py
"""
import math
import os
import sys

import pathops
from fontTools.pens.cu2quPen import Cu2QuPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont
from fontTools.ttLib.tables._g_l_y_f import Glyph
from fontTools.varLib import instancer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONTS = os.path.join(ROOT, "app/src/main/assets/fonts")

OPSZ = 12
GRAISSE = 2.0      # crans du réglage « Graisse » ajoutés à l'horizontale (fûts), 0,2 % du corps par cran
VERT = 4.8         # crans ajoutés à la verticale (déliés, attaches des arcs, panse du a, boucle du g)
VERT_I = 7.2       # le même pour l'italique, dont les cols (panse du a, d, q ; arc du m) sont plus fins
PENTE = 14.5       # inclinaison de l'italique en degrés (0 : celle de Newsreader, 17°)
APPROCHE = 0.0     # italique : blanc ajouté de chaque côté des lettres, en millièmes de cadratin
F_GAP = 0.035      # blanc minimal, en cadratin, entre le crochet du f et la lettre qui suit
QUOTES = ""       # guillemets anglais et apostrophe repris de : "literata", "spectral" ("" : dessinés ici)
A_GRAISSE = 1.5    # graisse horizontale minimale du a romain (voir build)
ECHELLE = 1.065    # agrandissement de tout le dessin dans le cadratin (hauteur d'x de Bookerly à corps égal)
SERRE = 1.0        # chasse de l'italique (1 = celle de Newsreader)
HINT = False
OUT = ""           # dossier de sortie ("" : celui des polices de l'app)
BOLD_SHIFT = 0     # décalage de la graisse des gras (variantes)

# (style, fichier source, graisse wght, italique)
# Italique resserré (SERRE < 1) : build relève la graisse de l'axe d'autant que le resserrement
# amincit les fûts (0,00024 em par unité de wght vers 400, 0,0004 vers 700, mesuré au corps 12).
# Gras : 670 plutôt que 700, son gris rapporté au romain (1,45 à 700) se rapproche de celui de
# Bookerly (1,35).
STYLES = [
    ("Regular", "Newsreader.ttf", 400, False),
    ("Italic", "Newsreader-Italic.ttf", 400, True),
    ("Bold", "Newsreader.ttf", 670, False),
    ("BoldItalic", "Newsreader-Italic.ttf", 670, True),
]

# Espaces, en cadratin (comme Bookerly). U+2007 : chasse d'un chiffre ; U+2008 : d'un point.
SPACES = {0x2000: .5, 0x2001: 1, 0x2002: .5, 0x2003: 1, 0x2004: 1 / 3, 0x2005: .25, 0x2006: 1 / 6,
          0x2007: "zero", 0x2008: "period", 0x2009: .2, 0x200A: .05, 0x202F: .125, 0x205F: 2 / 9,
          0x200B: 0, 0x200C: 0, 0x200D: 0, 0x2060: 0, 0xFEFF: 0}
# Lettres supérieures (modificatrices) : caractère → lettre de base.
SUPERIORS = {0x1D43: "a", 0x1D47: "b", 0x1D9C: "c", 0x1D48: "d", 0x1D49: "e", 0x1DA0: "f",
             0x1D4D: "g", 0x02B0: "h", 0x2071: "i", 0x02B2: "j", 0x1D4F: "k", 0x02E1: "l",
             0x1D50: "m", 0x207F: "n", 0x1D52: "o", 0x1D56: "p", 0x02B3: "r", 0x02E2: "s",
             0x1D57: "t", 0x1D58: "u", 0x1D5B: "v", 0x02B7: "w", 0x02E3: "x", 0x02B8: "y",
             0x1DBB: "z"}
SUP_WGHT = 300     # graisse ajoutée aux lettres supérieures avant réduction (sinon trop maigres)


def walk(obj, fn, seen=None):
    """Parcourt les sous-tables de GPOS et applique [fn] à chacune."""
    if seen is None:
        seen = set()
    if id(obj) in seen or obj is None or isinstance(obj, (int, float, str, bytes)):
        return
    seen.add(id(obj))
    if isinstance(obj, (list, tuple)):
        for o in obj:
            walk(o, fn, seen)
        return
    if isinstance(obj, dict):
        for o in obj.values():
            walk(o, fn, seen)
        return
    if not hasattr(obj, "__dict__"):
        return
    fn(obj)
    for o in list(vars(obj).values()):
        walk(o, fn, seen)


def outline(gs, name, matrix):
    """Contour du glyphe [name] (composites décomposés), transformé par [matrix]."""
    path = pathops.Path()
    gs[name].draw(TransformPen(pathops.PathPen(path, glyphSet=gs), matrix))
    return path


def transformed(path, matrix):
    out = pathops.Path()
    path.draw(TransformPen(out.getPen(), matrix))
    return out


def embolden(path, ax, ay):
    """Trait d'une plume elliptique (demi-axes [ax], [ay]) ajouté tout autour du contour."""
    if len(path) == 0 or ay <= 0.5:
        return path
    k = ax / ay
    squashed = transformed(path, (1, 0, 0, k, 0, 0))     # l'ellipse devient un cercle
    stroke = pathops.Path()
    squashed.draw(stroke.getPen())
    stroke.stroke(2 * ax, pathops.LineCap.ROUND_CAP, pathops.LineJoin.ROUND_JOIN, 4)
    stroke.convertConicsToQuads(0.1)
    union = pathops.op(squashed, stroke, pathops.PathOp.UNION, fix_winding=True, clockwise=True)
    return pathops.simplify(transformed(union, (1, 0, 0, 1 / k, 0, 0)), clockwise=True)


def kern_f(path):
    """
    Le crochet du f (et du ff) vient toucher les hampes, accents et ponctuations qui le
    suivent (fb, fh, fk, fï, f’, f), f?) : on écarte chaque paire juste assez pour laisser
    [F_GAP] de blanc, mesuré rangée par rangée sur les contours définitifs, crénage existant
    compris (HarfBuzz). Le décalage s'ajoute dans une recherche « kern » à part.
    """
    import freetype
    import numpy as np
    import uharfbuzz as hb
    from fontTools.otlLib.builder import buildLookup, buildPairPosGlyphs, buildValue
    from fontTools.ttLib.tables import otTables as ot

    f = TTFont(path)
    upm = f["head"].unitsPerEm
    cmap = f.getBestCmap()
    blob = hb.Blob.from_file_path(path)
    hbfont = hb.Font(hb.Face(blob))
    face = freetype.Face(path)
    face.set_char_size(upm * 64 // 4)                      # 1 px = 4 unités

    def profile(gname):
        """Bords gauche et droit de l'encre, rangée par rangée (unités, origine à 0)."""
        face.load_glyph(f.getGlyphID(gname), freetype.FT_LOAD_RENDER | freetype.FT_LOAD_NO_HINTING)
        g = face.glyph
        b = g.bitmap
        if b.rows == 0:
            return {}
        a = np.array(b.buffer, dtype=np.uint8).reshape(b.rows, b.pitch)[:, :b.width] > 64
        out = {}
        for r in range(b.rows):
            xs = np.nonzero(a[r])[0]
            if len(xs):
                out[g.bitmap_top - r] = ((g.bitmap_left + xs[0]) * 4, (g.bitmap_left + xs[-1] + 1) * 4)
        return out

    def advance_after(first, text):
        """Chasse de [first] devant le second caractère de [text], crénage compris."""
        buf = hb.Buffer()
        buf.add_str(text)
        buf.guess_segment_properties()
        hb.shape(hbfont, buf, {})
        if hbfont.glyph_to_string(buf.glyph_infos[0].codepoint) != first or len(buf.glyph_infos) != 2:
            return None
        return buf.glyph_positions[0].x_advance

    firsts = [n for n in ("f", "f_f") if n in f.getGlyphOrder()]
    seconds = "t" + "ïîìíĳ" + "’'\"”)]}?!»*" + "BDEFHIKLMNPRTÉÈÊ"
    gap = F_GAP * upm
    pairs = {}
    for first in firsts:
        pf = profile(first)
        text1 = "ff" if first == "f_f" else "f"
        for ch in seconds:
            if ord(ch) not in cmap:
                continue
            second = cmap[ord(ch)]
            ps = profile(second)
            adv = advance_after(first, text1 + ch)
            if adv is None:
                continue
            rows = [y for y in pf if y in ps]
            if not rows:
                continue
            room = min(adv + ps[y][0] - pf[y][1] for y in rows)
            if room < gap:
                pairs[(first, second)] = round(gap - room)
    if not pairs:
        return
    sub = buildPairPosGlyphs({k: (buildValue({"XAdvance": v}), None) for k, v in pairs.items()}, f.getReverseGlyphMap())
    gpos = f["GPOS"].table
    gpos.LookupList.Lookup.append(buildLookup(sub))
    idx = len(gpos.LookupList.Lookup) - 1
    gpos.LookupList.LookupCount = len(gpos.LookupList.Lookup)
    for fr in gpos.FeatureList.FeatureRecord:
        if fr.FeatureTag == "kern":
            fr.Feature.LookupListIndex.append(idx)
            fr.Feature.LookupCount = len(fr.Feature.LookupListIndex)
    f.save(path)
    print("  f :", ", ".join(f"{a.replace('_', '')}+{b} {v}" for (a, b), v in sorted(pairs.items())))


def band(path, y0, y1):
    """Partie de [path] comprise entre les hauteurs [y0] et [y1]."""
    r = pathops.Path()
    pen = r.getPen()
    pen.moveTo((-5000, y0)); pen.lineTo((-5000, y1)); pen.lineTo((9000, y1)); pen.lineTo((9000, y0))
    pen.closePath()
    return pathops.op(path, r, pathops.PathOp.INTERSECTION, fix_winding=True, clockwise=True)


def runs(path, y):
    """Bords [gauche, droit] de l'encre à la hauteur [y], de gauche à droite."""
    cut = band(path, y - 1, y + 1)
    return sorted((c.bounds[0], c.bounds[2]) for c in cut.contours if len(c))


def f_ligatures(paths, f, xh):
    """
    Ligatures fb, fh, fk (et ffb, ffh, ffk) : le haut de la ligature fl (le crochet du f qui
    rejoint la hampe du l) sur le bas du b, du h ou du k, hampes alignées. Sans elles, le
    crochet du f touche la hampe qui suit, ou il faudrait l'en écarter d'un blanc visible.
    """
    order = f.getGlyphOrder()
    asc = f["OS/2"].sTypoAscender
    cut = xh + 0.35 * (asc - xh)               # au-dessus de la barre du f, sous le crochet
    mid = (xh * 1.08 + cut) / 2                # hauteur où l'on aligne les hampes
    made = {}
    for lig, single in (("fl", "f"), ("f_f_l", "f_f")):
        if lig not in order or single not in order:
            continue
        lp, lw = paths[lig]
        sp, _ = paths[single]
        lstem = runs(lp, mid)[-1][0]           # bord gauche de la hampe du l dans la ligature
        for ch in "bhk":
            gp, gw = paths[ch]
            r = runs(gp, mid)
            if not r:
                continue
            shift = lstem - r[0][0]
            moved = transformed(band(gp, -2000, cut), (1, 0, 0, 1, shift, 0))
            p = pathops.op(band(lp, cut, 4000), band(sp, -2000, cut), pathops.PathOp.UNION, fix_winding=True, clockwise=True)
            p = pathops.op(p, moved, pathops.PathOp.UNION, fix_winding=True, clockwise=True)
            name = single + "_" + ch
            paths[name] = (p, round(shift + gw))
            made[name] = (single, ch)
    return made


QUOTE_GLYPHS = ("quoteleft", "quoteright", "quotedblleft", "quotedblright", "quotesinglbase", "quotedblbase")
QUOTE_SOURCES = {   # (romain, italique, variable) ; licence OFL comme Newsreader
    "literata": ("Literata.ttf", "Literata-Italic.ttf", True, "Copyright 2017 The Literata Project Authors"),
    "spectral": ("Spectral-{}.ttf", "Spectral-{}.ttf", False, "Copyright 2017 The Spectral Project Authors"),
}


# Guillemet fermant ’ (et virgule) dessiné ici, à la place de la bille de Newsreader : un coin à
# tête plate qui s'amincit régulièrement jusqu'à une pointe émoussée, la queue bombée vers la
# droite puis repartant vers la gauche. Unité : la hauteur du signe ; profondeur 0 en haut.
QUOTE_TOP = (0.13, 0.37)            # bords gauche et droit du haut de la tête (plat, coins arrondis)
QUOTE_SPINE = [                      # (profondeur, milieu, épaisseur), de la tête à la queue
    (0.10, 0.26, 0.41), (0.25, 0.32, 0.38), (0.40, 0.34, 0.35), (0.55, 0.32, 0.32),
    (0.70, 0.26, 0.29), (0.85, 0.17, 0.26),
]
QUOTE_TIP = ((0.13, 0.995), (0.03, 0.975))   # pointe émoussée : bout droit, bout gauche
QUOTE_W = 0.88                       # épaisseur de tout le signe (> 1 : plus épais)
COMMA_W = 1.12                       # la virgule, un peu plus large que le guillemet
DOT_SCALE = 0.88                     # points de ponctuation réduits (les gros points ronds de Newsreader)
DOT_GLYPHS = ("period", "colon", "semicolon", "exclam", "question", "ellipsis", "exclamdown",
              "questiondown", "periodcentered")
QUOTE_PAD = 0.0                      # blanc ajouté de chaque côté des guillemets et de l'apostrophe, en cadratin
QUOTE_DRAWN = ("quoteright", "quoteleft", "quotedblright", "quotedblleft", "quotesinglbase", "quotedblbase")


def drawn_quote(h, flip, lin, wide=1.0):
    """Le signe ’ (‘ si [flip]) haut de [h], incliné par la partie linéaire [lin] de l'italique."""
    k = QUOTE_W * wide
    right = [(QUOTE_TOP[1], 0.0)] + [(c + k * w / 2, d) for d, c, w in QUOTE_SPINE] + [QUOTE_TIP[0]]
    left = [QUOTE_TIP[1]] + [(c - k * w / 2, d) for d, c, w in reversed(QUOTE_SPINE)] + [(QUOTE_TOP[0], 0.0)]
    pts = []
    for x, d in right + left:
        x, y = (x - 0.27) * h, -d * h
        pts.append((-x, -h - y) if flip else (x, y))
    path = pathops.Path()
    pen = path.getPen()
    n = len(pts)
    # Catmull-Rom fermée : une courbe lisse qui passe par chaque point.
    pen.moveTo(pts[0])
    for i in range(n):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[(i + 1) % n], pts[(i + 2) % n]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        pen.curveTo(c1, c2, p2)
    pen.closePath()
    path = pathops.simplify(path, clockwise=True)
    return transformed(path, lin)


def redraw_quotes(paths, lin):
    """
    Remplace chaque signe des guillemets anglais par [drawn_quote], centré sur l'ancien ; la
    virgule (seule et dans le point-virgule) prend le même dessin, à la hauteur du guillemet,
    tête à la hauteur de l'ancienne.
    """
    qx0, qy0, qx1, qy1 = paths["quoteright"][0].bounds
    hq = qy1 - qy0

    def put(q, cx, top=None, cy=None):
        x0, y0, x1, y1 = q.bounds
        dy = top - y1 if top is not None else cy - (y0 + y1) / 2
        return transformed(q, (1, 0, 0, 1, cx - (x0 + x1) / 2, dy))

    for name in QUOTE_DRAWN:
        if name not in paths:
            continue
        old, adv = paths[name]
        flip = name in ("quoteleft", "quotedblleft")
        out = pathops.Path()
        for c in old.contours:
            x0, y0, x1, y1 = c.bounds
            q = put(drawn_quote(y1 - y0, flip, lin), (x0 + x1) / 2, cy=(y0 + y1) / 2)
            out = pathops.op(out, q, pathops.PathOp.UNION, fix_winding=True, clockwise=True)
        pad = QUOTE_PAD * 2000
        paths[name] = (transformed(out, (1, 0, 0, 1, pad, 0)), round(adv + 2 * pad))
    for name in ("comma", "semicolon"):
        if name not in paths:
            continue
        old, adv = paths[name]
        cs = sorted(old.contours, key=lambda c: c.bounds[1])
        x0, y0, x1, y1 = cs[0].bounds              # la virgule : le contour le plus bas
        out = put(drawn_quote(hq, False, lin, COMMA_W), (x0 + x1) / 2, top=y1)
        for c in cs[1:]:
            keep = pathops.Path()
            c.draw(keep.getPen())
            out = pathops.op(out, keep, pathops.PathOp.UNION, fix_winding=True, clockwise=True)
        paths[name] = (out, adv)


def shrink_dots(paths, xh):
    """
    Points de [DOT_GLYPHS] réduits de [DOT_SCALE] : ceux de Newsreader, une fois le trait
    épaissi, dépassent ceux de Bookerly (0,30 de la hauteur d'x contre 0,26). Un point posé sur
    la ligne de base y reste ; les autres sont réduits autour de leur centre.
    """
    for name in DOT_GLYPHS:
        if name not in paths:
            continue
        old, adv = paths[name]
        out = pathops.Path()
        for c in old.contours:
            x0, y0, x1, y1 = c.bounds
            w, h = x1 - x0, y1 - y0
            p = pathops.Path()
            c.draw(p.getPen())
            if w < 0.45 * xh and h < 0.45 * xh and abs(w - h) < 0.3 * max(w, h):
                cx, cy = (x0 + x1) / 2, (y0 if abs(y0) < 0.05 * xh else (y0 + y1) / 2)
                k = DOT_SCALE
                p = transformed(p, (k, 0, 0, k, cx * (1 - k), cy * (1 - k)))
            out = pathops.op(out, p, pathops.PathOp.UNION, fix_winding=True, clockwise=True)
        paths[name] = (out, adv)


def import_quotes(paths, f, style, wght, italic):
    """
    Guillemets anglais (‘ ’ “ ” ‚ „) et apostrophe d'une autre police libre : ceux de
    Newsreader, en virgule à gros point rond, font vieillots. Mis à la hauteur des capitales
    de Newsreader ; le crénage de Newsreader reste (mêmes noms de glyphes).
    """
    roman, ital, variable, _ = QUOTE_SOURCES[QUOTES]
    if variable:
        src = TTFont(os.path.join(FONTS, ital if italic else roman))
        src = instancer.instantiateVariableFont(src, {"wght": wght, "opsz": OPSZ})
    else:
        src = TTFont(os.path.join(FONTS, (ital if italic else roman).format(style)))
    k = f["OS/2"].sCapHeight / src["OS/2"].sCapHeight
    gs = src.getGlyphSet()
    for name in QUOTE_GLYPHS:
        if name in gs and name in paths:
            paths[name] = (outline(gs, name, (k, 0, 0, k, 0, 0)), round(gs[name].width * k))


# Queue du y romain : la bille de Newsreader remplacée par un dessin fait ici. Chaque variante
# est une épine (x, y, épaisseur) en unités de Newsreader (cadratin de 2000), qui prolonge la
# diagonale fine du y à partir de la ligne de base, et une terminaison : "pointe" (arrondie),
# ("coupe", angle) : bout plat tourné de [angle] degrés par rapport à la perpendiculaire.
Y_TAIL = "goutte2" # "" : la queue à bille d'origine ; sinon une clé de Y_TAILS
Y_CUT = -40        # la queue d'origine est gardée au-dessus de cette hauteur
Y_START = [(649, 100, 93), (608, 0, 93), (567, -100, 93)]   # la diagonale, reprise telle quelle
Y_TAILS = {
    # Effilée, à la Garamond : la courbe file vers la gauche et s'amincit jusqu'à une pointe.
    "effilee": ([(520, -205, 90), (462, -300, 84), (388, -380, 74), (300, -436, 61), (205, -462, 46),
                 (118, -462, 32)], "pointe"),
    # Coupe franche, à la Bookerly / Literata : la courbe s'épaissit un peu et finit coupée net.
    "coupe": ([(522, -205, 93), (470, -298, 96), (402, -374, 102), (318, -424, 110), (236, -440, 118)],
              ("coupe", 14)),
    # Droite, à la Charter : la diagonale continue tout droit et se pose sur un petit pied plat.
    "droite": ([(526, -200, 93), (485, -300, 93), (456, -370, 93)], "pied"),
    # Goutte discrète, à la Tiempos / Lyon : le crochet finit par un léger renflement.
    "goutte": ([(520, -205, 90), (464, -302, 82), (394, -384, 72), (312, -438, 64), (236, -456, 70),
                (176, -446, 88), (140, -424, 98)], "pointe"),
    # Goutte moyenne : entre la goutte discrète et la bille de Newsreader, trait moins aminci.
    "goutte2": ([(520, -205, 110), (464, -302, 124), (394, -384, 132), (312, -438, 134), (236, -458, 138),
                 (180, -446, 152), (150, -418, 166)], "pointe"),
    # Comme le j de la police : la queue s'incurve à peine et finit coupée en biais, sans crochet.
    "j": ([(522, -202, 93), (472, -292, 94), (408, -372, 95), (330, -440, 96), (290, -470, 96)], ("coupe", 0)),
    # Coupe évasée : le crochet de la coupe franche, qui s'élargit nettement avant la coupe.
    "evasee": ([(522, -205, 92), (470, -298, 92), (402, -374, 96), (322, -424, 108), (250, -440, 128)],
               ("coupe", 10)),
    # Crochet relevé, à la Baskerville : la queue remonte en s'amincissant.
    "crochet": ([(520, -205, 90), (466, -305, 84), (398, -392, 76), (312, -448, 68), (218, -462, 58),
                 (142, -432, 44), (100, -370, 30)], "pointe"),
}


def spline(pen, pts, first=True):
    """Catmull-Rom ouverte par [pts] (tangentes d'extrémité : celles des segments extrêmes)."""
    if first:
        pen.moveTo(pts[0])
    else:
        pen.lineTo(pts[0])
    n = len(pts)
    for i in range(n - 1):
        p0, p1, p2, p3 = pts[max(0, i - 1)], pts[i], pts[i + 1], pts[min(n - 1, i + 2)]
        pen.curveTo((p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6),
                    (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6), p2)


def y_tail(key):
    """Contour de la queue [key], de la diagonale du y à sa terminaison."""
    spine, end = Y_TAILS[key]
    spine = Y_START + spine
    n = len(spine)
    left, right, dirs = [], [], []
    for i, (x, y, w) in enumerate(spine):
        ax, ay = spine[max(0, i - 1)][:2]
        bx, by = spine[min(n - 1, i + 1)][:2]
        d = math.hypot(bx - ax, by - ay)
        ux, uy = (bx - ax) / d, (by - ay) / d              # sens de la marche (vers le bas, la gauche)
        dirs.append((ux, uy))
        left.append((x + uy * w / 2, y - ux * w / 2))      # côté gauche du trait en descendant…
        right.append((x - uy * w / 2, y + ux * w / 2))
    path = pathops.Path()
    pen = path.getPen()
    (x, y, w), (ux, uy) = spine[-1], dirs[-1]
    if end == "pied":
        # Pied plat : le trait descend tout droit jusqu'à la base, d'où part un court
        # empattement vers la gauche, raccordé au trait par un congé.
        base, th, lg = y - 78, 64, 150
        k = (base - right[-1][1]) / uy
        foot = (right[-1][0] + ux * k, base)                 # le bord droit, prolongé jusqu'à la base
        k = (base + th - left[-1][1]) / uy
        heel = (left[-1][0] + ux * k, base + th)             # le bord gauche, à la hauteur du dessus du pied
        spline(pen, right)
        pen.lineTo(foot)
        pen.lineTo((heel[0] - lg, base))
        pen.curveTo((heel[0] - lg - 30, base), (heel[0] - lg - 30, base + th), (heel[0] - lg, base + th))
        pen.lineTo((heel[0] - 70, base + th))
        pen.curveTo((heel[0] - 22, base + th), (left[-1][0] - 6, left[-1][1] - 30), left[-1])
        spline(pen, left[::-1], False)
    elif end == "pointe":
        spline(pen, right)
        r = w / 2
        tip = (x + ux * r, y + uy * r)
        k = 0.5523 * r
        pen.curveTo((right[-1][0] + ux * k, right[-1][1] + uy * k), (tip[0] - uy * k, tip[1] + ux * k), tip)
        pen.curveTo((tip[0] + uy * k, tip[1] - ux * k), (left[-1][0] + ux * k, left[-1][1] + uy * k), left[-1])
        spline(pen, left[::-1], False)
    else:
        # Coupe : les deux bords glissent le long de la marche, en sens contraires.
        t = math.tan(math.radians(end[1])) * w / 2
        right[-1] = (right[-1][0] + ux * t, right[-1][1] + uy * t)
        left[-1] = (left[-1][0] - ux * t, left[-1][1] - uy * t)
        spline(pen, right)
        spline(pen, left[::-1], False)
    pen.closePath()
    return pathops.simplify(path, clockwise=True)


def redraw_y(paths, f):
    """Remplace la queue du y, et de ses formes accentuées, par [y_tail] (romain maigre seulement)."""
    tail = y_tail(Y_TAIL)
    glyf = f["glyf"]
    names = ["y"] + [n for n in f.getGlyphOrder() if glyf[n].isComposite()
                     and any(c.glyphName == "y" and (c.x, c.y) == (0, 0) for c in glyf[n].components)]
    keep = pathops.Path()
    pen = keep.getPen()
    pen.moveTo((-4000, Y_CUT)); pen.lineTo((6000, Y_CUT)); pen.lineTo((6000, 4000)); pen.lineTo((-4000, 4000))
    pen.closePath()
    for name in names:
        p, adv = paths[name]
        p = pathops.op(p, keep, pathops.PathOp.INTERSECTION, fix_winding=True, clockwise=True)
        paths[name] = (pathops.op(p, tail, pathops.PathOp.UNION, fix_winding=True, clockwise=True), adv)


def build(style, src, wght, italic, family="Bioreader"):
    path_src = os.path.join(FONTS, src)
    if italic and SERRE < 1:
        # Le resserrement amincit les fûts d'autant : l'axe le leur rend (voir STYLES).
        wght += round((1 / SERRE - 1) * (330 if wght < 600 else 400))
    wght += BOLD_SHIFT if wght >= 600 else 0
    f = instancer.instantiateVariableFont(TTFont(path_src), {"wght": wght, "opsz": OPSZ})
    heavy = instancer.instantiateVariableFont(TTFont(path_src), {"wght": min(800, wght + SUP_WGHT), "opsz": OPSZ})
    upm = f["head"].unitsPerEm
    ax = max(0.5, upm * 0.002 * GRAISSE / 2)                 # trait ajouté de chaque côté, à l'horizontale
    ay = max(ax, upm * 0.002 * (VERT_I if italic else VERT) / 2)   # et à la verticale

    # Italique : redressé de la pente t0 à t1 (cisaillement x' = x + y·(t1 − t0), recentré à
    # mi-hauteur d'x), puis resserré le long de la nouvelle pente : x'' = s·(x' − y·t1) + y·t1,
    # enfin les approches. Soit x'' = s·x + c·y + dx.
    t0 = math.tan(math.radians(-f["post"].italicAngle)) if italic else 0.0
    t1 = math.tan(math.radians(PENTE)) if italic and PENTE else t0
    s = SERRE if italic else 1.0
    xh = f["OS/2"].sxHeight
    c = s * (t1 - t0) + (1 - s) * t1
    pad = round(APPROCHE * upm / 1000) if italic else 0
    dx = s * (t0 - t1) * xh / 2 + pad
    m = (s, 0, c, 1, dx, 0)
    tx = lambda x, y: s * x + c * y + dx
    if italic and PENTE:
        f["post"].italicAngle = -PENTE
        f["hhea"].caretSlopeRise, f["hhea"].caretSlopeRun = 1000, round(1000 * t1)

    gs, hgs = f.getGlyphSet(), heavy.getGlyphSet()
    glyf, hmtx, cmap = f["glyf"], f["hmtx"], f.getBestCmap()
    paths = {name: (outline(gs, name, m), round(hmtx[name][0] * s) + (2 * pad if hmtx[name][0] else 0))
             for name in f.getGlyphOrder()}

    ligs = f_ligatures(paths, f, xh)
    if QUOTES:
        import_quotes(paths, f, style, wght, italic)
    else:
        redraw_quotes(paths, (1, 0, t1, 1, 0, 0))   # dessin droit, incliné à la pente finale
    shrink_dots(paths, xh)
    if Y_TAIL and style == "Regular":
        redraw_y(paths, f)

    # Lettres supérieures : la lettre d'une graisse plus forte, réduite à la taille des
    # chiffres supérieurs et posée sur leur ligne de base (déplacée le long de la pente).
    zb, zs = glyf["zero"], glyf["zerosuperior"]
    zb.recalcBounds(glyf)
    zs.recalcBounds(glyf)
    k = (zs.yMax - zs.yMin) / (zb.yMax - zb.yMin)
    dy = zs.yMin - zb.yMin * k
    sb = round(0.03 * upm)
    added = []
    for u, base in SUPERIORS.items():
        if u in cmap or ord(base) not in cmap:
            continue
        b = cmap[ord(base)]
        p = outline(hgs, b, (k * s, 0, k * c, k, 0, dy))
        x0, _, x1, _ = p.bounds
        p = transformed(p, (1, 0, 0, 1, sb - x0, 0))
        name = "uni%04X" % u
        paths[name] = (p, round(x1 - x0 + 2 * sb))
        added.append((u, name))
    for u, w in SPACES.items():
        if u in cmap:
            continue
        adv = (hmtx[cmap[ord("0")]][0] * s if w == "zero" else
               hmtx[cmap[ord(".")]][0] * s if w == "period" else w * upm / ECHELLE)
        name = "uni%04X" % u
        paths[name] = (pathops.Path(), round(adv))
        added.append((u, name))

    order = f.getGlyphOrder() + [n for _, n in added] + list(ligs)
    # Le col presque vertical où la panse du a rejoint son fût échappe au trait vertical :
    # un peu de graisse horizontale pour le a, ses formes accentuées et æ.
    import unicodedata
    stout = {n for u, n in cmap.items() if unicodedata.normalize("NFD", chr(u))[:1] in "aæ"}
    axa = max(ax, upm * 0.002 * A_GRAISSE / 2)
    for name, (p, adv) in paths.items():
        p = embolden(p, axa if name in stout and not italic else ax, ay)
        # La plume ajoute [ay] sous les pieds : la ligne de base tomberait au quart de pixel, et
        # sans hinting l'encre électronique arrondit cette rangée grise tantôt en noir, tantôt en
        # blanc (lettres qui semblent mal posées). Relevé de [ay] (le long de la pente), les pieds
        # plats retombent exactement sur 0, comme ceux de Bookerly.
        if ay > 0.5 and len(p):
            p = transformed(p, (1, 0, 0, 1, ay * t1, ay))
        pen = TTGlyphPen(None)
        p.draw(Cu2QuPen(pen, max_err=0.5, all_quadratic=True))
        g = pen.glyph() if len(p) else Glyph()
        glyf[name] = g
        g.recalcBounds(glyf)
        hmtx[name] = (adv, g.xMin if g.numberOfContours else 0)
    f.setGlyphOrder(order)
    glyf.glyphOrder = order
    for table in f["cmap"].tables:
        if table.isUnicode():
            for u, name in added:
                if u <= 0xFFFF or table.format in (12, 13):
                    table.cmap[u] = name

    if ligs:
        from fontTools.otlLib.builder import buildLigatureSubstSubtable, buildLookup
        gsub = f["GSUB"].table
        sub = buildLigatureSubstSubtable({(a, b): name for name, (a, b) in ligs.items()})
        gsub.LookupList.Lookup.append(buildLookup([sub]))
        idx = len(gsub.LookupList.Lookup) - 1
        gsub.LookupList.LookupCount = idx + 1
        for fr in gsub.FeatureList.FeatureRecord:
            if fr.FeatureTag == "liga":
                fr.Feature.LookupListIndex.append(idx)
                fr.Feature.LookupCount = len(fr.Feature.LookupListIndex)
        gdef = f["GDEF"].table
        if gdef.GlyphClassDef:
            for name in ligs:
                gdef.GlyphClassDef.classDefs[name] = 2

    if italic:
        def scale(o):
            for a in ("XAdvance", "XPlacement"):
                if isinstance(getattr(o, a, None), int):
                    setattr(o, a, round(getattr(o, a) * s))
            if type(o).__name__ == "Anchor" and hasattr(o, "XCoordinate"):
                o.XCoordinate = round(tx(o.XCoordinate, o.YCoordinate))
        if "GPOS" in f:
            walk(f["GPOS"].table, scale)
        if "GDEF" in f and f["GDEF"].table.LigCaretList:
            f["GDEF"].table.LigCaretList = None

    for tag in ("DSIG", "STAT", "fvar", "gvar", "avar", "HVAR", "MVAR", "cvar", "prep", "gasp"):
        if tag in f:
            del f[tag]

    bold = style.startswith("Bold")
    os2 = f["OS/2"]
    os2.usWeightClass = 700 if bold else 400
    os2.fsSelection = ((os2.fsSelection & ~0b1100001) | (0x20 if bold else 0) | (1 if italic else 0)
                       | (0x40 if not (bold or italic) else 0))
    os2.usLastCharIndex = 0xFFFF
    f["head"].macStyle = (1 if bold else 0) | (2 if italic else 0)
    os2.achVendID = "NONE"

    name = f["name"]
    copyright = name.getDebugName(0)
    keep = [(r.nameID, r.toUnicode()) for r in name.names if r.nameID in (8, 9, 11, 12, 13, 14)]
    name.names = []
    pretty = {"Regular": "Regular", "Italic": "Italic", "Bold": "Bold", "BoldItalic": "Bold Italic"}[style]
    for nid, val in [
        (0, copyright + ("\n" + QUOTE_SOURCES[QUOTES][3] + " (guillemets)" if QUOTES else "")
            + "\nBioreader : modifications 2026, Bioread."),
        (1, family),
        (2, pretty),
        (3, f"1.100;NONE;{family}-{style}"),
        (4, f"{family} {pretty}" if style != "Regular" else family),
        (5, "Version 1.100; Newsreader 1.003, echelle %g, opsz %d, graisse +%g/%g/%g, italique %d %% %g deg %+g"
            % (ECHELLE, OPSZ, GRAISSE, VERT, VERT_I, round(SERRE * 100), PENTE or 17, APPROCHE)),
        (6, f"{family}-{style}"),
    ] + keep:
        name.setName(val, nid, 3, 1, 0x409)
    # Agrandissement : un cadratin plus petit, tout le reste (contours, chasses, crénage,
    # métriques verticales) reste en unités et grandit d'autant.
    f["head"].unitsPerEm = round(upm / ECHELLE)
    out = os.path.join(OUT or FONTS, f"{family}-{style}.ttf")
    f.save(out)
    kern_f(out)
    if HINT:
        import ttfautohint
        data = ttfautohint.ttfautohint(
            in_file=out,
            hinting_range_min=8, hinting_range_max=96, hinting_limit=0,
            increase_x_height=32,            # au-delà de 32 px, hauteur d'x au pixel le plus proche
            default_script="latn", fallback_script="latn",
            gray_stem_width_mode=ttfautohint.StemWidthMode.NATURAL,
            gdi_cleartype_stem_width_mode=ttfautohint.StemWidthMode.NATURAL,
            dw_cleartype_stem_width_mode=ttfautohint.StemWidthMode.NATURAL,
            no_info=True,
        )
        with open(out, "wb") as fh:
            fh.write(data)
    print(out, os.path.getsize(out) // 1024, "ko,", len(added), "glyphes ajoutés")


if __name__ == "__main__":
    # make_bioreader.py [Famille] [OPSZ=12 GRAISSE=2 VERT=4.8 SERRE=0.93 PENTE=14 HINT=0…] [Regular Italic…]
    family = "Bioreader"
    only = []
    for a in sys.argv[1:]:
        if "=" in a:
            k, v = a.split("=")
            old = globals()[k]
            globals()[k] = (v != "0") if isinstance(old, bool) else v if isinstance(old, str) else type(old)(float(v))
        elif a in [st[0] for st in STYLES]:
            only.append(a)
        else:
            family = a
    for st in STYLES:
        if not only or st[0] in only:
            build(*st, family=family)
