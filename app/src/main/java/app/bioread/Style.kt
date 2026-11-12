package app.bioread

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import java.util.concurrent.ConcurrentHashMap

/*
 * Mode « Fidèle au livre » : du style calculé (Css.kt) aux blocs et à leur mise en page.
 *
 * Principes :
 *  - les réglages du lecteur priment : tout est mesuré par rapport au texte courant du livre
 *    ([Ref]), qui reste composé comme sans le mode (Bookerly, corps choisi, justifié, alinéa
 *    maison) ; seuls les écarts voulus par l'éditeur sont rendus ;
 *  - le texte des blocs garde la même longueur que sans le mode : les positions (lecture,
 *    recherche, ancres, liens, notes) restent valables d'un mode à l'autre. Capitales et petites
 *    capitales remplacent chaque lettre par une seule autre ; ce qui est masqué reste dans le
 *    texte (bloc non mis en page, ou passage de largeur nulle).
 */

/** Mise en forme d'un bloc voulue par le livre. Longueurs en cadratins du corps du texte courant. */
class BlockStyle {
    @JvmField var align = 0          // 0 = justifié, 1 = centré, 2 = droite, 3 = en drapeau ; titre : -1 = selon la maison
    @JvmField var left = 0f
    @JvmField var right = 0f
    @JvmField var leftP = 0f         // parts proportionnelles à la largeur du texte
    @JvmField var rightP = 0f
    @JvmField var indent = 0f        // retrait de première ligne (négatif = sommaire, pour les vers)
    @JvmField var top = 0f           // blanc avant le bloc
    @JvmField var pageBreak = false
    @JvmField var ratio = 1f         // corps, par rapport au texte courant
    @JvmField var face: Typeface? = null     // null = Bookerly
    @JvmField var noHyphen = false
    @JvmField var spacing = 0f
    @JvmField var marks = false      // le texte porte des [LineMark]
}

/** Police imposée à un passage (famille différente de celle du bloc, ou variante simulée). */
class FaceSpan(private val tf: Typeface?, private val fakeBold: Boolean, private val skew: Boolean) : MetricAffectingSpan() {
    override fun updateDrawState(p: TextPaint) = apply(p)
    override fun updateMeasureState(p: TextPaint) = apply(p)
    private fun apply(p: TextPaint) {
        if (tf != null) p.typeface = tf
        if (fakeBold) p.isFakeBoldText = true
        if (skew) p.textSkewX = -0.2f
    }
}

/** Interlettrage ajouté, en cadratins. */
class TrackSpan(private val em: Float) : MetricAffectingSpan() {
    override fun updateDrawState(p: TextPaint) { p.letterSpacing += em }
    override fun updateMeasureState(p: TextPaint) { p.letterSpacing += em }
}

/** Passage masqué (display: none dans une ligne) : il reste dans le texte, sans largeur ni tracé. */
class HiddenSpan : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) = 0
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {}
}

/** Ligne d'un bloc (vers, ligne d'adresse) : marges en cadratins, converties en pixels à la composition. */
class LineMark(val left: Float, val first: Float)

/**
 * Blanc entre deux lignes d'un même bloc (strophe, signature), en cadratins approximatifs. Posé
 * sur le retour à la ligne qui précède : il allonge le bas de la dernière ligne avant le blanc.
 */
class GapSpan(private val em: Float) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
        if (text is Spanned && text.getSpanEnd(this) == end) {
            val extra = ((fm.descent - fm.ascent) * 0.6f * em).toInt()
            fm.descent += extra
            fm.bottom += extra
        }
    }
}

/**
 * Suivi du style pendant l'analyse d'un chapitre (voir [parseChapter]), sur le modèle de
 * [LinkScan] : l'analyseur signale balises et texte, et demande ici le texte mis en forme et le
 * style de chaque bloc. Une erreur interne coupe le suivi pour la fin du chapitre, sans plus.
 */
class StyleScan(private val css: BookCss, html: String, base: String, private val fonts: Fonts) {
    private val ref: Ref = css.prepare(fonts)
    private val stats = css.stats
    private val set: SheetSet
    private var dead = false

