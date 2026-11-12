package app.bioread

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * Feuille ouverte par un appui long sur un mot : le mot (souligné sur la page), sa définition
 * dans le dictionnaire de la langue du livre ([Dict], hors-ligne), en dessous sa traduction dans
 * la langue choisie — une de chaque à l'ouverture, un lien par zone déroule tous les sens et
 * toutes les traductions du dictionnaire —, et deux actions — traduire la phrase entière à la place, chercher le mot
 * dans le livre. La feuille se place en bas, ou en haut quand le mot est dans le bas de la page,
 * pour ne pas le recouvrir ; déroulé, son contenu défile au lieu de couvrir la page. Fermeture : croix, toucher hors de la feuille, Retour.
 */
class WordView(
    private val host: MainActivity,
    reader: ReaderView,
    shown: Shown,
    hit: WordHit,
    private val lang: String,
) : FrameLayout(host) {
    private val d = resources.displayMetrics.density
    private val text = hit.block.text.toString()
    private val word = text.substring(hit.start, hit.end).replace("­", "")
    private val sentence: String
    private val quote = TextView(host)
    private val result = TextView(host)
    private val defs = TextView(host)
    private val defLink = TextView(host)
    private val defRow = LinearLayout(host)
    private val rule = View(host)
    private var trLabel: TextView? = null
    private val trList = TextView(host)
    private val trLink = TextView(host)
    private val backRow = LinearLayout(host)
    private val body: Body
    private val found: Dict.Result?
    private val defined: Boolean
    private var senses = 0                 // nombre de sens, toutes catégories confondues
    private val translated = ArrayList<Pair<Dict.Section, Dict.Group>>()   // catégories qui ont des traductions
    private var mode = COMPACT
    private var compactMax = 0
    private var openMax = 0
    private var sideOf = 0
    private val toggle: TextView
    private var whole = false
    private var asked = 0
    private var closed = false

    init {
        // Mode sélection : tant que la feuille est ouverte, toucher un autre mot de la page le choisit.
        reader.pickOrClose(this)
        val span = Words.sentence(text, hit.start, lang)
        sentence = text.substring(span[0], span[1]).replace("­", "").replace('\n', ' ')

        // Le mot reste souligné sur la page tant que la feuille est ouverte.
        val rects = shown.ts.markRects(shown.page, hit.block.start + hit.start, hit.end - hit.start)
        if (rects.isNotEmpty()) MarkView(host, rects).let { addView(it, it.params()) }
        val low = rects.isNotEmpty() && rects[0].bottom > reader.height * 0.55f

        val side = maxOf(shown.ts.mx, dp(16))   // aligné sur la colonne de texte du livre
        sideOf = side
        val panel = LinearLayout(host)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Theme.bg)
        panel.isClickable = true
        if (low) panel.setPadding(0, reader.topInset, 0, 0)

        val head = LinearLayout(host).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(side, dp(6), dp(8), 0)
            addView(TextView(host).apply {
                text = word
                typeface = host.fonts.bold
                textSize = 22f
                setTextColor(Theme.ui)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(IconButton(host, IconButton.CLOSE) { host.hideSheet() }.apply { contentDescription = "Fermer" })
        }
        panel.addView(head)

        // Feuille compacte : une définition, une traduction. Chaque zone a son lien, qui la déroule
        // en entier à la place du reste ; le contenu défile alors, et un lien fixe permet de replier.
        val same = lang == Prefs.target
        // Apostrophe d'une lettre avalée devant le mot (’ouse), et non guillemet fermant un autre mot.
        val clipped = hit.start > 0 && text[hit.start - 1] in "'’" && (hit.start < 2 || !Character.isLetter(text[hit.start - 2]))
        found = Perf.time("dict.lookup") { Dict.lookup(word, lang, span[0] == hit.start, clipped, if (same) "" else Prefs.target) }
        defined = found != null && found.sections.isNotEmpty()
        for (sec in found?.sections ?: emptyList()) for (g in sec.groups) {
            senses += g.count
            if (g.trCount > 0) translated.add(sec to g)
        }
        // Repliée, la feuille s'arrête avant le mot souligné ; déroulée, elle peut prendre plus de place.
        val room = if (rects.isEmpty()) reader.height / 2 else if (low) rects.minOf { it.top }.toInt() else reader.height - rects.maxOf { it.bottom }.toInt()
        compactMax = maxOf(dp(140), minOf(room - dp(14), reader.height * 6 / 10) - dp(116))   // moins l'en-tête et les boutons
        openMax = maxOf(compactMax, reader.height * 72 / 100 - dp(156))
        body = Body(compactMax)
        val column = LinearLayout(host)
        column.orientation = LinearLayout.VERTICAL
        val credit = found?.credit ?: ""
        if (defined) {
            prose(defs)
            column.addView(defs)
            // Lien pour dérouler, et à droite l'attribution demandée par la licence du Wiktionnaire.
            defRow.gravity = Gravity.CENTER_VERTICAL
            defRow.setPadding(side, 0, side, 0)
            link(defLink, "Tous les sens ($senses)") { show(SENSES) }
            defLink.visibility = if (senses > 1) VISIBLE else INVISIBLE
            defRow.addView(defLink, LinearLayout.LayoutParams(-2, dp(40)))
            defRow.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
            defRow.addView(small(credit))
            column.addView(defRow)
            if (!same) {
                rule.setBackgroundColor(Theme.ui)
                column.addView(rule, LinearLayout.LayoutParams(-1, maxOf(1, dp(1))).apply { leftMargin = side; rightMargin = side })
                trLabel = small("Traduction").apply {
                    typeface = host.fonts.uiBold
                    setPadding(side, dp(8), side, dp(2))
                }
                column.addView(trLabel)
            }
        }

        // La phrase d'origine, montrée seulement quand c'est elle que l'on traduit.
        quote.typeface = host.fonts.italic
        quote.textSize = 16f
        quote.setTextColor(Theme.ui)
        quote.setLineSpacing(0f, 1.25f)
        quote.maxLines = 6
        quote.ellipsize = TextUtils.TruncateAt.END
        quote.setPadding(side, dp(2), side, dp(8))
        quote.visibility = GONE
        column.addView(quote)

        result.typeface = host.fonts.regular
        result.textSize = 18f
        result.setTextColor(Theme.ui)
        result.setLineSpacing(0f, 1.25f)
        result.maxLines = 9
        result.ellipsize = TextUtils.TruncateAt.END
        result.setPadding(side, 0, side, dp(if (translated.isEmpty()) 14 else 2))
        // Mot et livre dans la même langue : la définition suffit, il n'y a rien à traduire.
        if (!(same && defined)) column.addView(result)

        // Traductions du dictionnaire, par sens : repliées derrière leur lien.
        if (translated.isNotEmpty()) {
            prose(trList)
            trList.setPadding(side, dp(6), side, dp(10))
            column.addView(trList)
            link(trLink, "Autres traductions (${translated.sumOf { it.second.trCount }})") { show(TRANSLATIONS) }
            trLink.setPadding(side, 0, side, 0)
            column.addView(trLink, LinearLayout.LayoutParams(-2, dp(40)))
        }

        if (!defined) {
            column.addView(small(
                if (found != null) "« $word » n'est pas dans le dictionnaire ${Dict.name(lang)}."
                else if (Dict.outdated(lang)) "Dictionnaire ${Dict.name(lang)} d'un ancien format : déposez le nouveau ${Dict.fileName(lang)} dans Download."
                else "Dictionnaire ${Dict.name(lang)} non installé (${Dict.fileName(lang)} à déposer dans Download)."
            ).apply { setPadding(side, 0, side, dp(14)) })
        }
        body.addView(column)
        panel.addView(body, LinearLayout.LayoutParams(-1, -2))

        // Zone déroulée : son lien pour replier reste sous la liste, hors du défilement.
        backRow.gravity = Gravity.CENTER_VERTICAL
        backRow.setPadding(side, 0, side, 0)
        val back = TextView(host)
        link(back, "Replier") { show(COMPACT) }
        backRow.addView(back, LinearLayout.LayoutParams(-2, dp(40)))
        backRow.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        backRow.addView(small(credit))
        panel.addView(backRow)
        show(COMPACT)

        val row = LinearLayout(host)
        row.setPadding(dp(12), 0, dp(12), dp(14))
        toggle = button("Traduire la phrase") { setWhole(!whole) }
        if (!same) row.addView(toggle, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        row.addView(button("Rechercher") {
            host.hideSheet()
            host.showSearch(word)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(row)

        val wrap = LinearLayout(host)
        wrap.orientation = LinearLayout.VERTICAL
        val line = View(host).apply { setBackgroundColor(Theme.ui) }
        if (low) {
            wrap.addView(panel, LinearLayout.LayoutParams(-1, -2))
            wrap.addView(line, LinearLayout.LayoutParams(-1, dp(2)))
        } else {
            wrap.addView(line, LinearLayout.LayoutParams(-1, dp(2)))
            wrap.addView(panel, LinearLayout.LayoutParams(-1, -2))
        }
        addView(wrap, LayoutParams(-1, -2, if (low) Gravity.TOP else Gravity.BOTTOM))

        if (same) result.text = "Ce livre est déjà en ${Translator.label(lang).lowercase()} : il n'y a rien à traduire."
        else translate()
    }

    private fun dp(v: Int) = (v * d).toInt()

    /** Ligne discrète : catégorie, attribution, dictionnaire absent. Jamais de gris clair (liseuse). */
    private fun small(s: String) = TextView(host).apply {
        text = s
        typeface = host.fonts.ui
        textSize = 13f
        setTextColor(Theme.ui)
    }

    /** Texte courant de la feuille : définitions, traductions par sens. */
    private fun prose(v: TextView) {
        v.typeface = host.fonts.regular
        v.textSize = 17f
        v.setTextColor(Theme.ui)
        v.setLineSpacing(0f, 1.22f)
        v.setPadding(sideOf, 0, sideOf, 0)
    }

    /** Lien souligné qui déroule ou replie une zone. */
    private fun link(v: TextView, label: String, onClick: () -> Unit) {
        v.text = label
        v.typeface = host.fonts.uiBold
        v.textSize = 14f
        v.setTextColor(Theme.ui)
        v.paintFlags = v.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        v.gravity = Gravity.CENTER_VERTICAL
        v.setOnClickListener { onClick() }
    }

    private fun SpannableStringBuilder.add(s: String, vararg spans: Any) {
        val a = length
        append(s)
        for (x in spans) setSpan(x, a, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun SpannableStringBuilder.trimEnd() {
        while (isNotEmpty() && this[length - 1] == '\n') delete(length - 1, length)
    }

    /**
     * Les trois états de la feuille. [COMPACT] : le premier sens et la traduction en contexte,
     * chacun avec son lien. [SENSES] : tous les articles et tous leurs sens, seuls.
     * [TRANSLATIONS] : la traduction en contexte puis toutes celles du dictionnaire, seules.
     */
    private fun show(m: Int) {
        mode = m
        fun View.on(b: Boolean) { visibility = if (b) VISIBLE else GONE }
        val single = !whole   // la phrase traduite prend la place de tout le reste
        if (defined) defs.text = definitions(m == SENSES)
        if (m == TRANSLATIONS && trList.text.isEmpty()) trList.text = translations()
        defs.on(defined && m != TRANSLATIONS && single)
        defRow.on(defined && m == COMPACT && single)
        rule.on(m == COMPACT && single)
        trLabel?.on(m != SENSES)
        result.on(m != SENSES)
        // Suivie du lien ou de la liste des traductions, la traduction en contexte s'y accole.
        result.setPadding(sideOf, 0, sideOf, dp(if (single && translated.isNotEmpty()) 2 else 14))
        trList.on(m == TRANSLATIONS)
        trLink.on(m == COMPACT && single)
        backRow.on(m != COMPACT)
        body.max = if (m == COMPACT) compactMax else openMax
        body.scrollTo(0, 0)
        body.requestLayout()
    }

    /**
     * Compose les articles du dictionnaire : pour chacun, ce qui le relie au mot du livre
     * (« Pluriel de cheval. »), le lemme s'il diffère et sa prononciation, puis par catégorie
     * grammaticale ses sens numérotés. Replié ([all] faux) : le premier sens du premier article.
     */
    private fun definitions(all: Boolean): CharSequence {
        val sb = SpannableStringBuilder()
        val res = found ?: return sb
        for (sec in if (all) res.sections else res.sections.take(1)) {
            if (sb.isNotEmpty()) sb.add("\n", RelativeSizeSpan(0.45f))
            if (sec.note.isNotEmpty()) sb.add(sec.note + "\n", FontSpan(host.fonts, false, true), RelativeSizeSpan(0.94f))
            val other = !sec.entry.word.replace('’', '\'').equals(word.replace('’', '\''), false)
            val ipa = sec.entry.ipa
            if (other) sb.add(sec.entry.word, FontSpan(host.fonts, true, false), RelativeSizeSpan(1.06f))
            if (ipa.isNotEmpty()) sb.add(if (other) "  $ipa" else ipa, TypefaceSpan(host.fonts.ui), RelativeSizeSpan(0.88f))
            if (other || ipa.isNotEmpty()) sb.append("\n")
            for ((k, g) in (if (all) sec.groups else sec.groups.take(1)).withIndex()) {
                if (k > 0) sb.add("\n", RelativeSizeSpan(0.3f))
                sb.add(g.label + "\n", TypefaceSpan(host.fonts.uiBold), RelativeSizeSpan(0.78f))
                if (!all || g.count == 1) sb.append(g.first).append("\n")
                else for ((i, gloss) in g.glosses.withIndex()) sb.add("${i + 1}. $gloss\n", LeadingMarginSpan.Standard(0, dp(18)))
            }
        }
        sb.trimEnd()
        return sb
    }

    /**
     * Traductions du dictionnaire vers la langue choisie, regroupées par sens : libellé du sens
     * en italique, puis ses équivalents.
     */
    private fun translations(): CharSequence {
        val sb = SpannableStringBuilder()
        for ((sec, g) in translated) {
            // Plusieurs articles ou catégories : chacun annonce le sien.
            if (translated.size > 1) {
                if (sb.isNotEmpty()) sb.add("\n", RelativeSizeSpan(0.3f))
                sb.add("${sec.entry.word} · ${g.label}\n", TypefaceSpan(host.fonts.uiBold), RelativeSizeSpan(0.78f))
            }
            for (t in g.tr) {
                val a = sb.length
                if (t.sense.isNotEmpty()) {
                    sb.add(t.sense, FontSpan(host.fonts, false, true), RelativeSizeSpan(0.94f))
                    sb.append(" : ")
                }
                sb.append(t.words).append("\n")
                sb.setSpan(LeadingMarginSpan.Standard(0, dp(18)), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        sb.trimEnd()
        return sb
    }

    /**
     * Colonne qui défile, bornée en hauteur. Sur liseuse, pas de défilement continu (l'encre le
     * rend mal) : un glissement fait sauter d'une hauteur de fenêtre, d'un coup. Un trait à
     * droite montre où l'on est quand tout ne tient pas.
     */
    private inner class Body(var max: Int) : ScrollView(host) {
        private val bar = Paint().apply { color = Theme.ui }
        private val slop = ViewConfiguration.get(host).scaledTouchSlop
        private var downY = 0f

        init {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            isFillViewport = false
        }

        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (!Theme.eink) return super.onInterceptTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_DOWN) downY = e.y
            return e.actionMasked == MotionEvent.ACTION_MOVE && abs(e.y - downY) > slop
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!Theme.eink) return super.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP && abs(e.y - downY) > slop) {
                val step = height - dp(40)   // deux lignes restent en vue d'un saut à l'autre
                scrollTo(0, (scrollY + if (e.y < downY) step else -step).coerceIn(0, maxOf(0, range() - height)))
            }
            return true
        }

        private fun range() = getChildAt(0)?.height ?: 0

        override fun onDrawForeground(c: Canvas) {
            super.onDrawForeground(c)
            val all = range()
            if (all <= height) return
            val w = dp(3).toFloat()
            val top = scrollY + height.toFloat() * scrollY / all
            c.drawRect(width - w, top, width.toFloat(), top + height.toFloat() * height / all, bar)
        }
    }

    override fun onDetachedFromWindow() {
        closed = true
        super.onDetachedFromWindow()
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(host).apply {
        text = label
        typeface = host.fonts.uiBold
        textSize = 15f
        setTextColor(Theme.ui)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(8), 0)
        background = GradientDrawable().apply {
            setColor(Theme.bg)
            setStroke(maxOf(1, dp(1)), Theme.ui)
            cornerRadius = 6 * d
        }
        setOnClickListener { onClick() }
    }

    private fun setWhole(on: Boolean) {
        whole = on
        toggle.text = if (on) "Traduire le mot seul" else "Traduire la phrase"
        quote.text = sentence
        quote.visibility = if (on) VISIBLE else GONE
        // La phrase et sa traduction prennent la place de la définition, qui revient avec le mot seul.
        show(COMPACT)
        translate()
    }

    /** Demande la traduction du mot (la phrase sert de contexte) ou de la phrase entière. */
    private fun translate() {
        val n = ++asked
        result.text = "…"
        val what = if (whole) sentence else word
        Translator.translate(listOf(what), if (whole) "" else sentence, lang, Prefs.target) { res ->
            // Une réponse tardive (autre demande entre-temps, feuille fermée) est ignorée.
            if (n == asked && !closed) {
                // L'erreur détaillée est déjà affichée par le traducteur ; la feuille reste utilisable.
                // Moteur indisponible (hors-ligne, modèle absent) : la première traduction du dictionnaire en tient lieu.
                result.text = res?.getOrNull(0)?.toString()?.trim()?.ifEmpty { null }
                    ?: translated.firstOrNull()?.takeIf { !whole }?.second?.tr?.get(0)?.words
                    ?: "Traduction indisponible pour l'instant."
            }
        }
    }

    private companion object {
        const val COMPACT = 0
        const val SENSES = 1
        const val TRANSLATIONS = 2
    }
}
