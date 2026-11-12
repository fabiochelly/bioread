package app.bioread

import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

/** La page à l'écran : son compositeur, son chapitre, sa mise en page (texte d'origine). */
class Shown(val ts: Typesetter, val lc: LaidChapter, val page: Page)

/** Endroit de la page sous un point : tranche et ligne de sa mise en page. */
class Spot(val slice: Slice, val line: Int)

class LinkHit(val block: Block, val link: Link, val distance: Float)

/** Mot de la page : caractères [start, end) du texte de [block]. */
class WordHit(val block: Block, val start: Int, val end: Int)

/** Tranche et ligne de texte sous le point (x, y) de la page [p], ou null (marge, image, blanc). */
fun Typesetter.spotAt(p: Page, x: Float, y: Float): Spot? {
    if (x < mx || x > mx + cw) return null
    for (s in p.slices) {
        val l = s.layout ?: continue
        val yy = (y - (top + s.y - l.getLineTop(s.a))).toInt()
        if (yy < l.getLineTop(s.a) || yy >= l.getLineBottom(s.b - 1)) continue
        return Spot(s, l.getLineForVertical(yy).coerceIn(s.a, s.b - 1))
    }
    return null
}

/** Distance du point (x, y) aux rectangles d'un passage (voir [markRects] : du haut de ligne à la ligne de base). */
private fun distance(rects: List<RectF>, x: Float, y: Float): Float {
    var best = Float.MAX_VALUE
    for (r in rects) {
        // Jusqu'au bas des jambages.
        val bottom = r.bottom + (r.bottom - r.top) * 0.3f
        val dx = maxOf(r.left - x, 0f, x - r.right)
        val dy = maxOf(r.top - y, 0f, y - bottom)
        val d = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (d < best) best = d
    }
    return best
}

/**
 * Lien le plus proche du point (x, y) sur la page [p] : à [noteSlop] pixels au plus pour un appel
 * de note (minuscule à 300 ppp), [linkSlop] pour un lien ordinaire. Une tolérance négative
 * écarte la catégorie. Ne coûte rien sur une page sans lien.
 */
fun Typesetter.linkNear(p: Page, x: Float, y: Float, noteSlop: Float, linkSlop: Float): LinkHit? {
    var best: LinkHit? = null
    for (s in p.slices) {
        val l = s.layout ?: continue
        val links = s.block.links ?: continue
        val a = l.getLineStart(s.a)
        val b = minOf(l.getLineEnd(s.b - 1), l.text.length)
        val dy = top + s.y - l.getLineTop(s.a)
        for (k in links) {
            val from = maxOf(k.start, a)
            val to = minOf(k.end, b)
            if (from >= to) continue
            val slop = if (k.note) noteSlop else linkSlop
            if (slop < 0) continue
            // Tri rapide sur les lignes, avant de mesurer le texte.
            if (y < dy + l.getLineTop(l.getLineForOffset(from)) - slop || y > dy + l.getLineBottom(l.getLineForOffset(to - 1)) + slop) continue
            val d = distance(markRects(p, s.block.start + from, to - from), x, y)
            if (d <= slop && (best == null || d < best.distance)) best = LinkHit(s.block, k, d)
        }
    }
    return best
}