    // Pile des éléments ouverts ; nodes[0] est la racine.
    private var nodes = arrayOfNulls<Node>(32)
    private var lasts = arrayOfNulls<Sig>(32)      // dernier enfant de chaque élément (sélecteur « + »)
    private var lineAt = IntArray(32)              // début de la ligne ouverte par un display: block en ligne, sinon -1
    private var top = 0
    private var over = 0

    // Passages du bloc en cours : [début, fin) et élément.
    private var runA = IntArray(16)
    private var runB = IntArray(16)
    private var runN = arrayOfNulls<Node>(16)
    private var runs = 0

    private class Line(val a: Int, val b: Int, val node: Node?, val indent: Float, val gap: Boolean)
    private val lines = ArrayList<Line>()

    private var blockNode: Node? = null
    private var voidStyle: CStyle? = null
    private var needBreak = false
    private var allGone = false
    private var marked = false

    // Marges verticales fusionnées depuis le dernier bloc, et saut de page demandé.
    private var vgap = 0f
    private var pend = 0f
    private var breakNext = false
    private var pendBreak = false

    // Bloc précédent, pour l'alinéa maison et les blancs.
    private var prevPara = false
    private var prevProse = false
    private var prevHead = false
    private var prevAlign = 0
    private var prevLeft = 0f
    private var prevRight = 0f
    private var prevRatio = 1f
    private var prevFace: Typeface? = null

    init {
        // En-tête du document : feuilles liées et <style>.
        val links = ArrayList<String>(2)
        var style: StringBuilder? = null
        val t = Tok(html)
        var inStyle = false
        var guard = 0
        scan@ while (guard++ < 3000) {
            when (t.next()) {
                Tok.END -> break@scan
                Tok.OPEN -> when (t.name) {
                    "body" -> break@scan
                    "link" -> {
                        val rel = t.attr("rel")?.lowercase()
                        if (rel != null && rel.contains("stylesheet") && !rel.contains("alternate") && links.size < 16) {
                            val h = t.attr("href")
                            if (h != null && h.isNotEmpty() && h.indexOf(':') < 0) links.add(resolvePath(base, h))
                        }
                    }
                    "style" -> inStyle = !t.selfClosing
                }
                Tok.TEXT -> if (inStyle) {
                    val sb = style ?: StringBuilder().also { style = it }
                    if (sb.length < BookCss.MAX_SHEET) appendDecoded(sb, t.s, t.textStart, t.textEnd, false)
                    sb.append('\n')
                }
                Tok.CLOSE -> when (t.name) {
                    "style" -> inStyle = false
                    "head" -> break@scan
                }
            }
        }
        set = css.set(links, style?.toString(), base)
        nodes[0] = set.root
        lineAt[0] = -1
    }

    private fun settle() {
        if (pend > 0f) {
            if (pend > vgap) vgap = pend
            pend = 0f
        }
        if (pendBreak) {
            breakNext = true
            pendBreak = false
        }
    }

    /** Balise ouvrante [nm] (appelée pour toutes, y compris vides et inconnues de l'analyseur). */
    fun open(t: Tok, nm: String, sb: StringBuilder) {
        if (dead) return
        try {
            settle()
            val node = set.child(nodes[top]!!, lasts[top], nm, t)
            lasts[top] = node.sig
            val st = node.style
            voidStyle = null
            if (t.selfClosing || nm in VOID) {
                voidStyle = st
                // <div class="saut"/> : seul le saut de page compte.
                if (!st.gone && (st.pbb || st.pba)) pendBreak = true
                return
            }
            if (top >= MAX_DEPTH) { over++; return }
            top++
            if (top == nodes.size) {
                nodes = nodes.copyOf(top * 2)
                lasts = lasts.copyOf(top * 2)
                lineAt = lineAt.copyOf(top * 2)
            }
            nodes[top] = node
            lasts[top] = null
            lineAt[top] = -1
            if (st.gone) return
            if (node.blockTag) {
                pend = st.mt
                if (st.pbb) pendBreak = true
            } else if (st.display == D_BLOCK) {
                lineBreak(sb)
                lineAt[top] = sb.length
            }
        } catch (e: Exception) {
            dead = true
        }
    }

