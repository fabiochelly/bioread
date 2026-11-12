package app.bioread

import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan

/**
 * Tokenizer XML/HTML minimal et tolérant : un seul passage, aucune allocation
 * hors des noms de balises. Sert pour l'OPF, le NCX, la nav et les chapitres.
 */
class Tok(val s: String) {
    companion object {
        const val END = 0
        const val TEXT = 1
        const val OPEN = 2
        const val CLOSE = 3
    }

    private var pos = 0
    private val n = s.length
    var name = ""
    var selfClosing = false
    var textStart = 0
    var textEnd = 0
    private var attrStart = 0
    private var attrEnd = 0

    fun next(): Int {
        while (true) {
            if (pos >= n) return END
            if (s[pos] != '<') {
                textStart = pos
                val e = s.indexOf('<', pos)
                pos = if (e < 0) n else e
                textEnd = pos
                return TEXT
            }
            if (s.startsWith("<!--", pos)) {
                val e = s.indexOf("-->", pos + 4)
                pos = if (e < 0) n else e + 3
                continue
            }
            if (s.startsWith("<![CDATA[", pos)) {
                textStart = pos + 9
                val e = s.indexOf("]]>", textStart)
                textEnd = if (e < 0) n else e
                pos = if (e < 0) n else e + 3
                return TEXT
            }
            if (pos + 1 < n && (s[pos + 1] == '!' || s[pos + 1] == '?')) {
                val e = s.indexOf('>', pos)
                pos = if (e < 0) n else e + 1
                continue
            }
            val close = pos + 1 < n && s[pos + 1] == '/'
            var i = pos + if (close) 2 else 1
            val ns = i
            while (i < n) {
                val c = s[i]
                if (c == '>' || c == '/' || c <= ' ') break
                i++
            }
            name = tagName(ns, i)
            attrStart = i
            // Fin de balise, en respectant les guillemets.
            var q = 0.toChar()
            while (i < n) {
                val c = s[i]
                if (q != 0.toChar()) {
                    if (c == q) q = 0.toChar()
                } else if (c == '"' || c == '\'') q = c
                else if (c == '>') break
                i++
            }
            attrEnd = i
            selfClosing = i > attrStart && s[i - 1] == '/'
            pos = if (i < n) i + 1 else n
            return if (close) CLOSE else OPEN
        }
    }

    private fun tagName(a: Int, b: Int): String {
        var st = a
        for (i in a until b) if (s[i] == ':') st = i + 1
        // Les noms courants sont déjà en minuscules : on évite la copie.
        var lower = true
        for (i in st until b) if (s[i] in 'A'..'Z') { lower = false; break }
        val r = s.substring(st, b)
        return if (lower) r else r.lowercase()
    }

    /** Valeur de l'attribut [want] (le préfixe d'espace de noms est ignoré), ou null. */
    fun attr(want: String): String? {
        var i = attrStart
        val e = attrEnd
        while (i < e) {
            while (i < e && (s[i] <= ' ' || s[i] == '/')) i++
            val ns = i
            while (i < e && s[i] != '=' && s[i] > ' ') i++
            val ne = i
            while (i < e && s[i] <= ' ') i++
            if (i >= e || s[i] != '=') continue
            i++
            while (i < e && s[i] <= ' ') i++
            if (i >= e) break
            val vs: Int
            val ve: Int
            val q = s[i]
            if (q == '"' || q == '\'') {
                vs = i + 1
                var j = vs
                while (j < e && s[j] != q) j++
                ve = j
                i = j + 1
            } else {
                vs = i
                while (i < e && s[i] > ' ') i++
                ve = i
            }
            val len = want.length
            if (ne - ns >= len && s.regionMatches(ne - len, want, 0, len, ignoreCase = true) &&
                (ne - ns == len || s[ne - len - 1] == ':')
            ) {
                return if (amp(vs, ve)) {
                    StringBuilder().also { appendDecoded(it, s, vs, ve, false) }.toString()
                } else s.substring(vs, ve)
            }
        }
        return null
    }

    /** Un « & » dans s[a, b) ? (Recherche bornée à la valeur : indexOf irait jusqu'au bout du document.) */
    private fun amp(a: Int, b: Int): Boolean {
        for (i in a until b) if (s[i] == '&') return true
        return false
    }

    fun hasAttrs() = attrEnd - attrStart > 3

