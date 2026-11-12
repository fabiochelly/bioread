package app.bioread

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import java.util.Locale

/** Bookerly pour le texte, Inter pour les titres et l'interface. Chargées une seule fois. */
class Fonts(c: Context) {
    val regular = load(c, "Bookerly-Regular.ttf", Typeface.NORMAL)
    val italic = load(c, "Bookerly-Italic.ttf", Typeface.ITALIC)
    val bold = load(c, "Bookerly-Bold.ttf", Typeface.BOLD)
    val boldItalic = load(c, "Bookerly-BoldItalic.ttf", Typeface.BOLD_ITALIC)
    val ui = inter(c, 450)
    val uiBold = inter(c, 650)

    private fun load(c: Context, name: String, style: Int): Typeface = try {
        Typeface.createFromAsset(c.assets, "fonts/$name")
    } catch (e: Exception) {
        Typeface.create(Typeface.SERIF, style)
    }

    private fun inter(c: Context, weight: Int): Typeface = try {
        Typeface.Builder(c.assets, "fonts/InterVariable.ttf")
            .setFontVariationSettings("'wght' $weight").build()
    } catch (e: Exception) {
        Typeface.create(Typeface.SANS_SERIF, if (weight > 500) Typeface.BOLD else Typeface.NORMAL)
    }

    fun body(b: Boolean, i: Boolean) = if (b) (if (i) boldItalic else bold) else (if (i) italic else regular)
}

/** Italique / gras à l'intérieur d'un paragraphe, en choisissant la vraie graisse Bookerly. */
class FontSpan(private val f: Fonts, val bold: Boolean, val italic: Boolean) : MetricAffectingSpan() {
    override fun updateDrawState(p: TextPaint) = apply(p)
    override fun updateMeasureState(p: TextPaint) = apply(p)

    private fun apply(p: TextPaint) {
        val t = p.typeface
        val b = t === f.bold || t === f.boldItalic
        val i = t === f.italic || t === f.boldItalic
        if (!b && !i && t !== f.regular) {
            if (italic) p.textSkewX = -0.2f   // titres en Inter : italique simulé
            return
        }
        p.typeface = f.body(b || bold, i || italic)
    }
}

/**
 * Tranche de bloc posée sur une page. Texte : lignes [a, b) de [layout], dessinées à partir de [y].
 * Image : [layout] nul, [a] × [b] = taille à l'écran.
 */
class Slice(val layout: Layout?, val a: Int, val b: Int, val y: Int, val block: Block)

class Page {
    val slices = ArrayList<Slice>(8)
    var start = 0            // position dans le chapitre du premier caractère
    var tr: Page? = null     // version traduite, une fois prête
    var trState = 0          // 0 = à demander, 1 = en cours, 2 = terminé, 3 = échec
}

class LaidChapter(val index: Int, val chapter: Chapter, val pages: ArrayList<Page>) {
    fun pageFor(offset: Int): Int {
        var lo = 0
        var hi = pages.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pages[mid].start <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }
}

