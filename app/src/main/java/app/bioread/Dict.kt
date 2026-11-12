package app.bioread

import android.os.Environment
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.Normalizer
import java.util.zip.Inflater

/**
 * Dictionnaire unilingue hors-ligne : un fichier par langue (bioread-dict-fr.bin…), fabriqué par
 * tools/make_dict.py à partir du Wiktionnaire et déposé dans Download/ — il n'est pas dans l'APK.
 *
 * Chaque article porte aussi ses traductions par sens vers les autres langues de l'app.
 *
 * Rien n'est chargé en mémoire : le fichier est projeté (mmap), une recherche dichotomique sur
 * la première clé de chaque bloc désigne un bloc de 4 Ko, seul décompressé puis parcouru.
 * Une forme fléchie mène à son lemme par les renvois du fichier (running → run), jamais par des
 * règles devinées. Le format est décrit en tête de tools/make_dict.py.
 */
object Dict {
    /** Traductions d'un sens : son libellé court (parfois vide) et ses équivalents, séparés par des virgules. */
    class Tr(val sense: String, val words: String)

    /**
     * Une catégorie grammaticale d'un mot : [count] sens, et [trCount] sens traduits vers la langue
     * demandée. Les textes ne sont décodés qu'à la demande (run a 118 sens ; la feuille repliée
     * n'en montre qu'un) : la recherche elle-même ne fait que repérer leur place dans le bloc lu.
     */
    class Group internal constructor(
        val pos: String, val label: String, private val raw: ByteArray,
        private val at: Int, val count: Int, private val trAt: Int, val trCount: Int,
    ) {
        /** Le premier sens. */
        val first: String get() = Bytes(raw).also { it.p = at }.str()

        val glosses: List<String> by lazy { val r = Bytes(raw); r.p = at; List(count) { r.str() } }

        val tr: List<Tr> by lazy { val r = Bytes(raw); r.p = trAt; List(trCount) { Tr(r.str(), r.str()) } }
    }
    class Entry(val word: String, val ipa: String, val groups: List<Group>)

    /** Un article à montrer. [note] : ce qui relie le mot du livre à cet article (« Pluriel de cheval. »). */
    class Section(val entry: Entry, val note: String, val groups: ArrayList<Group>, val linked: Boolean)

    /** [sections] vide : le mot n'est pas dans le dictionnaire. [credit] : source et licence, à afficher. */
    class Result(val sections: List<Section>, val credit: String)

    private class Link(val lemma: String, val pos: String, val label: String)
    private class Record(val entries: List<Entry>, val links: List<Link>)

    private class Book(val map: ByteBuffer, val blocks: Int, val index: Int, val pool: Int, val zdict: ByteArray, val credit: String)

    private const val PREFIX = "bioread-dict-"
    private const val FRONT = "\u0001"
    private const val MAGIC = "BIODICT3"   // le chiffre est la version du format
    private val main = Handler(Looper.getMainLooper())
    private var dir: File? = null
    private val books = HashMap<String, Book?>()
    private val copying = HashSet<String>()
    private val old = HashSet<String>()

    private var want = ""   // langue des traductions lues par la recherche en cours

