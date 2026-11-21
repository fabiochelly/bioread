package app.bioread

import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Comparaison des polices : un même paragraphe, composé dans chacune des polices comme sur une
 * page du livre (corps, interligne, alinéa, graisse, largeur, césure, compositeur de l'app),
 * toutes sur un seul écran. Le paragraphe est celui de la page ouverte (sinon un extrait de
 * Proust). S'il ne tient pas au corps de lecture, il est d'abord raccourci (jusqu'à deux lignes
 * environ) ; le corps n'est réduit qu'en dernier recours. Le blanc restant est réparti entre
 * les polices. Un toucher sur une police la choisit ; la croix ou le retour referme.
 */
class FontCompare(
    private val host: MainActivity,
    sample: CharSequence?,
    lang: String,
    private val topInset: Int,
    private val onPick: (Fonts.Family) -> Unit,
) : View(host) {
    private val d = resources.displayMetrics.density
    private val fonts = host.fonts
    // Planches temporaires (variantes de Bioreader à l'essai) : un toucher sur le titre passe de
    // l'une à l'autre. 0 : les polices ordinaires ; 1 : romain ; 2 : italique (paragraphe entier).
    private var sheet = if (fonts.families.any { it.sheet > 0 }) 1 else 0
    private var families = pick()
    private fun pick() = fonts.families.filter {
        if (sheet == 0) it.sheet == 0 else it.id == "bookerly" || it.id == "newsreader" || it.id == "bioreader" || it.sheet == sheet || it.sheet == 3
    }
    private val text: CharSequence = sample ?: PROUST
    private val locale: Locale = if (sample == null) Locale.FRENCH else if (lang.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(lang)
    private val hyphen by lazy { Hyphen.of(if (sample == null) "fr" else lang.ifEmpty { Locale.getDefault().language }) }

    private val titlePaint = Theme.textPaint().apply { typeface = fonts.uiBold; textSize = 17 * d; color = Theme.ui }
    private val hintPaint = Theme.textPaint().apply { typeface = fonts.ui; textSize = 12.5f * d; color = Theme.ui }
    private val namePaint = Theme.textPaint().apply { typeface = fonts.ui; textSize = 12.5f * d; color = Theme.ui }
    private val nameBold = Theme.textPaint().apply { typeface = fonts.uiBold; textSize = 12.5f * d; color = Theme.ui }
    private val crossPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.ui; strokeWidth = 1.6f * d; strokeCap = android.graphics.Paint.Cap.ROUND
    }

    private class Entry(val family: Fonts.Family, val layout: Layout, var top: Float = 0f, var bottom: Float = 0f)
    @Volatile private var entries: List<Entry>? = null
    private var shownSize = 0f      // corps retenu (px)
    private var computedFor = 0     // largeur × hauteur de la dernière composition
    private val work = Executors.newSingleThreadExecutor()

    private val mx get() = (Prefs.margin * d).coerceAtLeast(14 * d)
    private val headTop get() = topInset + 14 * d
    private val headH = 64 * d       // titre et consigne
    private val nameH get() = namePaint.fontSpacing + 3 * d
    private val minGap = 12 * d

    init {
        isClickable = true
        setBackgroundColor(Theme.bg)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w == 0 || h == 0 || w * 31 + h == computedFor) return
        computedFor = w * 31 + h
        compose(w, h)
    }

    private fun compose(w: Int, h: Int) {
        entries = null
        val cw = (w - 2 * mx).toInt()
        val avail = h - headTop - headH - host.insetBottom - 16 * d
        val fams = families
        val sh = sheet
        // Composition hors du thread principal : une dizaine d'essais pour chacune des polices.
        work.execute {
            val r = fit(cw, avail, fams, sh)
            post { if (fams === families) { place(r, avail); invalidate() } }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        work.shutdownNow()
    }

    // ---------------------------------------------------------------- composition

    /** Italique et gras du paragraphe, dans la police comparée (et non celle du livre). */
    private class FaceSpan(private val t: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(p: TextPaint) { p.typeface = t }
        override fun updateMeasureState(p: TextPaint) { p.typeface = t }
    }

    /** Les [n] premiers caractères, coupés à une fin de mot, avec leurs italiques et gras dans [faces]. */
    private fun styled(faces: Array<Typeface>, n: Int, sh: Int): CharSequence {
        val end = if (n >= text.length) text.length else {
            var e = n
            while (e > 0 && !text[e - 1].isWhitespace()) e--
            while (e > 0 && (text[e - 1].isWhitespace() || text[e - 1] in ".,;:—–-(«")) e--
            if (e <= 0) n else e
        }
        // Le dernier tiers de l'extrait (à partir d'un début de mot) en italique : le romain et
        // l'italique de chaque police se comparent dans le même bloc.
        var it = if (sh == 2) 0 else end * 2 / 3
        while (it > 0 && !text[it - 1].isWhitespace()) it--
        val sb = SpannableStringBuilder(text.subSequence(0, end).toString())
        if (end < text.length) sb.append('…')
        if (text is Spanned) {
            for (sp in text.getSpans(0, it, FontSpan::class.java)) {
                val a = text.getSpanStart(sp).coerceIn(0, it)
                val b = text.getSpanEnd(sp).coerceIn(0, it)
                if (b > a) sb.setSpan(FaceSpan(faces[(if (sp.italic) 1 else 0) + (if (sp.bold) 2 else 0)]), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (it < sb.length) sb.setSpan(FaceSpan(faces[1]), it, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun layout(f: Fonts.Family, faces: Array<Typeface>, size: Float, n: Int, cw: Int, sh: Int): Layout {
        val p = Theme.textPaint().apply {
            typeface = faces[0]
            textSize = size + boost(f)
            Theme.tune(this, f.hinted)
            // Trait plus épais pour les polices au dessin trop clair (voir [HEAVIER]), comme
            // le ferait le réglage « Graisse » (0,2 % du corps par cran).
            val w = Prefs.weight + (HEAVIER[f.id] ?: 0)
            if (w > 0) {
                style = android.graphics.Paint.Style.FILL_AND_STROKE
                strokeWidth = textSize * 0.002f * w
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            textLocale = locale
        }
        val t = styled(faces, n, sh)
        val mult = Prefs.lineSpacing / 100f * lineNorm(faces[0])
        val ind = (Prefs.indent / 10f * (size + boost(f))).toInt()
        KpLayout.make(t, p, cw, ind, 0, 0, mult, 0f, hyphen, locale)?.let { return it }
        return StaticLayout.Builder.obtain(t, 0, t.length, p, cw).setIncludePad(false)
            .setLineSpacing(0f, mult)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            .setIndents(intArrayOf(ind, 0), intArrayOf(0, 0))
            .build()
    }

    /**
     * Polices à petit œil (hauteur des minuscules faible) : un point de plus dans la comparaison,
     * pour les juger à taille apparente égale plutôt qu'à corps égal.
     */
    private fun boost(f: Fonts.Family) = if (f.id in BOOSTED) d else 0f

    private class Fit(val size: Float, val n: Int, val layouts: List<Layout>, val families: List<Fonts.Family>)

    private fun fit(cw: Int, avail: Float, families: List<Fonts.Family>, sh: Int): Fit {
        val all = families.map { fonts.faces(it) }
        fun build(size: Float, n: Int) = families.indices.map { layout(families[it], all[it], size, n, cw, sh) }
        fun height(ls: List<Layout>) = ls.sumOf { it.height.toDouble() }.toFloat() + families.size * nameH + (families.size - 1) * minGap
        val full = text.length
        var size = Prefs.fontSize * d
        // 1. Au corps de lecture, le paragraphe entier si possible.
        var ls = build(size, full)
        if (height(ls) <= avail) return Fit(size, full, ls, families)
        // 2. Raccourci, au plus juste (recherche par dichotomie sur la longueur).
        val perLine = (cw / (size * 0.48f)).toInt().coerceAtLeast(20)
        val minN = minOf(full, perLine * 2)
        var lo = minN
        var hi = full
        var best: List<Layout>? = null
        var bestN = minN
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val t = build(size, mid)
            if (height(t) <= avail) { best = t; bestN = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (best != null) return Fit(size, bestN, best, families)
        // 3. Même deux lignes ne tiennent pas : le corps diminue, par pas d'un demi-point.
        while (size > 8 * d) {
            size -= 0.5f * d
            ls = build(size, minN)
            if (height(ls) <= avail) return Fit(size, minN, ls, families)
        }
        return Fit(size, minN, ls, families)
    }

    private fun place(r: Fit, avail: Float) {
        val families = r.families
        shownSize = r.size
        val used = r.layouts.sumOf { it.height.toDouble() }.toFloat() + families.size * nameH
        // Blanc restant réparti entre les polices (sans dépasser trois lignes d'écart).
        val gap = if (families.size > 1) ((avail - used) / (families.size - 1)).coerceIn(minGap, 3 * r.size) else 0f
        var y = headTop + headH
        entries = families.indices.map { i ->
            val e = Entry(families[i], r.layouts[i])
            e.top = y
            y += nameH + e.layout.height
            e.bottom = y
            y += gap
            e
        }
    }

    // ---------------------------------------------------------------- dessin

    override fun onDraw(c: Canvas) {
        val x = mx
        val cw = width - 2 * mx
        val title = when (sheet) { 1 -> "Planche Bioreader : romain  ›"; 2 -> "Planche Bioreader : italique  ›"; else -> "Comparer les polices" + if (fonts.families.any { it.sheet > 0 }) "  ›" else "" }
        c.drawText(title, x, headTop + titlePaint.textSize, titlePaint)
        val pts = Math.round(shownSize / d)
        val hint = when {
            entries == null -> "Composition…"
            else -> "Corps $pts · fin en italique · touchez une police pour la choisir"
        }
        val shown = android.text.TextUtils.ellipsize(hint, hintPaint, cw - 50 * d, android.text.TextUtils.TruncateAt.END).toString()
        c.drawText(shown, x, headTop + titlePaint.textSize + 10 * d + hintPaint.textSize, hintPaint)
        // Croix de fermeture, en haut à droite, au trait fin comme les icônes de l'app.
        val r = 7 * d
        val cx = width - mx - r
        val cy = headTop + titlePaint.textSize * 0.6f
        c.drawLine(cx - r, cy - r, cx + r, cy + r, crossPaint)
        c.drawLine(cx - r, cy + r, cx + r, cy - r, crossPaint)
        c.drawRect(x, headTop + headH - 10 * d, x + cw, headTop + headH - 10 * d + maxOf(1f, d), namePaint)

        val es = entries ?: return
        for (e in es) {
            val cur = e.family === fonts.family
            val np = if (cur) nameBold else namePaint
            val heavier = HEAVIER[e.family.id]
            val name = e.family.label + (if (boost(e.family) > 0f) "  ·  corps ${pts + 1}" else "") +
                (if (heavier != null) "  ·  graisse +$heavier" else "") + (if (cur) "  ·  actuelle" else "")
            c.drawText(name, x, e.top + np.textSize, np)
            c.save()
            c.translate(x, e.top + nameH)
            e.layout.draw(c)
            c.restore()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        if (e.y < headTop + headH - 10 * d) {
            if (e.x > width - mx - 60 * d) host.hideCompare()
            else if (fonts.families.any { it.sheet > 0 }) {
                sheet = (sheet + 1) % 3
                families = pick()
                compose(width, height)
                invalidate()
            }
            return true
        }
        val es = entries ?: return true
        // La police dont le bloc (nom et paragraphe) est le plus proche du doigt.
        val hit = es.minByOrNull { if (e.y < it.top) it.top - e.y else if (e.y > it.bottom) e.y - it.bottom else 0f } ?: return true
        onPick(hit.family)
        host.hideCompare()
        return true
    }

    companion object {
        private val BOOSTED = setOf("spectral", "newsreader")
        /** Crans de graisse ajoutés dans la comparaison, pour les polices au trait fin. */
        private val HEAVIER = mapOf("newsreader" to 2)

        /** Début de « Du côté de chez Swann » (domaine public), quand aucun livre n'est ouvert. */
        private const val PROUST = "Longtemps, je me suis couché de bonne heure. Parfois, à peine ma bougie éteinte, " +
            "mes yeux se fermaient si vite que je n'avais pas le temps de me dire : « Je m'endors. » Et, une " +
            "demi-heure après, la pensée qu'il était temps de chercher le sommeil m'éveillait ; je voulais poser " +
            "le volume que je croyais avoir encore dans les mains et souffler ma lumière ; je n'avais pas cessé " +
            "en dormant de faire des réflexions sur ce que je venais de lire, mais ces réflexions avaient pris un " +
            "tour un peu particulier ; il me semblait que j'étais moi-même ce dont parlait l'ouvrage : une église, " +
            "un quatuor, la rivalité de François Ier et de Charles Quint."
    }
}