    /** Balise fermante [nm], avant que l'analyseur ne range le texte du bloc. */
    fun close(nm: String, sb: StringBuilder) {
        if (dead) return
        try {
            settle()
            voidStyle = null
            if (over > 0) { over--; return }
            var i = top
            while (i > 0 && nodes[i]!!.sig.tag != nm) i--
            if (i == 0) return
            for (k in top downTo i) {
                val node = nodes[k]!!
                val st = node.style
                if (!st.gone) {
                    if (node.blockTag) {
                        if (st.mb > pend) pend = st.mb
                        if (st.pba) pendBreak = true
                    } else if (lineAt[k] >= 0) {
                        if (sb.length > lineAt[k] && lines.size < MAX_LINES) lines.add(Line(lineAt[k], sb.length, node, 0f, false))
                        needBreak = true
                    }
                }
                nodes[k] = null
                lasts[k] = null
            }
            top = i - 1
        } catch (e: Exception) {
            dead = true
        }
    }

    /**
     * Début d'une ligne (élément en ligne passé en display: block) : il faut un retour à la ligne
     * juste avant. L'espace laissée par le balisage en tient lieu, le texte ne s'allonge pas ;
     * faute d'espace (« </span><span> » collés), un caractère est ajouté.
     */
    private fun lineBreak(sb: StringBuilder) {
        needBreak = false
        val n = sb.length
        if (n == 0 || (n == 2 && sb[0] == '•')) return
        var j = n
        var nl = 0
        while (j > 0 && (sb[j - 1] == ' ' || sb[j - 1] == '\n')) {
            if (sb[j - 1] == '\n') nl++
            j--
        }
        if (j == 0) return
        if (j == n) { sb.append('\n'); return }
        // Les espaces d'abord, les retours à la ligne ensuite : pas d'espace en tête de ligne.
        if (nl == 0) nl = 1
        for (k in j until n) sb.setCharAt(k, if (k < n - nl) ' ' else '\n')
    }

    /** Texte s[a, b) du nœud courant : remplace l'appel direct à [appendDecoded]. */
    fun text(sb: StringBuilder, s: String, a: Int, b: Int) {
        if (dead) { appendDecoded(sb, s, a, b, true); return }
        val before = sb.length
        try {
            settle()
            voidStyle = null
            val node = nodes[top]!!
            val st = node.style
            var n0 = sb.length
            if (st.ws == WS_NORMAL || st.ws == WS_NOWRAP || st.gone) appendDecoded(sb, s, a, b, true) else pre(sb, s, a, b, st.ws == WS_PRE)
            if (sb.length == n0) return
            if (needBreak) {
                needBreak = false
                if (n0 > 0) {
                    if (sb[n0] == ' ') sb.setCharAt(n0, '\n')
                    else if (sb[n0] != '\n' && sb[n0 - 1] != '\n') {
                        if (sb[n0 - 1] == ' ') sb.setCharAt(n0 - 1, '\n') else { sb.insert(n0, '\n'); n0++ }
                    }
                }
            }
            if (runs == 0) blockNode = node.block
            if (runs > 0 && runN[runs - 1] === node && runB[runs - 1] == n0) runB[runs - 1] = sb.length
            else if (runs < MAX_RUNS) {
                if (runs == runA.size) {
                    runA = runA.copyOf(runs * 2)
                    runB = runB.copyOf(runs * 2)
                    runN = runN.copyOf(runs * 2)
                }
                runA[runs] = n0
                runB[runs] = sb.length
                runN[runs] = node
                runs++
            }
        } catch (e: Exception) {
            dead = true
            if (sb.length == before) appendDecoded(sb, s, a, b, true)
        }
    }

