package app.bioread

import android.graphics.RectF
import android.text.Layout
import java.text.Normalizer

/**
 * Une occurrence trouvée : position dans le livre (élément du spine, caractère dans le chapitre,
 * le même repère que les positions de lecture) et le texte qui l'entoure.
 */
class Hit(
    val spine: Int,
    val offset: Int,
    val length: Int,
    val before: String,      // contexte brut avant l'occurrence (coupé plus tard à la largeur de l'écran)
    val match: String,
    val after: String,
    val atStart: Boolean,    // [before] commence au début du paragraphe
    val atEnd: Boolean,      // [after] va jusqu'à la fin du paragraphe
    val chapter: String,
    val percent: Int,
)

/**
 * Texte « plié » pour la comparaison : minuscules, sans accents, ligatures développées
 * (œ → oe), apostrophes et espaces typographiques ramenés à leur forme simple, césures
 * invisibles retirées. [idx] donne, pour chaque caractère plié, sa position dans la source.
 */
class Folder {
    var buf = CharArray(256)
        private set
    var idx = IntArray(256)
        private set
    var n = 0
        private set

    private fun put(c: Char, i: Int) {
        if (n == buf.size) {
            buf = buf.copyOf(n * 2)
            idx = idx.copyOf(n * 2)
        }
        buf[n] = c
        idx[n] = i
        n++
    }

    fun fold(s: CharSequence): String {
        n = 0
        for (i in 0 until s.length) {
            val c = s[i]
            if (c < '\u0080') {
                put(if (c in 'A'..'Z') c + 32 else if (c == '\n' || c == '\t') ' ' else c, i)
                continue
            }
            when (c) {
                'œ', 'Œ' -> { put('o', i); put('e', i) }
                'æ', 'Æ' -> { put('a', i); put('e', i) }
                'ß' -> { put('s', i); put('s', i) }
                '’', '‘', 'ʼ', '′' -> put('\'', i)
                '“', '”', '„' -> put('"', i)
                '‐', '‑' -> put('-', i)
                '­', '​', '‌', '‍', '⁠', '﻿' -> {}
                else -> when {
                    c in '̀'..'ͯ' -> {}
                    c < 'ɐ' -> put(LATIN[c.code], i)
                    Character.isSpaceChar(c) -> put(' ', i)
                    else -> put(Character.toLowerCase(c), i)
                }
            }
        }
        return String(buf, 0, n)
    }

    companion object {
        /** Lettre de base, en minuscule, de chaque caractère latin accentué. */
        private val LATIN = CharArray(0x250) { k ->
            val c = Character.toLowerCase(k.toChar())
            when {
                k == 0xA0 -> ' '
                k < 0xC0 -> c
                else -> {
                    val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
                    if (d.length > 1 && d[0] < '\u0080' && d[1] in '̀'..'ͯ') d[0] else when (c) {
                        'ø' -> 'o'; 'đ' -> 'd'; 'ł' -> 'l'; 'ı' -> 'i'
                        else -> c
                    }
                }
            }
        }

        /** Requête pliée : espaces superflus retirés. */
        fun query(q: String): String = Folder().fold(q).trim().replace(Regex(" +"), " ")
    }
}

/**
 * Recherche dans tout le livre, hors du thread principal : les chapitres sont lus et analysés
 * un par un, jamais gardés. [cancel] arrête le travail au chapitre suivant ; rien n'est
 * signalé ensuite. [progress] et [done] sont appelés sur le fil de recherche.
 */
