#!/usr/bin/env python3
"""
Bioramond : EB Garamond retravaillée pour la lecture sur liseuse.

EB Garamond est trop fine pour l'encre électronique : ses déliés font 2,7 % du corps (1,2 pixel
au corps de lecture de la Boox, 47 px par cadratin) contre 6,8 % pour Bookerly, et sa hauteur
d'x 40 % du corps contre 51 %. Ses graisses plus fortes n'y changent rien : elles épaississent
les fûts, pas les déliés (contraste 2,6 en Regular, 3,0 en Medium).

Part des polices variables d'EB Garamond (dict-out/ebgaramond/, téléchargées de
github.com/google/fonts, ofl/ebgaramond) et écrit Bioramond-{Regular,Italic,Bold,BoldItalic}.ttf :

  - trait épaissi par une plume elliptique (voir make_bioreader.embolden) : [GRAISSE] crans à
    l'horizontale (fûts), [VERT] à la verticale (déliés), 0,2 % du corps par cran. Le contraste
    passe de 2,6 à 1,6 : fût 9,6 % du corps, barre du e 6 % (2,8 pixels sur la Boox) ;
  - dessin agrandi de [ECHELLE] dans le cadratin (1000 → 862 unités) : hauteur d'x de 50 %.
    Montantes et descendantes grandissent d'autant (84 % et 33 % du corps) : l'interligne
    doit rester d'au moins 1,2 ;
  - approches : la plume mange le blanc entre les lettres, on le rend aux chasses, plus
    [APPROCHE] % du corps (Garamond est serrée) ;
  - pieds posés exactement sur la ligne de base (le trait ajouté dessous est compensé) ;
  - gras : graisse [GRAS] de l'axe plutôt que 700, pour un gris proche de celui de Bookerly ;
  - espaces fines manquantes ajoutées (U+202F, U+205F).

  dict-out/ft/bin/python tools/make_bioramond.py [GRAISSE=6.5 VERT=12.5 …] [Regular Italic…]
"""
import math
import os
import sys

import pathops
from fontTools.pens.cu2quPen import Cu2QuPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont
from fontTools.ttLib.tables._g_l_y_f import Glyph
from fontTools.varLib import instancer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_bioreader import embolden, outline, transformed  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONTS = os.path.join(ROOT, "app/src/main/assets/fonts")
SRC = os.path.join(ROOT, "dict-out/ebgaramond")

LARGEUR = 1.0816   # lettres élargies de 4 % deux fois (Garamond est étroite) : le réglage précédent vu à 104 % dans les Options
GRAISSE = 4.16      # crans ajoutés à l'horizontale (fûts), 0,2 % du corps par cran
VERT = 11.0        # crans ajoutés à la verticale (déliés, barres, attaches des arcs)
ECHELLE = 1.16     # agrandissement de tout le dessin dans le cadratin
APPROCHE = 0.728     # blanc ajouté entre les lettres, en % du corps (en plus de celui que mange la plume)
BOUCLES = 0.1      # part de la plume qui mord dans les petites contre-formes (œil du e, panse du a)
BOUCLES_I = 0.35   # la même pour l'italique, dont la barre du e est plus fine encore
SEUIL = 0.62       # « petite » : moins haute que cette part de la hauteur d'x
GRAS = 650         # graisse de l'axe wght pour les gras
OUT = ""           # dossier de sortie ("" : celui des polices de l'app)

# (style, fichier source, italique)
STYLES = [
    ("Regular", "EBGaramond[wght].ttf", False),
    ("Italic", "EBGaramond-Italic[wght].ttf", True),
    ("Bold", "EBGaramond[wght].ttf", False),
    ("BoldItalic", "EBGaramond-Italic[wght].ttf", True),
]
# Espaces absentes d'EB Garamond, en cadratin (avant agrandissement).
SPACES = {0x202F: .125, 0x205F: 2 / 9}


