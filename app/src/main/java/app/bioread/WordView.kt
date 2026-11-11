package app.bioread

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Feuille ouverte par un appui long sur un mot : le mot (souligné sur la page), sa traduction
 * dans la langue choisie pour le livre, et deux actions — traduire la phrase entière à la place,
 * chercher le mot dans le livre. La feuille se place en bas, ou en haut quand le mot est dans le
 * bas de la page, pour ne pas le recouvrir. Fermeture : croix, toucher hors de la feuille, Retour.
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
    private val toggle: TextView
    private var whole = false
    private var asked = 0
    private var closed = false

    init {
        setOnClickListener { host.hideSheet() }
        val span = Words.sentence(text, hit.start, lang)
        sentence = text.substring(span[0], span[1]).replace("­", "").replace('\n', ' ')

        // Le mot reste souligné sur la page tant que la feuille est ouverte.
        val rects = shown.ts.markRects(shown.page, hit.block.start + hit.start, hit.end - hit.start)
        if (rects.isNotEmpty()) MarkView(host, rects).let { addView(it, it.params()) }
        val low = rects.isNotEmpty() && rects[0].bottom > reader.height * 0.55f

        val side = maxOf(shown.ts.mx, dp(16))   // aligné sur la colonne de texte du livre
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

        // La phrase d'origine, montrée seulement quand c'est elle que l'on traduit.
        quote.typeface = host.fonts.italic
        quote.textSize = 16f
        quote.setTextColor(Theme.ui)
        quote.setLineSpacing(0f, 1.25f)
        quote.maxLines = 6
        quote.ellipsize = TextUtils.TruncateAt.END
        quote.setPadding(side, dp(2), side, dp(8))
        quote.visibility = GONE
        panel.addView(quote)

        result.typeface = host.fonts.regular
        result.textSize = 18f
        result.setTextColor(Theme.ui)
        result.setLineSpacing(0f, 1.25f)
        result.maxLines = 9
        result.ellipsize = TextUtils.TruncateAt.END
        result.setPadding(side, 0, side, dp(14))
        panel.addView(result)

        val same = lang == Prefs.target
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
                result.text = res?.getOrNull(0)?.toString()?.trim()?.ifEmpty { null } ?: "Traduction indisponible pour l'instant."
            }
        }
    }
}