class SearchJob(
    private val epub: Epub,
    private val fonts: Fonts,
    private val query: String,
    private val progress: (Int) -> Unit,
    private val done: (SearchJob) -> Unit,
) {
    val hits = ArrayList<Hit>()
    /** Nombre réel d'occurrences ; seules les [MAX] premières sont gardées dans [hits]. */
    var total = 0
        private set
    var failed = false
        private set
    @Volatile var cancelled = false
        private set

    fun cancel() {
        cancelled = true
    }

    fun start() {
        Thread({
            try {
                run()
            } catch (x: Throwable) {
                // Livre fermé pendant la recherche, mémoire insuffisante…
                failed = true
            }
            if (!cancelled) done(this)
        }, "recherche").start()
    }

    private fun run() {
        val q = Folder.query(query)
        if (q.isEmpty()) return
        val folder = Folder()
        val toc = epub.toc
        val n = epub.spine.size
        var k = -1          // dernière entrée du sommaire placée avant la position examinée
        var shown = -1
        var last = 0L
        for (i in 0 until n) {
            if (cancelled) return
            val now = System.currentTimeMillis()
            val pc = (epub.cum[i] * 100 / epub.total).toInt()
            // L'écran d'une liseuse est lent : l'avancement n'est signalé que trois fois par seconde.
            if (pc != shown && now - last >= 300) {
                shown = pc
                last = now
                progress(pc)
            }
            val path = epub.spine[i]
            val ch = try {
                parseChapter(epub.read(path) ?: "", path, fonts, epub.styles())
            } catch (x: Exception) {
                if (cancelled) return
                continue
            }
            // Le sommaire est supposé dans l'ordre du livre : [k] ne fait qu'avancer.
            while (k + 1 < toc.size && toc[k + 1].spine < i) k++
            fun tocOffset(t: TocItem) = t.anchor?.let { ch.anchors[it] } ?: 0
            val size = epub.cum[i + 1] - epub.cum[i]
            for (b in ch.blocks) {
                if (b.kind == Block.IMG || b.text.length < q.length) continue
                val folded = folder.fold(b.text)
                var at = folded.indexOf(q)
                while (at >= 0) {
                    total++
                    if (hits.size < MAX) {
                        val idx = folder.idx
                        val s = idx[at]
                        var e = idx[at + q.length - 1] + 1
                        val t = b.text
                        // Accents écrits à part (forme décomposée) : ils suivent leur lettre.
                        while (e < t.length && t[e] in '̀'..'ͯ') e++
                        val off = b.start + s
                        while (k + 1 < toc.size && toc[k + 1].spine == i && tocOffset(toc[k + 1]) <= off) k++
                        val a = maxOf(0, s - CONTEXT)
                        val z = minOf(t.length, e + CONTEXT)
                        hits.add(Hit(
                            i, off, e - s,
                            flat(t, a, s), flat(t, s, e), flat(t, e, z), a == 0, z == t.length,
                            if (k >= 0) toc[k].title.ifEmpty { "Section ${i + 1}" } else "Section ${i + 1}",
                            ((epub.cum[i] + size * off / maxOf(1, ch.length)) * 100 / epub.total).toInt(),
                        ))
                    }
                    at = folded.indexOf(q, at + q.length)
                }
            }
        }
    }

    private fun flat(t: CharSequence, a: Int, b: Int) = t.subSequence(a, b).toString().replace('\n', ' ')

    companion object {
        const val MAX = 3000
        /** Caractères de contexte gardés de chaque côté de l'occurrence. */
        const val CONTEXT = 100
    }
}

/**
 * Rectangles (dans le repère de la page) qui soulignent le texte [offset, offset + length) du
 * chapitre sur la page [p] ; liste vide s'il n'y est pas.
 */
fun Typesetter.markRects(p: Page, offset: Int, length: Int): ArrayList<RectF> {
    val out = ArrayList<RectF>(2)
    for (s in p.slices) {
        val l = s.layout ?: continue
        val a = maxOf(offset - s.block.start, l.getLineStart(s.a))
        val b = minOf(offset + length - s.block.start, l.getLineEnd(s.b - 1), l.text.length)
        if (a >= b) continue
        val dy = top + s.y - l.getLineTop(s.a)
        val first = l.getLineForOffset(a)
        val last = l.getLineForOffset(b - 1)
        for (line in first..last) {
            if (line < s.a || line >= s.b) continue
            val x0 = lineX(l, line, maxOf(a, l.getLineStart(line)))
            val x1 = lineX(l, line, minOf(b, l.getLineEnd(line)))
            out.add(RectF(mx + minOf(x0, x1), (dy + l.getLineTop(line)).toFloat(), mx + maxOf(x0, x1), (dy + l.getLineBaseline(line)).toFloat()))
        }
    }
    return out
}

/**
 * Abscisse du caractère [offset] sur la ligne [line]. Sur une ligne justifiée, la mise en page
 * du système donne la position avant justification : le blanc ajouté est réparti ici sur les
 * espaces qui précèdent, comme au tracé.
 */
internal fun lineX(l: Layout, line: Int, offset: Int): Float {
    val t = l.text
    val start = l.getLineStart(line)
    var end = l.getLineEnd(line)
    val wrapped = end < t.length && t[end - 1] != '\n'
    while (end > start && (t[end - 1] == ' ' || t[end - 1] == '\n')) end--
    val natural = Layout.getDesiredWidth(t, start, end, l.paint)
    // Fin de ligne : la position rendue par le système serait celle du début de la ligne suivante.
    val x = if (offset >= end) l.getPrimaryHorizontal(start) + natural else l.getPrimaryHorizontal(offset)
    if (!wrapped) return x
    var spaces = 0
    var before = 0
    for (i in start until end) if (t[i] == ' ') { spaces++; if (i < offset) before++ }
    val extra = l.getLineMax(line) - natural
    if (spaces == 0 || extra < 1f) return x
    return x + extra * before / spaces
}
