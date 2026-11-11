package app.bioread

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.text.StaticLayout
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Vue de lecture. Les pages précédente / courante / suivante sont pré-rendues dans trois
 * bitmaps : tourner une page n'est qu'une permutation. La composition (un fil, chapitre par
 * chapitre, avec un chapitre d'avance) et le tracé des pages (un autre fil) se font hors du
 * thread principal, qui ne fait qu'attendre une page si elle n'est pas encore prête.
 */
class ReaderView(private val host: MainActivity) : View(host) {
    private val fonts = host.fonts
    private val exec = Executors.newSingleThreadExecutor()
    // Seul fil qui dessine les pages : une mise en page n'est jamais tracée par deux fils à la fois.
    private val painter = Executors.newSingleThreadExecutor { r ->
        Thread({
            // Une page tournée peut attendre ce fil : il passe devant les travaux de fond.
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) } catch (x: Throwable) {}
            r.run()
        }, "pages")
    }
    private val choreographer = Choreographer.getInstance()
    private val density = resources.displayMetrics.density
    private val eink = Theme.eink

    var book: BookEntry? = null
        private set
    var epub: Epub? = null
        private set
    private var ts: Typesetter? = null
    private var tsKey = ""       // géométrie et réglages de [ts]
    private val chapters = HashMap<Int, LaidChapter>()
    // Chapitres déjà lus et analysés : un changement de réglage ne refait que la composition.
    private val parsed = HashMap<Int, Chapter>()
    private val loading = HashSet<Int>()
    private var gen = 0
    private var trGen = 0
    private var ci = 0
    private var pi = 0
    private var ready = false
    private var pendingPos: ((LaidChapter) -> Int)? = null
    private var pendingTurn = 0
    // Réouverture : position enregistrée (-1 sinon) et page affichée avant que son chapitre soit composé.
    private var openOff = -1
    private var openPage = 0
    private var partial: Page? = null
    private var quickShown: CountDownLatch? = null
    private var openT0 = 0L
    private var benched = false

    /** Diagnostic : mesure une fois, plus tard et hors du passage, ce que l'appareil sait faire. */
    private fun bench(t: Typesetter, lc: LaidChapter, e: Epub) {
        benched = true
        Thread {
            try {
                Thread.sleep(6000)
                // La page n'est tracée que sur des mises en page neuves : aucun autre fil ne les dessine.
                val fresh = t.layoutChapter(lc.index, lc.chapter, e)
                val page = fresh.pages.firstOrNull { p -> p.slices.size > 1 && p.slices.all { it.layout != null } }
                Perf.log(t.bench(lc.chapter, page, e))
                Diag.flush()
            } catch (x: Throwable) {
                Perf.log("banc d'essai : $x")
            }
        }.start()
    }
    private var lastDir = 1      // sens de la dernière page tournée
    private var firstShown = true
    var topInset = 0
        private set

    // 0 = précédente, 1 = courante, 2 = suivante. Un bitmap rangé ici contient une page finie.
    private val bmp = arrayOfNulls<Bitmap>(3)
    private val held = arrayOfNulls<Page>(3)
    private val heldLc = arrayOfNulls<LaidChapter>(3)
    private val heldP = IntArray(3)
    private val jobs = arrayOfNulls<Job>(3)      // tracés en cours, par emplacement
    private val free = ArrayList<Bitmap>(2)      // bitmaps réutilisables (plus affichés nulle part)
    private val canvas = Canvas()                // réservé au fil [painter]

    private var animating = false
    private var animDir = 0
    private var animStart = 0L
    private val prepareR = Runnable { prepare() }
    private val prevR = Runnable {
        if (ready && !animating && pendingPos == null) locate(-lastDir)?.let { request(1 - lastDir, it.lc, it.p) }
    }

    // Nombre de pages par chapitre pour les réglages courants (0 = pas encore compté).
    private var counts = IntArray(0)
    private var countsKey = ""
    private var footLeft = ""
    private var footCenter = ""
    private var handled = false
    private var clock = ""
    private var clockMinute = 0L
    private val timeFormat = android.text.format.DateFormat.getTimeFormat(host)
    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (ready && visibility == VISIBLE) {
                if (!eink) invalidate() else if (!animating) wipe.showAll()
            }
        }
    }

    /** Appelé quand la position ou la numérotation change (panneau de navigation). */
    var onPosition: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f

    private class Loc(val lc: LaidChapter, val p: Int)

    // ---------------------------------------------------------------- livre

    /** Géométrie et réglages dont dépend la composition. */
    private fun layoutKey(w: Int, h: Int, top: Int) =
        "$w,$h,$top,${Prefs.fontSize},${Prefs.lineSpacing},${Prefs.margin}," +
            "${Prefs.weight},${Prefs.spacing},${Prefs.stretch},${Prefs.sharp}" + if (Prefs.faithful) ",css" else ""

    private fun setTypesetter(w: Int, h: Int, top: Int, lang: String) {
        ts = Typesetter(fonts, w, h, top, density, lang)
        tsKey = layoutKey(w, h, top)
    }

    fun open(b: BookEntry) {
        close()
        book = b
        val g = gen
        val file = Library.file(b)
        // Au lancement, la vue n'a pas encore de taille : celle de la dernière séance permet de
        // composer et de tracer la première page pendant que l'activité finit de démarrer. Si
        // la taille réelle diffère, tout est recomposé (voir sized()).
        if (width > 0 && height > 0) setTypesetter(width, height, topInset, b.lang)
        else Prefs.sp.getString("geom", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.let {
            if (it.size == 3 && it[0] > 0 && it[1] > 0) setTypesetter(it[0], it[1], it[2], b.lang)
        }
        val t = ts
        // Position enregistrée avec ces mêmes réglages : c'est un début de page, que l'on peut
        // afficher sans attendre la composition de tout le chapitre.
        var quick = -1
        val pk = Prefs.sp.getString("pk_${b.id}", null)?.split('|')
        if (t != null && !Prefs.translate && pk != null && pk.size == 4 && pk[0] == tsKey &&
            pk[1] == b.spine.toString() && pk[2] == b.offset.toString()
        ) {
            quick = b.offset
            openPage = pk[3].toIntOrNull() ?: 0
        }
        openT0 = System.nanoTime()
        exec.execute {
            val e = try { Perf.time("epub.open") { Epub(file) } } catch (x: Exception) { null }
            val ok = e != null && e.spine.isNotEmpty()
            val i = if (ok) b.spine.coerceIn(0, e!!.spine.size - 1) else 0
            post {
                if (g != gen || book !== b) {
                    e?.close()
                } else if (!ok) {
                    e?.close()
                    host.toast("EPUB illisible")
                    host.showLibrary()
                } else {
                    epub = e
                    ci = i
                    pendingPos = { it.pageFor(b.offset) }
                    if (t != null && ts === t) {
                        // Le chapitre est déjà en cours de composition sur ce fil (ci-dessous).
                        openOff = quick
                        loading.add(i)
                        startCounting(e!!, b)
                    } else reflow()
                }
            }
            // Sans attendre le thread principal, occupé par le démarrage.
            if (ok && t != null) compose(e!!, t, i, g, null, if (i == b.spine) quick else -1)
        }
    }

    fun close() {
        flushPosition()
        gen++
        val old = epub
        if (old != null) exec.execute { try { old.close() } catch (x: Exception) {} }
        epub = null
        book = null
        pageTouch.jumped()
        ts = null
        tsKey = ""
        chapters.clear()
        parsed.clear()
        loading.clear()
        ready = false
        animating = false
        pendingPos = null
        pendingTurn = 0
        openOff = -1
        partial = null
        for (s in 0..2) clearSlot(s)
        if (eink) wipe.showAll() else invalidate()
    }

    /** Recompose après un changement de réglage ou de taille, en conservant la position. */
    fun reflow() {
        val e = epub ?: return
        val b = book ?: return
        if (width == 0 || height == 0) return
        val pos = pendingPos ?: run {
            val off = chapters[ci]?.pages?.getOrNull(pi)?.start ?: b.offset
            { lc: LaidChapter -> lc.pageFor(off) }
        }
        gen++
        chapters.clear()
        loading.clear()
        animating = false
        if (eink) wipe.finish()
        partial = null
        setTypesetter(width, height, topInset, b.lang)
        startCounting(e, b)
        show(ci.coerceIn(0, e.spine.size - 1), pos)
    }

    /** Le mode « Fidèle au livre » vient de changer : les chapitres déjà analysés ne valent plus. */
    fun restyle() {
        parsed.clear()
        reflow()
    }

    /** La taille ou les marges système viennent d'être connues ou de changer. */
    private fun sized() {
        if (width == 0 || height == 0) return
        val geom = "$width,$height,$topInset"
        if (Prefs.sp.getString("geom", null) != geom) Prefs.sp.edit().putString("geom", geom).apply()
        // Composition déjà lancée avec la bonne géométrie (prédite à l'ouverture) : rien à refaire.
        if (ts != null && tsKey == layoutKey(width, height, topInset)) return
        ready = false
        if (pendingPos == null && chapters[ci] != null) {
            val off = chapters[ci]!!.pages.getOrNull(pi)?.start ?: 0
            pendingPos = { it.pageFor(off) }
        }
        for (s in 0..2) clearSlot(s)
        // Plus aucune vue ne doit référencer les images rendues.
        if (eink) wipe.showAll() else invalidate()
        if (ts == null && epub == null) return   // ouverture en cours : elle composera à cette taille
        if (epub == null) { ts = null; tsKey = ""; gen++; reopen(); return }
        reflow()
    }

    /** La géométrie prédite était fausse et l'EPUB n'est pas encore ouvert : on repart de zéro. */
    private fun reopen() {
        val b = book ?: return
        open(b)
    }

    /**
     * Compte les pages de tout le livre en tâche de fond, à basse priorité, avec son propre
     * compositeur. Le résultat est mémorisé par livre et par réglages : aux ouvertures
     * suivantes la numérotation est exacte tout de suite. En attendant, les chapitres non
     * encore comptés sont estimés d'après leur taille.
     */
    private fun startCounting(e: Epub, b: BookEntry) {
        val n = e.spine.size
        val t = ts ?: return
        countsKey = "$tsKey,${b.size},n"
        val c = IntArray(n)
        val saved = Prefs.sp.getString("pages_${b.id}", null)?.split('|')
        if (saved != null && saved.size == 2 && saved[0] == countsKey) {
            val v = saved[1].split(',')
            if (v.size == n) for (i in 0 until n) c[i] = v[i].toIntOrNull() ?: 0
        }
        counts = c
        if (c.all { it > 0 }) return
        val g = gen
        val key = countsKey
        val t2 = Typesetter(fonts, t.w, t.h, t.topInset, density, b.lang)
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            // Laisse d'abord le chapitre affiché et ses voisins se composer.
            try { Thread.sleep(1500) } catch (x: InterruptedException) {}
            val t0 = System.nanoTime()
            for (i in 0 until n) {
                if (g != gen) return@Thread
                if (c[i] > 0) continue
                val pages = try {
                    t2.layoutChapter(i, parseChapter(e.read(e.spine[i]) ?: "", e.spine[i], fonts, e.styles()), e).pages.size
                } catch (x: Throwable) {
                    if (g != gen) return@Thread
                    1
                }
                post { if (g == gen && c[i] == 0) c[i] = pages }
            }
            Perf.mark("count.all ($n chapitres)", t0)
            post {
                if (g == gen) {
                    Prefs.sp.edit().putString("pages_${b.id}", key + "|" + c.joinToString(",")).apply()
                    updateFooter()
                    if (!animating) present()
                }
            }
        }.start()
    }

    /** Pages du chapitre [i] : exactes si connues, sinon estimées d'après sa taille. */
    private fun pagesOf(i: Int, ratio: Double): Int {
        val e = epub ?: return 1
        return if (counts[i] > 0) counts[i] else maxOf(1, Math.round((e.cum[i + 1] - e.cum[i]) * ratio).toInt())
    }

    private fun ratio(): Double {
        val e = epub ?: return 0.0
        var pages = 0L
        var bytes = 0L
        for (i in counts.indices) if (counts[i] > 0) { pages += counts[i]; bytes += e.cum[i + 1] - e.cum[i] }
        return if (bytes > 0) pages.toDouble() / bytes else 1.0 / 2000
    }

    val title get() = book?.title ?: ""

    /** Numéro de la page courante dans le livre entier (à partir de 1). */
    fun pageNumber(): Int {
        if (ci >= counts.size) return 1
        val r = ratio()
        var n = pi + 1
        for (i in 0 until ci) n += pagesOf(i, r)
        return n
    }

    fun totalPages(): Int {
        val r = ratio()
        var n = 0
        for (i in counts.indices) n += pagesOf(i, r)
        return maxOf(n, pageNumber())
    }

    fun percent(): Int = chapters[ci]?.let { percent(it, pi) } ?: (book?.percent ?: 0)

    /** Va à la page [page] du livre entier (curseur de navigation). */
    fun seekTo(page: Int) {
        if (epub == null || ts == null || counts.isEmpty()) return
        val r = ratio()
        var left = page - 1
        var i = 0
        while (i < counts.size - 1 && left >= pagesOf(i, r)) { left -= pagesOf(i, r); i++ }
        val frac = left.toDouble() / pagesOf(i, r)
        show(i) { lc -> (frac * lc.pages.size).toInt() }
    }

    /** Entrée du sommaire où se trouve la page courante, ou -1. */
    fun tocIndex(): Int {
        val toc = epub?.toc ?: return -1
        var best = -1
        for (k in toc.indices) if (tocCompare(toc[k]) <= 0) best = k else if (toc[k].spine > ci) break
        return best
    }

    /** Position d'une entrée du sommaire par rapport à la page courante (<0 avant, 0 sur la page, >0 après). */
    private fun tocCompare(t: TocItem): Int {
        if (t.spine != ci) return t.spine - ci
        val lc = chapters[ci] ?: return 0
        val a = t.anchor ?: return -pi
        return lc.pageFor(lc.chapter.anchors[a] ?: 0) - pi
    }

    fun chapterTitle(): String = epub?.toc?.getOrNull(tocIndex())?.title ?: ""

    /** Chapitre suivant ([d] = 1) ou début du chapitre courant puis chapitre précédent ([d] = -1). */
    fun chapterStep(d: Int) {
        val e = epub ?: return
        if (!ready || pendingPos != null) return
        val toc = e.toc
        if (toc.isEmpty()) {
            val n = ci + d
            if (n >= 0 && n < e.spine.size) goTo(n, null)
            return
        }
        val target = if (d > 0) toc.firstOrNull { tocCompare(it) > 0 } else toc.lastOrNull { tocCompare(it) < 0 }
        if (target != null) goTo(target.spine, target.anchor)
    }

    private fun updateFooter() {
        val page = held[1]
        val lc = heldLc[1]
        if (page != null && lc == null && page === partial) {
            // Page affichée avant son chapitre : même position qu'à la fermeture du livre.
            footLeft = "${book?.percent ?: 0} %"
            footCenter = "${pageNumber()} / ${totalPages()}"
            return
        }
        if (page == null || lc == null) {
            footLeft = ""
            footCenter = ""
            return
        }
        // L'état de la traduction est porté par l'icône ; « … » tant que la page n'est pas prête.
        val pc = "${percent(lc, heldP[1])} %"
        footLeft = if (Prefs.translate && page.trState == 1) "$pc  …" else pc
        footCenter = "${pageNumber()} / ${totalPages()}"
        onPosition?.invoke()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val f = IntentFilter(Intent.ACTION_TIME_TICK)
        if (Build.VERSION.SDK_INT >= 33) host.registerReceiver(tick, f, Context.RECEIVER_NOT_EXPORTED)
        else host.registerReceiver(tick, f)
    }

    override fun onDetachedFromWindow() {
        host.unregisterReceiver(tick)
        super.onDetachedFromWindow()
    }

    fun goTo(spine: Int, anchor: String?) {
        if (epub == null || ts == null) return
        show(spine) { lc -> if (anchor == null) 0 else lc.pageFor(lc.chapter.anchors[anchor] ?: 0) }
    }

    /**
     * Va à la page qui contient le caractère [offset] du chapitre [spine] (résultat de recherche).
     * [shown] est appelé une fois cette page affichée, avec son compositeur.
     */
    fun goToOffset(spine: Int, offset: Int, shown: ((Typesetter, Page) -> Unit)? = null) {
        val t = ts ?: return
        if (epub == null) return
        show(spine) { lc ->
            val p = lc.pageFor(offset)
            if (shown != null) post { if (ts === t && held[1] === lc.pages[p]) shown(t, lc.pages[p]) }
            p
        }
    }

    /** Numéro, dans le livre entier, de la page [page] (à partir de 0) du chapitre [spine]. */
    fun pageNumberAt(spine: Int, page: Int): Int {
        val r = ratio()
        var n = page + 1
        for (i in 0 until minOf(spine, counts.size)) n += pagesOf(i, r)
        return n
    }

    /** Changement d'état ou de langue de traduction. */
    fun translationChanged(languageChanged: Boolean) {
        if (languageChanged) {
            // Nouvelle langue : nouvelles règles de césure, on recompose tout.
            reflow()
            return
        }
        trGen++
        for (lc in chapters.values) for (p in lc.pages) {
            if (p.trState == 3 || p.trState == 1) p.trState = if (p.tr != null) 2 else 0
        }
        if (!ready || partial != null) return
        if (eink) wipe.finish()
        animating = false
        clearSlot(0)
        clearSlot(2)
        chapters[ci]?.let { renderNow(1, it, pi, true) }
        updateFooter()
        present()
        post(prepareR)
    }

    private fun show(c: Int, pos: (LaidChapter) -> Int) {
        pageTouch.jumped()
        ci = c
        pendingPos = pos
        pendingTurn = 0
        openOff = -1
        partial = null
        val lc = chapters[c]
        if (lc != null) applyPending(lc) else load(c)
    }

    private fun applyPending(lc: LaidChapter) {
        pi = (pendingPos ?: return)(lc).coerceIn(0, lc.pages.size - 1)
        val q = partial
        partial = null
        pendingPos = null
        openOff = -1
        if (eink) wipe.finish()
        animating = false
        val page = lc.pages[pi]
        if (q != null && held[1] === q && page.start == q.start && page.slices.size == q.slices.size && !Prefs.translate) {
            // La page affichée d'avance est bien celle du chapitre composé : rien à redessiner.
            held[1] = page
            heldLc[1] = lc
            heldP[1] = pi
            val l = footLeft
            val c = footCenter
            updateFooter()
            if (l != footLeft || c != footCenter) present()
        } else {
            ready = true
            clearSlot(0)
            clearSlot(2)
            renderNow(1, lc, pi, true)
            updateFooter()
            present()
        }
        savePosition()
        val d = pendingTurn
        pendingTurn = 0
        if (d != 0) turn(d) else post(prepareR)
    }

    /** Affiche la page [page] du chapitre [i], tracée d'avance, avant que le chapitre soit composé. */
    private fun showQuick(i: Int, page: Page, b: Bitmap) {
        if (i != ci || pendingPos == null || openOff < 0 || chapters[i] != null || ts == null) {
            b.recycle()
            quickShown?.countDown()
            return
        }
        for (s in 0..2) clearSlot(s)
        // Tracée avant que la fenêtre existe : l'envoi au processeur graphique est à redemander.
        b.prepareToDraw()
        bmp[1] = b
        held[1] = page
        heldLc[1] = null
        partial = page
        pi = openPage
        ready = true
        updateFooter()
        present()
        // La suite de la composition attend que cette page soit à l'écran.
        choreographer.postFrameCallback { post { quickShown?.countDown() } }
    }

    private fun load(i: Int) {
        val e = epub ?: return
        val t = ts ?: return
        if (i < 0 || i >= e.spine.size || chapters.containsKey(i) || !loading.add(i)) return
        val g = gen
        val pre = parsed[i]
        exec.execute { compose(e, t, i, g, pre, -1) }
    }

    /**
     * Fil de composition : lit, analyse et compose le chapitre [i]. Si [quick] désigne un début
     * de page, cette page seule est d'abord composée, tracée et montrée.
     */
    private fun compose(e: Epub, t: Typesetter, i: Int, g: Int, pre: Chapter?, quick: Int) {
        val lc = try {
            val ch = pre ?: run {
                val html = Perf.time("chapter.read $i") { e.read(e.spine[i]) ?: "" }
                Perf.time("chapter.parse $i (${html.length} car.)") { parseChapter(html, e.spine[i], fonts, e.styles()) }
            }
            var done: Array<StaticLayout?>? = null
            if (quick >= 0) {
                done = arrayOfNulls(ch.blocks.size)
                val page = Perf.time("page.quick $i") { t.quickPage(ch, quick, e, done) }
                if (page != null) {
                    val b = Bitmap.createBitmap(t.w, t.h, Bitmap.Config.ARGB_8888)
                    Perf.time("page.render (d'avance)") { t.draw(Canvas(b), page, e) }
                    val shown = CountDownLatch(1)
                    post {
                        quickShown = shown
                        if (g == gen) showQuick(i, page, b) else { b.recycle(); shown.countDown() }
                    }
                    // Le reste du chapitre peut attendre que la page soit affichée : au lancement,
                    // le thread principal a besoin des cœurs et des caches du moteur de texte.
                    try { shown.await(QUICK_WAIT, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (x: InterruptedException) {}
                }
            }
            Perf.time("chapter.layout $i (${ch.blocks.size} blocs)") { t.layoutChapter(i, ch, e, true, done) }
        } catch (x: Throwable) {
            LaidChapter(i, Chapter(ArrayList(), HashMap(), 0), arrayListOf(Page()))
        }
        post {
            if (g == gen) {
                loading.remove(i)
                chapters[i] = lc
                if (lc.chapter.blocks.isNotEmpty()) parsed[i] = lc.chapter
                if (i < counts.size) counts[i] = lc.pages.size
                if (Perf.recording && !benched && lc.chapter.blocks.size >= 8) bench(t, lc, e)
                if (i == ci && pendingPos != null) applyPending(lc)
                else if (pendingTurn != 0 && i == ci + pendingTurn) {
                    val d = pendingTurn
                    pendingTurn = 0
                    turn(d)
                } else if (ready) prepare()
            }
        }
    }

    /** Charge les chapitres voisins et pré-rend les pages adjacentes, hors animation. */
    private fun prepare() {
        if (!ready || animating || pendingPos != null) return
        chapters.keys.removeAll { it < ci - 1 || it > ci + 1 }
        parsed.keys.removeAll { it < ci - 1 || it > ci + 1 }
        // D'abord dans le sens de la lecture.
        load(ci + lastDir)
        load(ci - lastDir)
        prerender()
        // La page laissée derrière soi est encore là ; si elle manque, elle sert rarement et
        // n'est préparée qu'une fois le lecteur posé.
        removeCallbacks(prevR)
        postDelayed(prevR, 600)
    }

    /** Lance le tracé de la page suivante dans le sens de la lecture. */
    private fun prerender() {
        if (!ready || pendingPos != null) return
        locate(lastDir)?.let { request(1 + lastDir, it.lc, it.p) }
    }

    private fun locate(d: Int): Loc? {
        val lc = chapters[ci] ?: return null
        val p = pi + d
        if (p >= 0 && p < lc.pages.size) return Loc(lc, p)
        val o = chapters[ci + if (p < 0) -1 else 1] ?: return null
        return Loc(o, if (p < 0) o.pages.size - 1 else 0)
    }

    // ---------------------------------------------------------------- pages

    fun turn(d: Int) {
        if (partial != null) {
            // Page affichée d'avance : elle tournera dès que son chapitre sera composé.
            pendingTurn = d
            return
        }
        if (!ready || pendingPos != null) return
        val t0 = if (Perf.on) System.nanoTime() else 0L
        // Tap pendant un balayage : on le termine d'un coup avant d'enchaîner.
        if (animating && eink) wipe.finish()
        animating = false
        val loc = locate(d)
        if (loc == null) {
            // Chapitre voisin pas encore composé : la page tournera dès qu'il sera prêt.
            val nc = ci + d
            if (nc >= 0 && nc < (epub?.spine?.size ?: 0)) { pendingTurn = d; load(nc) }
            return
        }
        val slot = 1 + d
        val page = loc.lc.pages[loc.p]
        val waited = held[slot] !== page
        if (waited) renderNow(slot, loc.lc, loc.p, false)
        if (held[slot] !== page) return
        val from = 1 - d   // la page quittée glisse vers l'emplacement opposé
        // Celle d'avant a pu être affichée jusqu'à l'instant : son bitmap ne resservira qu'après.
        retire(bmp[from])
        bmp[from] = bmp[1]; held[from] = held[1]; heldLc[from] = heldLc[1]; heldP[from] = heldP[1]; jobs[from] = jobs[1]
        bmp[1] = bmp[slot]; held[1] = held[slot]; heldLc[1] = heldLc[slot]; heldP[1] = heldP[slot]; jobs[1] = null
        bmp[slot] = null; held[slot] = null; heldLc[slot] = null; jobs[slot] = null
        ci = loc.lc.index
        pi = loc.p
        lastDir = d
        pageTouch.turned()
        updateFooter()
        if (Prefs.anim) {
            animating = true
            animDir = d
            animStart = 0
            if (eink) {
                wipe.start(d)
                // La page d'après se trace pendant le balayage, deux images plus tard : le
                // bitmap libéré n'est alors plus référencé par l'affichage.
                frames(2) { prerender() }
            } else invalidate()
        } else {
            present()
            post(prepareR)
        }
        savePosition()
        if (Perf.on) Perf.log("turn $d : ${(System.nanoTime() - t0) / 1000} us" + if (waited) " (page tracée à la demande)" else "")
    }

    // ---------------------------------------------------------------- tracé des pages

    /** Tracé d'une page dans un bitmap, exécuté sur [painter]. */
    private inner class Job(
        val page: Page, val shown: Page, val lc: LaidChapter, val p: Int,
        val t: Typesetter, val e: Epub?, val b: Bitmap,
    ) : Runnable {
        val done = CountDownLatch(1)
        var taken = false   // thread principal : bitmap déjà rangé ou rendu

        override fun run() {
            try {
                Perf.time("page.render") {
                    canvas.setBitmap(b)
                    t.draw(canvas, shown, e)
                    canvas.setBitmap(null)
                }
                // Envoi anticipé de l'image au processeur graphique : la page tournée n'a plus à l'attendre.
                b.prepareToDraw()
            } catch (x: Throwable) {
            } finally {
                done.countDown()
            }
        }
    }

    private fun obtain(t: Typesetter): Bitmap {
        while (free.isNotEmpty()) {
            val b = free.removeAt(free.size - 1)
            if (!b.isRecycled && b.width == t.w && b.height == t.h) return b
            b.recycle()
        }
        return Bitmap.createBitmap(t.w, t.h, Bitmap.Config.ARGB_8888)
    }

    /** Rend un bitmap qui n'a jamais été affiché. */
    private fun release(b: Bitmap) {
        if (free.size < 2) free.add(b) else b.recycle()
    }

    /**
     * Rend un bitmap qui a pu être affiché : il ne resservira que deux images plus tard, quand
     * plus aucune liste d'affichage ne le référence (on ne redessine jamais dans une image
     * que le fil de rendu du système peut encore lire).
     */
    private fun retire(b: Bitmap?) {
        b ?: return
        frames(2) { release(b) }
    }

    /** Exécute [f] au début de la [n]-ième image à venir. */
    private fun frames(n: Int, f: () -> Unit) {
        choreographer.postFrameCallback { if (n <= 1) f() else frames(n - 1, f) }
    }

    private fun clearSlot(s: Int) {
        retire(bmp[s])
        bmp[s] = null
        held[s] = null
        heldLc[s] = null
        jobs[s] = null
    }

    /**
     * Demande le tracé de la page [p] de [lc] pour l'emplacement [slot], sans attendre. La page
     * déjà présente reste en place jusqu'à l'arrivée de la nouvelle.
     */
    private fun request(slot: Int, lc: LaidChapter, p: Int, force: Boolean = false): Job? {
        val t = ts ?: return null
        val page = lc.pages[p]
        if (!force) {
            if (held[slot] === page) return null
            jobs[slot]?.let { if (it.page === page) return it }
        }
        // Demandée avant le tracé : la réponse du traducteur peut arriver immédiatement (cache).
        if (Prefs.translate && page.tr == null) requestTranslation(lc, page)
        val shown = if (Prefs.translate) page.tr ?: page else page
        val j = Job(page, shown, lc, p, t, epub, obtain(t))
        jobs[slot] = j
        painter.execute {
            j.run()
            post { landed(j) }
        }
        return j
    }

    /** Un tracé est terminé (thread principal). */
    private fun landed(j: Job) {
        if (j.taken) return
        val s = jobs.indexOf(j)
        if (s < 0) {
            // Plus attendu : la page a été tournée ou recomposée entre-temps.
            j.taken = true
            release(j.b)
            return
        }
        install(s, j)
        if (s == 1) {
            updateFooter()
            present()
        } else if (animating && !eink) invalidate()
    }

    private fun install(s: Int, j: Job) {
        j.taken = true
        jobs[s] = null
        // L'image remplacée peut encore être sous un balayage en cours.
        if (eink && animating && bmp[s] != null) wipe.finish()
        retire(bmp[s])
        bmp[s] = j.b
        held[s] = j.page
        heldLc[s] = j.lc
        heldP[s] = j.p
    }

    /** Trace la page et attend qu'elle soit prête : le thread principal en a besoin tout de suite. */
    private fun renderNow(slot: Int, lc: LaidChapter, p: Int, force: Boolean) {
        val j = request(slot, lc, p, force) ?: return
        try { j.done.await() } catch (x: InterruptedException) {}
        if (!j.taken && jobs[slot] === j) install(slot, j)
    }

    /** Montre la page courante telle quelle, sans animation. */
    private fun present() {
        if (eink) wipe.showAll() else invalidate()
        if (openT0 != 0L && ready && Perf.on) {
            Perf.mark("ouverture → page prête", openT0)
            openT0 = 0
        }
        if (firstShown && ready && Perf.on) {
            firstShown = false
            val t0 = Process.getStartUptimeMillis()
            Perf.log("première page prête : ${SystemClock.uptimeMillis() - t0} ms après le lancement du processus")
            choreographer.postFrameCallback { ns -> Perf.log("première page à l'image : ${ns / 1_000_000 - t0} ms") }
        }
    }

    private fun percent(lc: LaidChapter, p: Int): Int {
        val e = epub ?: return 0
        val i = lc.index
        val inChapter = (e.cum[i + 1] - e.cum[i]) * p / lc.pages.size
        return ((e.cum[i] + inChapter) * 100 / e.total).toInt()
    }

    private var saveDue = false
    private val saveR = Runnable { flushPosition() }

    /**
     * La position est notée tout de suite en mémoire et écrite un instant plus tard : pas
     * d'écriture sur le stockage pendant qu'une page tourne.
     */
    private fun savePosition() {
        val b = book ?: return
        val lc = chapters[ci] ?: return
        b.spine = ci
        b.offset = lc.pages[pi].start
        b.percent = percent(lc, pi)
        if (!saveDue) {
            saveDue = true
            postDelayed(saveR, 400)
        }
    }

    /** Écrit la position en attente (appelé aussi quand l'app passe à l'arrière-plan). */
    fun flushPosition() {
        if (!saveDue) return
        saveDue = false
        removeCallbacks(saveR)
        val b = book ?: return
        Library.savePosition(b)
        // Avec les réglages de composition : à la réouverture, la page s'affiche sans attendre le chapitre.
        if (tsKey.isNotEmpty()) Prefs.sp.edit().putString("pk_${b.id}", "$tsKey|${b.spine}|${b.offset}|$pi").apply()
    }

    // ---------------------------------------------------------------- traduction

    /** Le texte qui précède la page (600 caractères au plus), transmis au traducteur pour la cohérence. */
    private fun contextBefore(lc: LaidChapter, page: Page): String {
        val s = page.slices.firstOrNull { it.layout != null } ?: return ""
        val a = s.layout!!.getLineStart(s.a)
        var ctx = s.block.text.subSequence(maxOf(0, a - 600), a).toString()
        if (ctx.length < 300) {
            val i = lc.chapter.blocks.indexOf(s.block)
            if (i > 0) ctx = lc.chapter.blocks[i - 1].text.toString().takeLast(600 - ctx.length) + "\n" + ctx
        }
        return ctx
    }

    private fun requestTranslation(lc: LaidChapter, page: Page) {
        if (page.trState != 0) return
        val t = ts ?: return
        val src = book?.lang?.ifEmpty { "en" } ?: return
        val tgt = Prefs.target
        if (src == tgt) { page.trState = 2; return }
        val segs = Translator.segments(page, src)
        val idx = segs.indices.filter { segs[it].kind != Block.IMG }
        if (idx.isEmpty()) { page.trState = 2; return }
        page.trState = 1
        val g = gen
        val tg = trGen
        Translator.translate(idx.map { segs[it].text }, contextBefore(lc, page), src, tgt) { res ->
            if (g == gen && tg == trGen) {
                if (res == null) page.trState = 3 else {
                    for (k in idx.indices) {
                        val o = segs[idx[k]]
                        segs[idx[k]] = Block(o.kind, res[k], o.level, o.align, o.quote, o.gap, o.indent)
                    }
                    page.tr = t.fit(segs)
                    page.trState = 2
                }
                // Les emplacements qui montrent ou attendent cette page sont retracés ; l'ancienne
                // image reste affichée jusqu'à l'arrivée de la nouvelle.
                for (s in 0..2) {
                    if (held[s] === page) request(s, heldLc[s]!!, heldP[s], true)
                    else jobs[s]?.let { if (it.page === page) request(s, it.lc, it.p, true) }
                }
                if (held[1] === page) { updateFooter(); if (!animating) present() }
            }
        }
    }

    // ---------------------------------------------------------------- affichage

    override fun onDraw(c: Canvas) {
        val cur = bmp[1]
        // Sur liseuse, la page est portée par les bandes de [wipe], posées par-dessus.
        if (eink || !ready || cur == null) {
            c.drawColor(Theme.bg)
            return
        }
        if (animating) {
            val now = SystemClock.uptimeMillis()
            if (animStart == 0L) animStart = now - 16
            val old = bmp[1 - animDir]
            val t = (now - animStart) / Prefs.slideMs
            if (t < 1f && old != null) {
                val k = 1f - (1f - t) * (1f - t)
                val dx = (k * width).toInt().toFloat()
                if (animDir > 0) {
                    c.drawBitmap(old, -dx, 0f, null)
                    c.drawBitmap(cur, width - dx, 0f, null)
                } else {
                    c.drawBitmap(old, dx, 0f, null)
                    c.drawBitmap(cur, dx - width, 0f, null)
                }
                drawFooter(c)
                postInvalidateOnAnimation()
                return
            }
            animating = false
            post(prepareR)
        }
        c.drawBitmap(cur, 0f, 0f, null)
        drawFooter(c)
    }

    override fun setVisibility(v: Int) {
        super.setVisibility(v)
        if (eink) wipe.visibility = v
    }

    /**
     * Surface de lecture sur liseuse : la page est découpée en bandes verticales, une vue par
     * bande, toujours visibles. Pour tourner la page, les bandes passent une à une à la nouvelle
     * image, depuis le bord : redessiner une bande ne signale à l'écran que ses propres limites
     * (rendre une vue visible ou invisible, à l'inverse, fait redessiner tout son parent). Il
     * n'y a donc aucune image plein écran, ni pendant le balayage ni à la fin. Les étapes sont
     * calées sur les images de l'écran : jamais deux bandes dans la même image.
     */
    val wipe = WipeLayer()

    inner class WipeLayer : ViewGroup(host) {
        private var shown = 0
        private var dir = 1
        private var interval = 0f     // ms entre deux bandes
        private var startNs = 0L      // image qui a montré la première bande
        private var running = false
        @Suppress("DEPRECATION")
        private val frameMs = 1000f / (host.windowManager.defaultDisplay.refreshRate.takeIf { it >= 30f } ?: 60f)
        private val tickR = Choreographer.FrameCallback { ns -> tick(ns) }

        private fun ensure(n: Int) {
            if (childCount == n) return
            removeAllViews()
            for (i in 0 until n) addView(Band())
        }

        fun start(d: Int) {
            val n = Prefs.wipeSteps
            interval = Prefs.wipeMs / n
            ensure(n)
            dir = d
            shown = 0
            startNs = 0
            running = true
            reveal()
            choreographer.postFrameCallback(tickR)
        }

        private fun reveal() {
            (getChildAt(if (dir > 0) childCount - 1 - shown else shown) as Band).show()
            shown++
            if (Perf.on) Perf.log("balayage : bande $shown/$childCount à ${SystemClock.uptimeMillis()} ms")
        }

        private fun tick(ns: Long) {
            if (!running) return
            if (startNs == 0L) {
                // Image où la première bande est dessinée : origine des temps.
                startNs = ns
            } else if (shown < childCount && (ns - startNs) / 1e6f + frameMs / 2 >= shown * interval) reveal()
            if (shown < childCount) choreographer.postFrameCallback(tickR)
            else {
                running = false
                animating = false
                showAll()   // l'heure a pu changer pendant le balayage ; sinon rien n'est redessiné
                this@ReaderView.post(prepareR)
            }
        }

        /** Termine d'un coup un balayage en cours. */
        fun finish() {
            if (!running) return
            running = false
            choreographer.removeFrameCallback(tickR)
            animating = false
            showAll()
            this@ReaderView.post(prepareR)
        }

        /** Toutes les bandes sur la page courante (ou le fond s'il n'y en a pas). */
        fun showAll() {
            if (running) { finish(); return }
            if (childCount == 0) ensure(if (Prefs.anim) Prefs.wipeSteps else 1)
            refreshClock()
            for (i in 0 until childCount) (getChildAt(i) as Band).show()
        }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val n = childCount
            val w = r - l
            for (i in 0 until n) getChildAt(i).layout(i * w / n, 0, (i + 1) * w / n, b - t)
        }

        override fun onViewAdded(child: View?) {
            super.onViewAdded(child)
            (child as? Band)?.show()
        }
    }

    /** Une bande : sa tranche d'une page et du pied de page qui va avec. */
    private inner class Band : View(host) {
        private var src: Bitmap? = null
        private var fl = ""
        private var fc = ""
        private var clk = ""

        /** Passe à la page courante ; ne redessine que si quelque chose change. */
        fun show() {
            val b = if (ready) bmp[1] else null
            if (b === src && footLeft == fl && footCenter == fc && clk == clock) return
            src = b
            fl = footLeft
            fc = footCenter
            clk = clock
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val b = src
            if (b == null || b.isRecycled) {
                c.drawColor(Theme.bg)
                return
            }
            c.translate(-left.toFloat(), 0f)
            c.drawBitmap(b, 0f, 0f, null)
            ts?.drawFooter(c, fl, fc, clk, Prefs.translate)
        }
    }

    /** Relit l'heure si la minute a changé ; vrai si le texte affiché change. */
    private fun refreshClock(): Boolean {
        val now = System.currentTimeMillis()
        if (now / 60000 == clockMinute) return false
        clockMinute = now / 60000
        val c = timeFormat.format(java.util.Date(now))
        if (c == clock) return false
        clock = c
        return true
    }

    /** Le pied de page reste fixe pendant le glissement ; l'heure est relue une fois par minute. */
    private fun drawFooter(c: Canvas) {
        refreshClock()
        ts?.drawFooter(c, footLeft, footCenter, clock, Prefs.translate)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w == ow && h == oh) return
        sized()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val t = insets.displayCutout?.safeInsetTop ?: 0
        if (t != topInset) {
            topInset = t
            if (ts != null) sized()
        }
        return super.onApplyWindowInsets(insets)
    }

    // ---------------------------------------------------------------- liens, notes, appui long

    /** Tout ce que le toucher fait en plus de tourner les pages : voir PageTouch.kt. */
    private val pageTouch = PageTouch(host, this)

    /**
     * La page à l'écran, si c'est le texte d'origine d'un chapitre composé ; null pendant un
     * chargement et sur une page traduite (sa mise en page n'est pas celle du livre).
     */
    fun shownPage(): Shown? {
        val t = ts ?: return null
        val lc = heldLc[1] ?: return null
        val p = held[1] ?: return null
        if (!ready || pendingPos != null || partial != null || (Prefs.translate && p.tr != null)) return null
        return Shown(t, lc, p)
    }

    /** Le geste en cours est pris ailleurs (appui long sur un mot) : ni balayage ni toucher à sa suite. */
    fun consumeTouch() {
        handled = true
    }

    // ---------------------------------------------------------------- toucher

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                handled = false
                // Bord droit (page suivante) et coin haut-gauche (page précédente), hors pied de
                // page : la page tourne dès le contact, sans attendre que le doigt se lève. Le
                // bas du bord gauche reste « page suivante », au lever du doigt, pour qu'un
                // balayage vers la droite puisse encore partir de là.
                val t = ts
                if (t == null || e.y <= height - t.footerZone) {
                    val d = if (e.x > width * 0.75f) 1
                    else if (e.x < width * 0.20f && e.y < height / 3f) -1 else 0
                    // Exception : sur un appel de note, le geste se décide au lever du doigt.
                    if (d != 0 && !pageTouch.note(e.x, e.y)) {
                        handled = true
                        if (Perf.on) Perf.log("toucher → traitement : ${SystemClock.uptimeMillis() - e.eventTime} ms")
                        turn(d)
                    }
                }
                if (!handled) pageTouch.arm(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> if (!handled) {
                pageTouch.moved(e.x - downX, e.y - downY)
                // Balayage : la page tourne dès que le doigt a glissé assez loin, sans attendre
                // qu'il se lève. Vers la gauche : page suivante ; vers la droite : page précédente.
                val dx = e.x - downX
                if (abs(dx) > 28 * density && abs(dx) > abs(e.y - downY) * 1.5f) {
                    handled = true
                    turn(if (dx < 0) 1 else -1)
                }
            }
            MotionEvent.ACTION_UP -> if (!handled) {
                pageTouch.cancel()
                if (abs(e.x - downX) < 20 * density && abs(e.y - downY) < 20 * density) tap(downX, downY)
            }
            MotionEvent.ACTION_CANCEL -> pageTouch.cancel()
        }
        return true
    }

    /** Haut-gauche : page précédente. Centre et centre-haut : options. Pied de page : navigation. Ailleurs : page suivante. */
    private fun zone(x: Float, y: Float): Int {
        val t = ts
        if (t != null && y > height - t.footerZone) return if (x > width - t.translateZone) TRANSLATE else if (x < t.searchZone) SEARCH else FOOTER
        val col = (x * 3 / width).toInt()
        val row = (y * 3 / height).toInt()
        return when {
            col == 0 && row == 0 -> PREV
            col == 1 && row <= 1 -> MENU
            else -> NEXT
        }
    }

    /** Icône du pied de page : active ou coupe la traduction de la page. */
    private fun toggleTranslation() {
        Prefs.translate = !Prefs.translate
        Prefs.save()
        val b = book
        if (Prefs.translate && b != null && b.lang == Prefs.target) {
            host.toast("Ce livre est déjà en ${Translator.label(Prefs.target).lowercase()}")
        }
        translationChanged(false)
    }

    private fun tap(x: Float, y: Float) {
        if (pageTouch.tap(x, y)) return     // lien ou appel de note sous le doigt
        when (zone(x, y)) {
            FOOTER -> host.showNav()
            TRANSLATE -> toggleTranslation()
            SEARCH -> host.showSearch()
            PREV -> turn(-1)
            MENU -> host.showOptions()
            else -> turn(1)
        }
    }

    companion object {
        // Par vitesse (douce, normale, rapide, ultra) : durée du glissement sur smartphone, puis
        // nombre de bandes et durée du balayage sur liseuse. Moins de bandes = moins de
        // rafraîchissements partiels à enchaîner, donc une page affichée plus tôt.
        private val DURATION = floatArrayOf(200f, 140f, 115f, 100f)
        private val EINK_STEPS = intArrayOf(8, 5, 4, 3)
        private const val FOOTER = 1
        private const val PREV = 2
        private const val MENU = 3
        private const val NEXT = 4
        private const val TRANSLATE = 5
        private const val SEARCH = 6
        private val EINK_DURATION = floatArrayOf(240f, 110f, 80f, 50f)
        private const val QUICK_WAIT = 400L
    }
}