# EB Garamond compte 3 200 glyphes (1,3 Mo par style une fois statique) : on garde le latin,
# le grec et le cyrillique courants, la ponctuation, les chiffres et les signes usuels, avec les
# fonctions de composition appliquées d'office. Sortent : grec polytonique, phonétique, petites
# capitales, chiffres alternatifs, variantes historiques et d'ornement.
RANGES = [(0x20, 0x24F), (0x2B0, 0x36F), (0x370, 0x3FF), (0x400, 0x45F), (0x1D00, 0x1DBF), (0x1E00, 0x1EFF),
          (0x2000, 0x206F), (0x2070, 0x209F), (0x20A0, 0x20BF), (0x2100, 0x218F), (0x2190, 0x21FF),
          (0x2200, 0x22FF), (0x25A0, 0x25FF), (0xFB00, 0xFB06)]
# L'app ne demande aucune fonction optionnelle (ses petites capitales sont composées à part) :
# seules celles qu'Android applique d'office sont gardées.
FEATURES = ["kern", "mark", "mkmk", "ccmp", "locl", "liga", "rlig", "calt"]


def subset(f):
    from fontTools import subset as st
    o = st.Options()
    o.layout_features = FEATURES
    o.glyph_names = True
    o.name_IDs = ["*"]
    o.notdef_outline = True
    o.hinting = False
    sub = st.Subsetter(o)
    sub.populate(unicodes=[u for a, b in RANGES for u in range(a, b + 1)])
    sub.subset(f)


def plume(p, ax, ay, small, part):
    """
    Trait de plume autour de [p], en ménageant les petites boucles : l'œil du e, la panse du a,
    la boucle du g se boucheraient si la plume y mordait autant qu'ailleurs. Les contre-formes
    de moins de [small] de haut ne perdent que [part] fois l'épaisseur de la plume.
    """
    full = embolden(p, ax, ay)
    if part >= 1 or not len(p):
        return full
    light = embolden(p, ax * part, ay * part)
    contours = list(light.contours)
    if len(contours) < 2:
        return full
    outer = max(contours, key=lambda c: abs(c.area)).clockwise
    holes = pathops.Path()
    for c in contours:
        x0, y0, x1, y1 = c.bounds
        if c.clockwise != outer and y1 - y0 < small:
            c.draw(holes.getPen())
    if not len(holes):
        return full
    return pathops.op(full, holes, pathops.PathOp.DIFFERENCE, fix_winding=True, clockwise=True)


