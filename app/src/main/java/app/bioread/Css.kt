package app.bioread

import android.graphics.Paint
import android.graphics.Typeface
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/*
 * Mode « Fidèle au livre » : lecture d'un sous-ensemble utile du CSS de l'éditeur.
 * Ce fichier contient l'analyse des feuilles (tolérante et bornée : elle ne lève jamais
 * d'exception et ne boucle jamais), les sélecteurs, la cascade et les polices embarquées.
 * Style.kt en tire les blocs et leur mise en page. Rien ici n'est exécuté quand le mode est coupé.
 */

// Propriétés retenues.
internal const val P_SIZE = 0
internal const val P_ITALIC = 1
internal const val P_BOLD = 2
internal const val P_FAMILY = 3
internal const val P_CAPS = 4
internal const val P_TRANSFORM = 5
internal const val P_ALIGN = 6
internal const val P_INDENT = 7
internal const val P_ML = 8
internal const val P_MR = 9
internal const val P_MT = 10
internal const val P_MB = 11
internal const val P_PL = 12
internal const val P_PR = 13
internal const val P_DISPLAY = 14
internal const val P_WS = 15
internal const val P_VA = 16
internal const val P_DECO = 17
internal const val P_HYPH = 18
internal const val P_PBB = 19
internal const val P_PBA = 20
internal const val P_SPACING = 21
internal const val P_FLOAT = 22
internal const val P_LIST = 23
internal const val P_COLOR = 24

// Nature d'une valeur.
internal const val K_ENUM = 0
internal const val K_EM = 1      // relatif au corps de l'élément (ou du parent pour font-size)
internal const val K_ABS = 2     // en cadratins de la racine (px / 16, pt / 12, rem)
internal const val K_PCT = 3
internal const val K_INHERIT = 4

// text-align
internal const val A_START = 0
internal const val A_CENTER = 1
internal const val A_RIGHT = 2
internal const val A_LEFT = 3
internal const val A_JUSTIFY = 4

// display
internal const val D_AUTO = 0
internal const val D_BLOCK = 1
internal const val D_INLINE = 2
internal const val D_NONE = 3

// white-space
internal const val WS_NORMAL = 0
internal const val WS_PRELINE = 1
internal const val WS_PRE = 2
internal const val WS_NOWRAP = 3

class Decl(val prop: Int, val kind: Int, val num: Float, val str: String?, val important: Boolean)

/** Style calculé d'un élément. Longueurs en cadratins de la racine (1 = corps par défaut du livre). */
class CStyle(p: CStyle?) {
    // Hérités.
    @JvmField var size: Float = p?.size ?: 1f
    @JvmField var italic: Boolean = p?.italic ?: false
    @JvmField var bold: Boolean = p?.bold ?: false
    @JvmField var family: String? = p?.family
    @JvmField var caps: Boolean = p?.caps ?: false
    @JvmField var transform: Int = p?.transform ?: 0     // 1 = capitales, 2 = minuscules, 3 = initiales
    @JvmField var align: Int = p?.align ?: A_START
    @JvmField var indent: Float = p?.indent ?: 0f
    @JvmField var ws: Int = p?.ws ?: WS_NORMAL
    @JvmField var noHyph: Boolean = p?.noHyph ?: false
    @JvmField var spacing: Float = p?.spacing ?: 0f
    @JvmField var gone: Boolean = p?.gone ?: false           // display: none, ici ou plus haut
    @JvmField var deco: Int = p?.deco ?: 0               // 1 = souligné, 2 = barré (se propage aux descendants)
    @JvmField var listNone: Boolean = p?.listNone ?: false
    @JvmField var color: Int = p?.color ?: 0             // couleur du texte (smartphone seulement) ; 0 = celle du thème

    // Propres à l'élément.
    @JvmField var display = D_AUTO
    @JvmField var ml = 0f
    @JvmField var mr = 0f
    @JvmField var mt = 0f
    @JvmField var mb = 0f
    @JvmField var pl = 0f
    @JvmField var pr = 0f
    @JvmField var mlp = 0f      // part de marge en fraction de la largeur
    @JvmField var mrp = 0f
    @JvmField var va: Int = p?.va ?: 0   // 1 = exposant, 2 = indice (suit le contenu en ligne, pas les blocs)
    @JvmField var pbb = false
    @JvmField var pba = false
    @JvmField var floated = false

    fun apply(d: Decl, parent: CStyle?) {
        val inh = d.kind == K_INHERIT
        when (d.prop) {
            P_SIZE -> size = when (d.kind) {
                K_EM -> (parent?.size ?: 1f) * d.num
                K_ABS -> d.num
                else -> parent?.size ?: 1f
            }.coerceIn(0.3f, 4f)
            P_ITALIC -> italic = if (inh) parent?.italic ?: false else d.num != 0f
            P_BOLD -> bold = if (inh) parent?.bold ?: false else d.num != 0f
            P_FAMILY -> family = if (inh) parent?.family else d.str
            P_CAPS -> caps = if (inh) parent?.caps ?: false else d.num != 0f
            P_TRANSFORM -> transform = if (inh) parent?.transform ?: 0 else d.num.toInt()
            P_ALIGN -> align = if (inh) parent?.align ?: A_START else d.num.toInt()
            P_INDENT -> indent = if (inh) parent?.indent ?: 0f else len(d)
            P_ML -> { ml = if (inh) 0f else len(d); mlp = if (d.kind == K_PCT) d.num / 100f else 0f }
            P_MR -> { mr = if (inh) 0f else len(d); mrp = if (d.kind == K_PCT) d.num / 100f else 0f }
            P_MT -> mt = if (inh) 0f else vlen(d)
            P_MB -> mb = if (inh) 0f else vlen(d)
            P_PL -> pl = if (inh) 0f else vlen(d)
            P_PR -> pr = if (inh) 0f else vlen(d)
            P_DISPLAY -> if (!inh) { display = d.num.toInt(); if (display == D_NONE) gone = true }
            P_WS -> ws = if (inh) parent?.ws ?: WS_NORMAL else d.num.toInt()
            P_VA -> va = if (inh) 0 else d.num.toInt()
            P_DECO -> deco = if (inh) parent?.deco ?: 0 else if (d.num == 0f) 0 else deco or d.num.toInt()
            P_HYPH -> noHyph = if (inh) parent?.noHyph ?: false else d.num != 0f
            P_PBB -> pbb = d.num != 0f
            P_PBA -> pba = d.num != 0f
            P_SPACING -> spacing = if (inh) parent?.spacing ?: 0f else (if (d.kind == K_EM) d.num else d.num / size).coerceIn(-0.05f, 0.3f)
            P_FLOAT -> floated = d.num != 0f
            P_LIST -> listNone = if (inh) parent?.listNone ?: false else d.num != 0f
            P_COLOR -> color = if (inh) parent?.color ?: 0 else d.num.toInt()
        }
    }

    private fun len(d: Decl): Float = when (d.kind) {
        K_EM -> d.num * size
        K_ABS -> d.num
        else -> 0f
    }.coerceIn(-20f, 20f)

    /** Longueur sans part proportionnelle : un pourcentage est ramené à une mesure de 30 cadratins. */
    private fun vlen(d: Decl): Float = if (d.kind == K_PCT) (d.num * 0.3f).coerceIn(-20f, 20f) else len(d)
}

