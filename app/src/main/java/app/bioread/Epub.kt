package app.bioread

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

class TocItem(val title: String, val spine: Int, val anchor: String?, val depth: Int)

/** Accès direct au zip : seuls le conteneur et l'OPF sont lus à l'ouverture. */
class Epub(file: File) : Closeable {
    private val zip = ZipFile(file)
    var title = ""
    var author = ""
    var lang = ""
    val spine = ArrayList<String>()
    /** Taille cumulée (octets décompressés) avant chaque élément du spine ; sert au pourcentage. */
    lateinit var cum: LongArray
    var total = 1L
    /** Image de couverture dans le zip, si l'OPF en déclare une. */
    var coverPath: String? = null
    private var ncxPath: String? = null
    private var navPath: String? = null

    val toc: ArrayList<TocItem> by lazy { parseToc() }

    private val css by lazy { BookCss(this) }

    /** Feuilles de style du livre pour [parseChapter], ou null hors du mode « Fidèle au livre » (voir Css.kt). */
    fun styles(): BookCss? = if (Prefs.faithful) css else null

    init {
        var opf = "content.opf"
        read("META-INF/container.xml")?.let {
            val t = Tok(it)
            while (true) {
                val e = t.next()
                if (e == Tok.END) break
                if (e == Tok.OPEN && t.name == "rootfile") {
                    t.attr("full-path")?.let { p -> opf = p }
                    break
                }
            }
        }
        parseOpf(opf)
    }

    private fun parseOpf(opf: String) {
        val src = read(opf) ?: return
        val t = Tok(src)
        val href = HashMap<String, String>()
        val refs = ArrayList<String>()
        var ncxId: String? = null
        var coverId: String? = null
        var coverGuess: String? = null
        var want = 0
        while (true) {
            when (t.next()) {
                Tok.END -> break
                Tok.OPEN -> when (t.name) {
                    "title" -> want = 1
                    "creator" -> want = 2
                    "language" -> want = 3
                    "meta" -> {
                        want = 0
                        if (t.attr("name") == "cover") coverId = t.attr("content")
                    }
                    "item" -> {
                        val id = t.attr("id")
                        val h = t.attr("href")
                        if (id != null && h != null) {
                            val p = resolvePath(opf, h)
                            href[id] = p
                            if (t.attr("properties")?.split(' ')?.contains("nav") == true) navPath = p
                            val type = t.attr("media-type") ?: ""
                            if (type == "application/x-dtbncx+xml") ncxPath = p
                            if (type.startsWith("image/")) {
                                if (t.attr("properties")?.contains("cover-image") == true) coverPath = p
                                else if (coverGuess == null && (id.contains("cover", true) || h.contains("cover", true))) coverGuess = p
                            }
                        }
                    }
                    "spine" -> ncxId = t.attr("toc")
                    "itemref" -> t.attr("idref")?.let { refs.add(it) }
                    else -> want = 0
                }
                Tok.TEXT -> if (want != 0) {
                    val v = t.text().trim()
                    if (v.isNotEmpty()) when (want) {
                        1 -> if (title.isEmpty()) title = v
                        2 -> if (author.isEmpty()) author = v
                        3 -> if (lang.isEmpty()) lang = v
                    }
                    want = 0
                }
                Tok.CLOSE -> want = 0
            }
        }
        if (ncxId != null) href[ncxId]?.let { ncxPath = it }
        if (coverPath == null) {
            // EPUB 2 : <meta name="cover"> désigne l'image (parfois, à tort, une page XHTML).
            val byMeta = coverId?.let { href[it] }?.takeIf { !it.endsWith("html", true) && !it.endsWith(".xml", true) }
            coverPath = byMeta ?: coverGuess
        }
        for (r in refs) {
            val p = href[r] ?: continue
            if (zip.getEntry(p) != null) spine.add(p)
        }
        cum = LongArray(spine.size + 1)
        for (i in spine.indices) cum[i + 1] = cum[i] + maxOf(1L, zip.getEntry(spine[i]).size)
        total = maxOf(1L, cum[spine.size])
    }

    private fun parseToc(): ArrayList<TocItem> {
        val out = ArrayList<TocItem>()
        val index = HashMap<String, Int>()
        for (i in spine.indices) index[spine[i]] = i
        fun add(title: String, base: String, src: String, depth: Int) {
            val s = index[resolvePath(base, src)] ?: return
            val h = src.indexOf('#')
            out.add(TocItem(title.trim(), s, if (h >= 0) src.substring(h + 1) else null, depth))
        }
        val ncx = ncxPath
        val nav = navPath
        if (ncx != null) read(ncx)?.let { src ->
            val t = Tok(src)
            var depth = 0
            var label = ""
            var inText = false
            while (true) {
                when (t.next()) {
                    Tok.END -> break
                    Tok.OPEN -> when (t.name) {
                        "navpoint" -> depth++
                        "text" -> inText = true
                        "content" -> if (depth > 0) t.attr("src")?.let { add(label, ncx, it, depth - 1) }
                    }
                    Tok.TEXT -> if (inText) label = t.text()
                    Tok.CLOSE -> when (t.name) {
                        "navpoint" -> depth--
                        "text" -> inText = false
                    }
                }
            }
        }
        if (out.isEmpty() && nav != null) read(nav)?.let { src ->
            val t = Tok(src)
            var inToc = false
            var depth = 0
            var link: String? = null
            val label = StringBuilder()
            while (true) {
                when (t.next()) {
                    Tok.END -> break
                    Tok.OPEN -> when (t.name) {
                        "nav" -> inToc = out.isEmpty() && (t.attr("type") ?: "toc").contains("toc")
                        "ol" -> depth++
                        "a" -> if (inToc) { link = t.attr("href"); label.setLength(0) }
                    }
                    Tok.TEXT -> if (link != null) appendDecoded(label, t.s, t.textStart, t.textEnd, true)
                    Tok.CLOSE -> when (t.name) {
                        "nav" -> inToc = false
                        "ol" -> depth--
                        "a" -> {
                            link?.let { add(label.toString(), nav, it, maxOf(0, depth - 1)) }
                            link = null
                        }
                    }
                }
            }
        }
        return out
    }

    fun open(path: String): InputStream? = zip.getEntry(path)?.let { zip.getInputStream(it) }

    fun read(path: String): String? {
        val e = zip.getEntry(path) ?: return null
        val bytes = zip.getInputStream(e).use { it.readBytes() }
        val n = bytes.size
        return when {
            n >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, n - 2, Charsets.UTF_16LE)
            n >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, n - 2, Charsets.UTF_16BE)
            n >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() -> String(bytes, 3, n - 3, Charsets.UTF_8)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    override fun close() = zip.close()
}