def build(style, src, italic, family="Bioramond"):
    bold = style.startswith("Bold")
    wght = GRAS if bold else 400
    f = instancer.instantiateVariableFont(TTFont(os.path.join(SRC, src)), {"wght": wght})
    subset(f)
    upm = f["head"].unitsPerEm
    ax = upm * 0.002 * GRAISSE / 2
    ay = max(ax, upm * 0.002 * VERT / 2)
    slant = math.tan(math.radians(-f["post"].italicAngle))
    add = round(2 * ax + APPROCHE * upm / 100)
    xh = f["OS/2"].sxHeight

    gs = f.getGlyphSet()
    glyf, hmtx, cmap = f["glyf"], f["hmtx"], f.getBestCmap()
    # Tous les contours d'abord : un composite tracé après coup reprendrait sa base déjà épaissie.
    # Élargissement le long de la pente (l'italique garde son inclinaison) : x' = L·x + c·y.
    wide, c = LARGEUR, slant * (1 - LARGEUR)
    paths = {n: outline(gs, n, (wide, 0, c, 1, 0, 0)) for n in f.getGlyphOrder()}
    for n in f.getGlyphOrder():
        hmtx[n] = (round(hmtx[n][0] * wide), hmtx[n][1])
    added = []
    for u, w in SPACES.items():
        if u not in cmap:
            name = "bioramond%04X" % u
            paths[name] = pathops.Path()
            hmtx[name] = (round(w * upm / ECHELLE) - add, 0)   # ajoutées après l'élargissement : chasse exacte
            added.append((u, name))
    for n, p in paths.items():
        adv = hmtx[n][0]
        if len(p):
            # La plume ajoute [ay] sous les pieds : relevés d'autant (le long de la pente), ils
            # retombent sur la ligne de base. Le blanc ajouté va à droite : crénage et ancres
            # des accents restent justes.
            p = transformed(plume(p, ax, ay, SEUIL * xh, BOUCLES_I if italic else BOUCLES),
                            (1, 0, 0, 1, ay * slant, ay))
        pen = TTGlyphPen(None)
        p.draw(Cu2QuPen(pen, max_err=0.5, all_quadratic=True))
        g = pen.glyph() if len(p) else Glyph()
        glyf[n] = g
        g.recalcBounds(glyf)
        hmtx[n] = (adv + add if adv else 0, g.xMin if g.numberOfContours else 0)
    # glyf[n] = … inscrit déjà les nouveaux noms dans l'ordre des glyphes : pas de doublon.
    order = list(dict.fromkeys(f.getGlyphOrder() + [n for _, n in added]))
    f.setGlyphOrder(order)
    glyf.glyphOrder = order
    for table in f["cmap"].tables:
        if table.isUnicode():
            for u, name in added:
                table.cmap[u] = name

    # Le crénage suit l'élargissement ; les ancres des accents flottants aussi, et le relèvement.
    def lift(o, seen):
        if id(o) in seen or o is None or isinstance(o, (int, float, str, bytes)):
            return
        seen.add(id(o))
        if isinstance(o, (list, tuple)):
            for x in o:
                lift(x, seen)
            return
        if not hasattr(o, "__dict__"):
            return
        for a in ("XAdvance", "XPlacement"):          # crénage : élargi comme les lettres
            if isinstance(getattr(o, a, None), int):
                setattr(o, a, round(getattr(o, a) * wide))
        if type(o).__name__ == "Anchor" and hasattr(o, "YCoordinate"):
            o.XCoordinate = round(wide * o.XCoordinate + c * o.YCoordinate + ay * slant)
            o.YCoordinate = round(o.YCoordinate + ay)
        for x in list(vars(o).values()):
            lift(x, seen)
    if "GPOS" in f:
        lift(f["GPOS"].table, set())

    for tag in ("DSIG", "STAT", "fvar", "gvar", "avar", "HVAR", "MVAR", "cvar", "prep", "fpgm", "cvt ", "gasp"):
        if tag in f:
            del f[tag]

    os2 = f["OS/2"]
    os2.sxHeight = round(os2.sxHeight + 2 * ay)
    os2.sCapHeight = round(os2.sCapHeight + 2 * ay)
    os2.usWeightClass = 700 if bold else 400
    os2.fsSelection = ((os2.fsSelection & ~0b1100001) | (0x20 if bold else 0) | (1 if italic else 0)
                       | (0x40 if not (bold or italic) else 0))
    f["head"].macStyle = (1 if bold else 0) | (2 if italic else 0)
    os2.achVendID = "NONE"

    name = f["name"]
    copyright = name.getDebugName(0)
    keep = [(r.nameID, r.toUnicode()) for r in name.names if r.nameID in (8, 9, 11, 12, 13, 14)]
    name.names = []
    pretty = {"Regular": "Regular", "Italic": "Italic", "Bold": "Bold", "BoldItalic": "Bold Italic"}[style]
    for nid, val in [
        (0, copyright + "\nBioramond : modifications 2026, Bioread."),
        (1, family),
        (2, pretty),
        (3, f"1.000;NONE;{family}-{style}"),
        (4, f"{family} {pretty}" if style != "Regular" else family),
        (5, "Version 1.000; EB Garamond, wght %d, echelle %g, largeur %g, graisse +%g/%g, approche %g"
            % (wght, ECHELLE, LARGEUR, GRAISSE, VERT, APPROCHE)),
        (6, f"{family}-{style}"),
    ] + keep:
        name.setName(val, nid, 3, 1, 0x409)
    # Agrandissement : un cadratin plus petit, tout le reste (contours, chasses, crénage,
    # métriques verticales) reste en unités et grandit d'autant.
    f["head"].unitsPerEm = round(upm / ECHELLE)
    out = os.path.join(OUT or FONTS, f"{family}-{style}.ttf")
    f.save(out)
    print(out, os.path.getsize(out) // 1024, "ko")


if __name__ == "__main__":
    family = "Bioramond"
    only = []
    for a in sys.argv[1:]:
        if "=" in a:
            k, v = a.split("=")
            old = globals()[k]
            globals()[k] = v if isinstance(old, str) else type(old)(float(v))
        elif a in [st[0] for st in STYLES]:
            only.append(a)
        else:
            family = a
    for st in STYLES:
        if not only or st[0] in only:
            build(*st, family=family)