    /** Texte du nœud courant, entités décodées, espaces normalisés. */
    fun text(): String = StringBuilder().also { appendDecoded(it, s, textStart, textEnd, true) }.toString()
}

private val ENTITIES = HashMap<String, Char>().apply {
    val d = "nbsp amp&lt<gt>quot\"apos'mdash—ndash–hellip…laquo«raquo»lsquo‘rsquo’ldquo“rdquo”" +
        "sbquo‚bdquo„thinsp ensp emsp shy­times×euro€trade™middot·bull•sect§para¶" +
        "copy©reg®deg°iexcl¡iquest¿agraveàaacuteáacircâatildeãaumläaringå" +
        "aeligæccedilçegraveèeacuteéecircêeumlëigraveìiacuteíicircî" +
        "iumlïntildeñograveòoacuteóocircôotildeõoumlöoslashøugraveù" +
        "uacuteúucircûuumlüyacuteýyumlÿszligßoeligœ" +
        "AgraveÀAacuteÁAcircÂAumlÄAEligÆCcedilÇEgraveÈEacuteÉ" +
        "EcircÊEumlËIcircÎIumlÏNtildeÑOcircÔOumlÖUgraveÙUcircÛ" +
        "UumlÜOEligŒdagger†Dagger‡prime′Prime″frac12½frac14¼frac34¾minus−pound£"
    var i = 0
    while (i < d.length) {
        var j = i
        while (d[j].let { it in 'a'..'z' || it in 'A'..'Z' || (j > i && it in '0'..'9') }) j++
        put(d.substring(i, j), d[j])
        i = j + 1
    }
}

/**
 * Ajoute s[a,b) à [sb] en décodant les entités. Si [collapse], les suites
 * d'espaces ASCII sont réduites à une seule espace (l'insécable est préservée).
 */