/** Sélecteur simple composé : balise, identifiant, classes, :first-child. */
class Compound(val tag: String?, val id: String?, val classes: Array<String>?, val first: Boolean) {
    fun ok(s: Sig, prev: Sig?, prevKnown: Boolean): Boolean {
        if (tag != null && tag != s.tag) return false
        if (id != null && id != s.id) return false
        if (first && (!prevKnown || prev != null)) return false
        val c = classes ?: return true
        val have = s.classes ?: return false
        for (x in c) {
            var found = false
            for (y in have) if (x == y) { found = true; break }
            if (!found) return false
        }
        return true
    }
}

/** [combs]`[i]` relie parts[i-1] à parts[i] : ' ' descendant, '>' enfant, '+' frère immédiat. */
class Selector(val parts: Array<Compound>, val combs: CharArray, val spec: Int) {
    val last get() = parts[parts.size - 1]
    val sib: Boolean get() {
        for (c in combs) if (c == '+') return true
        for (p in parts) if (p.first) return true
        return false
    }

    /** L'élément ([sig], précédé de [prev], enfant de [parent]) est-il désigné ? [budget] borne la recherche. */
    fun matches(sig: Sig, prev: Sig?, parent: Node, budget: IntArray): Boolean = match(parts.size - 1, sig, prev, parent, budget)

    private fun match(i: Int, sig: Sig, prev: Sig?, parent: Node, budget: IntArray): Boolean {
        if (!parts[i].ok(sig, prev, true)) return false
        if (i == 0) return true
        if (combs[i] == '+') {
            val p = prev ?: return false
            if (!parts[i - 1].ok(p, null, false)) return false
            if (i == 1) return true
            return up(i - 1, parent, budget)
        }
        return up(i, parent, budget)
    }

    /** parts[i] est placé sur un enfant de [parent] : place parts[i-1] plus haut selon combs[i]. */
    private fun up(i: Int, parent: Node, budget: IntArray): Boolean {
        if (combs[i] == '>') {
            val gp = parent.parent ?: return false
            return match(i - 1, parent.sig, parent.prev, gp, budget)
        }
        var a = parent
        while (true) {
            val gp = a.parent ?: return false
            if (--budget[0] < 0) return false
            if (match(i - 1, a.sig, a.prev, gp, budget)) return true
            a = gp
        }
    }
}

class Rule(val sel: Selector, val decls: Array<Decl>, val key: Long)

class FontFace(val family: String, val path: String, val bold: Boolean, val italic: Boolean)

/** Une feuille analysée : règles dans l'ordre du texte, polices déclarées. */
class Sheet {
    val sels = ArrayList<Selector>()
    val decls = ArrayList<Array<Decl>>()
    val faces = ArrayList<FontFace>()
}

/** Ce qui identifie un élément pour les sélecteurs (partagé entre tous les éléments semblables). */
class Sig(val tag: String, val id: String?, val classes: Array<String>?, val n: Int)

/**
 * Élément dans son contexte (chaîne des ancêtres) avec son style calculé. Les éléments de même
 * balise, mêmes classes et mêmes ancêtres partagent un nœud : la cascade n'est faite qu'une fois
 * par contexte, puis chaque balise ne coûte qu'une recherche dans une table.
 */
class Node(
    val parent: Node?, val sig: Sig, val prev: Sig?, val style: CStyle, val blockTag: Boolean,
    /** Classes retenues et attribut style="", tels quels : avec [sig] et [prev], ce qui distingue les enfants d'un même élément. */
    val cls: String? = null, val inline: String? = null,
) {
    /** Bloc (au sens de l'analyseur) qui contient l'élément, ou lui-même. */
    val block: Node = if (blockTag || parent == null) this else parent.block
    val heading: Boolean = (parent?.heading ?: false) || (sig.tag.length == 2 && sig.tag[0] == 'h' && sig.tag[1] in '1'..'6')
    // Marges horizontales cumulées des blocs englobants.
    val left: Float = (parent?.left ?: 0f) + if (blockTag) style.ml + style.pl else 0f
    val right: Float = (parent?.right ?: 0f) + if (blockTag) style.mr + style.pr else 0f
    val leftP: Float = (parent?.leftP ?: 0f) + if (blockTag) style.mlp else 0f
    val rightP: Float = (parent?.rightP ?: 0f) + if (blockTag) style.mrp else 0f
    /** Enfants déjà résolus, par balise (quelques variantes par balise : classes, frère précédent…). */
    @Volatile var kids: ConcurrentHashMap<String, Array<Node>>? = null
    // Police de la famille de l'élément, résolue à la demande (voir StyleScan.face).
    @Volatile var faceState = 0
    @Volatile var face: Face? = null
}

/** Analyse d'une feuille de style. */
class CssParser(text: String, private val base: String, private val out: Sheet, private val importer: ((String) -> Sheet?)?) {
    private val s = strip(text)
    private val n = s.length

    fun parse(): Sheet {
        try {
            rules(0, n, 0)
        } catch (e: Exception) {
            // CSS malformé : on garde ce qui a été compris.
        }
        return out
    }