/** Mot sous le point (x, y) de la page [p], à [slop] pixels près dans la ligne ; null hors du texte. */
fun Typesetter.wordNear(p: Page, x: Float, y: Float, lang: String, slop: Float): WordHit? {
    val spot = spotAt(p, x, y) ?: return null
    val s = spot.slice
    val l = s.layout!!
    val text = s.block.text.toString()
    val ls = l.getLineStart(spot.line)
    val le = minOf(l.getLineEnd(spot.line), text.length)
    if (ls >= le) return null
    val lineTop = top + s.y - l.getLineTop(s.a) + l.getLineTop(spot.line)
    val lineBottom = lineTop + l.getLineBottom(spot.line) - l.getLineTop(spot.line)
    val bi = BreakIterator.getWordInstance(Locale.forLanguageTag(lang))
    bi.setText(text)
    var a = bi.preceding(ls + 1)
    if (a == BreakIterator.DONE) a = 0
    var best: WordHit? = null
    var bestD = slop
    while (a < le) {
        val b = bi.following(a)
        if (b == BreakIterator.DONE) break
        var word = false
        for (i in a until b) if (Character.isLetterOrDigit(text[i])) { word = true; break }
        if (word) {
            // Un mot coupé en fin de ligne a un rectangle par ligne : seul compte celui de la ligne touchée.
            for (r in markRects(p, s.block.start + a, b - a)) {
                if (r.bottom <= lineTop || r.top >= lineBottom) continue
                val d = maxOf(r.left - x, 0f, x - r.right)
                if (d <= bestD) { bestD = d; best = WordHit(s.block, a, b) }
            }
        }
        a = b
    }
    val w = best ?: return null
    val r = Words.refine(text, w.start, w.end, lang)
    return WordHit(w.block, r[0], r[1])
}

/** Limites de mot et de phrase, au-delà de ce que donne BreakIterator. */
object Words {
    private val HYPHENS = "-‐‑"
    private val APOSTROPHES = "'’ʼ"
    // Pronoms et particules accrochés par un trait d'union (dit-il, celui-ci) : ils ne font pas un mot composé.
    private val CLITICS = hashSetOf(
        "je", "tu", "il", "elle", "on", "nous", "vous", "ils", "elles", "ce", "t", "le", "la", "les",
        "lui", "leur", "moi", "toi", "y", "en", "ci", "là",
    )
    // Élisions : l'article ou le pronom élidé est détaché du mot (l'apostrophe → apostrophe).
    private val ELIDED = mapOf(
        "fr" to hashSetOf("l", "d", "j", "m", "t", "s", "n", "c", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu"),
        "it" to hashSetOf("l", "d", "un", "dell", "nell", "all", "dall", "sull", "quell", "c", "m", "t", "s", "v", "n"),
        "ca" to hashSetOf("l", "d", "m", "t", "s", "n"),
    )

    private fun letter(s: String, i: Int) = i >= 0 && i < s.length && Character.isLetterOrDigit(s[i])

    /**
     * Ajuste le mot s[a, b) rendu par BreakIterator : les mots composés sont réunis
     * (arrière-pays, mother-in-law), l'élision est détachée (l'aube → aube).
     */
    fun refine(s: String, a0: Int, b0: Int, lang: String): IntArray {
        var a = a0
        var b = b0
        val fr = lang == "fr"
        fun part(i: Int, j: Int) = s.substring(i, j).lowercase()
        // Vers la droite.
        while (b < s.length - 1 && HYPHENS.indexOf(s[b]) >= 0 && letter(s, b + 1) && letter(s, b - 1)) {
            var e = b + 1
            while (e < s.length && (Character.isLetterOrDigit(s[e]) || (APOSTROPHES.indexOf(s[e]) >= 0 && letter(s, e + 1)) || s[e] == '­')) e++
            if (fr && part(b + 1, e) in CLITICS) break
            b = e
        }
        // Vers la gauche, sauf depuis un pronom accroché (« il » de « dit-il » reste seul).
        if (!(fr && part(a, b) in CLITICS)) {
            while (a > 1 && HYPHENS.indexOf(s[a - 1]) >= 0 && letter(s, a - 2) && letter(s, a)) {
                var e = a - 1
                while (e > 0 && (Character.isLetterOrDigit(s[e - 1]) || (APOSTROPHES.indexOf(s[e - 1]) >= 0 && letter(s, e - 2)) || s[e - 1] == '­')) e--
                a = e
            }
        }
        val elided = ELIDED[lang]
        if (elided != null && !part(a, b).replace('\'', '’').startsWith("c’est-à-dire")) {
            for (i in a until b - 1) if (APOSTROPHES.indexOf(s[i]) >= 0) {
                if (part(a, i) in elided && letter(s, i + 1)) a = i + 1
                break
            }
        }
        return intArrayOf(a, b)
    }

    /** La phrase de [s] qui contient le caractère [at], sans les blancs autour. */
    fun sentence(s: String, at: Int, lang: String): IntArray {
        val bi = BreakIterator.getSentenceInstance(Locale.forLanguageTag(lang))
        bi.setText(s)
        var e = bi.following(at)
        if (e == BreakIterator.DONE) e = s.length
        var a = bi.previous()
        if (a == BreakIterator.DONE) a = 0
        while (a < e && (s[a] <= ' ' || s[a] == ' ')) a++
        while (e > a && (s[e - 1] <= ' ' || s[e - 1] == ' ')) e--
        return intArrayOf(a, e)
    }
}

/**
 * Ce que le toucher de la page fait en plus de tourner les pages : suivre un lien, ouvrir une
 * note dans sa fenêtre, choisir un mot par appui long. [ReaderView] garde la main sur les zones
 * et les balayages ; il consulte cette classe à trois moments (contact, déplacement, lever).
 */
class PageTouch(private val host: MainActivity, private val reader: ReaderView) {
    private val d = host.resources.displayMetrics.density
    private val moveSlop = ViewConfiguration.get(host).scaledTouchSlop
    private var px = 0f
    private var py = 0f
    private val longR = Runnable { longPress() }
    private var seq = 0

