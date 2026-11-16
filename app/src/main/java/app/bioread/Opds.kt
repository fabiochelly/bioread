package app.bioread

import android.app.AlertDialog
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Catalogue enregistré : son nom, l'adresse de sa racine et, s'il en demande, un compte. */
class Catalog(val name: String, val url: String, val user: String, val pass: String)

/** Fichier EPUB proposé pour un livre ([label] : la variante, quand il y en a plusieurs). */
class OpdsFile(val label: String, val url: String)

/**
 * Ligne d'un catalogue : une rubrique à ouvrir ([nav]) ou un livre à télécharger ([files]).
 * [state] : avancement du téléchargement, puis [book] une fois le livre dans la bibliothèque.
 */
class OpdsEntry(val title: String, val author: String, val summary: String, val nav: String?, val files: List<OpdsFile>) {
    var state = ""
    var book: File? = null
}

class OpdsFeed(val title: String, val entries: ArrayList<OpdsEntry>, val next: String?, val search: String?, val searchDirect: Boolean)

/**
 * Catalogues en ligne au format OPDS 1 (des flux Atom) : celui de Calibre et de Calibre-Web,
 * Kavita, Komga, COPS, le projet Gutenberg, Ebooks libres et gratuits… On y navigue de rubrique
 * en rubrique, on y cherche, et un toucher sur un livre le télécharge dans la bibliothèque,
 * sans passer par un ordinateur ni par un câble.
 */
object Opds {
    private val DEFAULTS = listOf(
        Catalog("Projet Gutenberg", "https://m.gutenberg.org/ebooks.opds/", "", ""),
        Catalog("Ebooks libres et gratuits", "https://www.ebooksgratuits.com/opds/index.php", "", ""),
    )