/** Composition et pagination pour une géométrie et des réglages donnés (immuable). */
class Typesetter(
    private val f: Fonts,
    val w: Int,
    val h: Int,
    val topInset: Int,
    private val density: Float,
    lang: String,
) {
    private val locale: Locale = if (lang.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(lang)
    // Les pages traduites sont coupées selon les règles de césure de la langue cible.
    private val targetLocale: Locale = Locale.forLanguageTag(Prefs.target)
    private val textPx = Prefs.fontSize * density
    private val mult = Prefs.lineSpacing / 100f
    val mx = (Prefs.margin * density).toInt()
    val top = (Prefs.margin * 0.6f * density + 14 * density).toInt() + topInset
    val bottom = (Prefs.margin * 0.4f * density + 30 * density).toInt()
    val cw = w - 2 * mx
    val ch = h - top - bottom
    // Partagés entre le thread de composition et le thread principal (pages traduites).
    private val bodyPaints = java.util.concurrent.ConcurrentHashMap<Float, TextPaint>()
    private val headPaints = java.util.concurrent.ConcurrentHashMap<Float, TextPaint>()
    private val footPaint = Theme.textPaint().apply {
        typeface = f.ui
        // Attention : dans ce bloc, « density » seul désignerait le champ de TextPaint (1.0).
        textSize = 14 * this@Typesetter.density
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.7f * density }
    private val oval = android.graphics.RectF()
    private val iconSize = footPaint.textSize * 1.25f

    /** Largeur, depuis le bord droit, de la zone tactile de l'icône de traduction. */
    val translateZone = (mx + iconSize + 20 * density).toInt()

    /** Largeur, depuis le bord gauche, de la zone tactile de la loupe (recherche). */
    val searchZone = if (Theme.eink) (mx + iconSize + 13 * density).toInt() else maxOf(mx + iconSize + 12 * density, 48 * density).toInt()

    // Liseuse : icône de rafraîchissement de l'écran, à droite de la loupe.
    // Large espace de part et d'autre : toute la bande entre la loupe et le pourcentage la déclenche.
    private val refreshX = mx + iconSize * 1.5f + 26 * density
    private val footTextX = if (Theme.eink) refreshX + iconSize / 2f + 24 * density else mx + iconSize + 8 * density

    /** Bord droit de la zone tactile du rafraîchissement (0 hors liseuse : pas d'icône). */
    val refreshZone = if (Theme.eink) (footTextX - 2 * density).toInt() else 0
    private val arrow = android.graphics.Path()
    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val styled = Styled()   // blocs du mode « Fidèle au livre »

    private fun bodyPaint(scale: Float, tr: Boolean) = bodyPaints.getOrPut(if (tr) -scale else scale) {
        Theme.textPaint().apply {
            typeface = f.regular
            textSize = textPx * scale
            Theme.tune(this)
            textLocale = if (tr) targetLocale else locale
        }
    }

    private fun headPaint(level: Int, scale: Float, tr: Boolean): TextPaint {
        val k = when (level) { 1 -> 1.5f; 2 -> 1.3f; 3 -> 1.15f; else -> 1f }
        return headPaints.getOrPut(if (tr) -k * scale else k * scale) {
            Theme.textPaint().apply {
                typeface = f.uiBold
                textSize = textPx * k * scale
                Theme.hint(this)
                    textLocale = if (tr) targetLocale else locale
                letterSpacing = -0.01f
            }
        }
    }

    private fun lineHeight(p: TextPaint): Int {
        val fm = p.fontMetricsInt
        return Math.round((fm.descent - fm.ascent) * mult)
    }

    private fun layout(b: Block, scale: Float, tr: Boolean): StaticLayout {
        val head = b.kind == Block.HEAD
        val p = if (head) headPaint(b.level, scale, tr) else bodyPaint(scale, tr)
        b.css?.let { return styled.layout(b, it, p, cw, mult) }
        val t = b.text
        val sb = StaticLayout.Builder.obtain(t, 0, t.length, p, cw).setIncludePad(false)
        if (head) {
            sb.setLineSpacing(0f, 1.15f)
                .setAlignment(if (b.level <= 2 || b.align == 1) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
        } else {
            sb.setLineSpacing(0f, mult)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            when (b.align) {
                1 -> sb.setAlignment(Layout.Alignment.ALIGN_CENTER)
                2 -> sb.setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
                else -> sb.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            }
            val q = (b.quote * 1.6f * p.textSize).toInt()
            val ind = if (b.indent) (1.3f * p.textSize).toInt() else 0
            if (q > 0 || ind > 0) sb.setIndents(intArrayOf(q + ind, q), intArrayOf(q, q))
        }
        return sb.build()
    }

    /**
     * [parallel] : répartit la composition des paragraphes sur les cœurs disponibles (chapitre
     * attendu par le lecteur). [done] : paragraphes déjà composés par [quickPage], réutilisés.
     * [stop] : consulté à chaque paragraphe ; dès qu'il rend vrai (réglage changé entre-temps,
     * livre fermé), le travail est abandonné par une [java.util.concurrent.CancellationException].
     */
    fun layoutChapter(
        index: Int, c: Chapter, epub: Epub, parallel: Boolean = false, done: Array<StaticLayout?>? = null,
        stop: (() -> Boolean)? = null,
    ) = LaidChapter(index, c, paginate(c.blocks, 1f, epub, false, parallel, done, stop = stop))

    /**
     * La seule page qui commence à la position [offset] du chapitre, sans composer ce qui la
     * précède : à la réouverture d'un livre, elle s'affiche avant que le chapitre entier soit
     * prêt. Rend null si aucune ligne ne commence exactement là (réglages changés depuis).
     * Les paragraphes composés sont rangés dans [done] pour la composition complète.
     */
    fun quickPage(c: Chapter, offset: Int, epub: Epub, done: Array<StaticLayout?>): Page? {
        val blocks = c.blocks
        var lo = 0
        var hi = blocks.size - 1
        if (hi < 0) return null
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (blocks[mid].start <= offset) lo = mid else hi = mid - 1
        }
        val b = blocks[lo]
        var line = 0
        if (b.kind == Block.IMG) {
            if (b.start != offset) return null
        } else {
            val l = layout(b, 1f, false)
            done[lo] = l
            val rel = offset - b.start
            if (rel < 0 || rel >= b.text.length) return null
            line = l.getLineForOffset(rel)
            if (l.getLineStart(line) != rel) return null
        }
        val pages = paginate(blocks, 1f, epub, false, false, done, lo, line, 1)
        return pages.firstOrNull()?.takeIf { it.start == offset }
    }

    /**
     * Compose tous les blocs de texte en parallèle. La coupure des lignes (césure, justification)
     * est de loin l'étape la plus coûteuse et chaque paragraphe est indépendant : les fils se
     * partagent les blocs un par un, le fil appelant participe.
     */
    private fun layoutAll(
        blocks: List<Block>, scale: Float, tr: Boolean, done: Array<StaticLayout?>?, stop: (() -> Boolean)?,
    ): Array<StaticLayout?> {
        val out = done ?: arrayOfNulls<StaticLayout>(blocks.size)
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val work = Runnable {
            while (true) {
                val i = next.getAndIncrement()
                if (i >= blocks.size || (stop != null && stop())) break
                val b = blocks[i]
                if (b.kind != Block.IMG && !b.hidden && out[i] == null) out[i] = layout(b, scale, tr)
            }
        }
        val helpers = ArrayList<java.util.concurrent.Future<*>>(WORKERS)
        for (k in 0 until WORKERS) helpers.add(POOL.submit(work))
        work.run()
        for (h in helpers) h.get()
        if (stop != null && stop()) throw java.util.concurrent.CancellationException()
        return out
    }

    /** Met [blocks] sur une seule page en réduisant le corps si nécessaire (page traduite). */
    fun fit(blocks: List<Block>): Page {
        var scale = 1f
        var pages = paginate(blocks, scale, null, true, false)
        var tries = 0
        while (pages.size > 1 && tries++ < 9) {
            scale *= 0.94f
            pages = paginate(blocks, scale, null, true, false)
        }
        return pages[0]
    }

    /**
     * [done] : paragraphes déjà composés (complété au passage). [fromBlock], [fromLine] et
     * [maxPages] limitent la pagination aux premières pages à partir d'une ligne donnée.
     */
    private fun paginate(
        blocks: List<Block>, scale: Float, epub: Epub?, tr: Boolean, parallel: Boolean,
        done: Array<StaticLayout?>? = null, fromBlock: Int = 0, fromLine: Int = 0, maxPages: Int = Int.MAX_VALUE,
        stop: (() -> Boolean)? = null,
    ): ArrayList<Page> {
        val ready = if (parallel && WORKERS > 0 && blocks.size >= 24) layoutAll(blocks, scale, tr, done, stop) else done
        val pages = ArrayList<Page>()
        var cur = Page()
        val bodyLH = lineHeight(bodyPaint(scale, tr))
        var y = 0
        var after = 0   // marge basse du bloc précédent

        fun close() {
            if (cur.slices.isEmpty()) return
            val s = cur.slices[0]
            cur.start = s.block.start + (s.layout?.getLineStart(s.a) ?: 0)
            pages.add(cur)
            cur = Page()
        }

        for (index in fromBlock until blocks.size) {
            if (pages.size >= maxPages) break
            if (stop != null && stop()) throw java.util.concurrent.CancellationException()
            val b = blocks[index]
            if (b.hidden) continue   // note appelée depuis le texte : lue dans sa fenêtre
            if (b.kind == Block.IMG) {
                if (b.imgW < 0 && epub != null) measureImage(b, epub)
                if (b.imgW <= 0 || b.imgH <= 0) continue
                var dw = minOf(b.imgW * density, cw.toFloat())
                var dh = b.imgH * dw / b.imgW
                if (dh > ch) { dh = ch.toFloat(); dw = b.imgW * dh / b.imgH }
                var m = if (y == 0) 0 else maxOf(after, bodyLH / 2)
                if (y > 0 && y + m + dh > ch) { close(); y = 0; m = 0 }
                cur.slices.add(Slice(null, dw.toInt(), dh.toInt(), y + m, b))
                y += m + dh.toInt()
                after = bodyLH / 2
                continue
            }
            val l = ready?.get(index) ?: layout(b, scale, tr).also { if (ready != null) ready[index] = it }
            val head = b.kind == Block.HEAD
            var m = when {
                y == 0 -> 0
                head -> maxOf(after, (bodyLH * 1.3f).toInt())
                b.gap -> maxOf(after, bodyLH)
                else -> after
            }
            // Mode « Fidèle au livre » : saut de page ou blanc demandé par le livre.
            val st = b.css
            if (st != null && y > 0) {
                if (st.pageBreak) { close(); y = 0; m = 0 } else if (st.top > 0f) m = maxOf(m, (st.top * textPx * scale).toInt())
            }
            // Un titre ne reste jamais seul en bas de page.
            if (head && y > 0 && y + m + l.height + 2 * bodyLH > ch) { close(); y = 0; m = 0 }
            val n = l.lineCount
            val full = if (head) 0 else bodyLH
            var from = if (index == fromBlock) fromLine else 0
            var sy = y + m
            y += m
            for (line in from until n) {
                val lh = l.getLineBottom(line) - l.getLineTop(line)
                if (y + lh > ch && y > 0) {
                    if (line > from) cur.slices.add(Slice(l, from, line, sy, b))
                    close()
                    if (pages.size >= maxPages) break
                    from = line
                    sy = 0
                    y = 0
                }
                // La dernière ligne d'un StaticLayout n'a pas d'interligne : on le rétablit.
                y += if (line == n - 1) maxOf(lh, full) else lh
            }
            if (pages.size >= maxPages) break
            cur.slices.add(Slice(l, from, n, sy, b))
            after = if (head) (bodyLH * 0.7f).toInt() else 0
        }
        if (pages.size < maxPages) close()
        if (pages.isEmpty()) pages.add(cur)
        // Une image seule sur sa page (couverture, planche) est centrée verticalement.
        for (p in pages) if (p.slices.size == 1 && p.slices[0].layout == null) {
            val s = p.slices[0]
            p.slices[0] = Slice(null, s.a, s.b, (ch - s.b) / 2, s.block)
        }
        return pages
    }

    private fun measureImage(b: Block, epub: Epub) {
        b.imgW = 0
        b.imgH = 0
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            epub.open(b.img!!)?.use { BitmapFactory.decodeStream(it, null, o) }
            b.imgW = maxOf(0, o.outWidth)
            b.imgH = maxOf(0, o.outHeight)
        } catch (e: Exception) {
        }
    }

    /** Hauteur de la bande basse qui réagit au toucher (ouvre la navigation). */
    val footerZone = maxOf(bottom, (44 * density).toInt())

    /** Pied de page, dessiné par-dessus la page à chaque rafraîchissement (l'heure change). */
    fun drawFooter(c: Canvas, left: String, center: String, right: String, translating: Boolean) {
        val y = h - bottom / 2f + footPaint.textSize / 3f
        footPaint.textAlign = Paint.Align.LEFT
        c.drawText(left, footTextX, y, footPaint)
        footPaint.textAlign = Paint.Align.CENTER
        c.drawText(center, w / 2f, y, footPaint)
        footPaint.textAlign = Paint.Align.RIGHT
        c.drawText(right, w - mx - iconSize - 10 * density, y, footPaint)

        // Icône de traduction (globe) : au trait quand elle est coupée, pleine quand elle est active.
        val r = iconSize / 2f
        val cx = w - mx - r
        val cy = h - bottom / 2f
        iconPaint.color = Theme.footIcon
        iconPaint.style = if (translating) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        c.drawCircle(cx, cy, r, iconPaint)
        iconPaint.style = Paint.Style.STROKE
        if (translating) iconPaint.color = Theme.bg
        oval.set(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r)
        c.drawOval(oval, iconPaint)
        c.drawLine(cx - r, cy, cx + r, cy, iconPaint)

        // Loupe (recherche dans le livre), à gauche du pourcentage.
        iconPaint.color = Theme.footIcon
        val lr = r * 0.66f
        val lx = mx + lr + iconPaint.strokeWidth / 2f
        val ly = cy - (r - lr) * 0.6f
        c.drawCircle(lx, ly, lr, iconPaint)
        c.drawLine(lx + lr * 0.71f, ly + lr * 0.71f, lx + lr * 0.71f + r * 0.62f, ly + lr * 0.71f + r * 0.62f, iconPaint)

        // Rafraîchissement de l'écran (liseuse) : flèche circulaire, ouverte en haut à droite.
        if (Theme.eink) {
            val rr = r * 0.78f
            oval.set(refreshX - rr, cy - rr, refreshX + rr, cy + rr)
            c.drawArc(oval, -20f, 300f, false, iconPaint)
            // Pointe au bout de l'arc (à -80°), dans le sens de rotation.
            val ax = refreshX + rr * 0.174f
            val ay = cy - rr * 0.985f
            arrow.rewind()
            arrow.moveTo(ax - rr * 0.10f, ay - rr * 0.62f)
            arrow.lineTo(ax + rr * 0.48f, ay + rr * 0.06f)
            arrow.lineTo(ax - rr * 0.34f, ay + rr * 0.52f)
            c.drawPath(arrow, iconPaint)
        }
    }

    fun draw(c: Canvas, p: Page, epub: Epub?) {
        c.drawColor(Theme.bg)
        for (s in p.slices) {
            val l = s.layout
            if (l == null) {
                drawImage(c, s, epub ?: continue)
                continue
            }
            val t = l.getLineTop(s.a)
            // Marge de découpe haute : l'interligne vide de la ligne précédente, pour ne pas
            // rogner les accents des capitales sur la première ligne de la page.
            var slack = 0
            if (s.a > 0) {
                val fm = l.paint.fontMetricsInt
                slack = maxOf(0, l.getLineBottom(s.a - 1) - l.getLineTop(s.a - 1) - (fm.descent - fm.ascent))
            }
            c.save()
            c.translate(mx.toFloat(), (top + s.y - t).toFloat())
            c.clipRect(0, t - slack, cw, l.getLineBottom(s.b - 1))
            l.draw(c)
            c.restore()
        }
    }

    private fun drawImage(c: Canvas, s: Slice, epub: Epub) {
        val b = s.block
        try {
            val o = BitmapFactory.Options()
            var sample = 1
            while (b.imgW / (sample * 2) >= s.a) sample *= 2
            o.inSampleSize = sample
            val bmp = epub.open(b.img!!)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return
            val x = mx + (cw - s.a) / 2
            c.drawBitmap(bmp, null, Rect(x, top + s.y, x + s.a, top + s.y + s.b), imgPaint)
            bmp.recycle()
        } catch (e: Throwable) {
        }
    }

    /**
     * Banc d'essai pour le rapport de diagnostic (jamais appelé en usage normal) : compose les
     * paragraphes de [c] avec 1 à 4 fils, puis sans coupure optimale, et trace une page.
     * Le corps change un peu à chaque passe pour que le cache de mots du système ne serve pas.
     */
    fun bench(c: Chapter, page: Page?, epub: Epub?): String {
        val blocks = c.blocks
        var seq = 0
        fun run(threads: Int, strategy: Int): Long {
            val base = TextPaint(bodyPaint(1f, false)).apply { textSize = textSize * (1f + (++seq) * 0.0004f) }
            val next = java.util.concurrent.atomic.AtomicInteger(0)
            val t0 = System.nanoTime()
            val work = Runnable {
                while (true) {
                    val i = next.getAndIncrement()
                    if (i >= blocks.size) break
                    val b = blocks[i]
                    if (b.kind != Block.PARA) continue
                    val t = b.text
                    StaticLayout.Builder.obtain(t, 0, t.length, base, cw).setIncludePad(false).setLineSpacing(0f, mult)
                        .setBreakStrategy(strategy).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                        .setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD).build()
                }
            }
            val hs = ArrayList<Thread>()
            for (k in 1 until threads) hs.add(Thread(work).also { it.start() })
            work.run()
            for (h in hs) h.join()
            return (System.nanoTime() - t0) / 1000
        }
        fun med(f: () -> Long): Long = LongArray(5) { f() }.also { it.sort() }[2]
        val sb = StringBuilder("banc d'essai : ${blocks.size} blocs, ${c.length} caractères\n")
        run(1, Layout.BREAK_STRATEGY_HIGH_QUALITY)
        for (th in 1..4) sb.append("  composition, $th fil(s) : ${med { run(th, Layout.BREAK_STRATEGY_HIGH_QUALITY) }} us\n")
        sb.append("  composition, 1 fil, coupure simple : ${med { run(1, Layout.BREAK_STRATEGY_SIMPLE) }} us\n")
        if (page != null) for (cfg in arrayOf(android.graphics.Bitmap.Config.ARGB_8888, android.graphics.Bitmap.Config.RGB_565)) {
            val bm = android.graphics.Bitmap.createBitmap(w, h, cfg)
            val cv = Canvas(bm)
            draw(cv, page, epub)
            sb.append("  tracé d'une page en $cfg : ${med { val t0 = System.nanoTime(); draw(cv, page, epub); (System.nanoTime() - t0) / 1000 }} us\n")
            bm.recycle()
        }
        return sb.toString()
    }

    companion object {
        // Deux fils en plus de celui qui appelle : au-delà, les verrous globaux du moteur de texte
        // (cache de mots, cache de glyphes, allocateur) annulent le gain — mesuré, voir Perf.
        private val WORKERS = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(0, 2)
        private val POOL = java.util.concurrent.Executors.newFixedThreadPool(maxOf(1, WORKERS))
    }
}