fun appendDecoded(sb: StringBuilder, s: String, a: Int, b: Int, collapse: Boolean) {
    var i = a
    while (i < b) {
        val c = s[i]
        if (c == '&') {
            val semi = s.indexOf(';', i + 1)
            if (semi in (i + 2)..minOf(i + 10, b - 1)) {
                if (s[i + 1] == '#') {
                    val hex = s[i + 2] == 'x' || s[i + 2] == 'X'
                    val cp = s.substring(i + if (hex) 3 else 2, semi).toIntOrNull(if (hex) 16 else 10)
                    if (cp != null && cp > 0 && cp <= 0x10FFFF) {
                        sb.appendCodePoint(if (cp == 0x2028 || cp == 0x2029) 0x20 else cp)
                        i = semi + 1
                        continue
                    }
                } else {
                    val r = ENTITIES[s.substring(i + 1, semi)]
                    if (r != null) {
                        sb.append(r)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append('&')
            i++
        } else if (collapse && (c == ' ' || c == '\n' || c == '\t' || c == '\r')) {
            val l = sb.length
            if (l > 0 && sb[l - 1] != ' ' && sb[l - 1] != '\n') sb.append(' ')
            i++
        } else {
            sb.append(c)
            i++
        }
    }
}

class Block(
    val kind: Int,
    val text: CharSequence,
    val level: Int = 0,      // niveau de titre 1..6
    val align: Int = 0,      // 0 = défaut, 1 = centré, 2 = droite
    val quote: Int = 0,      // profondeur de citation
    val gap: Boolean = false,    // saut de scène avant le bloc
    val indent: Boolean = false, // alinéa de première ligne
    val img: String? = null,
) {
    var start = 0   // position du bloc dans le chapitre (caractères)
    var imgW = -1
    var imgH = -1
    var links: ArrayList<Link>? = null   // liens portés par le texte, dans l'ordre (voir Links.kt)
    var hidden = false                   // note sortie du fil du texte : gardée à sa place, jamais mise en page
    var css: BlockStyle? = null          // mode « Fidèle au livre » : mise en forme voulue par le livre (voir Style.kt)
    @Volatile var ww: WordWise.Marks? = null   // mots à traduire sous le texte, pour le dernier réglage employé
    val length get() = if (kind == IMG) 1 else text.length

    companion object {
        const val PARA = 0
        const val HEAD = 1
        const val IMG = 2
        const val RULE = 3
    }
}

class Chapter(val blocks: ArrayList<Block>, val anchors: HashMap<String, Int>, val length: Int) {
    /** Fin des éléments de bloc porteurs d'un identifiant (étendue d'une note). */
    var ends: HashMap<String, Int> = HashMap()
}

private const val S_ITALIC = 1
private const val S_BOLD = 2
private const val S_SUP = 3
private const val S_SUB = 4

internal val BLOCK_TAGS = hashSetOf(
    "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li", "ul", "ol", "dl", "dt", "dd",
    "section", "article", "aside", "header", "footer", "nav", "main", "figure", "figcaption", "table",
    "tr", "td", "th", "tbody", "thead", "center", "pre", "body", "html", "address", "caption",
)
private val INLINE_TAGS = hashSetOf(
    "i", "em", "cite", "var", "dfn", "b", "strong", "sup", "sub", "span", "a", "small", "u", "font", "q",
)

/** Transforme un document XHTML en une liste plate de blocs prêts à composer. */
fun parseChapter(html: String, basePath: String, fonts: Fonts, css: BookCss? = null): Chapter {
    val t = Tok(html)
    val blocks = ArrayList<Block>()
    val anchors = HashMap<String, Int>()
    val sb = StringBuilder()
    val spans = ArrayList<IntArray>()          // kind, start, end
    val inline = ArrayList<IntArray>()         // pile : kind, start
    val aligns = ArrayList<Int>()
    var total = 0
    var skip = 0
    var heading = 0
    var quote = 0
    var gap = false
    var bullet = false
    var prevKind = -1
    var prevCentered = false
    val ls = LinkScan(basePath)                // liens, étendue des notes
    // Mode « Fidèle au livre » : suivi du CSS (null quand le mode est coupé, et rien n'est alors fait).
    val ss = if (css == null) null else try { StyleScan(css, html, basePath, fonts) } catch (e: Exception) { null }

    fun emit(b: Block) {
        b.start = total
        ss?.block(b)
        total += b.length
        blocks.add(b)
        prevKind = b.kind
        prevCentered = b.align != 0
        gap = false
    }

    fun flush() {
        var e = sb.length
        while (e > 0 && (sb[e - 1] == ' ' || sb[e - 1] == '\n')) e--
        var blank = true
        for (i in 0 until e) if (sb[i] != ' ' && sb[i] > ' ') { blank = false; break }
        if (blank) {
            if (e > 0) gap = true     // paragraphe ne contenant que des insécables = saut de scène
        } else {
            sb.setLength(e)
            for (o in inline) if (o[0] != 0 && e > o[1]) spans.add(intArrayOf(o[0], o[1], e))
            val text: CharSequence = ss?.build(sb, e) ?: if (spans.isEmpty()) sb.toString() else {
                val sp = SpannableString(sb)
                for (x in spans) {
                    val a = x[1]
                    val b = minOf(x[2], e)
                    if (b <= a) continue
                    when (x[0]) {
                        S_ITALIC -> sp.setSpan(FontSpan(fonts, false, true), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        S_BOLD -> sp.setSpan(FontSpan(fonts, true, false), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        else -> {
                            sp.setSpan(RelativeSizeSpan(0.7f), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            sp.setSpan(if (x[0] == S_SUP) SuperscriptSpan() else SubscriptSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
                sp
            }
            val align = if (aligns.isEmpty()) 0 else aligns[aligns.size - 1]
            val first = prevKind != Block.PARA || gap || prevCentered
            emit(
                Block(
                    if (heading > 0) Block.HEAD else Block.PARA, text, heading, align, quote, gap,
                    indent = heading == 0 && align == 0 && !first && !bullet,
                )
            )
        }
        ls.flush(if (blank) null else blocks[blocks.size - 1], e, sb)
        ss?.reset()
        sb.setLength(0)
        spans.clear()
        for (o in inline) o[1] = 0
        bullet = false
    }

    fun styleAlign(inherit: Int): Int {
        if (!t.hasAttrs()) return inherit
        val st = t.attr("style") ?: return inherit
        val i = st.indexOf("text-align")
        if (i < 0) return inherit
        val v = st.substring(i + 10).trimStart(':', ' ')
        return when {
            v.startsWith("center") -> 1
            v.startsWith("right") -> 2
            else -> 0
        }
    }

    while (true) {
        when (t.next()) {
            Tok.END -> break
            Tok.TEXT -> if (skip == 0) {
                if (sb.isEmpty() && bullet) sb.append("• ")
                if (ss != null) ss.text(sb, t.s, t.textStart, t.textEnd) else appendDecoded(sb, t.s, t.textStart, t.textEnd, true)
            }
            Tok.OPEN -> {
                val nm = t.name
                if (skip > 0) {
                    if (!t.selfClosing && (nm == "head" || nm == "style" || nm == "script")) skip++
                    continue
                }
                val id = if (t.hasAttrs()) t.attr("id") else null
                if (id != null) anchors[id] = total + sb.length
                ss?.open(t, nm, sb)
                when {
                    ss != null && ss.takes(nm, sb) -> {}    // <br> masqué par le livre
                    nm == "head" || nm == "style" || nm == "script" -> if (!t.selfClosing) skip++
                    nm == "br" -> if (sb.isEmpty()) gap = true else sb.append('\n')
                    nm == "hr" -> {
                        flush()
                        gap = true
                        emit(Block(Block.RULE, "* * *", align = 1, gap = true))
                    }
                    nm == "img" || nm == "image" -> {
                        val src = t.attr("src") ?: t.attr("href")
                        if (src != null && !src.startsWith("data:")) {
                            flush()
                            emit(Block(Block.IMG, "", img = resolvePath(basePath, src)))
                        }
                    }
                    nm in BLOCK_TAGS -> {
                        flush()
                        if (t.selfClosing) continue
                        ls.blockOpen(t, nm, id, blocks.size)
                        val inherit = if (aligns.isEmpty()) 0 else aligns[aligns.size - 1]
                        aligns.add(if (nm == "center") 1 else styleAlign(inherit))
                        when (nm) {
                            "blockquote" -> quote++
                            "li" -> bullet = true
                            else -> if (nm.length == 2 && nm[0] == 'h' && nm[1] in '1'..'6') heading = nm[1] - '0'
                        }
                    }
                    nm in INLINE_TAGS -> if (!t.selfClosing) {
                        var k = when (nm) {
                            "i", "em", "cite", "var", "dfn" -> S_ITALIC
                            "b", "strong" -> S_BOLD
                            "sup" -> S_SUP
                            "sub" -> S_SUB
                            else -> 0
                        }
                        if (k == 0 && nm == "span" && t.hasAttrs()) {
                            val st = t.attr("style")
                            if (st != null) {
                                if (st.contains("italic")) k = S_ITALIC
                                else if (st.contains("bold")) k = S_BOLD
                            }
                        }
                        if (nm == "a" && t.hasAttrs()) ls.open(t, sb.length)
                        inline.add(intArrayOf(k, sb.length))
                    }
                }
            }
            Tok.CLOSE -> {
                val nm = t.name
                if (skip > 0) {
                    if (nm == "head" || nm == "style" || nm == "script") skip--
                    continue
                }
                ss?.close(nm, sb)
                if (nm in BLOCK_TAGS) {
                    flush()
                    ls.blockClose(total, blocks.size)
                    if (aligns.isNotEmpty()) aligns.removeAt(aligns.size - 1)
                    when (nm) {
                        "blockquote" -> if (quote > 0) quote--
                        else -> if (nm.length == 2 && nm[0] == 'h' && nm[1] in '1'..'6') heading = 0
                    }
                } else if (nm in INLINE_TAGS && inline.isNotEmpty()) {
                    val o = inline.removeAt(inline.size - 1)
                    if (nm == "a") ls.close(sb)
                    if (o[0] != 0 && sb.length > o[1]) spans.add(intArrayOf(o[0], o[1], sb.length))
                }
            }
        }
    }
    flush()
    ls.finish(blocks)
    return Chapter(blocks, anchors, total).also { it.ends = ls.ends }
}

/** Résout [href] par rapport au fichier [base] à l'intérieur du zip. */
fun resolvePath(base: String, href: String): String {
    var h = href
    val hash = h.indexOf('#')
    if (hash >= 0) h = h.substring(0, hash)
    if (h.indexOf('%') >= 0) h = try { java.net.URLDecoder.decode(h.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { h }
    val parts = ArrayList<String>()
    if (!h.startsWith("/")) {
        val dir = base.lastIndexOf('/')
        if (dir > 0) parts.addAll(base.substring(0, dir).split('/'))
    }
    for (p in h.split('/')) {
        when (p) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(p)
        }
    }
    return parts.joinToString("/")
}
