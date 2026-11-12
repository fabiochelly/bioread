package app.bioread

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Navigation dans le livre, ouverte en touchant le pied de page : titre, position,
 * curseur sur tout le livre, chapitre précédent / suivant et table des matières.
 */
class NavView(private val host: MainActivity, private val reader: ReaderView) : FrameLayout(host) {
    private val d = resources.displayMetrics.density
    private val panel = LinearLayout(host)
    private var chapter: TextView? = null
    private var where: TextView? = null
    private var seek: PageSlider? = null
    private var dragging = false

    init {
        setOnClickListener { host.hideNav() }
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Theme.bg)
        panel.isClickable = true
        val wrap = LinearLayout(host)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.addView(View(host).apply { setBackgroundColor(Theme.ui) }, LinearLayout.LayoutParams(-1, dp(2)))
        wrap.addView(panel, LinearLayout.LayoutParams(-1, -2))
        addView(wrap, LayoutParams(-1, -2, Gravity.BOTTOM))
        showPosition()
        reader.onPosition = { refresh() }
    }

    fun close() {
        reader.onPosition = null
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun label(text: String, bold: Boolean = false, size: Float = 15f, lines: Int = 1) = TextView(host).apply {
        this.text = text
        typeface = if (bold) host.fonts.uiBold else host.fonts.ui
        textSize = size
        setTextColor(Theme.ui)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun button(text: String, onClick: () -> Unit) = label(text).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(11), dp(10), dp(11))
        background = GradientDrawable().apply {
            setColor(Theme.bg)
            setStroke(dp(1), Theme.ui)
            cornerRadius = 6 * d
        }
        setOnClickListener { onClick() }
    }

    private fun positionText(page: Int) = "Page $page sur ${reader.totalPages()}"

    private fun showPosition() {
        panel.removeAllViews()
        panel.addView(label(reader.title, true, 17f, 2).apply { setPadding(dp(20), dp(16), dp(20), 0) })
        chapter = label("", false, 14f).apply { setPadding(dp(20), dp(4), dp(20), 0) }
        panel.addView(chapter)
        where = label("", true, 14f).apply { setPadding(dp(20), dp(12), dp(20), 0) }
        panel.addView(where)

        val sb = PageSlider(host)
        // Pendant le glissement on n'affiche que la page visée : la page n'est composée qu'au relâchement.
        sb.onChange = { p, fromUser -> if (fromUser) where?.text = positionText(p + 1) }
        sb.onStart = { dragging = true }
        sb.onStop = {
            dragging = false
            reader.seekTo(sb.progress + 1)
        }
        seek = sb
        panel.addView(sb, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(host)
        row.setPadding(dp(20), dp(4), dp(20), dp(22))
        fun add(v: View, first: Boolean) =
            row.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (!first) leftMargin = dp(8) })
        // Chapitre précédent, sommaire, chapitre suivant : des icônes plutôt que des mots.
        fun icon(kind: Int, what: String, onClick: () -> Unit) = FrameLayout(host).apply {
            // Contour à la couleur de l'icône (noir sur liseuse).
            val c = Theme.tint(kind)
            background = GradientDrawable().apply { setColor(Theme.bg); setStroke(if (c == Theme.ui) dp(1) else dp(2) * 3 / 4, c); cornerRadius = 6 * d }
            contentDescription = what
            addView(IconButton(host, kind, 34, 26), LayoutParams(-2, -2, Gravity.CENTER))
            minimumHeight = dp(48)
            setOnClickListener { onClick() }
        }
        add(icon(IconButton.PREV_CHAPTER, "Chapitre précédent") { reader.chapterStep(-1) }, true)
        add(icon(IconButton.CONTENTS, "Sommaire") { showToc() }, false)
        add(icon(IconButton.NEXT_CHAPTER, "Chapitre suivant") { reader.chapterStep(1) }, false)
        panel.addView(row)
        refresh()
    }

    /** Remet à jour les libellés après un déplacement (ou quand le décompte des pages progresse). */
    private fun refresh() {
        val sb = seek ?: return
        if (dragging) return
        val total = reader.totalPages()
        val page = reader.pageNumber()
        chapter?.text = reader.chapterTitle()
        where?.text = "${positionText(page)}  ·  ${reader.percent()} %"
        sb.max = maxOf(1, total - 1)
        sb.progress = page - 1
    }

    private fun showToc() {
        val toc = reader.epub?.toc ?: return
        if (toc.isEmpty()) {
            host.toast("Ce livre n'a pas de sommaire")
            return
        }
        panel.removeAllViews()
        chapter = null
        where = null
        seek = null
        val head = LinearLayout(host).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
            addView(label("Sommaire", true, 17f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(IconButton(host, IconButton.BACK) { showPosition() })
        }
        panel.addView(head)
        val current = reader.tocIndex()
        val list = ListView(host)
        list.divider = null
        list.adapter = object : BaseAdapter() {
            override fun getCount() = toc.size
            override fun getItem(i: Int) = toc[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, parent: ViewGroup): View {
                val tv = (v as? TextView) ?: label("")
                val it = toc[i]
                // Le chapitre en cours est repéré par une puce et du gras.
                tv.text = if (i == current) "●  ${it.title}" else it.title
                tv.typeface = if (i == current) host.fonts.uiBold else host.fonts.ui
                tv.setPadding(dp(20 + 16 * minOf(it.depth, 4)), dp(12), dp(20), dp(12))
                return tv
            }
        }
        list.setOnItemClickListener { _, _, i, _ ->
            host.hideNav()
            reader.goTo(toc[i].spine, toc[i].anchor)
        }
        if (current > 2) list.setSelection(current - 2)
        panel.addView(list, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.6f).toInt()))
    }
}