    /**
     * white-space: pre, pre-wrap ou pre-line. Les espaces sont réduites comme d'habitude (même
     * longueur de texte), mais une suite qui contient un retour à la ligne devient un retour à la
     * ligne ; une ligne vide devient un blanc, et en « pre » le retrait de début de ligne est rendu
     * par une marge.
     */
    private fun pre(sb: StringBuilder, s: String, a: Int, b: Int, keepIndent: Boolean) {
        var i = a
        while (i < b) {
            val c = s[i]
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r') {
                var j = i
                var nl = 0
                var sp = 0
                while (j < b) {
                    val k = s[j]
                    if (k == '\n') { nl++; sp = 0 } else if (k == ' ') sp++ else if (k == '\t') sp += 4 else if (k != '\r') break
                    j++
                }
                val l = sb.length
                if (l > 0) {
                    val last = sb[l - 1]
                    if (last != ' ' && last != '\n') sb.append(if (nl > 0) '\n' else ' ')
                    else if (nl > 0 && last == ' ') sb.setCharAt(l - 1, '\n')
                    if (nl > 0 && (nl > 1 || (keepIndent && sp > 0)) && lines.size < MAX_LINES) {
                        lines.add(Line(sb.length, sb.length + 1, null, if (keepIndent) sp * 0.5f else 0f, nl > 1))
                    }
                } else if (keepIndent && sp > 0 && lines.size < MAX_LINES) lines.add(Line(0, 1, null, sp * 0.5f, false))
                i = j
            } else {
                var j = i + 1
                while (j < b) {
                    val k = s[j]
                    if (k == ' ' || k == '\n' || k == '\t' || k == '\r') break
                    j++
                }
                appendDecoded(sb, s, i, j, false)
                i = j
            }
        }
    }

    /** <br> masqué par le livre (vers dont chaque ligne est déjà un bloc) : il garde sa place, sans effet. */
    fun takes(nm: String, sb: StringBuilder): Boolean {
        if (dead || nm != "br") return false
        val st = voidStyle ?: return false
        if (!st.gone) return false
        if (sb.isNotEmpty()) sb.append(' ')
        return true
    }

    private var objs = ArrayList<Any>()
    private var ranges = IntArray(32)

    private fun span(o: Any, a: Int, b: Int) {
        val k = objs.size * 2
        if (k + 2 > ranges.size) ranges = ranges.copyOf(ranges.size * 2)
        ranges[k] = a
        ranges[k + 1] = b
        objs.add(o)
    }

    /** Texte du bloc en cours (les [e] premiers caractères de [sb]) avec sa mise en forme ; null si le suivi est coupé. */
    fun build(sb: StringBuilder, e: Int): CharSequence? {
        if (dead) return null
        try {
            return build0(sb, e)
        } catch (x: Exception) {
            dead = true
            return null
        }
    }

    private fun build0(sb: StringBuilder, e: Int): CharSequence {
        val base = (if (runs > 0) blockNode else null) ?: nodes[top]!!.block
        blockNode = base
        val bs = base.style
        val head = base.heading
        val sampling = stats != null
        val blockFace = if (head || sampling) null else face(base)
        var chars: CharArray? = null
        objs.clear()
        allGone = runs > 0 || bs.gone
        marked = false
        var caps = 0
        for (r in 0 until runs) {
            val node = runN[r]!!
            val st = node.style
            val a = runA[r]
            val b = minOf(runB[r], e)
            if (b <= a) continue
            if (st.gone) { span(GONE, a, b); continue }
            allGone = false
            if (st.transform != 0 || st.caps || (st.ws == WS_NOWRAP && b - a <= 40)) {
                val c = chars ?: CharArray(e).also { sb.getChars(0, e, it, 0); chars = it }
                when (st.transform) {
                    1 -> for (i in a until b) c[i] = Character.toUpperCase(c[i])
                    2 -> for (i in a until b) c[i] = Character.toLowerCase(c[i])
                    3 -> for (i in a until b) {
                        if (Character.isLetter(c[i]) && (i == 0 || !(Character.isLetterOrDigit(c[i - 1]) || c[i - 1] == '\'' || c[i - 1] == '’'))) c[i] = Character.toUpperCase(c[i])
                    }
                }
                if (st.ws == WS_NOWRAP && b - a <= 40) for (i in a until b) if (c[i] == ' ') c[i] = ' '
                // Petites capitales : la police n'en a pas, les minuscules deviennent des capitales réduites.
                if (st.caps) {
                    var from = -1
                    for (i in a until b) {
                        val ch = c[i]
                        val up = Character.toUpperCase(ch)
                        if (up != ch && Character.isLowerCase(ch)) {
                            c[i] = up
                            if (from < 0) from = i
                        } else if (from >= 0 && (Character.isLetterOrDigit(ch) || ch == '\n')) {
                            if (caps++ < MAX_CAPS) span(RelativeSizeSpan(0.8f), from, i)
                            from = -1
                        }
                    }
                    if (from >= 0 && caps++ < MAX_CAPS) span(RelativeSizeSpan(0.8f), from, b)
                }
            }
            if (sampling) continue
            // Police, gras, italique.
            val bold = st.bold && !head
            val italic = st.italic
            val f = if (head) null else if (st.family === bs.family) blockFace else face(node)
            if (f === blockFace) {
                if (f == null) { if (bold || italic) span(FontSpan(fonts, bold, italic), a, b) }
                else if (bold || italic) span(FaceSpan(f.pick(bold, italic), f.fakeBold(bold, italic), f.skew(bold, italic)), a, b)
            } else if (f == null) span(FaceSpan(fonts.body(bold, italic), false, false), a, b)
            else span(FaceSpan(f.pick(bold, italic), f.fakeBold(bold, italic), f.skew(bold, italic)), a, b)
            if (st.spacing != bs.spacing) span(TrackSpan(st.spacing - bs.spacing), a, b)
            // Corps ; un appel de note relevé est toujours réduit.
            var rel = st.size / bs.size
            if (st.va != 0 && rel > 0.75f) rel = 0.7f
            rel = rel.coerceIn(0.5f, 2f)
            if (rel < 0.98f || rel > 1.02f) span(RelativeSizeSpan(rel), a, b)
            if (st.va == 1) span(SuperscriptSpan(), a, b) else if (st.va == 2) span(SubscriptSpan(), a, b)
            if (st.color != 0) span(android.text.style.ForegroundColorSpan(st.color), a, b)
            if (st.deco and 1 != 0) span(UnderlineSpan(), a, b)
            if (st.deco and 2 != 0) span(StrikethroughSpan(), a, b)
        }
        if (allGone) return sb.toString()
        // Puce d'une liste que le livre veut sans puce.
        if (bs.listNone && e > 2 && sb[0] == '•') span(GONE, 0, 2)
        for (l in lines) {
            val a = l.a
            val b = minOf(l.b, e)
            if (b <= a) continue
            val n = l.node
            if (n == null) {
                if (l.indent > 0f) { span(LineMark(l.indent, l.indent), a, b); marked = true }
                if (l.gap && a > 0 && sb[a - 1] == '\n') span(GapSpan(1f), a - 1, a)
            } else {
                val st = n.style
                val left = maxOf(0f, (st.ml + st.pl) / ref.size)
                val first = maxOf(0f, left + st.indent / ref.size)
                if (left > 0f || first > 0f) { span(LineMark(left, first), a, b); marked = true }
                if (st.align != bs.align) when (st.align) {
                    A_CENTER -> span(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), a, b)
                    A_RIGHT -> span(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), a, b)
                    A_LEFT, A_START -> span(AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL), a, b)
                }
                if (st.mt > 0.2f && a > 0 && sb[a - 1] == '\n') span(GapSpan(minOf(st.mt / ref.size, 3f)), a - 1, a)
            }
        }
        val c = chars
        if (objs.isEmpty()) return if (c != null) String(c) else sb.toString()
        val sp = if (c != null) SpannableString(String(c)) else SpannableString(sb)
        for (k in objs.indices) {
            val o = objs[k]
            sp.setSpan(if (o === GONE) HiddenSpan() else o, ranges[k * 2], ranges[k * 2 + 1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        objs.clear()
        return sp
    }

    private fun face(n: Node): Face? {
        if (n.faceState == 0) {
            n.face = set.face(n.style.family)
            n.faceState = 1
        }
        return n.face
    }

    /** Bloc qui vient d'être créé : style, ou masquage (display: none). */
    fun block(b: Block) {
        if (dead) return
        try {
            block0(b)
        } catch (e: Exception) {
            dead = true
        }
    }

    private fun block0(b: Block) {
        if (b.kind == Block.IMG || b.kind == Block.RULE) {
            val st = voidStyle ?: nodes[top]!!.style
            voidStyle = null
            if (st.gone) b.hidden = true else { prevPara = false; prevHead = false }
            return
        }
        val base = blockNode ?: nodes[top]!!.block
        val st = base.style
        if (allGone) { b.hidden = true; return }
        val css = BlockStyle()
        b.css = css
        css.pageBreak = breakNext
        breakNext = false
        val gap = vgap / ref.size
        vgap = 0f
        css.marks = marked
        if (b.kind == Block.HEAD) {
            css.align = when (st.align) { A_CENTER -> 1; A_RIGHT -> 2; A_LEFT, A_JUSTIFY -> 3; else -> -1 }
            prevPara = false
            prevHead = true
            return
        }
        val al = when (st.align) {
            A_CENTER -> 1
            A_RIGHT -> 2
            A_JUSTIFY -> 0
            // « left » partout dans le livre n'est pas un choix de mise en page : le texte courant reste justifié.
            A_LEFT -> if (ref.align == A_LEFT && lines.isEmpty()) 0 else 3
            else -> if (ref.align == A_JUSTIFY) 3 else 0
        }
        css.align = al
        css.left = maxOf(0f, (base.left - ref.left) / ref.size)
        css.right = maxOf(0f, (base.right - ref.right) / ref.size)
        css.leftP = maxOf(0f, base.leftP - ref.leftP)
        css.rightP = maxOf(0f, base.rightP - ref.rightP)
        var ratio = (st.size / ref.size).coerceIn(0.75f, 2f)
        if (ratio > 0.96f && ratio < 1.04f) ratio = 1f
        css.ratio = ratio
        val sampling = stats != null
        if (!sampling) css.face = face(base)?.regular
        css.spacing = st.spacing
        css.noHyphen = st.noHyph || al == 3 || lines.isNotEmpty()
        // Prose du livre : alinéa et blancs de la maison, comme sans le mode.
        val prose = ref.prose && lines.isEmpty() && st.ws == WS_NORMAL && al == 0 && Math.abs(st.indent / st.size - ref.indent / ref.size) < 0.01f
        val same = prevPara && css.left == prevLeft && css.right == prevRight && ratio == prevRatio && css.face === prevFace
        css.top = when {
            prevHead -> 0f
            prose && prevProse && same && gap <= ref.margin / ref.size + 0.01f -> 0f
            else -> minOf(gap, 3f)
        }
        css.indent = when {
            prose -> if (prevPara && prevAlign != 1 && prevAlign != 2 && css.top == 0f && !b.gap && !(b.text.length > 1 && b.text[0] == '•')) 1.3f * ratio else 0f
            al == 1 || al == 2 -> 0f
            else -> st.indent / ref.size
        }
        if (sampling && b.length > 0) {
            val fam = st.family?.substringBefore(',')?.replace('|', ' ') ?: ""
            val key = "${st.size}|$fam|${st.indent}|${maxOf(st.mt, st.mb)}|${st.align}|${base.left}|${base.right}|${base.leftP}|${base.rightP}"
            // Les blocs centrés, à droite ou faits de lignes ne sont pas de la prose : comptés pour le corps seulement.
            val k = if (al == 1 || al == 2 || lines.isNotEmpty() || st.ws != WS_NORMAL) "$key|x" else key
            val v = stats!!.getOrPut(k) { IntArray(2) }
            v[0] += b.length
            if (k === key) v[1]++ else v[1] = 10_000_000
        }
        prevPara = true
        prevHead = false
        prevProse = prose
        prevAlign = al
        prevLeft = css.left
        prevRight = css.right
        prevRatio = ratio
        prevFace = css.face
    }

    /** Fin du bloc en cours, qu'il ait donné un bloc ou non. */
    fun reset() {
        runs = 0
        lines.clear()
        needBreak = false
        blockNode = null
        allGone = false
        marked = false
        for (k in 1..top) if (lineAt[k] >= 0) lineAt[k] = 0
    }

    companion object {
        private const val MAX_DEPTH = 200
        private const val MAX_RUNS = 4000
        private const val MAX_LINES = 2000
        private const val MAX_CAPS = 300
        private val GONE = Any()
        private val VOID = hashSetOf(
            "br", "hr", "img", "image", "meta", "link", "input", "col", "wbr", "area", "base", "source", "embed", "param", "track",
            "head", "style", "script",
        )
    }
}

/** Composition des blocs stylés, pour un [Typesetter] : peintures dérivées de celles du texte courant. */
class Styled {
    private data class Key(val base: TextPaint, val ratio: Float, val face: Typeface?, val spacing: Float)

    // Partagé entre les fils de composition, comme les peintures du Typesetter ; borné.
    private val paints = ConcurrentHashMap<Key, TextPaint>()

    private fun paint(base: TextPaint, st: BlockStyle): TextPaint {
        if (st.ratio == 1f && st.face == null && st.spacing == 0f) return base
        val k = Key(base, st.ratio, st.face, st.spacing)
        paints[k]?.let { return it }
        if (paints.size >= 64) paints.clear()
        val p = TextPaint(base)
        p.textSize = base.textSize * st.ratio
        st.face?.let { p.typeface = it }
        // Graisse, espacement et largeur du lecteur, recalculés pour ce corps.
        Theme.tune(p)
        p.letterSpacing += st.spacing
        return paints.putIfAbsent(k, p) ?: p
    }

    /** [base] : peinture du texte courant (ou du titre) à cette échelle ; [cw] : largeur du texte. */
    fun layout(b: Block, st: BlockStyle, base: TextPaint, cw: Int, mult: Float, add: Float = 0f): StaticLayout {
        if (b.kind == Block.HEAD) {
            val t = b.text
            val center = if (st.align < 0) b.level <= 2 else st.align == 1
            return StaticLayout.Builder.obtain(t, 0, t.length, base, cw).setIncludePad(false)
                .setLineSpacing(0f, 1.15f)
                .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else if (st.align == 2) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .build()
        }
        val p = paint(base, st)
        val em = base.textSize
        var t = b.text
        if (st.marks && t is Spanned) {
            val sp = SpannableString(t)
            val half = cw / 2
            for (m in sp.getSpans(0, sp.length, LineMark::class.java)) {
                sp.setSpan(
                    LeadingMarginSpan.Standard((m.first * em).toInt().coerceIn(0, half), (m.left * em).toInt().coerceIn(0, half)),
                    sp.getSpanStart(m), sp.getSpanEnd(m), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            t = sp
        }
        val sb = StaticLayout.Builder.obtain(t, 0, t.length, p, cw).setIncludePad(false)
            .setLineSpacing(add, mult)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(if (st.noHyphen) Layout.HYPHENATION_FREQUENCY_NONE else Layout.HYPHENATION_FREQUENCY_NORMAL)
        when (st.align) {
            1 -> sb.setAlignment(Layout.Alignment.ALIGN_CENTER)
            2 -> sb.setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
            3 -> {}
            else -> sb.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        }
        // Marges du livre, à l'intérieur de celles du lecteur ; le bloc garde au moins 55 % de la largeur.
        var l = st.left * em + st.leftP * cw
        var r = st.right * em + st.rightP * cw
        val max = cw * 0.45f
        if (l + r > max) {
            val k = max / (l + r)
            l *= k
            r *= k
        }
        val first = (l + st.indent * em).coerceIn(0f, l + cw * 0.25f)
        if (l >= 1f || r >= 1f || first >= 1f) sb.setIndents(intArrayOf(first.toInt(), l.toInt()), intArrayOf(r.toInt(), r.toInt()))
        return sb.build()
    }
}