    // Retour après un lien suivi : un seul niveau.
    private var pill: FrameLayout? = null
    private var turns = 0
    private var own = false

    // ---------------------------------------------------------------- contact

    /**
     * Le doigt vient de se poser dans une zone qui tourne la page dès le contact : vrai s'il est
     * sur un appel de note, auquel cas la page ne tourne pas et le geste se décide au lever.
     */
    fun note(x: Float, y: Float): Boolean {
        val s = reader.shownPage() ?: return false
        return s.ts.linkNear(s.page, x, y, EDGE_SLOP * d, -1f) != null || s.ts.glossAt(s.page, x, y, 4 * d) != null
    }

    /** Arme l'appui long (le contact n'a pas tourné la page). */
    fun arm(x: Float, y: Float) {
        px = x
        py = y
        reader.removeCallbacks(longR)
        reader.postDelayed(longR, LONG_PRESS)
    }

    fun moved(dx: Float, dy: Float) {
        if (abs(dx) > moveSlop || abs(dy) > moveSlop) cancel()
    }

    fun cancel() {
        reader.removeCallbacks(longR)
    }

    // ---------------------------------------------------------------- choix d'un mot

    /**
     * Choix d'un mot en cours : l'appui long l'a ouvert, le doigt est encore posé. Tant qu'il
     * glisse, le mot sous le doigt est souligné et rappelé dans une étiquette au-dessus ; la
     * feuille (définition, traduction) s'ouvre sur le mot choisi quand le doigt se lève. Les
     * mots des bords, où un toucher tourne la page dès le contact, s'atteignent ainsi en posant
     * le doigt plus au centre puis en glissant jusqu'à eux.
     */
    var selecting = false
        private set
    private var sel: WordHit? = null
    private var selShown: Shown? = null
    private var selLang = ""
    private var bubble: TextView? = null
    private var hinted = false   // bandeau déjà affiché pendant l'appui

    private fun longPress() {
        val s = reader.shownPage() ?: return     // page traduite ou en cours de chargement : rien
        if (py > reader.height - s.ts.footerZone) return
        selLang = reader.book?.lang?.ifEmpty { "en" } ?: return
        reader.consumeTouch()
        selecting = true
        selShown = s
        sel = null
        hinted = false
        select(px, py)
        // Aucun mot sous le doigt : le bandeau du mode sélection s'affiche tout de suite, doigt
        // encore posé, pour signaler que le mode est actif.
        if (sel == null) {
            hinted = true
            host.showSheet(PickHint(host, reader))
        }
    }