    private val FR_ELIDED = hashSetOf("l", "d", "j", "m", "t", "s", "n", "c", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu")
    private val EN_ENDINGS = arrayOf("'s", "'", "'ll", "'d", "'ve", "'re")

    /** Nom de la langue dans « dictionnaire … ». */
    fun name(lang: String) = when (lang) {
        "fr" -> "français"; "en" -> "anglais"; "es" -> "espagnol"; "de" -> "allemand"
        "it" -> "italien"; "pt" -> "portugais"; "nl" -> "néerlandais"; else -> "« $lang »"
    }

    fun fileName(lang: String) = "$PREFIX$lang.bin"

    /**
     * Installe les dictionnaires déposés dans Download/ : copie dans le stockage de l'app (en
     * arrière-plan, le fichier pèse des dizaines de Mo), puis suppression de l'original — seulement
     * si la copie est complète et valide. Appelé au lancement et à chaque retour dans l'app.
     */
    fun install(host: MainActivity) {
        val into = File(host.filesDir, "dict")
        dir = into
        try {
            if (!Library.hasAccess(host)) return
            @Suppress("DEPRECATION")
            val down = File(Environment.getExternalStorageDirectory(), "Download")
            // Un simple test d'existence par langue : rien n'est parcouru à chaque retour dans l'app.
            for (lang in Translator.LANGS) {
                val src = File(down, fileName(lang))
                if (!src.isFile || !copying.add(lang)) continue
                Thread {
                    var ok = false
                    val tmp = File(into, "$lang.part")
                    try {
                        into.mkdirs()
                        val size = src.length()
                        src.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                        ok = tmp.length() == size && valid(tmp) && tmp.renameTo(File(into, "$lang.bin"))
                        if (ok) {
                            src.delete()
                            android.media.MediaScannerConnection.scanFile(host.applicationContext, arrayOf(src.path), null, null)
                        }
                    } catch (e: Exception) {
                    }
                    if (!ok) tmp.delete()
                    main.post {
                        // En cas d'échec, l'original reste en place : nouvel essai au prochain lancement.
                        if (ok) {
                            copying.remove(lang)
                            synchronized(Dict) { books.remove(lang) }   // book() tourne aussi sur le fil de tracé
                            host.toast("Dictionnaire ${name(lang)} installé")
                        }
                    }
                }.start()
            }
        } catch (e: Exception) {
        }
    }

    private fun valid(f: File): Boolean = try {
        f.inputStream().use { val b = ByteArray(8); it.read(b) == 8 && String(b, Charsets.US_ASCII) == MAGIC }
    } catch (e: Exception) {
        false
    }

    /** Vrai si le dictionnaire installé pour [lang] est d'un format antérieur : il faut le remplacer. */
    @Synchronized
    fun outdated(lang: String) = book(lang) == null && lang in old

    private fun book(lang: String): Book? {
        if (books.containsKey(lang)) return books[lang]
        var b: Book? = null
        try {
            val f = File(dir ?: return null, "$lang.bin")
            if (f.length() > 48) {
                val map = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()) }
                map.order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(8) { map.get(it) }
                val m = String(magic, Charsets.US_ASCII)
                if (m != MAGIC && m.startsWith("BIODICT")) old.add(lang) else old.remove(lang)
                if (m == MAGIC) {
                    val meta = ByteArray(map.getInt(28))
                    for (i in meta.indices) meta[i] = map.get(map.getInt(24) + i)
                    val j = JSONObject(String(meta, Charsets.UTF_8))
                    val zdict = ByteArray(map.getInt(44))
                    (map.duplicate().position(map.getInt(40)) as ByteBuffer).get(zdict)
                    b = Book(map, map.getInt(8), map.getInt(32), map.getInt(36), zdict, "${j.optString("source", "Wiktionary")} · ${j.optString("license", "CC BY-SA")}")
                }
            }
        } catch (e: Exception) {
        }
        books[lang] = b
        return b
    }

    /** Clé de recherche : même transformation que norm() dans tools/make_dict.py. */
    fun norm(w: String): String {
        val sb = StringBuilder(w.length)
        // Sans diacritique combinant (le cas courant), le texte est déjà en forme NFC.
        val nfc = if (w.all { it < '\u0300' || it in '\u2010'..'\u2019' }) w else Normalizer.normalize(w, Normalizer.Form.NFC)
        for (c in nfc) when (c) {
            '­' -> {}
            '’', 'ʼ', '‘' -> sb.append('\'')
            '‐', '‑' -> sb.append('-')
            else -> sb.append(c)
        }
        return sb.toString().lowercase()
    }

    /**
     * Définitions du mot [written] tel qu'il est écrit dans le livre ; [start] : il ouvre sa phrase
     * (sa majuscule ne dit alors rien) ; [clipped] : une apostrophe le précède (’ouse pour house).
     * [target] : langue des traductions à joindre aux articles.
     * Null : pas de dictionnaire installé pour [lang] (ou d'un ancien format, voir [outdated]).
     * Quelques dixièmes de milliseconde : s'appelle depuis le thread principal.
     */
    @Synchronized   // aussi appelé par le fil de tracé des pages (voir WordWise.fallback)
    fun lookup(written: String, lang: String, start: Boolean, clipped: Boolean, target: String): Result? {
        val b = book(lang) ?: return null
        want = target
        memo.clear()
        val out = ArrayList<Section>()
        try {
            val word = written.replace("\u00AD", "").trim('\'', '’', '-')
            val w = norm(word)
            for (k in candidates(w, lang, clipped)) {
                // « dog's » : le mot nu d'abord, puis l'éventuel article de la forme écrite (it's).
                val shown = when {
                    k == w -> word
                    w.startsWith(k) -> word.take(k.length)
                    w.endsWith(k) -> word.takeLast(k.length)
                    else -> k
                }
                collect(b, k, shown, start, out)
                if (out.isNotEmpty() && !(lang == "en" && k.length < w.length)) break
            }
            // Mot composé inconnu (tea-table) : chacun de ses éléments.
            if (out.isEmpty() && w.indexOf('-') > 0) {
                for (part in word.split('-', '‐', '‑')) if (part.length > 1 && out.size < 4) {
                    for (k in candidates(norm(part), lang, false)) {
                        val n = out.size
                        collect(b, k, part, start, out)
                        if (out.size > n) break
                    }
                }
            }
        } catch (e: Exception) {
            // Fichier tronqué ou abîmé : on se contente de ce qui a été lu.
        }
        memo.clear()
        return Result(out, b.credit)
    }

    /** Les graphies à essayer, de la plus fidèle à la plus lâche. */
    private fun candidates(w: String, lang: String, clipped: Boolean): List<String> {
        val out = ArrayList<String>(4)
        fun add(s: String) { if (s.isNotEmpty() && s !in out) out.add(s) }
        // Parler populaire des romans anglais : 'ouse et nothin' ont leur article, apostrophe comprise.
        if (clipped && lang == "en") add("'$w")
        val q = w.indexOf('\'')
        if (q > 0 && lang == "en") for (e in EN_ENDINGS) if (w.endsWith(e)) { add(w.dropLast(e.length)); break }
        add(w)
        if (q > 0 && lang != "en" && w.substring(0, q) in FR_ELIDED) add(w.substring(q + 1))
        // Ligatures souvent absentes des livres numériques : coeur → cœur.
        if (w.contains("oe") || w.contains("ae")) add(w.replace("oe", "œ").replace("ae", "æ"))
        if (w.indexOf('-') > 0) add(w.replace("-", ""))
        if (lang == "en") { add("$w'"); add("'$w") }
        return out
    }

    private fun collect(b: Book, key: String, written: String, start: Boolean, out: ArrayList<Section>) {
        val rec = find(b, key) ?: return
        // Polish / polish, Paris / paris : la casse du livre choisit l'article. En début de phrase
        // la majuscule ne prouve rien, le nom commun passe devant ; un mot écrit en minuscules
        // n'est jamais un nom propre.
        fun flat(s: String) = s.replace('’', '\'')
        val seen = flat(written)
        val lower = seen.lowercase()
        val cap = seen != lower
        val exact = rec.entries.filter { flat(it.word) == seen }
        val low = if (cap) rec.entries.filter { flat(it.word) == lower } else emptyList()
        val own = (if (start) low + exact else exact + low).ifEmpty {
            // Aucun article à cette casse : ceux d'une autre casse, noms propres exceptés pour un mot en minuscules.
            if (rec.links.isNotEmpty()) emptyList()
            else if (cap) rec.entries
            else rec.entries.filter { e -> e.groups.any { it.pos != "name" } }
        }
        var front = out.size
        for (e in own) if (out.none { it.entry.word == e.word }) out.add(Section(e, "", ArrayList(e.groups), false))
        if (cap && !start && exact.isNotEmpty()) return
        for (l in rec.links) {
            if (!cap && l.lemma[0].isUpperCase()) continue
            val target = find(b, norm(l.lemma)) ?: continue
            val named = target.entries.filter { it.word == l.lemma }
            for (e in named.ifEmpty { target.entries }) {
                // Seule la catégorie visée par le renvoi : « left » → le verbe « leave », pas le nom.
                val groups = e.groups.filter { it.pos == l.pos }.ifEmpty { e.groups }
                val same = out.firstOrNull { it.linked && it.entry.word == e.word }
                if (same != null) { for (g in groups) if (same.groups.none { it.label == g.label }) same.groups.add(g) }
                // Renvoi marqué par le fichier (prétérit anglais : left → leave) : il passe devant
                // l'article du mot lui-même.
                else if (l.label.startsWith(FRONT)) out.add(front++, Section(e, l.label.substring(1), ArrayList(groups), true))
                else out.add(Section(e, l.label, ArrayList(groups), true))
            }
        }
    }

    // ---------------------------------------------------------------- lecture du fichier

    /** Compare [key] à la première clé du bloc [i] (ordre des octets non signés). */
    private fun compareFirst(b: Book, i: Int, key: ByteArray): Int {
        val p = b.pool + b.map.getInt(b.index + 12 * i)
        val n = b.map.getShort(p).toInt() and 0xFFFF
        val m = minOf(n, key.size)
        for (j in 0 until m) {
            val d = (key[j].toInt() and 0xFF) - (b.map.get(p + 2 + j).toInt() and 0xFF)
            if (d != 0) return d
        }
        return key.size - n
    }

    /** Un même lemme est souvent visé par plusieurs renvois (steps → step, nom et verbe) : lu une seule fois par recherche. */
    private val memo = HashMap<String, Record?>()

    private fun find(b: Book, word: String): Record? {
        if (memo.containsKey(word)) return memo[word]
        val r = read(b, word)
        memo[word] = r
        return r
    }

    private val inf = Inflater(true)   // un seul décompresseur, remis à zéro à chaque bloc

    private fun read(b: Book, word: String): Record? {
        val key = word.toByteArray(Charsets.UTF_8)
        if (b.blocks == 0 || compareFirst(b, 0, key) < 0) return null
        var lo = 0
        var hi = b.blocks - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (compareFirst(b, mid, key) >= 0) lo = mid else hi = mid - 1
        }
        val at = b.index + 12 * lo
        val from = b.map.getInt(at + 4)
        val packed = ByteArray(b.map.getInt(at + 16) - from)
        (b.map.duplicate().position(from) as ByteBuffer).get(packed)
        val raw = ByteArray(b.map.getInt(at + 8))
        inf.reset()
        // Flux deflate brut : le dictionnaire commun se donne avant les données (voir tools/make_dict.py).
        if (b.zdict.isNotEmpty()) inf.setDictionary(b.zdict)
        inf.setInput(packed)
        var n = 0
        while (n < raw.size) {
            val got = inf.inflate(raw, n, raw.size - n)
            if (got <= 0) break
            n += got
        }
        val r = Bytes(raw)
        while (r.p < raw.size) {
            val kl = r.int()
            val ks = r.p
            r.p += kl
            val vl = r.int()
            var d = 0
            val m = minOf(kl, key.size)
            for (j in 0 until m) {
                d = (key[j].toInt() and 0xFF) - (raw[ks + j].toInt() and 0xFF)
                if (d != 0) break
            }
            if (d == 0) d = key.size - kl
            if (d == 0) return record(r)
            if (d < 0) return null    // les clés sont triées : on a dépassé
            r.p += vl
        }
        return null
    }

    private fun record(r: Bytes): Record {
        val entries = ArrayList<Entry>()
        repeat(r.int()) {
            val word = r.str()
            val ipa = r.str()
            val groups = ArrayList<Group>()
            repeat(r.int()) {
                val pos = r.str()
                val label = r.str()
                val count = r.int()
                val at = r.p
                repeat(count) { r.skip() }
                // Traductions : seul le bloc de la langue demandée est repéré, les autres sont sautés.
                var trAt = 0
                var trCount = 0
                repeat(r.int()) {
                    val lang = r.str()
                    val end = r.int() + r.p
                    if (lang == want) { trCount = r.int(); trAt = r.p }
                    r.p = end
                }
                groups.add(Group(pos, label, r.b, at, count, trAt, trCount))
            }
            entries.add(Entry(word, ipa, groups))
        }
        val links = ArrayList<Link>()
        repeat(r.int()) { links.add(Link(r.str(), r.str(), r.str())) }
        return Record(entries, links)
    }

    internal class Bytes(val b: ByteArray) {
        var p = 0

        fun int(): Int {
            var v = 0
            var s = 0
            while (true) {
                val x = b[p++].toInt()
                v = v or ((x and 0x7F) shl s)
                if (x >= 0) return v
                s += 7
            }
        }

        fun skip() {
            val n = int()   // lu d'abord : « p += int() » perdrait l'avance faite par int()
            p += n
        }

        fun str(): String {
            val n = int()
            val s = String(b, p, n, Charsets.UTF_8)
            p += n
            return s
        }
    }
}
