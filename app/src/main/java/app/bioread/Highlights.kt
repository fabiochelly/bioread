package app.bioread

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Passage surligné : caractères [start, end) du chapitre [spine] (les positions de [Block.start]),
 * avec son texte — qui sert à l'export et à retrouver le passage si la mise en forme du chapitre
 * change —, une note éventuelle, et de quoi le situer dans une liste (chapitre, pourcentage).
 * Immuable : une modification remplace l'objet.
 */
class Highlight(
    val spine: Int, val start: Int, val end: Int, val text: String, val note: String,
    val chapter: String, val percent: Int, val time: Long,
)

/**
 * Surlignages et notes du livre ouvert. Un fichier JSON par livre dans le stockage de l'app
 * (files/marks), relu à l'ouverture du livre ; à chaque changement, il est réécrit et une copie
 * lisible est déposée dans Download/bioread-notes/<titre>.md (Markdown, par USB ou dans toute
 * app de notes), comme le carnet des mots consultés ([WordLog]).
 *
 * La liste est remplacée d'un bloc à chaque changement : le fil qui trace les pages la lit sans verrou.
 */
object Highlights {
    private lateinit var app: Context
    private val io = Executors.newSingleThreadExecutor()
    private var book: BookEntry? = null

    @Volatile
    var list: List<Highlight> = emptyList()
        private set

    fun init(c: Context) {
        app = c.applicationContext
    }

    private fun file(id: String) = File(File(app.filesDir, "marks").apply { mkdirs() }, "$id.json")

