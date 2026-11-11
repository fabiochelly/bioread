package app.bioread

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recherche dans le livre, ouverte par la loupe du pied de page. Plein écran : champ de saisie
 * et croix de fermeture en haut (la croix est présente dans tous les états), résultats dans
 * l'ordre du livre avec la position de lecture intercalée (« Vous êtes ici »), défilement par
 * écrans entiers en bas. Chaque résultat offre un aperçu — la vraie page du livre, en réduction,
 * que l'on peut feuilleter sans perdre la liste — et un accès direct.
 *
 * La vue est gardée par l'activité entre deux ouvertures : la requête, les résultats et le
 * défilement sont retrouvés tels quels. Traits fins, aplats pleins, aucune animation ; la liste
 * n'est dessinée qu'une fois la recherche terminée (encre électronique).
 */
class SearchView(private val host: MainActivity, private val reader: ReaderView) : FrameLayout(host) {
    val bookId: String? = reader.book?.id
    private val d = resources.displayMetrics.density
    private val fonts = host.fonts
    private val imm = host.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val hair = maxOf(1, dp(1))

    private val column = LinearLayout(host)
    private val field = EditText(host)
    private val status = label("", true, 14f)
    private val message = TextView(host)
    private val list = ListView(host)
    private val foot = LinearLayout(host)
    private val results = Results()
    private lateinit var statusLine: View
    private var markerRow: View? = null

    private var job: SearchJob? = null
    private var interrupted = false           // recherche arrêtée par la fermeture : relancée à la réouverture
    private var query = ""                    // requête des résultats affichés
    private var hits: List<Hit> = emptyList()
    private var total = 0
    private var marker = 0                    // nombre de résultats avant la position de lecture
    private var markerText = ""
    private var context = 60                  // caractères de contexte de chaque côté, selon la largeur
    private var wide = false
    // Premier élément visible à la fermeture : résultat (ou -1 = repère) et décalage en pixels.
    private var keepHit = -2
    private var keepTop = 0

