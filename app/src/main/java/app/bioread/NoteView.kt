package app.bioread

import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Mise en page d'une note pour sa fenêtre : même police, même corps, mêmes marges que le livre
 * (c'est le compositeur du livre, sur une « page » de la hauteur de la fenêtre). Une note courte
 * tient sur une page de sa propre hauteur ; une longue est découpée en pages de [height] pixels.
 */
class NoteLayout(val ts: Typesetter, val pages: ArrayList<Page>, val height: Int) {
    companion object {
        /** À appeler hors du thread principal. [max] : hauteur de texte à ne pas dépasser. */
        fun make(fonts: Fonts, note: Note, w: Int, max: Int, density: Float, lang: String, epub: Epub): NoteLayout {
            var at = 0
            for (b in note.blocks) { b.start = at; at += b.length }
            val ch = Chapter(note.blocks, HashMap(), at)
            // D'abord sans limite de hauteur, pour mesurer.
            val probe = Typesetter(fonts, w, 1 shl 20, 0, density, lang)
            val whole = probe.layoutChapter(0, ch, epub)
            fun used(p: Page): Int {
                val s = p.slices.lastOrNull() ?: return 0
                val l = s.layout
                return s.y + if (l == null) s.b else l.getLineBottom(s.b - 1) - l.getLineTop(s.a)
            }
            val h = whole.pages.lastOrNull()?.let { used(it) } ?: 0
            if (whole.pages.size == 1 && h <= max) return NoteLayout(probe, whole.pages, h)
            val t = Typesetter(fonts, w, max + probe.top + probe.bottom, 0, density, lang)
            val pages = t.layoutChapter(0, ch, epub).pages
            // Hauteur de la page la plus remplie : pas de blanc inutile sous la dernière ligne.
            return NoteLayout(t, pages, minOf(max, pages.maxOf { used(it) }))
        }
    }
}

/**
 * Fenêtre de note, ouverte en touchant un appel de note : le texte de la note au bas de l'écran,
 * sans quitter la page. Une note longue se lit page par page (boutons, ou toucher à gauche et à
 * droite du texte). Fermeture par la croix, par un toucher hors de la fenêtre ou par Retour.
 * [go] : aller à la note dans le livre (null si elle n'y a pas de place).
 */
class NoteView(
    private val host: MainActivity,
    private val reader: ReaderView,
    note: Note,
    private val laid: NoteLayout,
    go: (() -> Unit)?,
) : FrameLayout(host) {
    private val d = resources.displayMetrics.density
    private val body = Body()
    private val count = label("", false, 14f)
    private var page = 0

    init {
        setOnClickListener { host.hideSheet() }
        val panel = LinearLayout(host)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Theme.bg)
        panel.isClickable = true

        val head = LinearLayout(host).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(laid.ts.mx, dp(6), dp(8), dp(2))
            addView(label(if (note.label.isEmpty()) "Note" else "Note ${note.label}", true, 17f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(IconButton(host, IconButton.CLOSE) { host.hideSheet() }.apply { contentDescription = "Fermer la note" })
        }
        panel.addView(head)
        panel.addView(body, LinearLayout.LayoutParams(-1, laid.height + dp(PAD_TOP) + dp(PAD_BOTTOM)))

        val many = laid.pages.size > 1
        if (many || go != null) {
            val row = LinearLayout(host)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), dp(8), dp(12), dp(14))
            if (many) {
                row.addView(boxed(IconButton.LEFT, "Page précédente de la note") { step(-1) }, LinearLayout.LayoutParams(dp(56), dp(48)))
                count.gravity = Gravity.CENTER
                row.addView(count, LinearLayout.LayoutParams(dp(64), -2))
                row.addView(boxed(IconButton.RIGHT, "Page suivante de la note") { step(1) }, LinearLayout.LayoutParams(dp(56), dp(48)))
            }
            row.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
            if (go != null) {
                row.addView(LinearLayout(host).apply {
                    gravity = Gravity.CENTER
                    background = outline()
                    setPadding(dp(14), 0, dp(6), 0)
                    addView(label("Aller à la note", true, 15f))
                    addView(IconButton(host, IconButton.FORWARD, 30, 20))
                    setOnClickListener {
                        host.hideSheet()
                        go()
                    }
                }, LinearLayout.LayoutParams(-2, dp(48)))
            }
            panel.addView(row)
        } else panel.addView(View(host), LinearLayout.LayoutParams(-1, dp(18)))

        val wrap = LinearLayout(host)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.addView(View(host).apply { setBackgroundColor(Theme.ui) }, LinearLayout.LayoutParams(-1, dp(2)))
        wrap.addView(panel, LinearLayout.LayoutParams(-1, -2))
        addView(wrap, LayoutParams(-1, -2, Gravity.BOTTOM))
        show(0)
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun label(text: String, bold: Boolean, size: Float) = TextView(host).apply {
        this.text = text
        typeface = if (bold) host.fonts.uiBold else host.fonts.ui
        textSize = size
        setTextColor(Theme.ui)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun outline() = GradientDrawable().apply {
        setColor(Theme.bg)
        setStroke(maxOf(1, dp(1)), Theme.ui)
        cornerRadius = 6 * d
    }

    private fun boxed(kind: Int, what: String, onClick: () -> Unit) = FrameLayout(host).apply {
        background = outline()
        contentDescription = what
        addView(IconButton(host, kind, 30, 22), LayoutParams(-2, -2, Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    private fun show(p: Int) {
        page = p
        count.text = "${p + 1} / ${laid.pages.size}"
        body.invalidate()
    }

    private fun step(dir: Int) {
        val p = page + dir
        if (p >= 0 && p < laid.pages.size) show(p)
    }

    /** Le texte de la note : la page courante, tracée par le compositeur du livre. */
    private inner class Body : View(host) {
        private var downX = 0f

        override fun onDraw(c: Canvas) {
            val p = laid.pages.getOrNull(page) ?: return
            c.clipRect(0, 0, width, height)
            c.translate(0f, (dp(PAD_TOP) - laid.ts.top).toFloat())
            laid.ts.draw(c, p, reader.epub)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            // Comme dans le livre : balayage, ou toucher à gauche (retour) et ailleurs (suite).
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downX = e.x
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX
                    if (Math.abs(dx) > 28 * d) step(if (dx < 0) 1 else -1)
                    else step(if (e.x < width / 3f) -1 else 1)
                }
            }
            return true
        }
    }

    companion object {
        // Marges du texte dans la fenêtre, en dp : accents des capitales en haut, jambages en bas.
        private const val PAD_TOP = 6
        private const val PAD_BOTTOM = 8
    }
}
