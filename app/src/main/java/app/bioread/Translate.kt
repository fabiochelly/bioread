package app.bioread

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.LruCache
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Traduction de paragraphes avec cache. DeepL si une clé est renseignée et que le réseau
 * répond (italique et gras conservés, contexte du passage transmis), sinon ML Kit sur
 * l'appareil (hors-ligne une fois le modèle téléchargé).
 */
object Translator {
    val LANGS = arrayOf("fr", "en", "es", "de", "it", "pt", "nl")
    val LABELS = arrayOf("Français", "English", "Español", "Deutsch", "Italiano", "Português", "Nederlands")

    private val NBSP = Char(0xA0)        // espace insécable
    private val NNBSP = Char(0x202F)     // espace fine insécable

    private val cache = LruCache<String, CharSequence>(800)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val clients = HashMap<String, com.google.mlkit.nl.translate.Translator>()
    private val models = HashMap<String, Task<Void>>()
    private var deeplRetryAt = 0L
    private lateinit var app: Context
    private lateinit var fonts: Fonts
    private var started = false

    /**
     * Boîte de message au centre de l'écran : (titre, détails). Un titre nul la referme.
     * Sert au téléchargement du modèle et à toutes les erreurs, avec leur détail complet.
     */
    var box: ((String?, String) -> Unit)? = null

    fun init(c: Context, f: Fonts) {
        app = c.applicationContext
        fonts = f
    }

    private fun closeClients() {
        for (c in clients.values) try { c.close() } catch (e: Exception) {}
        clients.clear()
        models.clear()
    }

    /** Délestage : traduction coupée, le moteur hors-ligne et les traductions gardées sont rendus. */
    fun trim() {
        if (Prefs.translate) return
        closeClients()
        cache.evictAll()
    }

    fun label(code: String) = LABELS[maxOf(0, LANGS.indexOf(code))]

