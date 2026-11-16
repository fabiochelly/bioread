package app.bioread

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.CharacterStyle
import android.text.style.ParagraphStyle
import android.text.style.ReplacementSpan
import java.text.BreakIterator
import java.util.Locale

/**
 * Paragraphe justifié composé par [KpBreaker] (coupure optimale sur le paragraphe entier) au
 * lieu du moteur du système. C'est une mise en page comme une autre pour le reste de l'app
 * (pagination, recherche, toucher, traductions sous les mots) : elle répond aux mêmes questions
 * qu'un StaticLayout, avec la même hauteur de ligne.
 *
 * Ce qu'elle fait de plus que le système :
 * - les coupures sont choisies sur tout le paragraphe : blancs réguliers, moins de césures ;
 * - les blancs peuvent aussi se resserrer un peu (d'un cinquième au plus) au lieu de seulement s'étirer ;
 * - césure par les motifs de TeX ([Hyphen]), identique sur tous les appareils ; jamais dans le
 *   dernier mot d'un paragraphe ;
 * - marge optique : en bout de ligne, le trait d'union, le point, la virgule et les guillemets
 *   débordent un peu dans la marge, et les guillemets ouvrants en début de ligne aussi ; le bord
 *   du texte paraît plus droit que s'il était aligné au pixel ;
 * - toutes les lignes ont la même hauteur (un appel de note en exposant n'écarte plus sa ligne).
 *
 * Chaque mot est tracé à sa place exacte (les blancs sont répartis ici, pas par le système) :
 * les positions rendues à la recherche et au toucher sont celles du tracé, au pixel près.
 *
 * Écritures non prises en charge (droite à gauche, idéogrammes), paragraphes à lignes
 * marquées, mot plus large que la ligne : [make] rend null et le système compose le paragraphe.
 */