    /** Charge les surlignages de [b] (quelques kilo-octets au plus). */
    fun open(b: BookEntry) {
        book = b
        val out = ArrayList<Highlight>()
        try {
            val f = file(b.id)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        Highlight(
                            o.getInt("spine"), o.getInt("start"), o.getInt("end"), o.getString("text"), o.optString("note"),
                            o.optString("chapter"), o.optInt("percent"), o.optLong("time"),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Fichier illisible : le livre s'ouvre sans surlignages, le fichier n'est pas touché.
        }
        list = out
    }

    fun close() {
        book = null
        list = emptyList()
    }

    /** Le surlignage qui couvre le caractère [offset] du chapitre [spine]. */
    fun at(spine: Int, offset: Int): Highlight? = list.firstOrNull { it.spine == spine && offset >= it.start && offset < it.end }

    /**
     * Enregistre [h], à la place de [old] s'il s'agit d'une modification. Les surlignages qu'il
     * chevauche sont absorbés : le passage devient leur réunion, leurs notes sont gardées.
     */
    fun put(old: Highlight?, h: Highlight, ch: Chapter) {
        var start = h.start
        var end = h.end
        var note = h.note
        val rest = ArrayList<Highlight>()
        for (x in list) {
            if (x === old) continue
            if (x.spine == h.spine && x.start < end && x.end > start) {
                start = minOf(start, x.start)
                end = maxOf(end, x.end)
                if (x.note.isNotEmpty() && x.note != note) note = if (note.isEmpty()) x.note else note + "\n" + x.note
            } else rest.add(x)
        }
        val merged = if (start == h.start && end == h.end && note == h.note) h
        else Highlight(h.spine, start, end, ch.slice(start, end), note, h.chapter, h.percent, h.time)
        rest.add(merged)
        rest.sortWith(compareBy<Highlight> { it.spine }.thenBy { it.start })
        list = rest
        save()
    }

    fun remove(h: Highlight) {
        list = list.filter { it !== h }
        save()
    }

    /**
     * Le chapitre [spine] vient d'être analysé : un surlignage dont le texte n'est plus à sa place
     * (mise en forme « Fidèle au livre » activée ou coupée entre-temps, fichier corrigé) est
     * recherché dans le chapitre et recalé sur l'occurrence la plus proche.
     */
    fun anchor(spine: Int, ch: Chapter) {
        if (list.none { it.spine == spine }) return
        var changed = false
        val out = list.map { h ->
            if (h.spine != spine || ch.slice(h.start, h.end) == h.text) return@map h
            val key = h.text.substringBefore('\n').take(80)
            var best = -1
            if (key.length >= 4) for (b in ch.blocks) {
                if (b.kind == Block.IMG) continue
                val t = b.text.toString()
                var i = t.indexOf(key)
                while (i >= 0) {
                    val at = b.start + i
                    if (best < 0 || Math.abs(at - h.start) < Math.abs(best - h.start)) best = at
                    i = t.indexOf(key, i + 1)
                }
            }
            if (best < 0) return@map h
            changed = true
            val end = minOf(best + h.end - h.start, ch.length)
            Highlight(spine, best, end, ch.slice(best, end), h.note, h.chapter, h.percent, h.time)
        }
        if (changed) {
            list = out
            save()
        }
    }

    private fun save() {
        val b = book ?: return
        val items = list
        val f = file(b.id)
        io.execute {
            try {
                val arr = JSONArray()
                for (h in items) arr.put(
                    JSONObject().put("spine", h.spine).put("start", h.start).put("end", h.end).put("text", h.text)
                        .put("note", h.note).put("chapter", h.chapter).put("percent", h.percent).put("time", h.time)
                )
                // Écrit à côté puis renommé : une coupure en cours d'écriture ne perd pas les surlignages.
                val tmp = File(f.path + ".tmp")
                tmp.writeText(arr.toString())
                tmp.renameTo(f)
                export(b, items)
            } catch (e: Exception) {
            }
        }
    }

    /** Dossier de la copie lisible : Download/bioread-notes, ou le dossier de l'app sans accès au stockage partagé. */
    private fun exportDir(): File? {
        @Suppress("DEPRECATION")
        val shared = File(Environment.getExternalStorageDirectory(), "Download/bioread-notes")
        if (Library.hasAccess(app) && (shared.isDirectory || shared.mkdirs())) return shared
        return app.getExternalFilesDir("notes")
    }

    private fun exportName(b: BookEntry) = b.title.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), " ").trim().take(80).ifEmpty { b.id } + ".md"

    /** Où lire la copie des notes du livre ouvert (pour l'afficher dans la liste). */
    fun exportPath(): String {
        val b = book ?: return ""
        val f = File(exportDir() ?: return "", exportName(b))
        @Suppress("DEPRECATION")
        return f.path.removePrefix(Environment.getExternalStorageDirectory().path + "/")
    }

    private fun export(b: BookEntry, items: List<Highlight>) {
        val f = File(exportDir() ?: return, exportName(b))
        if (items.isEmpty()) {
            f.delete()
            return
        }
        val date = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.FRENCH)
        val sb = StringBuilder()
        sb.append("# ").append(b.title).append('\n')
        if (b.author.isNotEmpty()) sb.append('*').append(b.author).append("*\n")
        var chapter: String? = null
        for (h in items) {
            if (h.chapter != chapter) {
                chapter = h.chapter
                if (h.chapter.isNotEmpty()) sb.append("\n## ").append(h.chapter).append('\n')
            }
            sb.append('\n')
            for (line in h.text.split('\n')) sb.append("> ").append(line.trim()).append("\n>\n")
            sb.setLength(sb.length - 2)   // pas de ligne vide en fin de citation
            if (h.note.isNotEmpty()) sb.append('\n').append(h.note.trim()).append('\n')
            sb.append("\n— ").append(h.percent).append(" %, ").append(date.format(java.util.Date(h.time))).append('\n')
        }
        f.writeText(sb.toString())
    }

    // ---------------------------------------------------------------- tracé

    private val fill = Paint().apply {
        // Liseuse : gris clair, que l'encre rend en aplat ; smartphone : jaune paille sur le fond sable.
        color = if (Theme.eink) 0xFFD4D4D4.toInt() else 0xFFF3D36B.toInt()
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.fg }

    /** Aplats des surlignages du chapitre [spine] sur la page [p], à tracer avant le texte. */
    fun draw(c: Canvas, ts: Typesetter, p: Page, spine: Int, density: Float) {
        val items = list
        if (items.isEmpty()) return
        var end = -1
        for (h in items) {
            if (h.spine != spine || h.end <= p.start) continue
            if (end < 0) end = pageEnd(p)
            if (h.start >= end) continue
            val rects = ts.markRects(p, h.start, h.end - h.start)
            for (r in rects) {
                val a = r.height()   // du haut de la ligne à la ligne de base
                c.drawRect(r.left - 1, r.top + a * 0.06f, r.right + 1, r.bottom + a * 0.3f, fill)
            }
            // Une note : un point dans la marge, à la hauteur de la première ligne du passage.
            if (h.note.isNotEmpty() && h.start >= p.start && rects.isNotEmpty()) {
                val r = rects[0]
                c.drawCircle(ts.w - ts.mx / 2f, r.top + r.height() * 0.62f, minOf(3f * density, ts.mx / 4f), dot)
            }
        }
    }

    /** Position, dans le chapitre, de la fin du texte de la page. */
    private fun pageEnd(p: Page): Int {
        for (i in p.slices.indices.reversed()) {
            val s = p.slices[i]
            val l = s.layout ?: continue
            return s.block.start + l.getLineEnd(s.b - 1)
        }
        return p.start + 1
    }
}