    /**
     * [cb] reçoit les traductions dans l'ordre de [texts], ou null en cas d'échec, toujours sur
     * le thread principal. [context] : le texte qui précède, pour la cohérence (non traduit).
     */
    fun translate(texts: List<CharSequence>, context: String, src: String, tgt: String, cb: (List<CharSequence>?) -> Unit) {
        val mode = if (Prefs.engine == 1 || Prefs.deeplKey.isBlank()) "local" else "deepl"
        val out = arrayOfNulls<CharSequence>(texts.size)
        val missing = ArrayList<Int>()
        for (i in texts.indices) {
            out[i] = cache.get("$tgt|$mode|${texts[i]}")
            if (out[i] == null) missing.add(i)
        }
        if (missing.isEmpty()) {
            cb(out.map { it!! })
            return
        }
        val todo = missing.map { texts[it] }
        val done = { res: List<CharSequence>? ->
            if (res == null) cb(null) else {
                for (k in missing.indices) {
                    val r = if (tgt == "fr") frenchTypography(res[k]) else res[k]
                    out[missing[k]] = r
                    cache.put("$tgt|$mode|${todo[k]}", r)
                }
                cb(out.map { it!! })
            }
        }
        val offline = { mlkit(todo.map { it.toString() }, src, tgt, done) }
        val key = Prefs.deeplKey.trim()
        // Le lecteur choisit son moteur : DeepL n'est utilisé que s'il est sélectionné et qu'une clé existe.
        if (key.isEmpty() || Prefs.engine == 1 || System.currentTimeMillis() < deeplRetryAt) {
            offline()
            return
        }
        io.execute {
            var error = ""
            val res = try {
                deepl(todo.map { toTagged(it) }, context, src, tgt, key).mapIndexed { k, r ->
                    // Le modèle « qualité » entoure parfois les titres d'astérisques absents de l'original.
                    fromTagged(if (todo[k].indexOf('*') < 0) r.replace("*", "") else r)
                }
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName} : ${e.message}"
                null
            }
            main.post {
                if (res != null) done(res) else {
                    // Réseau ou clé en défaut : repli sur ML Kit, nouvel essai DeepL dans une minute.
                    deeplRetryAt = System.currentTimeMillis() + 60_000
                    box?.invoke("DeepL indisponible", "$error\n\n${network()}\n\nRepli sur la traduction hors-ligne pendant une minute.")
                    offline()
                }
            }
        }
    }

    // ---------------------------------------------------------------- DeepL

    private fun deepl(texts: List<String>, context: String, src: String, tgt: String, key: String): List<String> {
        val host = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val c = URL("https://$host/v2/translate").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 15000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Authorization", "DeepL-Auth-Key $key")
            c.setRequestProperty("Content-Type", "application/json")
            val target = when (tgt) { "en" -> "EN-GB"; "pt" -> "PT-PT"; else -> tgt.uppercase() }
            val body = JSONObject().put("text", JSONArray(texts)).put("target_lang", target)
                .put("tag_handling", "html")                      // <i> et <b> sont conservés et replacés
                .put("model_type", "prefer_quality_optimized")
                .put("preserve_formatting", true)
            if (src.length == 2) body.put("source_lang", src.uppercase())
            if (context.isNotBlank()) body.put("context", context)   // non facturé, améliore la cohérence
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code != 200) {
                val detail = try { c.errorStream?.bufferedReader()?.use { it.readText() } } catch (e: Exception) { null }
                val why = when (code) {
                    403 -> "clé refusée"
                    456 -> "quota mensuel épuisé"
                    429 -> "trop de requêtes"
                    else -> "erreur"
                }
                throw RuntimeException("HTTP $code ($why) ${detail ?: ""}".trim())
            }
            val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("translations")
            return List(arr.length()) { arr.getJSONObject(it).getString("text") }
        } finally {
            c.disconnect()
        }
    }

    /** Texte avec italique / gras balisés et caractères spéciaux échappés, pour DeepL. */
    private fun toTagged(cs: CharSequence): String {
        val s = cs.toString()
        val sb = StringBuilder(s.length + 16)
        fun esc(a: Int, b: Int) {
            for (i in a until b) when (val ch = s[i]) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> sb.append(ch)
            }
        }
        if (cs !is Spanned) {
            esc(0, s.length)
            return sb.toString()
        }
        var i = 0
        while (i < s.length) {
            val n = cs.nextSpanTransition(i, s.length, FontSpan::class.java)
            val spans = cs.getSpans(i, n, FontSpan::class.java)
            val bold = spans.any { it.bold }
            val italic = spans.any { it.italic }
            if (bold) sb.append("<b>")
            if (italic) sb.append("<i>")
            esc(i, n)
            if (italic) sb.append("</i>")
            if (bold) sb.append("</b>")
            i = n
        }
        return sb.toString()
    }

    /** Inverse de [toTagged] sur la réponse de DeepL. */
    private fun fromTagged(t: String): CharSequence {
        val sb = SpannableStringBuilder()
        val plain = StringBuilder()
        var italic = -1
        var bold = -1
        var i = 0
        fun flush() {
            if (plain.isNotEmpty()) {
                sb.append(plain)
                plain.setLength(0)
            }
        }
        while (i < t.length) {
            val lt = t.indexOf('<', i)
            val end = if (lt < 0) t.length else lt
            appendDecoded(plain, t, i, end, false)
            if (lt < 0) break
            val gt = t.indexOf('>', lt)
            if (gt < 0) {
                plain.append(t, lt, t.length)
                break
            }
            flush()
            when (t.substring(lt + 1, gt).trim().lowercase()) {
                "i", "em" -> italic = sb.length
                "b", "strong" -> bold = sb.length
                "/i", "/em" -> if (italic in 0 until sb.length) {
                    sb.setSpan(FontSpan(fonts, false, true), italic, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    italic = -1
                }
                "/b", "/strong" -> if (bold in 0 until sb.length) {
                    sb.setSpan(FontSpan(fonts, true, false), bold, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    bold = -1
                }
            }
            i = gt + 1
        }
        flush()
        return sb
    }

    // ---------------------------------------------------------------- typographie française

    /**
     * Met le texte traduit aux normes françaises : guillemets « », apostrophes courbes,
     * espaces insécables avant ; : ! ? et dans les guillemets, ligature œ. Les traducteurs
     * automatiques (ML Kit surtout) rendent des guillemets droits et des espaces ordinaires,
     * ce qui donne des « orphelins » en bout de ligne.
     */
    private fun frenchTypography(cs: CharSequence): CharSequence {
        val sb = SpannableStringBuilder(cs)
        var open = false
        var i = 0
        while (i < sb.length) {
            val ch = sb[i]
            val prev = if (i > 0) sb[i - 1] else ' '
            val next = if (i + 1 < sb.length) sb[i + 1] else ' '
            when {
                ch == '"' -> {
                    open = !open
                    if (open) {
                        sb.replace(i, i + 1, "«$NBSP")
                        if (i + 2 < sb.length && sb[i + 2] == ' ') sb.delete(i + 2, i + 3)
                        i++
                    } else if (prev == ' ') {
                        sb.replace(i - 1, i + 1, "$NBSP»")
                    } else {
                        sb.replace(i, i + 1, "$NBSP»")
                        i++
                    }
                }
                ch == '«' -> if (next == ' ') sb.replace(i + 1, i + 2, NBSP.toString())
                else if (next != NBSP && next != NNBSP) sb.insert(i + 1, NBSP.toString())
                ch == '»' -> if (prev == ' ') sb.replace(i - 1, i, NBSP.toString())
                else if (prev != NBSP && prev != NNBSP) { sb.insert(i, NBSP.toString()); i++ }
                ch == '\'' && prev.isLetter() && next.isLetter() -> sb.replace(i, i + 1, "’")
                ch == ';' || ch == '!' || ch == '?' || (ch == ':' && (next == ' ' || next == NBSP)) -> {
                    if (prev == ' ') sb.replace(i - 1, i, NNBSP.toString())
                    else if (prev.isLetterOrDigit() || prev == '»' || prev == ')') { sb.insert(i, NNBSP.toString()); i++ }
                }
                (ch == 'o' || ch == 'O') && (next == 'e') && i + 2 < sb.length -> {
                    // cœur, sœur, œuvre, bœuf, vœu, nœud, œuf, œil
                    val after = sb[i + 2]
                    if (after == 'u' || (after == 'i' && i + 3 < sb.length && sb[i + 3] == 'l')) {
                        sb.replace(i, i + 2, if (ch == 'o') "œ" else "Œ")
                    }
                }
            }
            i++
        }
        return sb
    }

    // ---------------------------------------------------------------- ML Kit (hors-ligne)

    private fun mlkit(texts: List<String>, src: String, tgt: String, cb: (List<CharSequence>?) -> Unit) {
        val s = TranslateLanguage.fromLanguageTag(src)
        val t = TranslateLanguage.fromLanguageTag(tgt)
        if (s == null || t == null) {
            box?.invoke("Traduction impossible", "Langue non prise en charge hors-ligne : $src → $tgt")
            cb(null)
            return
        }
        if (!started) {
            // ML Kit n'est plus initialisé au lancement de l'app, seulement à la première traduction.
            started = true
            try { MlKit.initialize(app) } catch (e: Exception) {}
        }
        val key = "$s>$t"
        // Un seul moteur chargé à la fois : chaque paire de langues garde son modèle en mémoire native.
        if (!clients.containsKey(key)) closeClients()
        val client = clients.getOrPut(key) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(s).setTargetLanguage(t).build())
        }
        // Une seule préparation de modèle par paire de langues, partagée par toutes les pages.
        val model = models.getOrPut(key) {
            val since = System.currentTimeMillis()
            val task = client.downloadModelIfNeeded(DownloadConditions.Builder().build())
            // Le premier appel télécharge le modèle (~30 Mo par langue) : si cela dure, une boîte
            // affiche l'avancement réel, relu chaque seconde auprès du système.
            var shown = false
            val poll = object : Runnable {
                override fun run() {
                    if (task.isComplete) return
                    val waited = (System.currentTimeMillis() - since) / 1000
                    val title = if (waited < 120) "Téléchargement du modèle de traduction" else "Téléchargement du modèle bloqué"
                    shown = true
                    box?.invoke(title, diagnostics("$src → $tgt", waited))
                    main.postDelayed(this, 1000)
                }
            }
            main.postDelayed(poll, 1500)
            task.addOnCompleteListener { done ->
                main.removeCallbacks(poll)
                if (done.isSuccessful) {
                    if (shown) box?.invoke(null, "")   // ne referme que la boîte de téléchargement
                } else {
                    models.remove(key)   // nouvel essai possible en réactivant la traduction
                    val e = done.exception
                    box?.invoke(
                        "Traduction hors-ligne indisponible",
                        "${e?.javaClass?.name} : ${e?.message}\n\n" +
                            diagnostics("$src → $tgt", (System.currentTimeMillis() - since) / 1000),
                    )
                }
            }
            task
        }
        model.addOnSuccessListener {
            val res = ArrayList<CharSequence>(texts.size)
            fun step() {
                if (res.size == texts.size) {
                    cb(res)
                    return
                }
                client.translate(texts[res.size])
                    .addOnSuccessListener { res.add(it); step() }
                    .addOnFailureListener { e ->
                        box?.invoke("Erreur de traduction", "${e.javaClass.name} : ${e.message}")
                        cb(null)
                    }
            }
            step()
        }.addOnFailureListener { cb(null) }
    }

    private fun network(): String = try {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        when {
            caps == null -> "Réseau : aucune connexion active"
            else -> {
                val kind = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                    else -> "autre"
                }
                val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                "Réseau : $kind, accès Internet ${if (ok) "vérifié" else "NON vérifié par le système"}"
            }
        }
    } catch (e: Exception) {
        "Réseau : état illisible (${e.message})"
    }

    /** Tout ce que l'app peut observer d'un téléchargement de modèle, pour comprendre un blocage. */
    private fun diagnostics(pair: String, waited: Long): String {
        val sb = StringBuilder()
        sb.append("Langues : $pair\nAttente : $waited s\n").append(network()).append('\n')
        try {
            val v = app.packageManager.getPackageInfo("com.google.android.gms", 0).versionName
            sb.append("Services Google Play : présents ($v)\n")
        } catch (e: Exception) {
            sb.append("Services Google Play : absents ou désactivés\n")
        }
        sb.append("Espace libre : ${app.filesDir.usableSpace / (1024 * 1024)} Mo\n")
        try {
            val dm = app.getSystemService(DownloadManager::class.java)
            var n = 0
            dm.query(DownloadManager.Query()).use { c ->
                while (c.moveToNext()) {
                    n++
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val what = when (status) {
                        DownloadManager.STATUS_PENDING -> "en file d'attente"
                        DownloadManager.STATUS_RUNNING -> "en cours"
                        DownloadManager.STATUS_PAUSED -> "en pause : " + when (reason) {
                            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "attend le réseau"
                            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "attend le Wi-Fi"
                            DownloadManager.PAUSED_WAITING_TO_RETRY -> "nouvel essai en attente"
                            else -> "raison $reason"
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> "terminé"
                        DownloadManager.STATUS_FAILED -> "échec : " + when (reason) {
                            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "espace insuffisant"
                            DownloadManager.ERROR_FILE_ERROR -> "erreur d'écriture du fichier"
                            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "stockage introuvable"
                            DownloadManager.ERROR_CANNOT_RESUME -> "reprise impossible"
                            DownloadManager.ERROR_HTTP_DATA_ERROR -> "erreur de transfert"
                            else -> "code $reason"
                        }
                        else -> "état $status"
                    }
                    sb.append("Téléchargement $n : $what, ${got / 1024} Ko sur ${if (total > 0) "${total / 1024} Ko" else "taille inconnue"}\n")
                }
            }
            if (n == 0) sb.append("Téléchargements système : aucun enregistré (la demande n'a pas atteint le gestionnaire de téléchargements d'Android)\n")
        } catch (e: Exception) {
            sb.append("Gestionnaire de téléchargements : inaccessible (${e.javaClass.simpleName} : ${e.message})\n")
        }
        return sb.toString().trimEnd()
    }

    // ---------------------------------------------------------------- découpage

    /**
     * Découpe la page en segments à traduire. Une phrase appartient à la page où elle commence :
     * on saute la fin de phrase héritée de la page précédente et on termine la dernière phrase,
     * pour ne jamais traduire une phrase coupée. L'italique et le gras du texte sont conservés.
     */
    fun segments(page: Page, lang: String): ArrayList<Block> {
        val out = ArrayList<Block>()
        var it: BreakIterator? = null
        for (s in page.slices) {
            val b = s.block
            val l = s.layout
            if (l == null) {
                out.add(b)
                continue
            }
            val text = b.text.toString()
            var a = l.getLineStart(s.a)
            var e = l.getLineEnd(s.b - 1)
            val start = a
            if (a > 0 || e < text.length) {
                val bi = it ?: BreakIterator.getSentenceInstance(Locale.forLanguageTag(lang)).also { x -> it = x }
                bi.setText(text)
                if (a > 0) a = boundary(bi, a, text.length)
                if (e < text.length) e = boundary(bi, e, text.length)
            }
            while (a < e && text[a] <= ' ') a++
            while (e > a && text[e - 1] <= ' ') e--
            if (e <= a) continue
            out.add(Block(b.kind, b.text.subSequence(a, e), b.level, b.align, b.quote, b.gap && start == 0, b.indent && a == 0))
        }
        return out
    }

    private fun boundary(bi: BreakIterator, at: Int, len: Int): Int {
        if (at >= len) return len
        if (bi.isBoundary(at)) return at
        val n = bi.following(at)
        return if (n == BreakIterator.DONE) len else n
    }
}
