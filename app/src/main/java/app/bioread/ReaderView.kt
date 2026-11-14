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
    // Ajustement des pages traduites à la taille de la page (voir Typesetter.fit).
    private val fitter = Executors.newSingleThreadExecutor()
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

    /**
     * Format des images de page. Sur liseuse, RGB_565 : l'écran n'a que 16 niveaux de gris,
     * le blanc et le noir y sont exacts, et une page pèse moitié moins (3 Mo au lieu de 6 en
     * 1072 × 1448), en mémoire comme sur le processeur graphique. Sur smartphone, ARGB_8888 :
     * en 565, le fond sable de la page s'écarterait de quelques niveaux de celui des panneaux.
     */
    private val pageConfig = if (eink) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888

    /**
     * Sur smartphone, une page finie est une image « matérielle » (Bitmap.Config.HARDWARE) :
     * ses pixels n'existent qu'une fois, en mémoire graphique. Une image ordinaire y est en
     * double : en mémoire native et, dès qu'elle a été affichée, en texture (10 Mo chacune en
     * 1080 × 2340). Le fil [painter] trace dans une seule image ordinaire, [scratch], copiée
     * ensuite. Pas sur liseuse : ses images 565 sont petites, et son affichage particulier
     * n'a pas pu être essayé avec ces images. Pas avant Android 10 non plus : la copie s'y
     * fait sur le fil de rendu du système, qu'elle arrêterait en plein glissement.
     */
    private val hwPages = !eink && Build.VERSION.SDK_INT >= 29
    private var scratch: Bitmap? = null          // réservé au fil [painter]

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
    // Lu aussi par les fils de composition et de comptage : un travail d'une génération passée
    // (réglage changé, livre fermé) s'arrête au paragraphe suivant au lieu d'aller au bout.
    @Volatile private var gen = 0
    // Chapitre que le fil de composition prépare pour l'affichage (-1 = aucun) : le comptage
    // des pages l'attend au lieu de le composer une seconde fois en parallèle.
    @Volatile private var busy = -1
    // La vue a quitté sa fenêtre (activité détruite) : les travaux de fond n'ont plus à l'attendre.
    @Volatile private var detached = false
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
    private var turns = 0        // pages tournées depuis le lancement (diagnostic)
    private var lastDir = 1      // sens de la dernière page tournée
    private var firstShown = true
    var topInset = 0
        private set

    // 0 = précédente, 1 = courante, 2 = suivante, 3 ([AHEAD]) = celle d'après dans le sens de la
    // lecture (voir [ahead]). Un bitmap rangé ici contient une page finie.
    private val bmp = arrayOfNulls<Bitmap>(4)
    private val held = arrayOfNulls<Page>(4)
    private val heldLc = arrayOfNulls<LaidChapter>(4)
    private val heldP = IntArray(4)
    private val jobs = arrayOfNulls<Job>(4)      // tracés en cours, par emplacement

    /**
     * Deux pages d'avance (la suivante et celle d'après) au lieu d'une : une page tournée est
     * alors déjà tracée même si l'on tape plus vite que le fil [painter] ne trace. Réservé à la
     * liseuse : son processeur est lent (le tracé y dure plusieurs dizaines de millisecondes)
     * et ses images de page, en RGB_565, sont petites — quatre y pèsent moins que les trois
     * images ARGB d'avant. Sur smartphone, la page suivante est prête quelques millisecondes
     * après le tour, bien avant qu'un doigt puisse tourner la suivante : une quatrième image
     * de 10 Mo n'y servirait à rien.
     */
    private val ahead = eink
    // Moment du dernier tour de page (diagnostic : délai avant que la page d'après soit prête).
    private var turnNs = 0L
    private val free = ArrayList<Bitmap>(1)      // bitmap réutilisable (plus affiché nulle part)
    // Délestage : la page n'est pas à l'écran (accueil, app en arrière-plan). Seule l'image de la
    // page courante est gardée ; rien n'est préparé d'avance ni mis de côté.
    private var lean = false
    private val canvas = Canvas()                // réservé au fil [painter]

    private var animating = false
    private var animDir = 0
    private var animStart = 0L   // ns, heure d'image (voir [slideR])
    private var slideNs = 0L
    // Glissement (smartphone) : chaque image note son heure puis demande à être redessinée ;
    // [onDraw] place les pages d'après cette heure.
    private val slideR = Choreographer.FrameCallback { ns -> slideNs = ns; invalidate() }
    // Durée d'une image d'écran (ms), d'après la fréquence annoncée par l'écran.
    @Suppress("DEPRECATION")
    private val frameMs = 1000f / (host.windowManager.defaultDisplay.refreshRate.takeIf { it >= 30f } ?: 60f)
    private val prepareR = Runnable { prepare() }
    private val prevR = Runnable {
        if (!lean && ready && !animating && pendingPos == null) locate(-lastDir)?.let { request(1 - lastDir, it.lc, it.p) }
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
            "${Prefs.weight},${Prefs.spacing},${Prefs.stretch},${Prefs.sharp},${fonts.family.id}" + (if (Prefs.faithful) ",css" else "") +
            if (Prefs.wordwise) ",ww" else ""

    private fun setTypesetter(w: Int, h: Int, top: Int, lang: String) {
        ts = Typesetter(fonts, w, h, top, density, lang)
        tsKey = layoutKey(w, h, top)
    }

    fun open(b: BookEntry) {
        close()
        WordWise.forgetNames()
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
        val pk = Prefs.pos.getString("pk_${b.id}", null)?.split('|')
        if (t != null && !Prefs.translate && pk != null && pk.size == 4 && pk[0] == tsKey &&
            pk[1] == b.spine.toString() && pk[2] == b.offset.toString()
        ) {
            quick = b.offset
            openPage = pk[3].toIntOrNull() ?: 0
        } else if (t != null && !Prefs.translate && b.offset == 0) {
            // Début de chapitre (livre jamais ouvert) : toujours un début de page, quels que
            // soient les réglages. Dans un livre d'un seul très gros chapitre, la première page
            // n'attend plus la composition de tout le texte.
            quick = 0
            openPage = 0
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
        for (s in bmp.indices) clearSlot(s)
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
        for (s in bmp.indices) clearSlot(s)
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
        val saved = Prefs.pages.getString("pages_${b.id}", null)?.split('|')
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
                // Le chapitre que le lecteur attend est déjà en cours de composition : son nombre
                // de pages arrivera avec lui.
                while (busy == i && g == gen && !detached) try { Thread.sleep(50) } catch (x: InterruptedException) {}
                if (g != gen || detached) return@Thread
                if (c[i] > 0) continue
                val pages = try {
                    val ch = parseChapter(e.read(e.spine[i]) ?: "", e.spine[i], fonts, e.styles())
                    t2.layoutChapter(i, ch, e, stop = { g != gen || detached }).pages.size
                } catch (x: Throwable) {
                    if (g != gen || detached) return@Thread
                    1
                }
                post { if (g == gen && c[i] == 0) c[i] = pages }
            }
            Perf.mark("count.all ($n chapitres)", t0)
            post {
                if (g == gen) {
                    Prefs.pages.edit().putString("pages_${b.id}", key + "|" + c.joinToString(",")).apply()
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
        detached = false
        val f = IntentFilter(Intent.ACTION_TIME_TICK)
        if (Build.VERSION.SDK_INT >= 33) host.registerReceiver(tick, f, Context.RECEIVER_NOT_EXPORTED)
        else host.registerReceiver(tick, f)
    }

    override fun onDetachedFromWindow() {
        detached = true
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
        clearAround()
        chapters[ci]?.let { renderNow(1, it, pi, true) }
        updateFooter()
        present()
        post(prepareR)
    }

    /**
     * Le vocabulaire du lecteur a changé (traduction sous les mots difficiles) : la mise en page
     * reste la même, seules les pages sont retracées, la courante d'abord.
     */
    fun glossChanged() {
        if (!ready || partial != null) return
        if (eink) wipe.finish()
        animating = false
        clearSlot(0)
        clearSlot(2)
        chapters[ci]?.let { renderNow(1, it, pi, true) }
        present()
        post(prepareR)
    }

    /** Le livre ouvert reçoit-il les traductions sous les mots difficiles ? */
    fun glossable(): Boolean = book?.let { WordWise.applies(it.lang) } ?: false

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
            clearAround()
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
        for (s in bmp.indices) clearSlot(s)
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
        // Demande d'une génération passée (plusieurs crans d'un réglage enchaînés) : rien à faire.
        if (g != gen) return
        busy = i
        val lc = try {
            val ch = pre ?: run {
                val html = Perf.time("chapter.read $i") { e.read(e.spine[i]) ?: "" }
                Perf.time("chapter.parse $i (${html.length} car.)") { parseChapter(html, e.spine[i], fonts, e.styles()) }
            }
            // Noms propres du chapitre (Eve) : jamais traduits, même quand ils ouvrent une phrase.
            if (book?.let { WordWise.applies(it.lang) } == true) WordWise.learnNames(ch.blocks)
            var done: Array<StaticLayout?>? = null
            if (quick >= 0) {
                done = arrayOfNulls(ch.blocks.size)
                val page = Perf.time("page.quick $i") { t.quickPage(ch, quick, e, done) }
                if (page != null) {
                    val b = Bitmap.createBitmap(t.w, t.h, pageConfig)
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
            Perf.time("chapter.layout $i (${ch.blocks.size} blocs)") { t.layoutChapter(i, ch, e, true, done) { g != gen } }
        } catch (x: Throwable) {
            LaidChapter(i, Chapter(ArrayList(), HashMap(), 0), arrayListOf(Page()))
        }
        if (g != gen) {
            // Abandonné en route : personne n'attend ce résultat.
            if (busy == i) busy = -1
            return
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
                } else if (ready) {
                    // Pendant une animation, [prepare] attendrait sa fin : les pages qui
                    // dépendent de ce chapitre se tracent tout de suite.
                    if (animating) prerender() else prepare()
                }
            }
            if (busy == i) busy = -1
        }
    }

    /**
     * La page n'est plus à l'écran : rend les images des pages voisines et celle gardée en
     * réserve (les deux tiers de la mémoire d'images). [deep] : l'app est en arrière-plan, les
     * chapitres voisins déjà composés sont rendus aussi. Tout revient seul avec [wake].
     */
    fun trim(deep: Boolean) {
        if (!lean) {
            lean = true
            removeCallbacks(prepareR)
            removeCallbacks(prevR)
            if (animating) {
                if (eink) wipe.finish() else { animating = false; invalidate() }
            }
            clearAround()
            for (b in free) b.recycle()
            free.clear()
            if (hwPages) painter.execute { scratch?.recycle(); scratch = null }
        }
        if (deep && ready && pendingPos == null) {
            chapters.keys.removeAll { it != ci }
            parsed.keys.removeAll { it != ci }
        }
    }

    /** La page revient à l'écran : les pages voisines sont de nouveau préparées. */
    fun wake() {
        if (!lean) return
        lean = false
        if (ready) post(prepareR)
    }

    /** Charge les chapitres voisins et pré-rend les pages adjacentes, hors animation. */
    private fun prepare() {
        if (lean || !ready || animating || pendingPos != null) return
        // Pages voisines tracées pendant l'animation qui vient de finir : envoyées maintenant au
        // processeur graphique (sans effet si elles y sont déjà).
        bmp[0]?.prepareToDraw()
        bmp[2]?.prepareToDraw()
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

    /**
     * Lance le tracé de la page suivante dans le sens de la lecture, puis (voir [ahead]) de
     * celle d'après : le fil [painter] les prend dans cet ordre.
     */
    private fun prerender() {
        if (lean || !ready || pendingPos != null) return
        // Le chapitre suivant, s'il manque, se compose dès maintenant : quand les pages tournent
        // en rafale, chaque tour interrompt l'animation du précédent et [prepare] n'est jamais
        // appelé ; au bout du chapitre, la page attendrait sa composition puis son tracé.
        load(ci + lastDir)
        locate(lastDir)?.let { request(1 + lastDir, it.lc, it.p) }
        if (ahead) locate(2 * lastDir)?.let { request(AHEAD, it.lc, it.p) }
    }

    /** La page à [d] pages de la courante (|d| ≤ 2), dans ce chapitre ou un voisin déjà composé. */
    private fun locate(d: Int): Loc? {
        val lc = chapters[ci] ?: return null
        val p = pi + d
        if (p >= 0 && p < lc.pages.size) return Loc(lc, p)
        val o = chapters[ci + if (p < 0) -1 else 1] ?: return null
        val q = if (p < 0) o.pages.size + p else p - lc.pages.size
        return if (q >= 0 && q < o.pages.size) Loc(o, q) else null
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
        if (ahead) {
            // La page d'après, tracée (ou en cours de tracé) d'avance dans ce sens, devient la
            // suivante ; dans l'autre sens, elle est trop loin et son image est rendue.
            val nx = if (d == lastDir) locate(d) else null
            val np = nx?.let { it.lc.pages[it.p] }
            if (np != null && (held[AHEAD] === np || jobs[AHEAD]?.page === np)) {
                bmp[slot] = bmp[AHEAD]; held[slot] = held[AHEAD]; heldLc[slot] = heldLc[AHEAD]; heldP[slot] = heldP[AHEAD]; jobs[slot] = jobs[AHEAD]
                bmp[AHEAD] = null; held[AHEAD] = null; heldLc[AHEAD] = null; jobs[AHEAD] = null
            } else clearSlot(AHEAD)
        }
        lastDir = d
        if (Perf.on) turnNs = System.nanoTime()
        pageTouch.turned()
        updateFooter()
        if (Prefs.anim) {
            animating = true
            animDir = d
            animStart = 0
            if (eink) wipe.start(d) else {
                slideNs = 0
                choreographer.removeFrameCallback(slideR)
                choreographer.postFrameCallback(slideR)
            }
            // La page d'après se trace pendant l'animation (glissement ou balayage), sans en
            // attendre la fin, deux images plus tard : le bitmap libéré n'est alors plus
            // référencé par l'affichage. Son envoi au processeur graphique attend, lui, la fin
            // de l'animation (voir [landed]).
            frames(2) { prerender() }
        } else {
            present()
            // Même délai : la page d'après reprend l'image libérée au lieu d'en créer une.
            frames(2) { prepare() }
        }
        savePosition()
        if (Perf.on) {
            Perf.log("turn $d : ${(System.nanoTime() - t0) / 1000} us" + if (waited) " (page tracée à la demande)" else "")
            // Suivi d'endurance : la mémoire toutes les 50 pages, une fois la page posée.
            if (++turns % 50 == 0) postDelayed({ Perf.log("après $turns pages, ${Diag.memory()}") }, 1500)
        }
    }

    // ---------------------------------------------------------------- tracé des pages

    /** Tracé d'une page dans un bitmap, exécuté sur [painter]. */
    private inner class Job(
        val page: Page, val shown: Page, val lc: LaidChapter, val p: Int,
        val t: Typesetter, val e: Epub?, private val target: Bitmap?,
    ) : Runnable {
        val done = CountDownLatch(1)
        var taken = false   // thread principal : bitmap déjà rangé ou rendu
        // L'image finie : [target], ou la copie matérielle de [scratch] (voir [hwPages]).
        @Volatile var b: Bitmap? = null

        override fun run() {
            try {
                val dst = target ?: scratchFor(t)
                try {
                    Perf.time("page.render") {
                        canvas.setBitmap(dst)
                        t.draw(canvas, shown, e, shown === page)
                        canvas.setBitmap(null)
                    }
                } catch (x: Throwable) {
                    try { canvas.setBitmap(null) } catch (y: Throwable) {}
                }
                b = if (target != null) target else {
                    // Copie impossible (mémoire graphique épuisée) : l'image ordinaire est
                    // cédée telle quelle, une autre servira au prochain tracé.
                    Perf.time("page.copy") { try { dst.copy(Bitmap.Config.HARDWARE, false) } catch (x: Throwable) { null } }
                        ?: dst.also { scratch = null }
                }
            } finally {
                done.countDown()
            }
        }
    }

    /** L'image de travail du fil [painter], à la taille des pages. */
    private fun scratchFor(t: Typesetter): Bitmap {
        scratch?.let { if (!it.isRecycled && it.width == t.w && it.height == t.h) return it else it.recycle() }
        return Bitmap.createBitmap(t.w, t.h, pageConfig).also { scratch = it }
    }

    private fun obtain(t: Typesetter): Bitmap {
        while (free.isNotEmpty()) {
            val b = free.removeAt(free.size - 1)
            if (!b.isRecycled && b.width == t.w && b.height == t.h && b.config == pageConfig) return b
            b.recycle()
        }
        return Bitmap.createBitmap(t.w, t.h, pageConfig)
    }

    /** Rend un bitmap qui n'a jamais été affiché. */
    private fun release(b: Bitmap) {
        // Une seule image en réserve : en régime établi, celle que la page quittée vient de rendre.
        // Les images matérielles ne se redessinent pas : elles sont rendues tout de suite.
        if (!lean && !hwPages && free.isEmpty() && !b.isRecycled) free.add(b) else b.recycle()
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

    /** Rend les images de toutes les pages sauf la courante. */
    private fun clearAround() {
        for (s in bmp.indices) if (s != 1) clearSlot(s)
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
        val j = Job(page, shown, lc, p, t, epub, if (hwPages) null else obtain(t))
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
        if (s < 0 || j.b == null) {
            // Plus attendu (la page a été tournée ou recomposée entre-temps), ou tracé manqué.
            j.taken = true
            if (s >= 0) jobs[s] = null
            j.b?.let { release(it) }
            return
        }
        install(s, j)
        if (s == 1) {
            updateFooter()
            present()
            return
        }
        if (Perf.on && turnNs != 0L && (s == 1 + lastDir || s == AHEAD)) {
            val what = if (s == AHEAD) "page d'après" else "page suivante"
            Perf.log("$what prête ${(System.nanoTime() - turnNs) / 1000} us après le tour")
        }
        // Envoi anticipé de l'image au processeur graphique, pour que la page tournée n'ait pas
        // à l'attendre ; pendant une animation, il lui prendrait une image : il attend sa fin
        // (voir [prepare]).
        if (animating) {
            if (!eink) invalidate()
        } else if (s != AHEAD) j.b?.prepareToDraw()
    }

    private fun install(s: Int, j: Job) {
        j.taken = true
        jobs[s] = null
        // L'image remplacée peut encore être sous un balayage en cours (pas celle d'après :
        // elle n'est jamais affichée).
        if (eink && animating && s != AHEAD && bmp[s] != null) wipe.finish()
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
        if (!j.taken && jobs[slot] === j && j.b != null) install(slot, j)
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
            postDelayed(saveR, 1000)
        }
    }

    /** Écrit la position en attente (appelé aussi quand l'app passe à l'arrière-plan). */
    fun flushPosition() {
        if (!saveDue) return
        saveDue = false
        removeCallbacks(saveR)
        val b = book ?: return
        // Avec les réglages de composition : à la réouverture, la page s'affiche sans attendre le
        // chapitre. Une seule écriture pour les deux.
        Library.savePosition(b, if (tsKey.isNotEmpty()) "$tsKey|${b.spine}|${b.offset}|$pi" else null)
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
            if (g != gen || tg != trGen) return@translate
            if (res == null) {
                page.trState = 3
                translated(page)
                return@translate
            }
            for (k in idx.indices) {
                val o = segs[idx[k]]
                segs[idx[k]] = Block(o.kind, res[k], o.level, o.align, o.quote, o.gap, o.indent)
            }
            // L'ajustement à la page (plusieurs compositions d'essai) se fait hors du thread principal.
            fitter.execute {
                val tr = try { t.fit(segs) } catch (x: Exception) { null }
                post {
                    if (g != gen || tg != trGen) return@post
                    if (tr == null) page.trState = 3 else { page.tr = tr; page.trState = 2 }
                    translated(page)
                }
            }
        }
    }

    /**
     * La traduction de [page] est arrivée (ou a échoué) : les emplacements qui la montrent ou
     * l'attendent sont retracés ; l'ancienne image reste affichée jusqu'à l'arrivée de la nouvelle.
     */
    private fun translated(page: Page) {
        for (s in bmp.indices) {
            if (held[s] === page) request(s, heldLc[s]!!, heldP[s], true)
            else jobs[s]?.let { if (it.page === page) request(s, it.lc, it.p, true) }
        }
        if (held[1] === page) { updateFooter(); if (!animating) present() }
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
            // Heure de l'image (le signal de l'écran, voir [slideR]), et non celle du tracé : le
            // glissement avance alors du même pas à chaque image, quel que soit le retard du
            // thread principal à dessiner. La première image montre déjà une image d'avance.
            val now = if (slideNs != 0L) slideNs else System.nanoTime()
            if (animStart == 0L) animStart = now - (frameMs * 1e6f).toLong()
            val old = bmp[1 - animDir]
            val t = (now - animStart) / 1e6f / Prefs.slideMs
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
                choreographer.postFrameCallback(slideR)
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
        private var interval = 0f     // ms entre deux bandes : un nombre entier d'images
        private var startNs = 0L      // image qui a montré la première bande
        private var running = false
        private val tickR = Choreographer.FrameCallback { ns -> tick(ns) }

        private fun ensure(n: Int) {
            if (childCount == n) return
            removeAllViews()
            for (i in 0 until n) addView(Band())
        }

        fun start(d: Int) {
            val n = Prefs.wipeSteps
            // Le même nombre d'images entre deux bandes, du début à la fin. Un intervalle qui
            // tombe entre deux (27,5 ms à 60 Hz) donnait des pas inégaux : 2, 1 puis 2 images.
            interval = maxOf(1, Math.round(Prefs.wipeMs / n / frameMs)) * frameMs
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

    /** Mode sélection sur le fond d'une feuille : voir [PageTouch.pickOrClose]. */
    fun pickOrClose(v: View) = pageTouch.pickOrClose(v)

    /** Le geste en cours est pris ailleurs (appui long sur un mot) : ni balayage ni toucher à sa suite. */
    fun consumeTouch() {
        handled = true
    }

    // ---------------------------------------------------------------- toucher

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Choix d'un mot en cours (après un appui long) : le doigt désigne, son lever valide.
        if (pageTouch.selecting) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> pageTouch.select(e.x, e.y)
                MotionEvent.ACTION_UP -> pageTouch.finish()
                MotionEvent.ACTION_CANCEL -> pageTouch.abort()
            }
            return true
        }
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
                    // Exception : sur un appel de note, le geste se décide au lever du doigt. Les
                    // mots de ces zones se choisissent en glissant depuis le centre (voir PageTouch).
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
        if (t != null && y > height - t.footerZone) return if (x > width - t.translateZone) TRANSLATE else if (x < t.searchZone) SEARCH else if (x < t.refreshZone) REFRESH else FOOTER
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
            REFRESH -> host.flashScreen()
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
        private const val REFRESH = 7
        private val EINK_DURATION = floatArrayOf(240f, 110f, 80f, 50f)
        private const val QUICK_WAIT = 400L
        private const val AHEAD = 3      // emplacement de la page d'après la suivante
    }
}