    /** Le doigt est en (x, y) : le mot qui s'y trouve devient le mot choisi. */
    fun select(x: Float, y: Float) {
        val s = selShown ?: return
        val w = try { s.ts.wordNear(s.page, x, y, selLang, 14 * d) } catch (e: Exception) { null } ?: return
        val old = sel
        if (old != null && old.block === w.block && old.start == w.start && old.end == w.end) return
        sel = w
        val rects = s.ts.markRects(s.page, w.block.start + w.start, w.end - w.start)
        if (rects.isEmpty()) return
        host.showMark(MarkView(host, rects))
        // Étiquette : le doigt cache le mot, elle le répète juste au-dessus (ou dessous, en haut de page).
        val b = bubble ?: TextView(host).apply {
            typeface = host.fonts.uiBold
            textSize = 17f
            setTextColor(Theme.bg)
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (7 * d).toInt())
            background = GradientDrawable().apply { setColor(Theme.ui); cornerRadius = 8 * d }
            maxLines = 1
            bubble = this
        }
        b.text = w.block.text.subSequence(w.start, w.end).toString().replace("\u00AD", "")
        b.measure(0, 0)
        val r = rects[0]
        val lp = FrameLayout.LayoutParams(-2, -2)
        lp.leftMargin = ((r.left + r.right) / 2f - b.measuredWidth / 2f).toInt().coerceIn(0, maxOf(0, reader.width - b.measuredWidth))
        val above = r.top - b.measuredHeight - 46 * d
        lp.topMargin = (if (above >= reader.topInset) above else r.bottom + 46 * d).toInt()
        if (b.parent == null) host.root.addView(b, lp) else b.layoutParams = lp
    }

    private fun endSelection() {
        selecting = false
        bubble?.let { host.root.removeView(it) }
        bubble = null
    }

    /**
     * Le doigt se lève : le mode sélection commence. La feuille s'ouvre sur le mot choisi, ou,
     * si l'appui long n'était sur aucun mot, un bandeau invite à en toucher un.
     */
    fun finish() {
        val w = sel
        val s = selShown
        endSelection()
        sel = null
        selShown = null
        if (s == null || reader.shownPage()?.page !== s.page) return
        if (w != null) open(s, w, selLang) else if (!hinted) host.showSheet(PickHint(host, reader))
    }

    private fun open(s: Shown, w: WordHit, lang: String) {
        host.showSheet(WordView(host, reader, s, w, lang))
        // Carnet des mots consultés (voir WordLog) : le mot, son livre et sa phrase.
        try {
            val text = w.block.text.toString()
            val span = Words.sentence(text, w.start, lang)
            val clean = { t: String -> t.replace("\u00AD", "") }
            val b = reader.book
            WordLog.add(host, clean(text.substring(w.start, w.end)), lang, b?.title ?: "", b?.author ?: "", clean(text.substring(span[0], span[1])))
        } catch (x: Exception) {
        }
    }

    /**
     * Mode sélection (une feuille de mot ou le bandeau est à l'écran) : un simple toucher en
     * (x, y) choisit le mot qui s'y trouve, n'importe où sur la page — y compris dans les zones
     * qui, en lecture, tournent la page dès le contact. Rend false s'il n'y a pas de mot là.
     */
    fun pick(x: Float, y: Float): Boolean {
        val s = reader.shownPage() ?: return false
        if (y > reader.height - s.ts.footerZone) return false
        val lang = reader.book?.lang?.ifEmpty { "en" } ?: return false
        val w = try { s.ts.wordNear(s.page, x, y, lang, 10 * d) } catch (e: Exception) { null } ?: return false
        open(s, w, lang)
        return true
    }

