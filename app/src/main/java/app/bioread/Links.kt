package app.bioread

/**
 * Lien porté par un bloc : caractères [start, end) du texte du bloc. La cible n'est résolue
 * qu'au toucher ([path], [frag]) : l'analyse du chapitre ne garde que l'attribut tel quel.
 */
class Link(
    val start: Int,
    val end: Int,
    val href: String,
    private val base: String,
    /** Déclaré comme appel de note par le livre (epub:type="noteref", role="doc-noteref"). */
    val typed: Boolean,
    /** Appel de note déclaré, ou qui en a tout l'air : libellé court (chiffre, lettre, *, [n]) vers une ancre. */
    val note: Boolean,
    /** Libellé de l'appel, sans crochets ni point (« 3 », « a », « * ») ; vide pour un lien ordinaire. */
    val label: String,
) {
    /** http:, mailto:… : hors du livre. */
    val external: Boolean
        get() {
            for (c in href) {
                if (c == ':') return true
                if (c == '/' || c == '#' || c == '?') return false
            }
            return false
        }

    /** Fichier visé, à l'intérieur du zip. */
    val path: String get() = if (href.startsWith("#")) base else resolvePath(base, href.substringBefore('?'))

    /** Ancre visée dans ce fichier, ou null. */
    val frag: String?
        get() {
            val h = href.indexOf('#')
            if (h < 0 || h == href.length - 1) return null
            val f = href.substring(h + 1)
            return if (f.indexOf('%') < 0) f else try { java.net.URLDecoder.decode(f.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { f }
        }
}

/**
 * Relevé des liens et de la structure des notes pendant l'analyse d'un chapitre (voir
 * [parseChapter]). Rien n'est fait hors des balises <a> et des blocs porteurs d'un identifiant :
 * le coût est nul sur un texte sans liens.
 */
class LinkScan(private val base: String) {
    private var href: String? = null      // lien ouvert
    private var from = 0
    private var typed = false
    private var pending: ArrayList<Link>? = null

    // Blocs ouverts porteurs d'un identifiant (les autres ne coûtent qu'un compteur) : identifiant,
    // puis par triplets profondeur, premier bloc d'une note à masquer (-1 sinon), 1 si simple paragraphe.
    private var depth = 0
    private val ids = ArrayList<String>()
    private var meta = IntArray(24)
    private var asides: ArrayList<Any>? = null     // identifiant, premier bloc, fin (triplets)

    /** Fin (position dans le chapitre) des éléments de bloc porteurs d'un identifiant, hors simples paragraphes. */
    val ends = HashMap<String, Int>()

    /** Balise <a> ouvrante, quand [at] caractères du bloc courant sont déjà lus. */
    fun open(t: Tok, at: Int) {
        val h = t.attr("href") ?: return
        if (h.isEmpty()) return
        href = h
        from = at
        typed = t.attr("type")?.contains("noteref") == true || t.attr("role") == "doc-noteref"
    }

    /** Balise </a>. */
    fun close(sb: StringBuilder) {
        val h = href ?: return
        href = null
        add(h, sb, sb.length)
    }

    private fun add(h: String, sb: CharSequence, end: Int) {
        var a = from
        // La puce d'un élément de liste ne fait pas partie du lien.
        if (a == 0 && end > 2 && sb[0] == '•' && sb[1] == ' ') a = 2
        if (end <= a) return
        var label = ""
        if (end - a <= 8) label = callLabel(sb, a, end)
        // Sans indication du livre, seul un libellé court vers une ancre passe pour un appel de note.
        val note = typed || (label.isNotEmpty() && h.indexOf('#') in 0 until h.length - 1 && !isExternal(h))
        (pending ?: ArrayList<Link>(2).also { pending = it }).add(Link(a, end, h, base, typed, note, if (note) label else ""))
    }

    private fun isExternal(h: String): Boolean {
        val c = h.indexOf(':')
        return c > 0 && c < h.indexOf('#')
    }

    /**
     * Fin d'un bloc de texte : [block] vient d'être créé avec les [e] premiers caractères lus
     * (null si le bloc était vide). Un lien encore ouvert se poursuit dans le bloc suivant.
     */
    fun flush(block: Block?, e: Int, sb: CharSequence) {
        val h = href
        if (h == null && pending == null) return
        if (block != null) {
            if (h != null) add(h, sb, e)
            val p = pending
            if (p != null) {
                var k = p.size
                while (--k >= 0) if (p[k].start >= e) p.removeAt(k)
                if (p.isNotEmpty()) block.links = p
            }
        }
        pending = null
        from = 0
    }

    /** Balise de bloc ouvrante, d'identifiant [id] (ou null) ; [index] = nombre de blocs déjà créés. */
    fun blockOpen(t: Tok, name: String, id: String?, index: Int) {
        depth++
        if (id == null) return
        var mark = -1
        if (name == "aside" && (t.attr("type")?.contains("footnote") == true || t.attr("role") == "doc-footnote")) mark = index
        val k = ids.size * 3
        if (k + 3 > meta.size) meta = meta.copyOf(meta.size * 2)
        meta[k] = depth
        meta[k + 1] = mark
        // Un paragraphe isolé ne dit pas où finit sa note (elle peut se poursuivre) : voir Notes.extract.
        meta[k + 2] = if (name == "p") 1 else 0
        ids.add(id)
    }

    /** Balise de bloc fermante, une fois son texte rangé : [total] caractères et [index] blocs. */
    fun blockClose(total: Int, index: Int) {
        // Les blocs restés ouverts plus profond (balisage fautif) se ferment avec celui-ci.
        while (ids.isNotEmpty() && meta[ids.size * 3 - 3] >= depth) {
            val k = ids.size * 3 - 3
            val id = ids.removeAt(ids.size - 1)
            if (meta[k + 2] == 0) ends[id] = total
            if (meta[k + 1] in 0 until index) {
                val a = asides ?: ArrayList<Any>().also { asides = it }
                a.add(id); a.add(meta[k + 1]); a.add(index)
            }
        }
        if (depth > 0) depth--
    }

    /**
     * Fin du chapitre : les notes de bas de page EPUB 3 (<aside epub:type="footnote">) appelées
     * depuis ce même fichier sortent du fil du texte. Leurs blocs restent dans la liste, à leur
     * place : aucune position (lecture, recherche) ne change, ils ne sont simplement pas mis en
     * page. Une note que rien n'appelle reste visible.
     */
    fun finish(blocks: ArrayList<Block>) {
        val a = asides ?: return
        val called = HashSet<String>()
        for (b in blocks) b.links?.let { for (l in it) if (l.path == base) l.frag?.let { f -> called.add(f) } }
        var k = 0
        while (k < a.size) {
            if ((a[k] as String) in called) for (i in (a[k + 1] as Int) until (a[k + 2] as Int)) blocks[i].hidden = true
            k += 3
        }
    }

    companion object {
        /**
         * Libellé d'un appel de note : un nombre, une lettre, ou un signe (* † ‡ § ¶), seuls ou
         * entre crochets ou parenthèses. Chaîne vide si s[a, b) n'y ressemble pas.
         */
        fun callLabel(s: CharSequence, a: Int, b: Int): String {
            var i = a
            var j = b
            while (i < j && (s[i] <= ' ' || s[i] == ' ')) i++
            while (j > i && (s[j - 1] <= ' ' || s[j - 1] == ' ')) j--
            if (j > i && s[j - 1] == '.') j--
            if (j - i >= 2 && ((s[i] == '[' && s[j - 1] == ']') || (s[i] == '(' && s[j - 1] == ')'))) { i++; j-- }
            else if (j - i >= 2 && (s[j - 1] == ')' || s[j - 1] == ']')) j--
            val n = j - i
            if (n < 1 || n > 4) return ""
            var digits = 0
            var letters = 0
            var signs = 0
            for (k in i until j) {
                val c = s[k]
                when {
                    c in '0'..'9' -> digits++
                    Character.isLetter(c) -> letters++
                    c == '*' || c == '†' || c == '‡' || c == '§' || c == '¶' || c == '#' -> signs++
                    else -> return ""
                }
            }
            val ok = digits == n || signs == n && n <= 3 || letters == 1 && n == 1 || (letters == 1 && digits == n - 1 && n <= 3)
            return if (ok) s.subSequence(i, j).toString() else ""
        }
    }
}

/** Texte d'une note, prêt à être mis en page dans sa fenêtre. */
class Note(
    val label: String,
    val blocks: ArrayList<Block>,
    /** Position de la note dans son chapitre. */
    val offset: Int,
    /** Faux si la note est masquée dans le fil du texte : il n'y a nulle part où aller. */
    val visible: Boolean,
)

object Notes {
    private const val MAX_BLOCKS = 30
    private const val MAX_CHARS = 12000
    /** Tolérance pour « l'ancre est au début du bloc » : puce, crochet ouvrant, espace. */
    const val HEAD = 4

    /** Indice du bloc qui contient la position [off], ou -1. */
    fun blockAt(ch: Chapter, off: Int): Int {
        val blocks = ch.blocks
        if (blocks.isEmpty() || off < 0 || off >= ch.length) return -1
        var lo = 0
        var hi = blocks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (blocks[mid].start <= off) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Vrai si l'ancre [frag] désigne le début d'un bloc (une note), et non un point au milieu d'un paragraphe (un appel). */
    fun atBlockStart(ch: Chapter, frag: String): Boolean {
        val off = ch.anchors[frag] ?: return false
        val i = blockAt(ch, off)
        return i >= 0 && off - ch.blocks[i].start <= HEAD
    }

    /**
     * La note désignée par l'ancre [frag] : son bloc, puis les suivants tant qu'ils lui
     * appartiennent — jusqu'à la fin de l'élément qui porte l'identifiant (<aside>, <li>, <div>…)
     * ou, pour un simple paragraphe, jusqu'à la note suivante quand il y en a une juste après.
     * Le rappel du numéro et le lien de retour sont retirés. Null si l'ancre est introuvable.
     */
    fun extract(ch: Chapter, frag: String, call: String): Note? {
        val off = ch.anchors[frag] ?: return null
        val first = blockAt(ch, off)
        if (first < 0) return null
        val blocks = ch.blocks
        val end = ch.ends[frag] ?: -1
        var last = first
        if (end > off) {
            while (last + 1 < blocks.size && blocks[last + 1].start < end) last++
        } else {
            // Paragraphes de suite sans identifiant, s'ils sont bien pris entre deux notes.
            val starts = HashSet<Int>()
            for (v in ch.anchors.values) starts.add(v)
            fun anchored(b: Block): Boolean {
                for (d in 0..HEAD) if ((b.start + d) in starts) return true
                return false
            }
            var k = first + 1
            while (k < blocks.size && k - first <= 8 && blocks[k].kind == Block.PARA && !blocks[k].hidden && !anchored(blocks[k])) k++
            if (k < blocks.size && k - first <= 8 && blocks[k].kind == Block.PARA && !blocks[k].hidden) last = k - 1
        }
        val out = ArrayList<Block>()
        var chars = 0
        for (i in first..last) {
            val b = blocks[i]
            var t = b.text
            if (i == first && b.kind != Block.IMG) {
                t = head(b, off - b.start, call)
                if (t.isEmpty()) continue
            }
            if (i == last && b.kind != Block.IMG) t = tail(t)
            out.add(Block(b.kind, t, b.level, b.align, b.quote, b.gap && out.isNotEmpty(), b.indent && out.isNotEmpty(), b.img).also { it.css = b.css })
            chars += t.length
            if (out.size >= MAX_BLOCKS || chars >= MAX_CHARS) break
        }
        if (out.isEmpty()) return null
        return Note(call, out, off, !blocks[first].hidden)
    }

    /** Début de la note : sans la puce, sans le lien de retour ni le numéro répété. */
    private fun head(b: Block, rel: Int, call: String): CharSequence {
        val t = b.text
        val n = t.length
        var a = if (rel > HEAD) rel else 0
        if (a == 0 && n > 2 && t[0] == '•' && t[1] == ' ') a = 2
        var cut = -1
        // Lien de retour en tête (« 1 », « [1] », « ↩ »).
        b.links?.let { for (l in it) if (l.start <= a + 1 && l.end > a && l.end - l.start <= 8) cut = l.end }
        if (cut < 0) {
            // Numéro répété en clair, suivi d'une ponctuation ou d'une espace.
            var i = a
            if (i < n && (t[i] == '[' || t[i] == '(')) i++
            if (call.isNotEmpty() && n - i > call.length && t.startsWith(call, i)) {
                val j = i + call.length
                val c = t[j]
                if (c == '.' || c == ')' || c == ']' || c == ':' || c <= ' ' || c == ' ') cut = j
                if (cut >= 0 && Character.isLetter(call[0]) && c <= ' ') cut = -1   // « a » peut être un mot
            }
        }
        if (cut >= 0) {
            a = cut
            while (a < n && (t[a] == '.' || t[a] == ')' || t[a] == ']' || t[a] == ':' || t[a] <= ' ' || t[a] == ' ' || t[a] == '—' || t[a] == '–')) a++
        }
        return if (a == 0) t else t.subSequence(a, n)
    }

    /** Fin de la note : sans la flèche de retour. */
    private fun tail(t: CharSequence): CharSequence {
        var e = t.length
        while (e > 0) {
            val c = t[e - 1]
            if (c <= ' ' || c == ' ' || c == '↩' || c == '↑' || c == '⏎' || c == '↵' || c == '︎' || c == '️' || c == '^') e-- else break
        }
        return if (e == t.length) t else t.subSequence(0, e)
    }
}