/** Le bloc de texte du chapitre qui contient le caractère [offset], ou null. */
fun Chapter.blockAt(offset: Int): Block? {
    var lo = 0
    var hi = blocks.size - 1
    if (hi < 0) return null
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (blocks[mid].start <= offset) lo = mid else hi = mid - 1
    }
    return blocks[lo].takeIf { it.kind != Block.IMG }
}

/** Le texte du chapitre entre les positions [a] et [b], un paragraphe par ligne. */
fun Chapter.slice(a: Int, b: Int): String {
    val sb = StringBuilder()
    for (blk in blocks) {
        if (blk.start >= b) break
        if (blk.kind == Block.IMG || blk.hidden || blk.start + blk.length <= a) continue
        val from = maxOf(a - blk.start, 0)
        val to = minOf(b - blk.start, blk.text.length)
        if (to <= from) continue
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(blk.text, from, to)
    }
    // Sans les caractères invisibles du livre (trait d'union virtuel, espace sans largeur).
    var i = 0
    while (i < sb.length) if (sb[i] == '\u00AD' || sb[i] == '\uFEFF') sb.deleteCharAt(i) else i++
    return sb.toString().trim()
}

/**
 * Feuille de surlignage, ouverte depuis la feuille d'un mot. Le passage part du mot choisi (ou
 * du surlignage existant) ; tant que la feuille est ouverte, toucher un autre mot de la page
 * l'étend jusqu'à lui, et toucher un mot à l'intérieur le raccourcit par le bout le plus proche.
 * Aucune poignée à faire glisser : des touchers seulement, ce que l'encre électronique suit sans
 * peine. « Phrase » arrondit le passage aux phrases entières, « Note » y attache un texte.
 */