    fun catalogs(): ArrayList<Catalog> {
        val out = ArrayList<Catalog>()
        if (Prefs.catalogs.isEmpty()) {
            out.addAll(DEFAULTS)
            return out
        }
        try {
            val arr = JSONArray(Prefs.catalogs)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Catalog(o.getString("name"), o.getString("url"), o.optString("user"), o.optString("pass")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun save(list: List<Catalog>) {
        val arr = JSONArray()
        for (c in list) arr.put(JSONObject().put("name", c.name).put("url", c.url).put("user", c.user).put("pass", c.pass))
        Prefs.catalogs = arr.toString()
        Prefs.save()
    }

    /** Ouvre [url] en suivant les redirections ; le compte du catalogue n'est envoyé qu'à son propre serveur. */
    private fun open(url: String, cat: Catalog?): HttpURLConnection {
        var at = url
        for (hop in 0 until 6) {
            val u = URL(at)
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 12000
            c.readTimeout = 20000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Bioread (OPDS)")
            c.setRequestProperty("Accept", "application/atom+xml, application/xml, application/epub+zip, */*")
            if (cat != null && cat.user.isNotEmpty() && try { URL(cat.url).host == u.host } catch (e: Exception) { false }) {
                c.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("${cat.user}:${cat.pass}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            }
            val code = c.responseCode
            if (code in 300..399) {
                val to = c.getHeaderField("Location")
                c.disconnect()
                at = URL(u, to ?: throw java.io.IOException("redirection sans adresse")).toString()
                continue
            }
            if (code == 401) { c.disconnect(); throw java.io.IOException("ce catalogue demande un identifiant et un mot de passe") }
            if (code >= 400) { c.disconnect(); throw java.io.IOException("réponse $code du serveur") }
            return c
        }
        throw java.io.IOException("trop de redirections")
    }

    fun fetch(url: String, cat: Catalog?): String {
        val c = open(url, cat)
        try {
            return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    /** Télécharge [url] dans [dest] ; [progress] reçoit le pourcentage quand la taille est connue, sinon -1. */
    fun download(url: String, cat: Catalog?, dest: File, progress: (Int) -> Unit) {
        val c = open(url, cat)
        try {
            val total = c.contentLengthLong
            var done = 0L
            var shown = -2
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val p = if (total > 0) (done * 100 / total).toInt() else -1
                        if (p != shown) { shown = p; progress(p) }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun resolve(base: String, href: String): String = try { URL(URL(base), href).toString() } catch (e: Exception) { href }

    private val TAGS = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")

    /** Lit un flux OPDS ; [base] : son adresse, pour les liens relatifs. Lève une exception si ce n'en est pas un. */
    fun parse(xml: String, base: String): OpdsFeed {
        val t = Tok(xml)
        val entries = ArrayList<OpdsEntry>()
        var feedTitle = ""
        var next: String? = null
        var search: String? = null
        var searchDirect = false
        var isFeed = false
        var inEntry = false
        var inAuthor = false
        var want = 0            // texte attendu : 1 titre, 2 nom d'auteur, 3 résumé
        var title = ""
        var author = ""
        var summary = ""
        var nav: String? = null
        val files = ArrayList<OpdsFile>()
        while (true) {
            when (t.next()) {
                Tok.END -> break
                Tok.OPEN -> when (t.name) {
                    "feed" -> isFeed = true
                    "entry" -> {
                        isFeed = true
                        inEntry = true
                        title = ""; author = ""; summary = ""; nav = null
                        files.clear()
                    }
                    "title" -> want = 1
                    "author" -> inAuthor = true
                    "name" -> if (inAuthor) want = 2
                    "summary", "content" -> if (inEntry && summary.isEmpty()) want = 3
                    "link" -> {
                        val href = t.attr("href")
                        val rel = t.attr("rel") ?: ""
                        val type = t.attr("type") ?: ""
                        if (href != null && !href.startsWith("data:")) {
                            val feedLink = type.contains("atom+xml") || type.contains("opds-catalog")
                            if (!inEntry) {
                                if (rel == "next" && feedLink) next = resolve(base, href)
                                else if (rel == "search") {
                                    // Adresse de recherche directe (Calibre), ou description OpenSearch à lire d'abord.
                                    if (feedLink && href.contains("{searchTerms}")) { search = resolve(base, href.replace("{", "%7B").replace("}", "%7D")).replace("%7B", "{").replace("%7D", "}"); searchDirect = true }
                                    else if (type.contains("opensearchdescription") && !searchDirect) search = resolve(base, href)
                                }
                            } else if (rel.contains("opds-spec.org/acquisition") && !rel.endsWith("/buy") && !rel.endsWith("/borrow") && !rel.endsWith("/subscribe")) {
                                if (type.startsWith("application/epub")) files.add(OpdsFile(t.attr("title") ?: "", resolve(base, href)))
                            } else if (feedLink && nav == null && rel != "self" && rel != "related" && rel != "alternate" && rel != "up" && rel != "start") {
                                nav = resolve(base, href)
                            }
                        }
                    }
                    else -> if (want != 3) want = 0
                }
                Tok.TEXT -> if (want != 0) {
                    val v = t.text().trim()
                    if (v.isNotEmpty()) when (want) {
                        1 -> if (inEntry) { if (title.isEmpty()) title = v } else if (feedTitle.isEmpty()) feedTitle = v
                        2 -> if (inEntry) author = if (author.isEmpty()) v else "$author, $v"
                        // Résumé parfois en HTML, balisé ou échappé : le texte seul.
                        3 -> summary = (if (summary.isEmpty()) "" else "$summary ") + v
                    }
                    if (want != 3) want = 0
                }
                Tok.CLOSE -> when (t.name) {
                    "entry" -> {
                        inEntry = false
                        want = 0
                        val clean = SPACES.replace(TAGS.replace(summary, " "), " ").trim()
                        if (title.isNotEmpty() && (nav != null || files.isNotEmpty())) {
                            entries.add(OpdsEntry(title, author, clean.take(240), if (files.isEmpty()) nav else null, ArrayList(files)))
                        }
                    }
                    "author" -> inAuthor = false
                    "summary", "content" -> want = 0
                    else -> if (want != 3) want = 0
                }
            }
        }
        if (!isFeed) throw java.io.IOException("cette adresse n'est pas un catalogue OPDS")
        return OpdsFeed(feedTitle, entries, next, search, searchDirect)
    }

    /** Adresse des résultats de la recherche de [query] dans le catalogue du flux [feed], ou null. */
    fun searchUrl(feed: OpdsFeed, cat: Catalog?, query: String): String? {
        var template = feed.search ?: return null
        if (!feed.searchDirect) {
            // Description OpenSearch : on y prend le modèle d'adresse qui rend un flux Atom.
            val doc = fetch(template, cat)
            val base = template
            val t = Tok(doc)
            var found: String? = null
            while (true) {
                val e = t.next()
                if (e == Tok.END) break
                if (e == Tok.OPEN && t.name == "url") {
                    val tpl = t.attr("template") ?: continue
                    val type = t.attr("type") ?: ""
                    if (type.contains("atom") || found == null) found = tpl
                    if (type.contains("atom")) break
                }
            }
            template = found ?: return null
            if (!template.contains("://")) template = resolve(base, template.replace("{", "%7B").replace("}", "%7D")).replace("%7B", "{").replace("%7D", "}")
        }
        return template.replace("{searchTerms}", URLEncoder.encode(query, "UTF-8"))
            .replace(Regex("\\{startPage\\??}"), "1")
            .replace(Regex("\\{[^}]*}"), "")
    }
}

/**
 * Écran des catalogues, ouvert depuis l'accueil : la liste des catalogues enregistrés, puis les
 * rubriques et les livres de celui qu'on parcourt. Du texte seulement, sans vignettes : net et
 * rapide sur encre électronique, sobre en réseau.
 */
class OpdsView(private val host: MainActivity) : LinearLayout(host) {
    private val d = resources.displayMetrics.density
    private val net = Executors.newSingleThreadExecutor()
    private val title = label("Catalogues", 20f, true)
    private val status = label("", 14f, false)
    private val list = ListView(host)
    private val searchButton: View
    private val addButton: View
    private var catalogs = Opds.catalogs()
    private var catalog: Catalog? = null
    private val trail = ArrayList<String>()        // adresses parcourues, la dernière est celle affichée
    private var feed: OpdsFeed? = null
    private var rows = ArrayList<OpdsEntry>()
    private var more: String? = null
    private var seq = 0

    private val adapter = object : BaseAdapter() {
        override fun getCount() = if (catalog == null) catalogs.size else rows.size + if (more != null) 1 else 0
        override fun getItem(i: Int): Any = i
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, v: View?, parent: ViewGroup): View {
            val cell = (v as? LinearLayout) ?: LinearLayout(host).apply {
                orientation = VERTICAL
                setPadding(dp(20), dp(11), dp(20), dp(11))
                addView(label("", 16f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                addView(label("", 13f, false).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.2f); setPadding(0, dp(3), 0, 0) })
                addView(label("", 13f, true).apply { maxLines = 2; setPadding(0, dp(3), 0, 0) })
            }
            val a = cell.getChildAt(0) as TextView
            val b = cell.getChildAt(1) as TextView
            val c = cell.getChildAt(2) as TextView
            var sub = ""
            var state = ""
            if (catalog == null) {
                a.text = catalogs[i].name
                sub = catalogs[i].url.substringAfter("://")
            } else if (i >= rows.size) {
                a.text = "Suite…"
            } else {
                val e = rows[i]
                // Une rubrique se distingue d'un livre par son chevron.
                a.text = if (e.nav != null) "${e.title}  ›" else e.title
                sub = if (e.author.isNotEmpty() && e.summary.isNotEmpty()) "${e.author} — ${e.summary}" else e.author + e.summary
                state = e.state
            }
            b.text = sub
            b.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
            c.text = state
            c.visibility = if (state.isEmpty()) View.GONE else View.VISIBLE
            return cell
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.bg)
        isClickable = true
        setPadding(0, host.insetTop, 0, host.insetBottom)
        val header = LinearLayout(host).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
            addView(IconButton(host, IconButton.BACK) { if (!back()) host.hideCatalog() }.apply { thin = true; contentDescription = "Retour" })
            addView(title.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        }
        searchButton = IconButton(host, IconButton.SEARCH) { askSearch() }.apply { thin = true; contentDescription = "Rechercher" }
        addButton = IconButton(host, IconButton.PLUS) { edit(null) }.apply { thin = true; contentDescription = "Ajouter un catalogue" }
        header.addView(searchButton)
        header.addView(addButton)
        addView(header)
        addView(View(host).apply { setBackgroundColor(Theme.ui) }, LayoutParams(-1, dp(1)))
        status.maxLines = 4
        status.setLineSpacing(0f, 1.25f)
        status.setPadding(dp(20), dp(14), dp(20), dp(6))
        addView(status)
        list.divider = null
        list.selector = ColorDrawable(0)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, i, _ -> tapped(i) }
        list.setOnItemLongClickListener { _, _, i, _ ->
            if (catalog == null && i < catalogs.size) { edit(catalogs[i]); true } else false
        }
        addView(list, LayoutParams(-1, 0, 1f))
        showRoot()
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun label(s: String, size: Float, bold: Boolean) = TextView(host).apply {
        text = s
        typeface = if (bold) host.fonts.uiBold else host.fonts.ui
        textSize = size
        setTextColor(Theme.ui)
    }

    private fun showRoot() {
        seq++
        catalog = null
        feed = null
        trail.clear()
        rows = ArrayList()
        more = null
        title.text = "Catalogues"
        status.text = if (catalogs.isEmpty()) "Aucun catalogue. Le bouton + en ajoute un : celui de Calibre, par exemple, à l'adresse http://adresse-du-pc:8080/opds."
        else "Touchez un catalogue pour le parcourir ; un appui long le modifie. Le bouton + en ajoute un (Calibre : http://adresse-du-pc:8080/opds)."
        status.visibility = View.VISIBLE
        searchButton.visibility = View.GONE
        addButton.visibility = View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    /** Charge le flux [url] ; [append] : la suite de la liste affichée. */
    private fun load(url: String, append: Boolean) {
        val cat = catalog
        val n = ++seq
        if (!append) {
            status.text = "Chargement…"
            status.visibility = View.VISIBLE
        }
        net.execute {
            var result: OpdsFeed? = null
            var error = ""
            try {
                result = Opds.parse(Opds.fetch(url, cat), url)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            post {
                if (n != seq) return@post
                val f = result
                if (f == null) {
                    status.text = "Catalogue injoignable : $error"
                    status.visibility = View.VISIBLE
                    return@post
                }
                if (append) rows.addAll(f.entries) else {
                    rows = f.entries
                    feed = f
                    if (f.title.isNotEmpty()) title.text = f.title
                    list.setSelection(0)
                    searchButton.visibility = if (f.search != null) View.VISIBLE else View.GONE
                }
                more = f.next
                status.text = if (rows.isEmpty()) "Rien ici." else ""
                status.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun go(url: String) {
        trail.add(url)
        rows = ArrayList()
        more = null
        adapter.notifyDataSetChanged()
        load(url, false)
    }

    private fun tapped(i: Int) {
        if (catalog == null) {
            val c = catalogs.getOrNull(i) ?: return
            catalog = c
            title.text = c.name
            addButton.visibility = View.GONE
            go(c.url)
            return
        }
        if (i >= rows.size) {
            val m = more ?: return
            more = null
            load(m, true)
            return
        }
        val e = rows[i]
        val nav = e.nav
        if (nav != null) { go(nav); return }
        val got = e.book
        if (got != null) {
            // Déjà téléchargé : le toucher l'ouvre.
            Library.books.firstOrNull { it.path == got.path }?.let { host.hideCatalog(); host.openBook(it) }
            return
        }
        if (e.state.startsWith("Téléchargement")) return
        if (e.files.size == 1) download(e, e.files[0])
        else AlertDialog.Builder(host).setTitle(e.title)
            .setItems(e.files.mapIndexed { k, f -> f.label.ifEmpty { "EPUB ${k + 1}" } }.toTypedArray()) { _, k -> download(e, e.files[k]) }
            .show()
    }

    private fun download(e: OpdsEntry, f: OpdsFile) {
        val cat = catalog
        e.state = "Téléchargement…"
        adapter.notifyDataSetChanged()
        Thread({
            val tmp = File(host.cacheDir, "opds-${System.nanoTime()}.epub")
            var file: File? = null
            var error = ""
            try {
                var last = 0L
                Opds.download(f.url, cat, tmp) { p ->
                    // Une mise à jour par seconde au plus : chacune redessine l'écran de la liseuse.
                    val now = System.currentTimeMillis()
                    if (p >= 0 && now - last > 1000) {
                        last = now
                        post { if (e.book == null) { e.state = "Téléchargement… $p %"; adapter.notifyDataSetChanged() } }
                    }
                }
                file = Library.adopt(tmp)
                if (file == null) error = "ce fichier n'est pas un EPUB lisible"
            } catch (x: Exception) {
                error = x.message ?: x.javaClass.simpleName
            }
            tmp.delete()
            post {
                val got = file
                if (got == null) {
                    e.state = "Échec : $error"
                    adapter.notifyDataSetChanged()
                } else Library.scan(host) {
                    e.book = got
                    e.state = "✓ Dans la bibliothèque — touchez pour l'ouvrir"
                    adapter.notifyDataSetChanged()
                }
            }
        }, "opds").start()
    }

    private fun askSearch() {
        val f = feed ?: return
        val cat = catalog
        val input = field("", "Titre, auteur…", 0)
        val box = FrameLayout(host).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(host).setTitle("Rechercher dans ce catalogue").setView(box)
            .setPositiveButton("Rechercher") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                val n = ++seq
                status.text = "Recherche…"
                status.visibility = View.VISIBLE
                net.execute {
                    val url = try { Opds.searchUrl(f, cat, q) } catch (e: Exception) { null }
                    post {
                        if (n != seq) return@post
                        if (url == null) status.text = "Ce catalogue n'a pas répondu à la recherche." else go(url)
                    }
                }
            }
            .setNegativeButton("Annuler", null).show()
    }

    private fun field(value: String, what: String, type: Int) = EditText(host).apply {
        setText(value)
        hint = what
        inputType = InputType.TYPE_CLASS_TEXT or type
        setSingleLine()
    }

    /** Ajout d'un catalogue, ou modification / suppression de [old]. */
    private fun edit(old: Catalog?) {
        val name = field(old?.name ?: "", "Nom", InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val url = field(old?.url ?: "http://", "Adresse (http://…/opds)", InputType.TYPE_TEXT_VARIATION_URI)
        val user = field(old?.user ?: "", "Identifiant (s'il en faut un)", InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val pass = field(old?.pass ?: "", "Mot de passe", InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val form = LinearLayout(host).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(name); addView(url); addView(user); addView(pass)
        }
        val dialog = AlertDialog.Builder(host).setTitle(if (old == null) "Ajouter un catalogue" else "Catalogue").setView(form)
            .setPositiveButton("Enregistrer") { _, _ ->
                var u = url.text.toString().trim()
                if (u.isEmpty() || u == "http://") return@setPositiveButton
                if (!u.contains("://")) u = "http://$u"
                val c = Catalog(name.text.toString().trim().ifEmpty { u.substringAfter("://").substringBefore('/') }, u, user.text.toString().trim(), pass.text.toString())
                val next = ArrayList(catalogs)
                val at = next.indexOf(old)
                if (at >= 0) next[at] = c else next.add(c)
                catalogs = next
                Opds.save(next)
                showRoot()
            }
        if (old != null) dialog.setNeutralButton("Supprimer") { _, _ ->
            catalogs = ArrayList(catalogs.filter { it !== old })
            Opds.save(catalogs)
            showRoot()
        }
        dialog.setNegativeButton("Annuler", null).show()
    }

    /** Touche Retour : remonte d'une rubrique, puis à la liste des catalogues ; faux quand il n'y a plus rien à remonter. */
    fun back(): Boolean {
        if (catalog == null) return false
        if (trail.size > 1) {
            trail.removeAt(trail.size - 1)
            val url = trail.removeAt(trail.size - 1)
            go(url)
        } else showRoot()
        return true
    }
}