    private var preview: Preview? = null
    // Fil de l'aperçu : compose et trace les pages, avec son propre compositeur.
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "apercu") }
    private val wanted = AtomicInteger(0)
    private var epoch = 0                     // change à chaque ouverture : les réglages ont pu changer
    private var laidKey = ""                  // réservés au fil de l'aperçu
    private var laidTs: Typesetter? = null
    private val laid = HashMap<Int, LaidChapter>()

    init {
        setBackgroundColor(Theme.bg)
        isClickable = true
        // Reçoit le focus quand le clavier se ferme (sinon il reviendrait au champ de saisie).
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false

        column.orientation = LinearLayout.VERTICAL
        addView(column, LayoutParams(-1, -1))

        // Barre haute : champ, bouton de recherche, croix.
        field.typeface = fonts.ui
        field.textSize = 16f
        field.setTextColor(Theme.ui)
        field.setHintTextColor(soft())
        field.highlightColor = if (Theme.eink) Color.rgb(0xCC, 0xCC, 0xCC) else (Theme.ui and 0xFFFFFF) or 0x40000000
        field.hint = "Rechercher dans le livre"
        field.setSingleLine(true)
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        field.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        field.setSelectAllOnFocus(true)
        field.background = outline()
        field.setPadding(dp(14), 0, dp(14), 0)
        field.gravity = Gravity.CENTER_VERTICAL
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_NULL) { submit(); true } else false
        }
        val bar = LinearLayout(host)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(12), dp(10), dp(6), dp(10))
        bar.addView(field, LinearLayout.LayoutParams(0, dp(46), 1f))
        bar.addView(
            IconButton(host, IconButton.SEARCH, 46, 22) { submit() }.apply { background = outline(color = Theme.tint(IconButton.SEARCH)); contentDescription = "Rechercher" },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) },
        )
        bar.addView(closeButton(), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(4) })
        column.addView(bar)
        column.addView(line(), LinearLayout.LayoutParams(-1, dp(2)))

        status.setPadding(dp(20), dp(10), dp(20), dp(10))
        status.visibility = GONE
        column.addView(status)
        column.addView(line().also { it.visibility = GONE; statusLine = it }, LinearLayout.LayoutParams(-1, hair))

        // Corps : la liste, ou un message (invite, avancement, aucun résultat).
        val body = FrameLayout(host)
        message.typeface = fonts.ui
        message.textSize = 16f
        message.setTextColor(Theme.ui)
        message.setLineSpacing(0f, 1.35f)
        message.gravity = Gravity.CENTER_HORIZONTAL
        message.setPadding(dp(28), dp(44), dp(28), 0)
        list.divider = null
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.overScrollMode = OVER_SCROLL_NEVER
        list.isVerticalFadingEdgeEnabled = false
        list.cacheColorHint = Color.TRANSPARENT
        // Ascenseur fixe (pas de fondu) : un simple trait plein.
        list.isScrollbarFadingEnabled = false
        if (Build.VERSION.SDK_INT >= 29) list.verticalScrollbarThumbDrawable = ColorDrawable(Theme.ui)
        list.scrollBarSize = dp(3)
        list.adapter = results
        list.visibility = GONE
        list.setOnItemClickListener { _, v, _, _ ->
            if (v is Row) v.hit?.let { openPreview(it, v.n) } else if (v === markerRow) host.hideSearch()
        }
        body.addView(list, LayoutParams(-1, -1))
        body.addView(message, LayoutParams(-1, -1))
        column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        // Barre basse : un écran vers le haut, retour au repère, un écran vers le bas.
        foot.orientation = LinearLayout.VERTICAL
        foot.addView(line(), LinearLayout.LayoutParams(-1, dp(2)))
        val row = LinearLayout(host)
        row.setPadding(dp(12), dp(10), dp(12), dp(12))
        row.addView(boxed(IconButton.UP, "Résultats précédents") { page(-1) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(
            label("●  Ma position", true, 15f).apply {
                gravity = Gravity.CENTER
                background = outline()
                setOnClickListener { toMarker() }
            },
            LinearLayout.LayoutParams(0, dp(48), 1.5f).apply { leftMargin = dp(8); rightMargin = dp(8) },
        )
        row.addView(boxed(IconButton.DOWN, "Résultats suivants") { page(1) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        foot.addView(row)
        foot.visibility = GONE
        column.addView(foot)

        prompt()

        setOnApplyWindowInsetsListener { v, insets ->
            // Clavier, barres système (elles reviennent avec le clavier) et encoche : rien ne passe dessous.
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(0, maxOf(i.top, reader.topInset), 0, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(0, maxOf(insets.systemWindowInsetTop, reader.topInset), 0, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    // ---------------------------------------------------------------- briques

    private fun dp(v: Int) = (v * d).toInt()

    private fun line() = View(host).apply { setBackgroundColor(Theme.ui) }

    /** Couleur atténuée (texte d'invite) : gris franc sur liseuse. */
    private fun soft() = if (Theme.eink) Color.rgb(0x70, 0x70, 0x70) else (Theme.ui and 0xFFFFFF) or (0x99 shl 24)

    private fun outline(filled: Boolean = false, color: Int = Theme.ui) = GradientDrawable().apply {
        setColor(if (filled) color else Theme.bg)
        setStroke(hair, color)
        cornerRadius = 9 * d
    }

    private fun label(text: String, bold: Boolean = false, size: Float = 15f) = TextView(host).apply {
        this.text = text
        typeface = if (bold) host.fonts.uiBold else host.fonts.ui
        textSize = size
        setTextColor(Theme.ui)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** La croix : toujours là, elle ramène au livre, à la page où on l'a laissé. */
    private fun closeButton() = IconButton(host, IconButton.CLOSE) { host.hideSearch() }.apply { contentDescription = "Revenir au livre" }

    /** Bouton encadré portant une icône. */
    private fun boxed(kind: Int, what: String, onClick: () -> Unit) = FrameLayout(host).apply {
        background = outline(color = Theme.tint(kind))
        contentDescription = what
        addView(IconButton(host, kind, 30, 22), LayoutParams(-2, -2, Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- ouverture, fermeture

    /** La vue vient d'être (re)posée sur le livre. */
    fun shown() {
        epoch++
        requestApplyInsets()
        if (interrupted) {
            interrupted = false
            val q = query
            query = ""
            field.setText(q)
            submit()
            return
        }
        if (hits.isEmpty()) {
            if (job == null) {
                field.requestFocus()
                field.post { imm.showSoftInput(field, 0) }
            }
            return
        }
        requestFocus()
        // La position de lecture a pu changer : le repère est replacé, la liste reste où elle était.
        place()
        // L'adaptateur est reposé : la liste oublie sa propre mémoire de défilement (elle
        // retiendrait le rang de la première rangée, que le déplacement du repère décale).
        list.adapter = results
        if (keepHit >= -1) {
            val pos = if (keepHit < 0) marker else if (keepHit < marker) keepHit else keepHit + 1
            list.setSelectionFromTop(pos.coerceIn(0, results.count - 1), keepTop)
        }
    }

    /** Recherche demandée depuis la page (appui long sur un mot) : à appeler juste avant [shown]. */
    fun search(q: String) {
        field.setText(q)
        submit()
    }

    /** La vue va être retirée : une recherche en cours est arrêtée, et on note où en est la liste. */
    fun hidden() {
        hideKeyboard()
        job?.let {
            it.cancel()
            job = null
            interrupted = true
        }
        closePreview()   // à la réouverture, on retrouve la liste
        keepHit = -2
        if (hits.isNotEmpty() && list.childCount > 0) {
            val p = list.firstVisiblePosition
            keepHit = if (p == marker) -1 else if (p < marker) p else minOf(p - 1, hits.size - 1)
            keepTop = list.getChildAt(0).top
        }
    }

    /** Plus jamais utilisée (autre livre) : arrête tout. */
    fun dispose() {
        job?.cancel()
        job = null
        wanted.incrementAndGet()
        worker.shutdownNow()
    }

    /** Retour arrière : de l'aperçu à la liste. Rend false s'il faut fermer la recherche. */
    fun back(): Boolean {
        if (preview == null) return false
        closePreview()
        return true
    }

    private fun hideKeyboard() {
        imm.hideSoftInputFromWindow(windowToken, 0)
        if (field.hasFocus()) requestFocus()
    }

    // ---------------------------------------------------------------- recherche

    private fun say(text: CharSequence) {
        message.text = text
        message.visibility = VISIBLE
        list.visibility = GONE
        foot.visibility = GONE
        status.visibility = GONE
        statusLine.visibility = GONE
    }

    private fun prompt() = say("Saisissez un mot ou une expression.\nMajuscules et accents sont ignorés.")

    private fun submit() {
        val q = field.text.toString().trim()
        hideKeyboard()
        if (Folder.query(q).length < 2) {
            // Une requête vide ou d'une lettre ne remplace pas des résultats déjà affichés.
            if (hits.isNotEmpty()) host.toast("Saisissez au moins deux caractères")
            else {
                job?.cancel()
                job = null
                say("Saisissez au moins deux caractères.")
            }
            return
        }
        if (q == query && (job != null || hits.isNotEmpty())) return
        val e = reader.epub ?: return
        job?.cancel()
        interrupted = false
        query = q
        hits = emptyList()
        total = 0
        results.notifyDataSetChanged()
        say("Recherche de « $q »…")
        lateinit var j: SearchJob
        j = SearchJob(
            e, fonts, q,
            { pc -> post { if (job === j) message.text = "Recherche de « $q »…\n$pc %" } },
            { post { if (job === j) finished(j) } },
        )
        job = j
        j.start()
    }

    private fun finished(j: SearchJob) {
        job = null
        hits = j.hits
        total = j.total
        if (hits.isEmpty()) {
            say(
                if (j.failed) "La recherche n'a pas pu aboutir."
                else "Aucun résultat pour « $query ».\nMajuscules et accents sont ignorés."
            )
            return
        }
        status.text = when {
            j.failed -> "Recherche interrompue : ${hits.size} résultats pour « $query »"
            total > hits.size -> "${hits.size} premiers résultats sur $total"
            total == 1 -> "1 résultat pour « $query »"
            else -> "$total résultats pour « $query »"
        }
        place()
        message.visibility = GONE
        status.visibility = VISIBLE
        statusLine.visibility = VISIBLE
        list.visibility = VISIBLE
        foot.visibility = VISIBLE
        // La liste s'ouvre sur la position de lecture, deux résultats au-dessus pour le contexte.
        list.adapter = results
        list.setSelectionFromTop(maxOf(0, marker - 2), 0)
    }

    /** Place le repère « Vous êtes ici » parmi les résultats, d'après la page affichée par le livre. */
    private fun place() {
        val b = reader.book
        marker = if (b == null) 0 else hits.count { it.spine < b.spine || (it.spine == b.spine && it.offset < b.offset) }
        markerText = "●  Vous êtes ici — page ${reader.pageNumber()} · ${reader.percent()} %"
    }

    // ---------------------------------------------------------------- liste

    private inner class Row : LinearLayout(host) {
        val num = label("", true, 14f)
        val chap = label("", false, 14f)
        val pct = label("", false, 14f)
        val text = TextView(host)
        var hit: Hit? = null
        var n = 0

        init {
            orientation = VERTICAL
            // À gauche le numéro, le chapitre et l'extrait ; à droite les deux boutons, l'un sous l'autre.
            val head = LinearLayout(host)
            head.gravity = Gravity.CENTER_VERTICAL
            head.addView(num)
            head.addView(chap, LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
            head.addView(pct, LayoutParams(-2, -2).apply { leftMargin = dp(10) })
            // L'extrait est dans la police du livre ; hauteur fixe, pour des rangées régulières.
            text.typeface = fonts.regular
            text.textSize = 16f
            text.setTextColor(Theme.ui)
            text.setLineSpacing(0f, 1.2f)
            text.setLines(3)
            text.ellipsize = TextUtils.TruncateAt.END
            text.setPadding(0, dp(5), 0, 0)
            val left = LinearLayout(host)
            left.orientation = VERTICAL
            left.addView(head)
            left.addView(text)
            val buttons = LinearLayout(host)
            buttons.orientation = VERTICAL
            buttons.addView(
                IconButton(host, IconButton.EYE, 46, 22) { hit?.let { openPreview(it, n) } }
                    .apply { background = outline(color = Theme.tint(IconButton.EYE)); contentDescription = "Aperçu de la page" },
            )
            buttons.addView(
                IconButton(host, IconButton.FORWARD, 46, 22) { hit?.let { go(it) } }
                    .apply { background = outline(color = Theme.tint(IconButton.FORWARD)); contentDescription = "Aller à ce passage" },
                LayoutParams(-2, -2).apply { topMargin = dp(8) },
            )
            val content = LinearLayout(host)
            content.gravity = Gravity.CENTER_VERTICAL
            content.setPadding(dp(20), dp(10), dp(14), dp(10))
            content.addView(left, LayoutParams(0, -2, 1f))
            content.addView(buttons, LayoutParams(-2, -2).apply { leftMargin = dp(14) })
            addView(content)
            addView(line(), LayoutParams(-1, hair))
        }

        fun bind(h: Hit, index: Int) {
            hit = h
            n = index + 1
            num.text = "$n / $total"
            chap.text = h.chapter
            pct.text = "${h.percent} %"
            pct.visibility = if (wide) VISIBLE else GONE
            text.text = snippet(h)
        }
    }

    /** L'occurrence en gras au milieu de son contexte, coupé entre deux mots. */
    private fun snippet(h: Hit): CharSequence {
        var b = h.before
        var cut = !h.atStart
        if (b.length > context) { b = b.substring(b.length - context); cut = true }
        if (cut) {
            val sp = b.indexOf(' ')
            if (sp >= 0) b = b.substring(sp + 1)
            b = "… $b"
        }
        var a = h.after
        cut = !h.atEnd
        if (a.length > context) { a = a.substring(0, context); cut = true }
        if (cut) {
            val sp = a.lastIndexOf(' ')
            if (sp >= 0) a = a.substring(0, sp)
            a = "$a …"
        }
        val sb = SpannableStringBuilder(b).append(h.match).append(a)
        sb.setSpan(FontSpan(fonts, true, false), b.length, b.length + h.match.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private inner class Results : BaseAdapter() {
        private val more get() = total > hits.size

        override fun getCount() = if (hits.isEmpty()) 0 else hits.size + 1 + (if (more) 1 else 0)
        override fun getItem(i: Int) = i
        override fun getItemId(i: Int) = i.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(i: Int) = if (i == marker) 1 else if (i > hits.size) 2 else 0
        override fun isEnabled(i: Int) = i <= hits.size

        override fun getView(i: Int, v: View?, parent: ViewGroup): View = when (getItemViewType(i)) {
            1 -> ((v as? TextView) ?: label("", true, 15f).apply {
                // Bandeau plein : impossible à manquer en faisant défiler.
                setBackgroundColor(Theme.ui)
                setTextColor(Theme.bg)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                layoutParams = AbsListView.LayoutParams(-1, dp(MARKER))
            }).also { it.text = markerText; markerRow = it }
            2 -> (v as? TextView) ?: label("", false, 14f).apply {
                text = "Seuls les ${hits.size} premiers résultats sont affichés.\nPrécisez la recherche pour voir les suivants."
                maxLines = 3
                setLineSpacing(0f, 1.3f)
                setPadding(dp(20), dp(16), dp(20), dp(20))
            }
            else -> {
                val k = if (i < marker) i else i - 1
                ((v as? Row) ?: Row()).also { it.bind(hits[k], k) }
            }
        }
    }

    private fun rowHeight(pos: Int): Int {
        if (pos == marker) return dp(MARKER)
        for (k in 0 until list.childCount) (list.getChildAt(k) as? Row)?.let { return it.height }
        return list.height / 5
    }

    /** Un écran de résultats plus bas ([dir] = 1) ou plus haut (-1), d'un coup, calé sur une rangée. */
    private fun page(dir: Int) {
        val n = list.childCount
        if (n == 0) return
        val first = list.firstVisiblePosition
        if (dir > 0) {
            // La première rangée qui n'est pas entièrement visible passe en tête.
            val lastChild = list.getChildAt(n - 1)
            val target = if (lastChild.bottom > list.height) first + n - 1 else first + n
            if (target < results.count) list.setSelectionFromTop(maxOf(target, first + 1), 0)
        } else {
            var p = if (list.getChildAt(0).top >= 0) first else first + 1
            var h = 0
            while (p > 0 && h + rowHeight(p - 1) <= list.height) { h += rowHeight(p - 1); p-- }
            if (p == first && first > 0) p--
            list.setSelectionFromTop(p, 0)
        }
    }

    private fun toMarker() {
        place()
        list.adapter = results
        list.setSelectionFromTop(maxOf(0, marker - 2), 0)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w == ow) return
        // Environ deux lignes et demie d'extrait : une ligne un quart de chaque côté de l'occurrence.
        val perLine = (w / d - 94) / (16 * 0.47f)
        context = (perLine * 1.2f).toInt().coerceIn(30, SearchJob.CONTEXT)
        wide = w / d >= 480
        results.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- aller au passage

    private fun go(h: Hit) = jump(h.spine, h.offset, h)

    /** Ferme la recherche et ouvre le livre à la page qui contient [offset] ; l'occurrence [h] y est soulignée. */
    private fun jump(spine: Int, offset: Int, h: Hit?) {
        host.hideSearch()
        reader.goToOffset(spine, offset) { t, page ->
            // La page traduite n'a pas la même mise en page : rien à souligner.
            if (h != null && !Prefs.translate) {
                val rects = t.markRects(page, h.offset, h.length)
                if (rects.isNotEmpty()) host.showMark(MarkView(host, rects))
            }
        }
    }

    // ---------------------------------------------------------------- aperçu

    private fun openPreview(h: Hit, n: Int) {
        if (preview != null) return
        hideKeyboard()
        val p = Preview(h, n)
        preview = p
        addView(p, LayoutParams(-1, -1))
    }

    private fun closePreview() {
        val p = preview ?: return
        preview = null
        wanted.incrementAndGet()
        removeView(p)
        p.release()
    }

    /**
     * Mini-page : la page du livre qui contient le résultat, composée avec les réglages et la
     * géométrie du lecteur (mêmes coupures de pages que dans le livre) et tracée en réduction —
     * le texte est dessiné à l'échelle, pas une image rétrécie : il reste net. On peut feuilleter
     * de part et d'autre, d'un chapitre à l'autre ; la liste des résultats reste dessous, intacte.
     */
    private inner class Preview(val hit: Hit, n: Int) : LinearLayout(host) {
        private val pageLabel = label("", false, 14f)
        private val sheet = Sheet()
        private var bmp: Bitmap? = null
        private var spine = hit.spine
        private var page = -1
        private var pages = 0
        private var start = 0
        private var onHit = false
        private var pending = false
        private val waitR = Runnable { if (pending) pageLabel.text = "Chargement…" }

        init {
            orientation = VERTICAL
            setBackgroundColor(Theme.bg)
            isClickable = true

            val bar = LinearLayout(host)
            bar.gravity = Gravity.CENTER_VERTICAL
            bar.setPadding(dp(12), dp(10), dp(6), dp(10))
            val backTo = LinearLayout(host).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = outline()
                setPadding(dp(6), 0, dp(14), 0)
                addView(IconButton(host, IconButton.LEFT, 30, 20))
                addView(label("Résultats", true, 15f))
                setOnClickListener { closePreview() }
            }
            bar.addView(backTo, LayoutParams(-2, dp(46)))
            bar.addView(
                LinearLayout(host).apply {
                    orientation = VERTICAL
                    addView(label("Résultat $n / $total", true, 14f))
                    addView(label(hit.chapter, false, 13f))
                },
                LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) },
            )
            bar.addView(closeButton(), LayoutParams(-2, -2).apply { leftMargin = dp(4) })
            addView(bar)
            addView(line(), LayoutParams(-1, dp(2)))

            addView(sheet, LayoutParams(-1, 0, 1f))

            addView(line(), LayoutParams(-1, dp(2)))
            val nav = LinearLayout(host)
            nav.gravity = Gravity.CENTER_VERTICAL
            nav.setPadding(dp(12), dp(10), dp(12), dp(12))
            nav.addView(boxed(IconButton.LEFT, "Page précédente") { step(-1) }, LayoutParams(dp(56), dp(48)))
            pageLabel.gravity = Gravity.CENTER
            nav.addView(pageLabel, LayoutParams(0, -2, 1f).apply { leftMargin = dp(6); rightMargin = dp(6) })
            nav.addView(boxed(IconButton.RIGHT, "Page suivante") { step(1) }, LayoutParams(dp(56), dp(48)))
            // Action principale : aplat plein.
            val goTo = LinearLayout(host).apply {
                gravity = Gravity.CENTER
                background = outline(true)
                setPadding(dp(14), 0, dp(8), 0)
                addView(label("Y aller", true, 15f).apply { setTextColor(Theme.bg) })
                addView(IconButton(host, IconButton.FORWARD, 30, 20).apply { inverted = true })
                setOnClickListener { if (page >= 0) jump(spine, if (onHit) hit.offset else start, if (onHit) hit else null) }
            }
            nav.addView(goTo, LayoutParams(-2, dp(48)).apply { leftMargin = dp(12) })
            addView(nav)
        }

        fun release() {
            removeCallbacks(waitR)
            bmp?.let { b -> host.window.decorView.postDelayed({ b.recycle() }, 300) }
            bmp = null
        }

        private fun step(dir: Int) {
            if (pending || page < 0) return
            val n = reader.epub?.spine?.size ?: return
            when {
                dir > 0 && page + 1 < pages -> render(spine, PAGE, page + 1)
                dir > 0 && spine + 1 < n -> render(spine + 1, PAGE, 0)
                dir < 0 && page > 0 -> render(spine, PAGE, page - 1)
                dir < 0 && spine > 0 -> render(spine - 1, LAST, 0)
            }
        }

        /** Demande la page au fil de l'aperçu ; elle remplace l'actuelle quand elle est prête. */
        fun render(sp: Int, mode: Int, value: Int) {
            val e = reader.epub ?: return
            val b = reader.book ?: return
            val w = reader.width
            val h = reader.height
            val top = reader.topInset
            val aw = sheet.width - 2 * sheet.pad
            val ah = sheet.height - 2 * sheet.pad
            if (w <= 0 || h <= 0 || aw <= 0 || ah <= 0) return
            val k = minOf(aw.toFloat() / w, ah.toFloat() / h)
            val key = "$epoch,$w,$h,$top"
            val g = wanted.incrementAndGet()
            pending = true
            removeCallbacks(waitR)
            postDelayed(waitR, 350)
            worker.execute {
                if (g != wanted.get()) return@execute
                var out: Bitmap? = null
                var p = 0
                var count = 0
                var st = 0
                var hitHere = false
                try {
                    if (key != laidKey) {
                        laidTs = Typesetter(fonts, w, h, top, d, b.lang)
                        laidKey = key
                        laid.clear()
                    }
                    val t = laidTs!!
                    val lc = laid[sp] ?: run {
                        val path = e.spine[sp]
                        val made = t.layoutChapter(sp, parseChapter(e.read(path) ?: "", path, fonts, e.styles()), e, true)
                        // Deux chapitres gardés : feuilleter autour d'une limite de chapitre reste immédiat.
                        if (laid.size >= 2) laid.keys.maxByOrNull { Math.abs(it - sp) }?.let { laid.remove(it) }
                        laid[sp] = made
                        made
                    }
                    count = lc.pages.size
                    p = when (mode) {
                        OFFSET -> lc.pageFor(value)
                        LAST -> count - 1
                        else -> value.coerceIn(0, count - 1)
                    }
                    val pg = lc.pages[p]
                    st = pg.start
                    val bm = Bitmap.createBitmap(maxOf(1, (w * k).toInt()), maxOf(1, (h * k).toInt()), Bitmap.Config.ARGB_8888)
                    val c = Canvas(bm)
                    c.scale(k, k)
                    t.draw(c, pg, e)
                    if (sp == hit.spine) {
                        val rects = t.markRects(pg, hit.offset, hit.length)
                        hitHere = rects.isNotEmpty()
                        val paint = Paint().apply { color = Theme.ui }
                        for (r in rects) c.drawRect(r.left, r.bottom + 2.5f * d, r.right, r.bottom + 5f * d, paint)
                    }
                    out = bm
                } catch (x: Throwable) {
                }
                val bm = out
                post {
                    if (g != wanted.get() || preview !== this) bm?.recycle()
                    else {
                        pending = false
                        removeCallbacks(waitR)
                        if (bm == null) pageLabel.text = "Page illisible"
                        else {
                            val old = bmp
                            bmp = bm
                            spine = sp
                            page = p
                            pages = count
                            start = st
                            onHit = hitHere
                            pageLabel.text = "Page ${reader.pageNumberAt(sp, p)} / ${reader.totalPages()}"
                            sheet.invalidate()
                            // L'ancienne image peut encore être lue par le fil de rendu du système.
                            old?.let { o -> postDelayed({ o.recycle() }, 300) }
                        }
                    }
                }
            }
        }

        /** La feuille : l'image de la page dans un cadre fin ; toucher à gauche ou à droite pour feuilleter. */
        private inner class Sheet : View(host) {
            val pad = dp(14)
            private val frame = Paint().apply { color = Theme.ui; style = Paint.Style.STROKE; strokeWidth = hair.toFloat() }
            private var downX = 0f

            override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
                // Première mise en place (ou rotation) : la page est tracée à la taille du cadre.
                if (w > 0 && h > 0) post { if (preview === this@Preview) { if (page < 0) render(hit.spine, OFFSET, hit.offset) else render(spine, PAGE, page) } }
            }

            override fun onDraw(c: Canvas) {
                val b = bmp ?: return
                if (b.isRecycled) return
                val x = (width - b.width) / 2f
                val y = (height - b.height) / 2f
                c.drawBitmap(b, x, y, null)
                c.drawRect(x - hair / 2f, y - hair / 2f, x + b.width + hair / 2f, y + b.height + hair / 2f, frame)
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> downX = e.x
                    MotionEvent.ACTION_UP -> {
                        val dx = e.x - downX
                        // Comme dans le livre : balayage, ou toucher à gauche (retour) et à droite (suite).
                        if (Math.abs(dx) > 28 * d) step(if (dx < 0) 1 else -1)
                        else step(if (e.x < width / 3f) -1 else 1)
                    }
                }
                return true
            }
        }
    }

    companion object {
        private const val MARKER = 52     // hauteur du repère, en dp
        private const val OFFSET = 0
        private const val PAGE = 1
        private const val LAST = 2
    }
}

/**
 * Trait sous l'occurrence, posé sur la page du livre après un saut depuis la recherche. La vue
 * ne couvre que le passage et ne reçoit pas le toucher ; l'activité la retire au premier contact.
 */
class MarkView(host: MainActivity, rects: List<RectF>) : View(host) {
    private val d = resources.displayMetrics.density
    private val paint = Paint().apply { color = Theme.fg }
    private val lines = ArrayList<RectF>()
    val bounds = RectF()

    init {
        for (r in rects) lines.add(RectF(r.left, r.bottom + 2.5f * d, r.right, r.bottom + 5f * d))
        if (lines.isNotEmpty()) bounds.set(lines[0])
        for (l in lines) bounds.union(l)
    }

    fun params() = FrameLayout.LayoutParams(Math.ceil(bounds.width().toDouble()).toInt() + 1, Math.ceil(bounds.height().toDouble()).toInt() + 1).apply {
        leftMargin = Math.floor(bounds.left.toDouble()).toInt()
        topMargin = Math.floor(bounds.top.toDouble()).toInt()
    }

    override fun onDraw(c: Canvas) {
        val ox = Math.floor(bounds.left.toDouble()).toFloat()
        val oy = Math.floor(bounds.top.toDouble()).toFloat()
        for (l in lines) c.drawRect(l.left - ox, l.top - oy, l.right - ox, l.bottom - oy, paint)
    }
}