class HighlightView(
    private val host: MainActivity,
    private val reader: ReaderView,
    private val shown: Shown,
    private var start: Int,
    private var end: Int,
    private val old: Highlight?,
    private val lang: String,
) : FrameLayout(host) {
    private val d = resources.displayMetrics.density
    private val spine = shown.lc.index
    private val chapter = shown.lc.chapter
    private val quote = TextView(host)
    private val rects = ArrayList<RectF>()
    private val tint = Paint().apply { color = if (Theme.eink) 0x38000000 else 0x66F0B429 }

    init {
        setWillNotDraw(false)
        val first = shown.ts.markRects(shown.page, start, end - start)
        val low = first.isNotEmpty() && first[0].bottom > reader.height * 0.55f
        val side = maxOf(shown.ts.mx, dp(16))
        val panel = LinearLayout(host)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Theme.bg)
        panel.isClickable = true
        if (low) panel.setPadding(0, reader.topInset, 0, 0)

        val head = LinearLayout(host).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(side, dp(6), dp(8), 0)
            addView(label(if (old == null) "Surligner" else "Surlignage", 18f, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(IconButton(host, IconButton.CLOSE) { host.hideSheet() }.apply { contentDescription = "Fermer" })
        }
        panel.addView(head)
        panel.addView(label("Touchez un mot de la page pour étendre ou raccourcir le passage.", 13f, false).apply {
            maxLines = 2
            setPadding(side, 0, side, dp(6))
        })
        quote.typeface = host.fonts.italic
        quote.textSize = 16f
        quote.setTextColor(Theme.ui)
        quote.setLineSpacing(0f, 1.25f)
        quote.maxLines = 4
        quote.ellipsize = TextUtils.TruncateAt.END
        quote.setPadding(side, 0, side, dp(10))
        panel.addView(quote)

        val row = LinearLayout(host)
        row.setPadding(dp(12), 0, dp(12), dp(14))
        val buttons = ArrayList<View>()
        buttons.add(button("Phrase") { sentences() })
        buttons.add(button("Note") { askNote() })
        if (old != null) buttons.add(button("Retirer") {
            Highlights.remove(old)
            host.hideSheet()
            reader.marksChanged()
        })
        buttons.add(button("OK") { save(old?.note ?: "") })
        for ((i, b) in buttons.withIndex()) row.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) leftMargin = dp(8) })
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
        refresh()
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun label(s: String, size: Float, bold: Boolean) = TextView(host).apply {
        text = s
        typeface = if (bold) host.fonts.uiBold else host.fonts.ui
        textSize = size
        setTextColor(Theme.ui)
    }

    private fun button(s: String, onClick: () -> Unit) = label(s, 15f, true).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setPadding(dp(6), 0, dp(6), 0)
        background = GradientDrawable().apply {
            setColor(Theme.bg)
            setStroke(maxOf(1, dp(1)), Theme.ui)
            cornerRadius = 6 * d
        }
        setOnClickListener { onClick() }
    }

    /** Le passage a changé : son voile sur la page et son texte dans la feuille. */
    private fun refresh() {
        rects.clear()
        for (r in shown.ts.markRects(shown.page, start, end - start)) {
            val a = r.height()
            rects.add(RectF(r.left - 1, r.top + a * 0.06f, r.right + 1, r.bottom + a * 0.3f))
        }
        quote.text = chapter.slice(start, end).replace('\n', ' ')
        val note = old?.note ?: ""
        if (note.isNotEmpty()) quote.text = "${quote.text}\n✎ $note"
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        for (r in rects) c.drawRect(r, tint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val w = try { shown.ts.wordNear(shown.page, e.x, e.y, lang, 14 * d) } catch (x: Exception) { null } ?: return true
        val a = w.block.start + w.start
        val b = w.block.start + w.end
        when {
            b <= start -> start = a
            a >= end -> end = b
            // Dans le passage : il est raccourci par le bout le plus proche du mot touché.
            a - start < end - b -> start = a
            else -> end = b
        }
        refresh()
        return true
    }

    /** Arrondit le passage aux phrases entières. */
    private fun sentences() {
        chapter.blockAt(start)?.let { b ->
            val s = Words.sentence(b.text.toString(), (start - b.start).coerceIn(0, maxOf(0, b.text.length - 1)), lang)
            start = minOf(start, b.start + s[0])
        }
        chapter.blockAt(end - 1)?.let { b ->
            val s = Words.sentence(b.text.toString(), (end - 1 - b.start).coerceIn(0, maxOf(0, b.text.length - 1)), lang)
            end = maxOf(end, b.start + s[1])
        }
        refresh()
    }

    private fun askNote() {
        val input = EditText(host).apply {
            setText(old?.note ?: "")
            hint = "Note"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 6
            setSelection(text.length)
        }
        val box = FrameLayout(host).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        // Valider la note enregistre le surlignage avec elle.
        AlertDialog.Builder(host).setTitle("Note").setView(box)
            .setPositiveButton("Enregistrer") { _, _ -> save(input.text.toString().trim()) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun save(note: String) {
        val text = chapter.slice(start, end)
        if (text.isNotEmpty()) {
            Highlights.put(
                old,
                Highlight(spine, start, end, text, note, old?.chapter ?: reader.chapterTitle(), old?.percent ?: reader.percent(), old?.time ?: System.currentTimeMillis()),
                chapter,
            )
        }
        host.hideSheet()
        reader.marksChanged()
    }
}