    /** Branche le mode sélection sur le fond [v] d'une feuille : toucher un mot le choisit, ailleurs referme. */
    fun pickOrClose(v: View) {
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP && !pick(e.x, e.y)) host.hideSheet()
            true
        }
    }

    /** Geste interrompu par le système. */
    fun abort() {
        endSelection()
        sel = null
        selShown = null
    }

    /** Toucher simple en (x, y) : vrai si un lien ou un mot traduit l'a pris. */
    fun tap(x: Float, y: Float): Boolean {
        val s = reader.shownPage() ?: return false
        if (y > reader.height - s.ts.footerZone) return false
        // Mot traduit sous les lignes (ou sa traduction) : sa fiche, avec toutes ses traductions.
        s.ts.glossAt(s.page, x, y, 4 * d)?.let { w ->
            open(s, w, reader.book?.lang?.ifEmpty { "en" } ?: "en")
            return true
        }
        val h = s.ts.linkNear(s.page, x, y, NOTE_SLOP * d, LINK_SLOP * d) ?: return false
        follow(s, h.block, h.link)
        return true
    }

    // ---------------------------------------------------------------- liens

    private fun follow(s: Shown, block: Block, l: Link) {
        if (l.external) {
            // Rien ne sort de l'app : l'adresse est seulement montrée.
            host.toast(if (l.href.length > 90) l.href.substring(0, 90) + "…" else l.href)
            return
        }
        val e = reader.epub ?: return
        val book = reader.book ?: return
        val path = l.path
        val frag = l.frag
        val spine = e.spine.indexOf(path)
        if (!l.note || frag == null || (!l.typed && notACall(block, l, frag))) {
            jump(s, spine, frag, -1)
            return
        }
        // Le fichier visé (souvent un autre : notes de fin) est lu et analysé hors du thread principal.
        val same = if (spine == s.lc.index) s.lc.chapter else null
        val token = ++seq
        val fonts = host.fonts
        val w = reader.width
        val max = (reader.height * 0.42f).toInt()
        val lang = book.lang
        Thread({
            var note: Note? = null
            var laid: NoteLayout? = null
            try {
                val ch = same ?: e.read(path)?.let { parseChapter(it, path, fonts, e.styles()) }
                // Un libellé court qui vise le milieu d'un paragraphe est un retour vers l'appel, pas une note.
                if (ch != null && (l.typed || Notes.atBlockStart(ch, frag))) note = Notes.extract(ch, frag, l.label)
                note?.let { laid = NoteLayout.make(fonts, it, w, max, d, lang, e) }
            } catch (x: Throwable) {
                // Livre fermé entre-temps, fichier illisible : traité comme un lien ordinaire.
            }
            reader.post {
                if (token == seq && reader.epub === e) {
                    val n = note
                    val nl = laid
                    if (n != null && nl != null) {
                        host.showSheet(NoteView(host, reader, n, nl, if (spine >= 0 && n.visible) ({ jump(s, spine, null, n.offset) }) else null))
                    } else jump(s, spine, frag, -1)
                }
            }
        }, "note").start()
    }

    /**
     * Un numéro cliquable n'est pas toujours un appel de note : dans une table des illustrations
     * ou un index, c'est un numéro de page. Indices : le lien est tout le contenu de son bloc
     * (un appel suit toujours du texte), ou son ancre se nomme « page… ».
     */
    private fun notACall(block: Block, l: Link, frag: String): Boolean {
        val t = block.text
        var a = 0
        var b = t.length
        while (a < b && (t[a] <= ' ' || t[a] == '•' || t[a] == '\u00A0')) a++
        while (b > a && (t[b - 1] <= ' ' || t[b - 1] == '\u00A0')) b--
        if (l.start <= a && l.end >= b) return true
        val f = frag.lowercase()
        return (f.startsWith("page") || f.startsWith("pg")) && f.any { it in '0'..'9' }
    }

    /** Va à la cible d'un lien (ancre [frag], ou position [offset] si elle est connue) et propose le retour. */
    private fun jump(s: Shown, spine: Int, frag: String?, offset: Int) {
        if (spine < 0) {
            host.toast("Ce lien ne mène à aucune page du livre")
            return
        }
        if (reader.shownPage()?.page !== s.page) return      // la page a changé pendant la résolution du lien
        if (spine == s.lc.index) {
            val off = if (offset >= 0) offset else frag?.let { s.lc.chapter.anchors[it] } ?: 0
            if (s.lc.pages[s.lc.pageFor(off)] === s.page) {
                host.toast("Ce passage est sur cette page")
                return
            }
        }
        val from = s.lc.index
        val at = s.page.start
        val label = "Retour p. ${reader.pageNumber()}"
        own = true
        if (offset >= 0) reader.goToOffset(spine, offset) else reader.goTo(spine, frag)
        own = false
        showBack(label, s.ts.bottom) { reader.goToOffset(from, at) }
    }

    // ---------------------------------------------------------------- retour

    /**
     * Pastille « Retour p. N » posée au milieu du pied de page, à la place du numéro de page.
     * Elle s'efface quand on s'en sert, après [KEEP] pages tournées depuis l'arrivée, ou dès
     * qu'on se déplace autrement (sommaire, curseur, recherche, autre lien).
     */
    private fun showBack(label: String, footer: Int, back: () -> Unit) {
        clearBack()
        val root = reader.parent as? ViewGroup ?: return
        val band = maxOf(footer, (44 * d).toInt())
        val box = LinearLayout(host).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Theme.bg); setStroke(maxOf(1, d.toInt()), Theme.ui); cornerRadius = 6 * d }
            setPadding((4 * d).toInt(), 0, (14 * d).toInt(), 0)
            minimumWidth = (150 * d).toInt()
            addView(IconButton(host, IconButton.LEFT, 28, 18))
            addView(TextView(host).apply {
                text = label
                typeface = host.fonts.uiBold
                textSize = 14f
                setTextColor(Theme.ui)
                maxLines = 1
            })
        }
        val v = FrameLayout(host).apply {
            contentDescription = label
            addView(box, FrameLayout.LayoutParams(-2, minOf(band - (8 * d).toInt(), (32 * d).toInt()), Gravity.CENTER))
            setOnClickListener {
                clearBack()
                own = true
                back()
                own = false
            }
        }
        pill = v
        turns = 0
        // Juste au-dessus de la page : les panneaux et la bibliothèque passent devant.
        root.addView(v, root.indexOfChild(reader.wipe) + 1, FrameLayout.LayoutParams(-2, band, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    }

    private fun clearBack() {
        val v = pill ?: return
        pill = null
        (v.parent as? ViewGroup)?.removeView(v)
    }

    /** Une page vient d'être tournée. */
    fun turned() {
        if (pill != null && ++turns > KEEP) clearBack()
    }

    /** Le livre saute ailleurs (sommaire, curseur, recherche, réglages) ou se ferme. */
    fun jumped() {
        if (!own) clearBack()
        cancel()
    }

    companion object {
        const val LONG_PRESS = 450L
        /** Tolérances autour d'un appel de note, en dp : en pleine page, et dans les zones qui tournent la page au contact. */
        const val NOTE_SLOP = 24
        const val EDGE_SLOP = 16
        /** Autour d'un lien ordinaire. */
        const val LINK_SLOP = 6
        /** Pages tournées après un saut avant que la pastille de retour s'efface. */
        const val KEEP = 3
    }
}

/**
 * Bandeau du mode sélection, quand l'appui long n'a désigné aucun mot : toute la page attend
 * qu'on touche un mot ; un toucher ailleurs (ou Retour) quitte le mode.
 */
class PickHint(host: MainActivity, reader: ReaderView) : FrameLayout(host) {
    init {
        val d = resources.displayMetrics.density
        reader.pickOrClose(this)
        addView(TextView(host).apply {
            text = "Mode sélection : touchez un mot"
            typeface = host.fonts.uiBold
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Theme.bg)
            setBackgroundColor(Theme.ui)
            setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt() + host.insetBottom)
        }, LayoutParams(-1, -2, Gravity.BOTTOM))
    }
}