    private fun strip(t: String): String {
        if (t.indexOf("/*") < 0) return t
        val sb = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val c = t.indexOf("/*", i)
            if (c < 0) { sb.append(t, i, t.length); break }
            sb.append(t, i, c).append(' ')
            val e = t.indexOf("*/", c + 2)
            if (e < 0) break            // commentaire jamais fermé : le reste est perdu
            i = e + 2
        }
        return sb.toString()
    }

    private fun ws(c: Char) = c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000C'

    /** Premier [c1] ou [c2] hors chaîne et hors parenthèses dans [a, b), ou -1. */
    private fun find(a: Int, b: Int, c1: Char, c2: Char): Int {
        var i = a
        var par = 0
        while (i < b) {
            val c = s[i]
            if (c == '"' || c == '\'') {
                i++
                while (i < b && s[i] != c && s[i] != '\n') { if (s[i] == '\\') i++; i++ }
            } else if (c == '\\') i++
            else if (c == '(') par++
            else if (c == ')') { if (par > 0) par-- }
            else if (par == 0 && (c == c1 || c == c2)) return i
            i++
        }
        return -1
    }

    /** Accolade fermante de celle ouverte en [open], ou [b]. */
    private fun close(open: Int, b: Int): Int {
        var i = open + 1
        var depth = 1
        while (i < b) {
            val c = s[i]
            if (c == '"' || c == '\'') {
                i++
                while (i < b && s[i] != c && s[i] != '\n') { if (s[i] == '\\') i++; i++ }
            } else if (c == '\\') i++
            else if (c == '{') depth++
            else if (c == '}') { if (--depth == 0) return i }
            i++
        }
        return b
    }

    private fun rules(a: Int, b: Int, depth: Int) {
        var i = a
        while (i < b) {
            val c = s[i]
            if (ws(c) || c == '}' || c == ';') { i++; continue }
            if (c == '<' && s.startsWith("<!--", i)) { i += 4; continue }
            if (c == '-' && s.startsWith("-->", i)) { i += 3; continue }
            if (c == '@') {
                var e = i + 1
                while (e < b && (s[e].isLetterOrDigit() || s[e] == '-')) e++
                val name = s.substring(i + 1, e).lowercase()
                val stop = find(e, b, ';', '{')
                if (stop < 0) return
                if (s[stop] == ';') {
                    if (name == "import" && depth == 0) import(s.substring(e, stop))
                    i = stop + 1
                    continue
                }
                val end = close(stop, b)
                when (name) {
                    "media" -> if (depth < 3 && mediaOk(s.substring(e, stop))) rules(stop + 1, end, depth + 1)
                    "font-face" -> fontFace(stop + 1, end)
                }
                i = end + 1
                continue
            }
            val open = find(i, b, '{', '{')
            if (open < 0) return
            val end = close(open, b)
            if (out.sels.size < MAX_RULES) {
                val d = declarations(s, open + 1, end)
                if (d.isNotEmpty()) {
                    var from = i
                    while (from < open) {
                        var comma = find(from, open, ',', ',')
                        if (comma < 0) comma = open
                        val sel = selector(s, from, comma)
                        if (sel != null && out.sels.size < MAX_RULES) { out.sels.add(sel); out.decls.add(d) }
                        from = comma + 1
                    }
                }
            }
            i = end + 1
        }
    }

    /** Écran ou tout support ; les blocs réservés au Kindle ou à l'impression sont ignorés. */
    private fun mediaOk(q: String): Boolean {
        val m = q.trim().lowercase()
        if (m.isEmpty()) return true
        if (m.contains("amzn") || m.startsWith("not") || m.contains("prefers-color-scheme")) return false
        if (m.contains("screen") || m.contains("all")) return true
        return m.startsWith("(")
    }

    private fun url(v: String): String? {
        var t = v.trim()
        val u = t.indexOf("url(", ignoreCase = true)
        if (u >= 0) {
            val e = t.indexOf(')', u)
            if (e < 0) return null
            t = t.substring(u + 4, e).trim()
        } else {
            // @import "feuille.css" écran;
            if (t.isEmpty() || (t[0] != '"' && t[0] != '\'')) return null
            val e = t.indexOf(t[0], 1)
            if (e < 0) return null
            t = t.substring(0, e + 1)
        }
        t = t.trim('"', '\'', ' ')
        if (t.isEmpty() || t.startsWith("data:") || t.indexOf(':') in 1..8) return null
        return t
    }

    private fun import(prelude: String) {
        val imp = importer ?: return
        val u = url(prelude) ?: return
        val rest = prelude.substringAfterLast(')', prelude.substringAfterLast('"', ""))
        if (!mediaOk(rest)) return
        val sh = imp(resolvePath(base, u)) ?: return
        out.sels.addAll(sh.sels)
        out.decls.addAll(sh.decls)
        out.faces.addAll(sh.faces)
    }

    private fun fontFace(a: Int, b: Int) {
        if (out.faces.size >= 64) return
        var family: String? = null
        var src: String? = null
        var bold = false
        var italic = false
        var i = a
        while (i < b) {
            var e = find(i, b, ';', ';')
            if (e < 0) e = b
            val colon = s.indexOf(':', i)
            if (colon in (i + 1) until e) {
                val name = s.substring(i, colon).trim().lowercase()
                val v = s.substring(colon + 1, e).trim()
                when (name) {
                    "font-family" -> family = v.trim('"', '\'', ' ').lowercase()
                    "font-weight" -> bold = v.startsWith("bold") || (v.toIntOrNull() ?: 400) >= 600
                    "font-style" -> italic = v.startsWith("italic") || v.startsWith("oblique")
                    "src" -> for (part in v.split(',')) {
                        if (src != null) break
                        val u = url(part) ?: continue
                        val low = u.lowercase()
                        // Android ne lit ni WOFF ni EOT : seuls TrueType et OpenType sont retenus.
                        if (low.endsWith(".ttf") || low.endsWith(".otf") || low.endsWith(".ttc")) src = resolvePath(base, u)
                    }
                }
            }
            i = e + 1
        }
        val f = family
        val p = src
        if (f != null && f.isNotEmpty() && p != null) out.faces.add(FontFace(f, p, bold, italic))
    }

    companion object {
        const val MAX_RULES = 40_000
        private val NONE = arrayOf<Decl>()

        /** Sélecteur de s[a, b), ou null s'il sort du sous-ensemble compris (il est alors ignoré, jamais approché). */
        fun selector(s: String, a: Int, b: Int): Selector? {
            val parts = ArrayList<Compound>(4)
            val combs = ArrayList<Char>(4)
            var i = a
            var comb = 0.toChar()
            var ids = 0
            var cls = 0
            var tags = 0
            while (i < b) {
                val c = s[i]
                if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000C') {
                    if (parts.isNotEmpty() && comb == 0.toChar()) comb = ' '
                    i++
                    continue
                }
                if (c == '>' || c == '+') {
                    if (parts.isEmpty() || (comb != 0.toChar() && comb != ' ')) return null
                    comb = c
                    i++
                    continue
                }
                if (parts.isNotEmpty() && comb == 0.toChar()) return null
                var tag: String? = null
                var id: String? = null
                var classes: ArrayList<String>? = null
                var first = false
                var any = false
                if (c == '*') { i++; any = true }
                else if (identStart(c)) {
                    val e = ident(s, i, b)
                    if (e < 0) return null
                    tag = s.substring(i, e).lowercase()
                    tags++
                    i = e
                    any = true
                }
                while (i < b) {
                    val k = s[i]
                    if (k == '#' || k == '.') {
                        val e = ident(s, i + 1, b)
                        if (e <= i + 1) return null
                        var v = s.substring(i + 1, e)
                        if (v.indexOf('\\') >= 0) v = v.replace("\\", "")
                        if (k == '#') { id = v; ids++ } else { (classes ?: ArrayList<String>(2).also { classes = it }).add(v); cls++ }
                        i = e
                        any = true
                    } else if (k == ':') {
                        val e = ident(s, i + 1, b)
                        if (e <= i + 1) return null       // « :: », pseudo-élément
                        when (s.substring(i + 1, e).lowercase()) {
                            "first-child" -> first = true
                            "link" -> {}
                            else -> return null
                        }
                        cls++
                        i = e
                        any = true
                    } else break
                }
                if (!any) return null          // « [attr] », « ~ », « | »…
                if (parts.size >= 10) return null
                if (parts.isNotEmpty()) {
                    // Un seul « + » d'affilée, et pas de :first-child sur le frère (inconnu).
                    if (comb == '+' && combs[combs.size - 1] == '+') return null
                    combs.add(comb)
                } else combs.add(' ')
                if (comb == '+' && parts[parts.size - 1].first) return null
                parts.add(Compound(tag, id, classes?.toTypedArray(), first))
                comb = 0.toChar()
            }
            if (parts.isEmpty() || (comb != 0.toChar() && comb != ' ')) return null
            return Selector(parts.toTypedArray(), CharArray(combs.size) { combs[it] }, (minOf(ids, 15) shl 16) or (minOf(cls, 255) shl 8) or minOf(tags, 255))
        }

        private fun identStart(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == '-' || c >= '\u0080' || c == '\\'

        /** Fin de l'identifiant commencé en [a] (les échappements d'un caractère sont admis), ou -1. */
        private fun ident(s: String, a: Int, b: Int): Int {
            var i = a
            while (i < b) {
                val c = s[i]
                if (c == '\\') {
                    if (i + 1 >= b || s[i + 1].isLetterOrDigit() || s[i + 1] <= ' ') return -1   // échappement hexadécimal : non géré
                    i += 2
                } else if (identStart(c) || c in '0'..'9') i++
                else break
            }
            return i
        }

        /** Déclarations de s[a, b), réduites aux propriétés retenues. */
        fun declarations(s: String, a: Int, b: Int): Array<Decl> {
            var out: ArrayList<Decl>? = null
            var i = a
            while (i < b) {
                // Fin de la déclaration, hors chaînes et parenthèses.
                var e = i
                var par = 0
                while (e < b) {
                    val c = s[e]
                    if (c == '"' || c == '\'') {
                        e++
                        while (e < b && s[e] != c) e++
                    } else if (c == '(') par++
                    else if (c == ')') { if (par > 0) par-- }
                    else if (c == ';' && par == 0) break
                    else if (c == '{' || c == '}') break
                    e++
                }
                if (e > b) e = b
                val colon = s.indexOf(':', i)
                if (colon in (i + 1) until e) {
                    var name = s.substring(i, colon).trim().lowercase()
                    if (name.startsWith("-")) {
                        val d = name.indexOf('-', 1)
                        if (d > 0) name = name.substring(d + 1)
                    }
                    var v = s.substring(colon + 1, e).trim()
                    var imp = false
                    val bang = v.lastIndexOf('!')
                    if (bang >= 0 && v.substring(bang + 1).trim().equals("important", true)) {
                        imp = true
                        v = v.substring(0, bang).trim()
                    }
                    if (v.isNotEmpty() && v.length < 400) {
                        val list = out ?: ArrayList<Decl>(6).also { out = it }
                        if (list.size < 64) decl(name, v, imp, list)
                    }
                }
                i = e + 1
            }
            return out?.toTypedArray() ?: NONE
        }

        private fun e(list: ArrayList<Decl>, prop: Int, v: Int, imp: Boolean) = list.add(Decl(prop, K_ENUM, v.toFloat(), null, imp))

        /** Longueur CSS : nature et valeur, ou null. */
        private fun length(v: String, out: FloatArray): Int {
            var i = 0
            val n = v.length
            if (i < n && (v[i] == '+' || v[i] == '-')) i++
            var digits = false
            while (i < n && (v[i] in '0'..'9' || v[i] == '.')) { if (v[i] != '.') digits = true; i++ }
            if (!digits) return -1
            val x = v.substring(0, i).toFloatOrNull() ?: return -1
            if (x.isNaN() || x.isInfinite()) return -1
            val u = v.substring(i).trim()
            val kind: Int
            out[0] = when (u) {
                "em" -> { kind = K_EM; x }
                "ex", "ch" -> { kind = K_EM; x * 0.5f }
                "%" -> { kind = K_PCT; x }
                "rem" -> { kind = K_ABS; x }
                "px" -> { kind = K_ABS; x / 16f }
                "pt" -> { kind = K_ABS; x / 12f }
                "pc" -> { kind = K_ABS; x }
                "in" -> { kind = K_ABS; x * 6f }
                "cm" -> { kind = K_ABS; x * 6f / 2.54f }
                "mm" -> { kind = K_ABS; x * 0.6f / 2.54f }
                "" -> { if (x != 0f) return -1; kind = K_ABS; 0f }
                else -> return -1
            }
            return kind
        }

        private fun len(list: ArrayList<Decl>, prop: Int, v: String, imp: Boolean) {
            if (v == "auto") { list.add(Decl(prop, K_ABS, 0f, null, imp)); return }
            if (v == "inherit") { list.add(Decl(prop, K_INHERIT, 0f, null, imp)); return }
            val f = FloatArray(1)
            val k = length(v, f)
            if (k >= 0) list.add(Decl(prop, k, f[0], null, imp))
        }

        private fun fontSize(list: ArrayList<Decl>, v: String, imp: Boolean): Boolean {
            val abs = when (v) {
                "xx-small" -> 0.6f; "x-small" -> 0.75f; "small" -> 0.89f; "medium" -> 1f
                "large" -> 1.2f; "x-large" -> 1.5f; "xx-large" -> 2f; "xxx-large" -> 3f
                else -> 0f
            }
            if (abs > 0f) { list.add(Decl(P_SIZE, K_ABS, abs, null, imp)); return true }
            when (v) {
                "larger" -> { list.add(Decl(P_SIZE, K_EM, 1.2f, null, imp)); return true }
                "smaller" -> { list.add(Decl(P_SIZE, K_EM, 0.83f, null, imp)); return true }
                "inherit" -> { list.add(Decl(P_SIZE, K_INHERIT, 0f, null, imp)); return true }
            }
            val f = FloatArray(1)
            val k = length(v, f)
            if (k < 0 || f[0] <= 0f) return false
            list.add(if (k == K_PCT) Decl(P_SIZE, K_EM, f[0] / 100f, null, imp) else Decl(P_SIZE, k, f[0], null, imp))
            return true
        }

        private fun family(v: String): String {
            val sb = StringBuilder(v.length)
            for (part in v.split(',')) {
                val p = part.trim().trim('"', '\'').trim().lowercase()
                if (p.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(p)
            }
            return sb.toString()
        }

        private fun weight(v: String): Int = when (v) {
            "bold", "bolder" -> 1
            "normal", "lighter" -> 0
            else -> v.toIntOrNull()?.let { if (it >= 600) 1 else 0 } ?: -1
        }

        private fun sides(list: ArrayList<Decl>, v: String, imp: Boolean, top: Int, right: Int, bottom: Int, left: Int) {
            val p = v.split(' ', '\t', '\n').filter { it.isNotEmpty() }
            if (p.isEmpty() || p.size > 4) return
            val t = p[0]
            val r = if (p.size > 1) p[1] else t
            val b = if (p.size > 2) p[2] else t
            val l = if (p.size > 3) p[3] else r
            if (top >= 0) len(list, top, t, imp)
            len(list, right, r, imp)
            if (bottom >= 0) len(list, bottom, b, imp)
            len(list, left, l, imp)
        }

        private fun decl(name: String, value: String, imp: Boolean, list: ArrayList<Decl>) {
            val v = value.lowercase()
            if (v == "inherit" && name != "margin" && name != "padding" && name != "font") {
                val p = when (name) {
                    "font-style" -> P_ITALIC; "font-weight" -> P_BOLD; "font-family" -> P_FAMILY; "font-size" -> P_SIZE
                    "text-align" -> P_ALIGN; "text-indent" -> P_INDENT; "text-transform" -> P_TRANSFORM
                    "font-variant", "font-variant-caps" -> P_CAPS; "white-space" -> P_WS; "color" -> if (Theme.eink) -1 else P_COLOR
                    else -> -1
                }
                if (p >= 0) list.add(Decl(p, K_INHERIT, 0f, null, imp))
                return
            }
            when (name) {
                "font-style" -> when {
                    v.startsWith("italic") || v.startsWith("oblique") -> e(list, P_ITALIC, 1, imp)
                    v == "normal" -> e(list, P_ITALIC, 0, imp)
                }
                "font-weight" -> weight(v).let { if (it >= 0) e(list, P_BOLD, it, imp) }
                "font-size" -> fontSize(list, v, imp)
                "font-family" -> family(value).let { if (it.isNotEmpty()) list.add(Decl(P_FAMILY, K_ENUM, 0f, it, imp)) }
                "font-variant", "font-variant-caps" -> when {
                    v.contains("small-caps") -> e(list, P_CAPS, 1, imp)
                    v == "normal" || v == "none" -> e(list, P_CAPS, 0, imp)
                }
                "font" -> font(list, value, imp)
                "text-transform" -> when (v) {
                    "uppercase" -> e(list, P_TRANSFORM, 1, imp)
                    "lowercase" -> e(list, P_TRANSFORM, 2, imp)
                    "capitalize" -> e(list, P_TRANSFORM, 3, imp)
                    "none" -> e(list, P_TRANSFORM, 0, imp)
                }
                "text-align" -> when (v) {
                    "left" -> e(list, P_ALIGN, A_LEFT, imp)
                    "right", "end" -> e(list, P_ALIGN, A_RIGHT, imp)
                    "center" -> e(list, P_ALIGN, A_CENTER, imp)
                    "justify" -> e(list, P_ALIGN, A_JUSTIFY, imp)
                    "start" -> e(list, P_ALIGN, A_START, imp)
                }
                "text-indent" -> len(list, P_INDENT, v.substringBefore(' '), imp)
                "margin" -> sides(list, v, imp, P_MT, P_MR, P_MB, P_ML)
                "margin-left", "margin-inline-start" -> len(list, P_ML, v, imp)
                "margin-right", "margin-inline-end" -> len(list, P_MR, v, imp)
                "margin-top", "margin-block-start" -> len(list, P_MT, v, imp)
                "margin-bottom", "margin-block-end" -> len(list, P_MB, v, imp)
                "padding" -> sides(list, v, imp, -1, P_PR, -1, P_PL)
                "padding-left", "padding-inline-start" -> len(list, P_PL, v, imp)
                "padding-right", "padding-inline-end" -> len(list, P_PR, v, imp)
                "display" -> when {
                    v == "none" -> e(list, P_DISPLAY, D_NONE, imp)
                    v == "inline" || v == "contents" || v == "ruby" -> e(list, P_DISPLAY, D_INLINE, imp)
                    // inline-block : un pavé insécable n'a pas d'équivalent ici, l'élément garde sa nature.
                    v.startsWith("inline") -> e(list, P_DISPLAY, D_AUTO, imp)
                    v == "block" || v == "list-item" || v == "flex" || v == "grid" || v == "table" || v == "flow-root" -> e(list, P_DISPLAY, D_BLOCK, imp)
                }
                "white-space" -> when (v) {
                    "pre", "pre-wrap", "break-spaces" -> e(list, P_WS, WS_PRE, imp)
                    "pre-line" -> e(list, P_WS, WS_PRELINE, imp)
                    "nowrap" -> e(list, P_WS, WS_NOWRAP, imp)
                    "normal" -> e(list, P_WS, WS_NORMAL, imp)
                }
                "vertical-align" -> when (v) {
                    "super", "text-top" -> e(list, P_VA, 1, imp)
                    "sub", "text-bottom" -> e(list, P_VA, 2, imp)
                    "baseline", "top", "middle", "bottom" -> e(list, P_VA, 0, imp)
                    else -> {
                        // « vertical-align: 0.4em » ou « 30% » : appel de note relevé à la main.
                        val f = FloatArray(1)
                        val k = length(v, f)
                        if (k >= 0) {
                            val x = if (k == K_PCT) f[0] / 100f else f[0]
                            e(list, P_VA, if (x > 0.15f) 1 else if (x < -0.15f) 2 else 0, imp)
                        }
                    }
                }
                "text-decoration", "text-decoration-line" -> {
                    var m = 0
                    if (v.contains("underline")) m = m or 1
                    if (v.contains("line-through")) m = m or 2
                    if (m != 0 || v.startsWith("none")) e(list, P_DECO, m, imp)
                }
                "hyphens", "hyphenate" -> when (v) {
                    "none", "manual" -> e(list, P_HYPH, 1, imp)
                    "auto" -> e(list, P_HYPH, 0, imp)
                }
                "page-break-before", "break-before" -> when (v) {
                    "always", "page", "left", "right", "recto", "verso" -> e(list, P_PBB, 1, imp)
                    "auto", "avoid", "avoid-page" -> e(list, P_PBB, 0, imp)
                }
                "page-break-after", "break-after" -> when (v) {
                    "always", "page", "left", "right", "recto", "verso" -> e(list, P_PBA, 1, imp)
                    "auto", "avoid", "avoid-page" -> e(list, P_PBA, 0, imp)
                }
                "letter-spacing" -> if (v == "normal") list.add(Decl(P_SPACING, K_EM, 0f, null, imp)) else {
                    val f = FloatArray(1)
                    val k = length(v, f)
                    if (k == K_EM || k == K_ABS) list.add(Decl(P_SPACING, k, f[0], null, imp))
                }
                "float" -> e(list, P_FLOAT, if (v == "left" || v == "right") 1 else 0, imp)
                "list-style", "list-style-type" -> if (v.contains("none")) e(list, P_LIST, 1, imp) else e(list, P_LIST, 0, imp)
                // Couleur du texte : jamais sur liseuse (noir sur blanc, rien d'autre).
                "color" -> if (!Theme.eink) color(v).let { if (it != NO_COLOR) list.add(Decl(P_COLOR, K_ENUM, it.toFloat(), null, imp)) }
            }
        }

        private const val NO_COLOR = 1

        private val NAMED = hashMapOf(
            "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
            "gray" to 0x808080, "grey" to 0x808080, "silver" to 0xC0C0C0, "maroon" to 0x800000, "navy" to 0x000080,
            "olive" to 0x808000, "purple" to 0x800080, "teal" to 0x008080, "aqua" to 0x00FFFF, "cyan" to 0x00FFFF,
            "fuchsia" to 0xFF00FF, "magenta" to 0xFF00FF, "lime" to 0x00FF00, "yellow" to 0xFFFF00, "orange" to 0xFFA500,
            "brown" to 0xA52A2A, "darkred" to 0x8B0000, "crimson" to 0xDC143C, "firebrick" to 0xB22222, "darkblue" to 0x00008B,
            "darkgreen" to 0x006400, "darkgray" to 0xA9A9A9, "darkgrey" to 0xA9A9A9, "dimgray" to 0x696969, "dimgrey" to 0x696969,
            "lightgray" to 0xD3D3D3, "lightgrey" to 0xD3D3D3, "slategray" to 0x708090, "indigo" to 0x4B0082, "gold" to 0xFFD700,
            "sienna" to 0xA0522D, "saddlebrown" to 0x8B4513, "chocolate" to 0xD2691E, "tomato" to 0xFF6347, "royalblue" to 0x4169E1,
            "steelblue" to 0x4682B4, "midnightblue" to 0x191970, "darkslategray" to 0x2F4F4F, "seagreen" to 0x2E8B57,
            "forestgreen" to 0x228B22, "darkorange" to 0xFF8C00, "violet" to 0xEE82EE, "pink" to 0xFFC0CB, "tan" to 0xD2B48C,
        )

        private fun lum(c: Int): Double {
            fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch((c shr 16) and 255) + 0.7152 * ch((c shr 8) and 255) + 0.0722 * ch(c and 255)
        }

        /**
         * Couleur ARGB d'une valeur CSS, 0 pour « celle du thème », [NO_COLOR] si elle n'est pas
         * comprise. Deux garde-fous de lisibilité : un noir ou quasi-noir rend la couleur du thème
         * (le brun du texte, pas du noir), et une couleur trop peu contrastée sur le fond aussi.
         */
        private fun color(v: String): Int {
            var rgb = -1
            if (v.startsWith("#")) {
                val h = v.substring(1)
                val x = h.toLongOrNull(16) ?: return NO_COLOR
                rgb = when (h.length) {
                    3, 4 -> { val s = if (h.length == 4) (x shr 4).toInt() else x.toInt(); ((s shr 8 and 15) * 17 shl 16) or ((s shr 4 and 15) * 17 shl 8) or ((s and 15) * 17) }
                    6 -> x.toInt()
                    8 -> (x shr 8).toInt()
                    else -> return NO_COLOR
                }
            } else if (v.startsWith("rgb")) {
                val a = v.indexOf('(')
                val b = v.indexOf(')')
                if (a < 0 || b < a) return NO_COLOR
                val p = v.substring(a + 1, b).split(',', ' ', '/').filter { it.isNotEmpty() }
                if (p.size < 3) return NO_COLOR
                var out = 0
                for (k in 0..2) {
                    val t = p[k]
                    val n = (if (t.endsWith("%")) t.dropLast(1).toFloatOrNull()?.times(2.55f) else t.toFloatOrNull()) ?: return NO_COLOR
                    out = (out shl 8) or n.toInt().coerceIn(0, 255)
                }
                rgb = out
            } else if (v == "currentcolor" || v == "transparent") return NO_COLOR
            else rgb = NAMED[v] ?: return NO_COLOR
            rgb = rgb and 0xFFFFFF
            if (maxOf((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255) < 0x40) return 0
            val l1 = lum(rgb)
            val l2 = lum(Theme.bg and 0xFFFFFF)
            val contrast = (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
            if (contrast < 3.0) return 0
            return rgb or (0xFF shl 24)
        }

        /** Raccourci « font: italic bold 1.2em/1.4 "Nom", serif ». */
        private fun font(list: ArrayList<Decl>, value: String, imp: Boolean) {
            val v = value.trim()
            var i = 0
            var italic = 0
            var bold = 0
            var caps = 0
            val n = v.length
            while (i < n) {
                while (i < n && v[i] == ' ') i++
                var e = i
                while (e < n && v[e] != ' ' && v[e] != ',') e++
                if (e == i) return
                val tok = v.substring(i, e).lowercase()
                when {
                    tok == "italic" || tok == "oblique" -> italic = 1
                    tok == "small-caps" -> caps = 1
                    tok == "normal" -> {}
                    weight(tok) >= 0 && tok != "normal" -> bold = weight(tok)
                    else -> {
                        val size = tok.substringBefore('/')
                        val tmp = ArrayList<Decl>(1)
                        if (!fontSize(tmp, size, imp)) return     // mot-clé système (caption, menu…) : ignoré
                        e(list, P_ITALIC, italic, imp)
                        e(list, P_BOLD, bold, imp)
                        e(list, P_CAPS, caps, imp)
                        list.addAll(tmp)
                        // « 1em / 1.4 Nom » : l'interligne peut être détaché.
                        var r = v.substring(e).trim()
                        if (r.startsWith("/")) r = r.substring(1).trim().substringAfter(' ', "")
                        val fam = family(r)
                        if (fam.isNotEmpty()) list.add(Decl(P_FAMILY, K_ENUM, 0f, fam, imp))
                        return
                    }
                }
                i = e
            }
        }
    }
}

/** Style du texte courant du livre : c'est par rapport à lui que tout le reste est mesuré. */
class Ref(
    val size: Float,
    /** Premier nom de la famille du texte courant, ou null. */
    val family: String?,
    /** Vrai si un style de prose a été reconnu (les quatre valeurs suivantes en viennent). */
    val prose: Boolean,
    val indent: Float,
    val margin: Float,
    val align: Int,
    val left: Float, val right: Float, val leftP: Float, val rightP: Float,
)

/** Famille de polices prête à l'emploi ; les variantes absentes sont simulées. */
class Face(val regular: Typeface, val italic: Typeface?, val bold: Typeface?, val boldItalic: Typeface?) {
    fun pick(b: Boolean, i: Boolean): Typeface = when {
        b && i -> boldItalic ?: bold ?: italic ?: regular
        b -> bold ?: regular
        i -> italic ?: regular
        else -> regular
    }
    fun fakeBold(b: Boolean, i: Boolean) = b && bold == null && (!i || boldItalic == null)
    fun skew(b: Boolean, i: Boolean) = i && italic == null && (!b || boldItalic == null)
}

/**
 * Les règles applicables à un chapitre (feuille par défaut, feuilles liées, <style>), indexées
 * par leur dernier sélecteur simple, et l'arbre des contextes déjà résolus. Partagé entre les
 * chapitres qui lient les mêmes feuilles, et entre les fils d'analyse.
 */
class SheetSet(val css: BookCss, sheets: List<Sheet>) {
    private val byTag = HashMap<String, ArrayList<Rule>>()
    private val byClass = HashMap<String, ArrayList<Rule>>()
    private val byId = HashMap<String, ArrayList<Rule>>()
    private val any = ArrayList<Rule>()
    private val classes = HashSet<String>()
    private val ids = HashSet<String>()
    private var sib = false
    private val fontFaces = HashMap<String, ArrayList<FontFace>>()
    private val sigs = ConcurrentHashMap<String, Sig>()
    private val faces = ConcurrentHashMap<String, Any>()
    private val count = AtomicInteger()
    val root = Node(null, Sig("", null, null, 0), null, CStyle(null), true)

    init {
        var order = 0L
        for ((k, sh) in sheets.withIndex()) {
            // La feuille par défaut (la première) cède devant toute règle du livre, quelle que soit sa précision.
            val origin = if (k == 0) 0L else 1L
            for (i in sh.sels.indices) {
                val sel = sh.sels[i]
                val r = Rule(sel, sh.decls[i], (origin shl 44) or (sel.spec.toLong() shl 22) or order++)
                val last = sel.last
                for (p in sel.parts) {
                    p.id?.let { ids.add(it) }
                    p.classes?.let { for (c in it) classes.add(c) }
                }
                if (!sib && sel.sib) sib = true
                val id = last.id
                val cl = last.classes
                when {
                    id != null -> byId.getOrPut(id) { ArrayList() }.add(r)
                    cl != null -> byClass.getOrPut(cl[0]) { ArrayList() }.add(r)
                    last.tag != null -> byTag.getOrPut(last.tag) { ArrayList() }.add(r)
                    else -> any.add(r)
                }
            }
            for (f in sh.faces) fontFaces.getOrPut(f.family) { ArrayList() }.add(f)
        }
    }

    /** Nœud de l'élément [nm] que [t] vient d'ouvrir, enfant de [parent] et précédé de [prev]. */
    fun child(parent: Node, prev: Sig?, nm: String, t: Tok): Node {
        var cls: String? = null
        var id: String? = null
        var style: String? = null
        if (t.hasAttrs()) {
            cls = t.attr("class")?.let { known(it) }
            id = t.attr("id")?.takeIf { it in ids }
            style = t.attr("style")?.trim()?.takeIf { it.isNotEmpty() }
        }
        val p = if (sib) prev else null
        val kids = parent.kids ?: synchronized(parent) { parent.kids ?: ConcurrentHashMap<String, Array<Node>>(8).also { parent.kids = it } }
        val known = kids[nm]
        if (known != null) for (n in known) if (n.prev === p && n.sig.id == id && n.cls == cls && n.inline == style) return n
        val sk = if (cls == null && id == null) nm else "$nm\u0002${cls ?: ""}\u0003${id ?: ""}"
        val sig = sigs[sk] ?: Sig(nm, id, cls?.split(' ')?.toTypedArray(), sigs.size + 1).let { sigs.putIfAbsent(sk, it) ?: it }
        val node = Node(parent, sig, p, cascade(parent, p, sig, style?.let { css.inline(it) }), nm in BLOCK_TAGS || nm in WRAPPERS, cls, style)
        // Bornes : un document qui multiplie les contextes (styles en ligne tous différents,
        // imbrication sans fin) est résolu balise par balise, sans rien garder de plus.
        if (count.get() >= MAX_NODES) return node
        synchronized(kids) {
            val cur = kids[nm]
            if (cur == null) kids[nm] = arrayOf(node)
            else {
                for (n in cur) if (n.prev === p && n.sig.id == id && n.cls == cls && n.inline == style) return n
                if (cur.size >= MAX_VARIANTS) return node
                kids[nm] = cur + node
            }
            count.incrementAndGet()
        }
        return node
    }

    /** Classes de [raw] citées par au moins une règle, séparées par une espace ; null s'il n'y en a pas. */
    private fun known(raw: String): String? {
        var single = true
        for (c in raw) if (c <= ' ') { single = false; break }
        if (single) return if (raw in classes) raw else null
        var sb: StringBuilder? = null
        var i = 0
        val n = raw.length
        while (i < n) {
            while (i < n && raw[i] <= ' ') i++
            var e = i
            while (e < n && raw[e] > ' ') e++
            if (e > i) {
                val c = raw.substring(i, e)
                if (c in classes) {
                    if (sb == null) sb = StringBuilder() else sb.append(' ')
                    sb.append(c)
                }
            }
            i = e
        }
        return sb?.toString()
    }

    private fun cascade(parent: Node, prev: Sig?, sig: Sig, inline: Array<Decl>?): CStyle {
        var m: ArrayList<Rule>? = null
        val budget = intArrayOf(3000)
        fun collect(l: ArrayList<Rule>?) {
            if (l == null) return
            for (r in l) if (r.sel.matches(sig, prev, parent, budget)) (m ?: ArrayList<Rule>(8).also { m = it }).add(r)
        }
        collect(byTag[sig.tag])
        sig.classes?.let { for (c in it) collect(byClass[c]) }
        sig.id?.let { collect(byId[it]) }
        collect(any)
        val ps = parent.style
        val s = CStyle(ps)
        if (sig.tag in BLOCK_TAGS) s.va = 0
        val rules = m
        if (rules == null && inline == null) return s
        if (rules != null && rules.size > 1) rules.sortWith { a, b -> a.key.compareTo(b.key) }
        // Le corps d'abord (les longueurs en em en dépendent), puis le reste ; les !important en dernier.
        for (pass in 0..3) {
            val size = pass < 2
            val imp = pass == 1 || pass == 3
            if (rules != null) for (r in rules) for (d in r.decls) if (d.important == imp && (d.prop == P_SIZE) == size) s.apply(d, ps)
            if (inline != null) for (d in inline) if (d.important == imp && (d.prop == P_SIZE) == size) s.apply(d, ps)
        }
        // Lettrine : la lettre reste dans le texte, à sa taille.
        if (s.floated && nmInline(sig.tag)) { s.size = ps.size; s.va = 0 }
        return s
    }

    private fun nmInline(tag: String) = tag !in BLOCK_TAGS

    /** Police d'un texte de famille [family] ; null = celle du texte courant (Bookerly). */
    fun face(family: String?): Face? {
        if (family == null) return null
        val got = faces[family]
        if (got != null) return got as? Face
        val f = resolve(family)
        if (faces.size < 200) faces[family] = f ?: NO_FACE
        return f
    }

    private fun resolve(family: String): Face? {
        val ref = css.ref
        var first = true
        for (name in family.split(',')) {
            // Même famille que le texte courant : c'est du texte courant.
            if (first && ref != null && name == ref.family) return null
            first = false
            val emb = fontFaces[name]
            if (emb != null) css.embedded(name, emb)?.let { return it }
            when (generic(name)) {
                1 -> return null
                2 -> return css.sans
                3 -> return css.mono
                4 -> return css.cursive
            }
        }
        return null
    }

    companion object {
        const val MAX_NODES = 5_000
        const val MAX_VARIANTS = 48

        // Conteneurs de blocs que l'analyseur ne connaît pas : leurs marges comptent comme celles d'un bloc.
        private val WRAPPERS = hashSetOf("hgroup", "details", "summary", "fieldset", "form", "legend", "dialog", "menu")
        private val NO_FACE = Any()

        /** 1 = à empattements, 2 = bâton, 3 = chasse fixe, 4 = manuscrite ou de fantaisie, 0 = inconnue. */
        private fun generic(name: String): Int = when (name) {
            "serif", "georgia", "times", "times new roman", "garamond", "palatino", "book antiqua", "cambria", "baskerville", "bookerly" -> 1
            "sans-serif", "sans", "arial", "helvetica", "helvetica neue", "verdana", "tahoma", "trebuchet ms", "gill sans", "futura",
            "calibri", "roboto", "open sans", "lato", "segoe ui", "lucida grande", "lucida sans", "geneva", "optima", "system-ui", "ui-sans-serif" -> 2
            "monospace", "courier", "courier new", "consolas", "menlo", "monaco", "lucida console", "andale mono", "ui-monospace" -> 3
            "cursive", "fantasy", "comic sans ms", "brush script mt", "zapf chancery", "zapfino" -> 4
            else -> 0
        }
    }
}

/** Tout ce qui se garde du CSS d'un livre ouvert : feuilles analysées, jeux de règles, polices, texte de référence. */
class BookCss(private val epub: Epub) {
    private val sheets = ConcurrentHashMap<String, Sheet>()
    private val sets = ConcurrentHashMap<String, SheetSet>()
    private val inlines = ConcurrentHashMap<String, Array<Decl>>()
    private val typefaces = ConcurrentHashMap<String, Any>()
    private val families = ConcurrentHashMap<String, Any>()
    private val encrypted: HashSet<String> by lazy { readEncrypted() }

    @Volatile var ref: Ref? = null
        private set
    @Volatile private var fonts: Fonts? = null

    /** Relevé en cours pour [ref] : caractères et blocs par style de paragraphe. */
    var stats: HashMap<String, IntArray>? = null
        private set

    val sans: Face? get() = fonts?.let { Face(it.ui, null, it.uiBold, null) }
    val mono: Face by lazy { Face(Typeface.MONOSPACE, null, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD), null) }
    // Manuscrite ou de fantaisie, sans police embarquée : l'italique du texte, ce qui s'en approche le plus.
    val cursive: Face? get() = fonts?.let { Face(it.italic, it.italic, it.boldItalic, it.boldItalic) }

    /** Déclarations d'un attribut style="" (les mêmes reviennent d'un paragraphe à l'autre). */
    fun inline(style: String): Array<Decl> {
        inlines[style]?.let { return it }
        val d = try { CssParser.declarations(style, 0, style.length) } catch (e: Exception) { arrayOf() }
        if (inlines.size < 4000) inlines[style] = d
        return d
    }

    private fun readBytes(path: String, max: Int): ByteArray? = try {
        epub.open(path)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16384)
            var ok = true
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                if (out.size() > max) { ok = false; break }
            }
            if (ok) out.toByteArray() else null
        }
    } catch (e: Exception) {
        null
    }

    private fun sheet(path: String, depth: Int): Sheet {
        sheets[path]?.let { return it }
        val t0 = System.nanoTime()
        val sh = Sheet()
        // Au-delà d'une taille raisonnable, la feuille est ignorée en entier.
        val bytes = readBytes(path, MAX_SHEET)
        if (bytes != null) {
            var text = String(bytes, Charsets.UTF_8)
            if (text.isNotEmpty() && text[0] == '﻿') text = text.substring(1)
            CssParser(text, path, sh, if (depth == 0) { p -> sheet(p, 1) } else null).parse()
        }
        Perf.mark("css.feuille $path (${sh.sels.size} règles)", t0)
        return sheets.putIfAbsent(path, sh) ?: sh
    }

    /** Jeu de règles d'un chapitre : feuilles liées [links] (chemins dans le zip) puis contenu des <style>. */
    fun set(links: List<String>, style: String?, base: String): SheetSet {
        val key = if (style == null && links.size == 1) links[0] else links.joinToString("\u0001") + "\u0002" + (style ?: "")
        sets[key]?.let { return it }
        val list = ArrayList<Sheet>(links.size + 2)
        list.add(UA)
        for (l in links) list.add(sheet(l, 0))
        if (style != null && style.length <= MAX_SHEET) list.add(CssParser(style, base, Sheet()) { p -> sheet(p, 1) }.parse())
        val s = SheetSet(this, list)
        if (sets.size >= 16) sets.clear()
        return sets.putIfAbsent(key, s) ?: s
    }

    /**
     * Texte de référence du livre, établi une fois : le style de paragraphe qui porte le plus de
     * texte dans ses deux plus gros chapitres. Son corps vaut le corps choisi par le lecteur, sa
     * famille vaut Bookerly, et ses alinéas et marges sont ceux de la maison.
     */
    fun prepare(f: Fonts): Ref {
        ref?.let { return it }
        synchronized(this) {
            ref?.let { return it }
            if (stats != null) return NEUTRAL       // relevé en cours, sur ce même fil
            fonts = f
            val t0 = System.nanoTime()
            val st = HashMap<String, IntArray>()
            stats = st
            try {
                val n = epub.spine.size
                val order = (0 until n).sortedByDescending { epub.cum[it + 1] - epub.cum[it] }
                for (k in 0 until minOf(2, n)) {
                    val path = epub.spine[order[k]]
                    var html = epub.read(path) ?: continue
                    if (html.length > SAMPLE) html = html.substring(0, SAMPLE)
                    parseChapter(html, path, f, this)
                }
            } catch (e: Throwable) {
            }
            stats = null
            val r = pick(st)
            // Les contextes résolus pendant le relevé ne dépendent pas de la référence : ils restent.
            ref = r
            Perf.mark("css.référence (corps ${r.size}, famille ${r.family}, alinéa ${r.indent}, prose ${r.prose})", t0)
            return r
        }
    }

    private fun pick(st: HashMap<String, IntArray>): Ref {
        // Clé : corps|famille|alinéa|marge|alignement|gauche|droite|gauche %|droite %
        var bestBody = ""
        var bodyChars = 0
        val body = HashMap<String, Int>()
        for ((k, v) in st) {
            val cut = k.indexOf('|', k.indexOf('|') + 1)
            val b = k.substring(0, cut)
            val c = (body[b] ?: 0) + v[0]
            body[b] = c
            if (c > bodyChars || (c == bodyChars && b < bestBody)) { bodyChars = c; bestBody = b }
        }
        if (bodyChars == 0) return NEUTRAL
        var best: String? = null
        var chars = 0
        for ((k, v) in st) {
            if (!k.startsWith("$bestBody|")) continue
            // De la prose : des paragraphes d'une certaine longueur, ni centrés ni en sommaire.
            if (v[0] < v[1] * 90L) continue
            if (v[0] > chars || (v[0] == chars && best != null && k < best)) { chars = v[0]; best = k }
        }
        val b = bestBody.split('|')
        val size = b[0].toFloatOrNull() ?: 1f
        val family = b[1].ifEmpty { null }
        val p = best?.split('|') ?: return Ref(size, family, false, 0f, 0f, A_START, 0f, 0f, 0f, 0f)
        fun f(i: Int) = p[i].toFloatOrNull() ?: 0f
        return Ref(size, family, true, f(2), f(3), p[4].toIntOrNull() ?: A_START, f(5), f(6), f(7), f(8))
    }

    /** Famille embarquée [name] déclarée par @font-face, ou null si aucune variante n'est lisible. */
    fun embedded(name: String, decl: List<FontFace>): Face? {
        val got = families[name]
        if (got != null) return got as? Face
        synchronized(families) {
            families[name]?.let { return it as? Face }
            val v = arrayOfNulls<Typeface>(4)
            for (d in decl) {
                val k = (if (d.bold) 2 else 0) + (if (d.italic) 1 else 0)
                if (v[k] == null) v[k] = typeface(d.path)
            }
            val reg = v[0]
            // Une famille déclarée seulement en italique ou en gras sert telle quelle.
            val face = if (reg != null) Face(reg, v[1], v[2], v[3]) else (v[1] ?: v[2] ?: v[3])?.let { Face(it, it, it, it) }
            families[name] = face ?: MISSING
            return face
        }
    }

    private fun typeface(path: String): Typeface? {
        val got = typefaces[path]
        if (got != null) return got as? Typeface
        var tf: Typeface? = null
        val t0 = System.nanoTime()
        // Polices chiffrées ou brouillées (encryption.xml) : non lues, la famille générique prend le relais.
        if (typefaces.size < MAX_FONTS && path !in encrypted) try {
            val bytes = readBytes(path, MAX_FONT)
            if (bytes != null && bytes.size > 100) {
                val tmp = File.createTempFile("police", ".ttf")
                try {
                    tmp.writeBytes(bytes)
                    tf = Typeface.Builder(tmp).build()
                } finally {
                    tmp.delete()
                }
                // Une police aux métriques aberrantes déréglerait l'interligne : refusée.
                tf?.let {
                    val fm = Paint().apply { typeface = it; textSize = 100f }.fontMetrics
                    val h = fm.descent - fm.ascent
                    if (h.isNaN() || h < 60f || h > 220f) tf = null
                }
            }
        } catch (e: Throwable) {
            tf = null
        }
        Perf.mark("css.police $path (${if (tf != null) "chargée" else "refusée"})", t0)
        typefaces[path] = tf ?: MISSING
        return tf
    }

    private fun readEncrypted(): HashSet<String> {
        val out = HashSet<String>()
        try {
            val src = readBytes("META-INF/encryption.xml", 1 shl 20)?.let { String(it, Charsets.UTF_8) } ?: return out
            val t = Tok(src)
            while (true) {
                val e = t.next()
                if (e == Tok.END) break
                if (e == Tok.OPEN && t.name == "cipherreference") t.attr("URI")?.let { out.add(resolvePath("", it)) }
            }
        } catch (e: Exception) {
        }
        return out
    }

    companion object {
        const val MAX_SHEET = 512 * 1024
        const val MAX_FONT = 8 * 1024 * 1024
        const val MAX_FONTS = 12
        const val SAMPLE = 60_000
        private val MISSING = Any()
        val NEUTRAL = Ref(1f, null, false, 0f, 0f, A_START, 0f, 0f, 0f, 0f)

        /** Feuille par défaut : ce que tout navigateur applique sans qu'on le lui demande. */
        val UA: Sheet by lazy {
            CssParser(
                """
                i, em, cite, var, dfn { font-style: italic }
                b, strong { font-weight: bold }
                sup { vertical-align: super; font-size: 0.7em }
                sub { vertical-align: sub; font-size: 0.7em }
                small { font-size: 0.83em }
                big { font-size: 1.17em }
                u, ins { text-decoration: underline }
                s, strike, del { text-decoration: line-through }
                code, kbd, samp, tt { font-family: monospace }
                pre { font-family: monospace; white-space: pre; margin: 1em 0 }
                center { text-align: center }
                p { margin: 1em 0 }
                blockquote { margin: 1em 1.6em }
                dd { margin-left: 1.6em }
                """, "", Sheet(), null,
            ).parse()
        }
    }
}