class KpLayout private constructor(
    text: CharSequence, paint: TextPaint, width: Int, mult: Float, add: Float,
    private val chars: CharArray,
    private val adv: FloatArray,               // avance de chaque caractère
    private val runEnds: IntArray?,            // fins des passages de style (null : un seul, celui de [paint])
    private val runPaints: Array<TextPaint>?,
    private val runDeco: ByteArray?,           // 1 = souligné, 2 = barré
    private val starts: IntArray,              // début de chaque ligne, puis longueur du texte
    private val ends: IntArray,                // fin du texte visible de chaque ligne
    private val x0: FloatArray,                // abscisse du début de chaque ligne
    private val extra: FloatArray,             // blanc ajouté à chaque espace de la ligne (négatif : resserré)
    private val hyph: BooleanArray,            // ligne terminée par un trait d'union ajouté
    private val pitch: Int,                    // hauteur d'une ligne avec son interligne
    private val lineH: Int,                    // hauteur de la dernière ligne, sans interligne
    private val descent: Int,
) : Layout(text, paint, width, Alignment.ALIGN_NORMAL, mult, add) {
    private val lines = ends.size
    private val clip = Rect()

    override fun getLineCount() = lines
    override fun getLineTop(line: Int) = if (line >= lines) (lines - 1) * pitch + lineH else line * pitch
    override fun getLineDescent(line: Int) = if (line >= lines - 1) descent else descent + pitch - lineH
    override fun getLineStart(line: Int) = starts[line.coerceIn(0, lines)]
    override fun getParagraphDirection(line: Int) = DIR_LEFT_TO_RIGHT
    override fun getLineContainsTab(line: Int) = false
    override fun getLineDirections(line: Int): Directions = LTR
    override fun getTopPadding() = 0
    override fun getBottomPadding() = 0
    override fun getEllipsisStart(line: Int) = 0
    override fun getEllipsisCount(line: Int) = 0

    /** Abscisse du caractère [offset] sur la ligne [line], telle qu'il est tracé. */
    fun xAt(line: Int, offset: Int): Float {
        val s = starts[line]
        val to = offset.coerceIn(s, ends[line])
        var x = x0[line]
        val ex = extra[line]
        for (k in s until to) {
            x += adv[k]
            if (chars[k] == ' ') x += ex
        }
        return x
    }

    override fun getPrimaryHorizontal(offset: Int): Float {
        val o = offset.coerceIn(0, chars.size)
        return xAt(getLineForOffset(o), o)
    }

    override fun getSecondaryHorizontal(offset: Int) = getPrimaryHorizontal(offset)
    override fun getLineLeft(line: Int) = x0[line]
    override fun getLineRight(line: Int) = xAt(line, ends[line]) + if (hyph[line]) hyphenWidth() else 0f
    override fun getLineMax(line: Int) = getLineRight(line) - x0[line]
    override fun getLineWidth(line: Int) = getLineMax(line)

    private fun hyphenWidth() = paint.measureText("-")

    override fun draw(c: Canvas) = drawLines(c)

    override fun draw(c: Canvas, highlight: Path?, highlightPaint: Paint?, cursorOffsetVertical: Int) = drawLines(c)

    private fun drawLines(c: Canvas) {
        if (lines == 0 || !c.getClipBounds(clip)) return
        val first = (clip.top / pitch).coerceIn(0, lines - 1)
        val last = ((clip.bottom - 1) / pitch).coerceIn(0, lines - 1)
        for (line in first..last) drawLine(c, line)
    }

    private fun runAt(i: Int): Int {
        val re = runEnds ?: return 0
        var k = 0
        while (k < re.size - 1 && re[k] <= i) k++
        return k
    }

    private fun drawLine(c: Canvas, line: Int) {
        val s = starts[line]
        val e = ends[line]
        if (e <= s) return
        val y = (getLineTop(line + 1) - getLineDescent(line)).toFloat()
        val ex = extra[line]
        var run = runAt(s)
        var i = s
        var x = x0[line]
        var p: TextPaint = paint
        while (i < e) {
            val re = if (runEnds == null) e else minOf(e, runEnds[run])
            if (i >= re) { run++; continue }
            p = runPaints?.get(run) ?: paint
            if (ex != 0f && chars[i] == ' ') {
                x += adv[i] + ex
                i++
                continue
            }
            // Ligne justifiée : un mot à la fois, posé à sa place. Sinon, tout le passage d'un coup.
            var j = i + 1
            if (ex != 0f) { while (j < re && chars[j] != ' ') j++ } else j = re
            c.drawTextRun(chars, i, j - i, i, j - i, x, y + p.baselineShift, false, p)
            for (k in i until j) x += adv[k]
            i = j
        }
        if (hyph[line]) c.drawText("-", x, y + p.baselineShift, p)
        val deco = runDeco ?: return
        // Soulignés et barrés : d'un trait continu, blancs compris.
        val re = runEnds!!
        var a = 0
        for (k in re.indices) {
            val b = re[k]
            if (deco[k].toInt() != 0 && b > s && a < e) {
                val rp = runPaints!![k]
                val xa = xAt(line, maxOf(a, s))
                val xb = xAt(line, minOf(b, e))
                val size = rp.textSize
                var thick = size / 18f
                if (deco[k].toInt() and 1 != 0) {
                    var pos = size / 9f
                    if (Build.VERSION.SDK_INT >= 29) { pos = rp.underlinePosition; thick = rp.underlineThickness }
                    c.drawRect(xa, y + pos, xb, y + pos + maxOf(1f, thick), rp)
                }
                if (deco[k].toInt() and 2 != 0) {
                    var pos = -size * 0.286f
                    if (Build.VERSION.SDK_INT >= 29) { pos = rp.strikeThruPosition; thick = rp.strikeThruThickness }
                    c.drawRect(xa, y + pos, xb, y + pos + maxOf(1f, thick), rp)
                }
            }
            a = b
        }
    }

    companion object {
        /** Coupé par « adb shell setprop debug.bioread.kp 0 » : retour à la composition du système, pour comparer. */
        val enabled: Boolean = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "debug.bioread.kp") != "0"
        } catch (e: Throwable) {
            true
        }

        // Le seul sens d'écriture pris en charge ; l'objet du système n'est pas constructible.
        private val LTR: Directions by lazy {
            StaticLayout.Builder.obtain("a", 0, 1, TextPaint(), 100).build().getLineDirections(0)
        }
        private val BREAKER = object : ThreadLocal<KpBreaker>() {
            override fun initialValue() = KpBreaker()
        }
        private val LINES = ThreadLocal<Pair<Locale, BreakIterator>>()

        // Coût d'une césure (le triple de celui de TeX : moins de mots coupés, quitte à des blancs un peu moins égaux)
        // et d'une coupure après un trait d'union du texte.
        private const val HYPHEN = 150
        private const val EXHYPHEN = 50
        private const val SHY = '\u00AD'

        private fun supported(c: Char): Boolean {
            val k = c.code
            if (k < 0x20) return c == '\n'
            if (k < 0x0590) return true
            if (k in 0x1E00..0x2BFF) return !(k == 0x200E || k == 0x200F || k in 0x2028..0x202E || k in 0x2066..0x2069)
            return k in 0xFB00..0xFB06 || k == 0xFEFF   // ligatures ; espace sans largeur, courante dans les EPUB
        }

        private fun isHyphen(c: Char) = c == '-' || c == '\u2010' || c == '\u2011'

        /** Part de sa largeur dont un signe déborde dans la marge de droite, en fin de ligne. */
        private fun hangRight(c: Char): Float = when (c) {
            '.', ',' -> 0.7f
            '-', '\u2010', SHY -> 0.6f
            '’', '”', '\'', '"' -> 0.5f
            ':', ';', '»', '–' -> 0.3f
            '!', '?', '—', '…' -> 0.2f
            else -> 0f
        }

        /** Et dans la marge de gauche, en début de ligne. */
        private fun hangLeft(c: Char): Float = when (c) {
            '“', '‘', '"', '\'' -> 0.5f
            '«', '—', '–' -> 0.3f
            '(' -> 0.15f
            else -> 0f
        }

        private fun lineBreaks(locale: Locale): BreakIterator {
            LINES.get()?.let { if (it.first == locale) return it.second }
            return BreakIterator.getLineInstance(locale).also { LINES.set(Pair(locale, it)) }
        }

        /**
         * Compose [text] sur la largeur [width] : [first] et [left] sont les retraits à gauche de la
         * première ligne et des suivantes, [right] le retrait à droite. Rend null si le paragraphe
         * n'est pas pris en charge (voir la classe).
         */
        fun make(
            text: CharSequence, paint: TextPaint, width: Int, first: Int, left: Int, right: Int,
            mult: Float, add: Float, hyphen: Hyphen?, locale: Locale,
        ): KpLayout? {
            val len = text.length
            if (!enabled || len == 0 || len > 30000) return null
            val firstW = (width - first - right).toFloat()
            val restW = (width - left - right).toFloat()
            val size = paint.textSize
            if (firstW < 4 * size || restW < 4 * size) return null
            val chars = CharArray(len)
            TextUtils.getChars(text, 0, len, chars, 0)
            var shy = false
            for (c in chars) {
                if (!supported(c)) return null
                if (c == SHY) shy = true
            }

            // Passages de style (italique, gras, exposant…) : une peinture par passage.
            var runEnds: IntArray? = null
            var runPaints: Array<TextPaint>? = null
            var runDeco: ByteArray? = null
            val adv = FloatArray(len)
            if (text is Spanned && text.getSpans(0, len, CharacterStyle::class.java).isNotEmpty()) {
                if (text.getSpans(0, len, ParagraphStyle::class.java).isNotEmpty()) return null
                if (text.getSpans(0, len, ReplacementSpan::class.java).isNotEmpty()) return null
                val ends = ArrayList<Int>()
                val paints = ArrayList<TextPaint>()
                var decorated = false
                var i = 0
                while (i < len) {
                    val e = text.nextSpanTransition(i, len, CharacterStyle::class.java)
                    val spans = text.getSpans(i, e, CharacterStyle::class.java)
                    var p = paint
                    if (spans.isNotEmpty()) {
                        p = TextPaint(paint)
                        for (sp in spans) sp.updateDrawState(p)
                        // Un passage dans un corps plus grand demande une ligne plus haute : au système.
                        if (p.textSize > size * 1.02f) return null
                        if (p.isUnderlineText || p.isStrikeThruText) decorated = true
                    }
                    ends.add(e)
                    paints.add(p)
                    i = e
                }
                if (paints.size > 1 || paints[0] !== paint) {
                    runEnds = IntArray(ends.size) { ends[it] }
                    runPaints = Array(paints.size) { paints[it] }
                    if (decorated) {
                        runDeco = ByteArray(paints.size)
                        for (k in paints.indices) {
                            val p = paints[k]
                            if (p === paint) continue
                            runDeco[k] = ((if (p.isUnderlineText) 1 else 0) or (if (p.isStrikeThruText) 2 else 0)).toByte()
                            p.isUnderlineText = false
                            p.isStrikeThruText = false
                        }
                    }
                    val tmp = FloatArray(len)
                    var a = 0
                    for (k in runEnds.indices) {
                        val b = runEnds[k]
                        runPaints[k].getTextWidths(chars, a, b - a, tmp)
                        System.arraycopy(tmp, 0, adv, a, b - a)
                        a = b
                    }
                }
            }
            if (runEnds == null) paint.getTextWidths(chars, 0, len, adv)

            val hyphenW = paint.measureText("-")
            val br = BREAKER.get()!!
            val bi = lineBreaks(locale)
            val str = String(chars)

            fun sum(a: Int, b: Int): Float {
                var w = 0f
                for (k in a until b) w += adv[k]
                return w
            }

            fun rightOf(a: Int, e: Int) = if (e > a) hangRight(chars[e - 1]) * adv[e - 1] else 0f
            fun leftOf(i: Int) = if (i < len) hangLeft(chars[i]) * adv[i] else 0f

            /** Les éléments du paragraphe ; [cut] : tableau de travail des césures, null pour ne pas en chercher. */
            fun build(cut: BooleanArray?) {
                br.reset()
                bi.setText(str)
                var prev = bi.first()
                var e = bi.next()
                while (e != BreakIterator.DONE) {
                    var ce = e
                    var newline = false
                    while (ce > prev && (chars[ce - 1] == ' ' || chars[ce - 1] == '\n')) {
                        if (chars[ce - 1] == '\n') newline = true
                        ce--
                    }
                    if (ce > prev) {
                        // Le mot : une boîte, ou plusieurs séparées par ses points de césure. Jamais de
                        // césure dans le dernier mot du paragraphe, ni dans un mot composé (porte-monnaie).
                        var any = false
                        if (cut != null && e < len) {
                            for (k in prev until ce - 1) if (chars[k] == SHY) { cut[k + 1] = true; any = true }
                            if (!any && hyphen != null && !isHyphen(chars[ce - 1]) && !(prev > 0 && isHyphen(chars[prev - 1]))) {
                                var i = prev
                                while (i < ce) {
                                    if (!Character.isLetter(chars[i])) { i++; continue }
                                    var j = i + 1
                                    // Ni noms propres ni sigles : aucun mot à capitale n'est coupé.
                                    var caps = Character.isUpperCase(chars[i])
                                    while (j < ce && Character.isLetter(chars[j])) {
                                        if (Character.isUpperCase(chars[j])) caps = true
                                        j++
                                    }
                                    if (j - i >= 6 && !caps && hyphen.points(chars, i, j, cut) > 0) any = true
                                    i = j
                                }
                            }
                        }
                        if (any) {
                            var bs = prev
                            for (p in prev + 1 until ce) if (cut!![p]) {
                                cut[p] = false
                                br.box(sum(bs, p))
                                br.penalty(hyphenW, HYPHEN, true, p, p, hyphenW * hangRight('-'), 0f)
                                bs = p
                            }
                            br.box(sum(bs, ce))
                        } else br.box(sum(prev, ce))
                    }
                    if (e == len || newline) {
                        // Fin de paragraphe ou retour à la ligne : la ligne n'est pas justifiée.
                        br.penalty(0f, KpBreaker.INFINITE, false, ce, e, 0f, 0f)
                        br.glue(0f, KpBreaker.FILL, 0f, ce, e, 0f, 0f)
                        br.penalty(0f, KpBreaker.FORCED, false, ce, e, rightOf(prev, ce), leftOf(e))
                    } else if (ce < e) {
                        // Espace : s'étire de moitié comme dans TeX, mais ne se resserre que d'un
                        // cinquième (un tiers dans TeX) : des mots trop rapprochés se lisent mal à l'écran.
                        val sp = sum(ce, e)
                        br.glue(sp, sp * 0.5f, sp * 0.2f, ce, e, rightOf(prev, ce), leftOf(e))
                    } else {
                        // Coupure sans espace : après un trait d'union, un tiret, une barre oblique. Après
                        // un trait d'union, seulement entre deux éléments d'au moins trois lettres : ni
                        // « enfonça- / t-il », ni « dit- / il ».
                        var ok = true
                        if (isHyphen(chars[e - 1])) {
                            var a = e - 1
                            while (a > 0 && Character.isLetter(chars[a - 1])) a--
                            var b = e
                            while (b < len && Character.isLetter(chars[b])) b++
                            ok = e - 1 - a >= 3 && b - e >= 3
                        }
                        if (ok) br.penalty(0f, EXHYPHEN, false, e, e, rightOf(prev, e), leftOf(e))
                    }
                    prev = e
                    e = bi.next()
                }
            }

            // D'abord sans césure, avec des blancs étirés de 50 % au plus ; puis avec césures ; puis,
            // faute de mieux (colonne étroite, mots longs), la moins mauvaise composition possible.
            val l0 = leftOf(0)
            build(null)
            var breaks = br.run(firstW, restW, l0, 1f)
            if (breaks == null) {
                if (hyphen != null || shy) build(BooleanArray(len + 1))
                breaks = br.run(firstW, restW, l0, 2.5f) ?: br.run(firstW, restW, l0, Float.MAX_VALUE)
            }
            breaks ?: return null

            val n = breaks.size
            val starts = IntArray(n + 1)
            val ends = IntArray(n)
            val x0 = FloatArray(n)
            val extra = FloatArray(n)
            val hyph = BooleanArray(n)
            var s = 0
            for (i in 0 until n) {
                val b = breaks[i]
                starts[i] = s
                ends[i] = br.end[b]
                hyph[i] = br.hyphenAt(b)
                s = br.next[b]
            }
            starts[n] = len
            for (i in 0 until n) {
                val a = starts[i]
                val e = ends[i]
                // Césure au milieu d'une ligature (of-fice) : les deux moitiés sont remesurées telles
                // qu'elles seront tracées, chacune de son côté.
                if (hyph[i] && e < len && e > a && adv[e] == 0f && chars[e] != SHY) {
                    var ws = e
                    while (ws > a && chars[ws - 1] != ' ') ws--
                    var we = e
                    while (we < len && chars[we] != ' ' && chars[we] != '\n') we++
                    val re = runEnds
                    var k = 0
                    if (re != null) while (k < re.size - 1 && re[k] <= ws) k++
                    if (re == null || re[k] >= we) {
                        val p = runPaints?.get(k) ?: paint
                        val tmp = FloatArray(we - ws)
                        p.getTextWidths(chars, ws, e - ws, tmp)
                        System.arraycopy(tmp, 0, adv, ws, e - ws)
                        p.getTextWidths(chars, e, we - e, tmp)
                        System.arraycopy(tmp, 0, adv, e, we - e)
                    }
                }
                val indent = if (i == 0) first else left
                val lp = if (e > a) hangLeft(chars[a]) * adv[a] else 0f
                val rp = if (hyph[i]) hyphenW * hangRight('-') else rightOf(a, e)
                var natural = if (hyph[i]) hyphenW else 0f
                var spaces = 0
                for (k in a until e) {
                    natural += adv[k]
                    if (chars[k] == ' ') spaces++
                }
                x0[i] = indent - lp
                if (spaces > 0) {
                    val ex = (width - indent - right + lp + rp - natural) / spaces
                    // Dernière ligne (ou ligne avant un retour) : jamais étirée, resserrée s'il le faut.
                    val nx = starts[i + 1]
                    val open = i == n - 1 || (nx > 0 && chars[nx - 1] == '\n')
                    extra[i] = if (open) minOf(0f, ex) else ex
                }
            }

            // Hauteur des lignes : celle que donnerait le système à un paragraphe sans exposant ni
            // corps étranger, la dernière ligne sans interligne.
            val fm = paint.fontMetricsInt
            val h = fm.descent - fm.ascent
            val ex = h * (mult - 1.0) + add
            val more = if (ex >= 0) (ex + 0.5).toInt() else -(-ex + 0.5).toInt()
            return KpLayout(
                text, paint, width, mult, add, chars, adv, runEnds, runPaints, runDeco,
                starts, ends, x0, extra, hyph, h + more, h, fm.descent,
            )
        }
    }
}
