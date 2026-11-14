package app.bioread

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.TextPaint
import android.text.TextUtils
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Traduction sous les mots difficiles : dans un livre anglais, chaque mot plus rare que le
 * vocabulaire du lecteur porte en tout petit, sous lui, sa traduction française.
 *
 * Le classement des mots est fait d'avance par tools/make_wordwise.py (fréquence, taux de
 * connaissance mesuré chez des non-natifs, âge d'acquisition ; voir l'en-tête du script) et
 * livré dans l'app (assets/wordwise-en-fr.bin). Chaque forme (ran, running) y porte un rang :
 * une taille de vocabulaire. Le réglage [Prefs.wwLevel] est celle du lecteur ; un mot est
 * traduit si son rang la dépasse. Les mots transparents (melancholy → mélancolie) ne sont
 * jamais traduits, les faux amis (library → bibliothèque) toujours.
 *
 * Les expressions (gave up → abandonner, put up with → supporter) sont dans la même table, à
 * chacune de leurs formes : un mot qui en commence une porte un drapeau, et seul ce mot-là fait
 * chercher les mots qui le suivent. Le lecteur peut enfin écarter un mot qu'il connaît, ou
 * demander qu'un mot courant soit toujours traduit (appui long, voir [choice]).
 *
 * Le fichier est projeté en mémoire (non compressé dans l'APK) : rien n'est chargé dans le tas.
 */
object WordWise {
    const val ASSET = "wordwise-en-fr.bin"

    /** Bornes du réglage (taille de vocabulaire) : en deçà de 3 000 mots, on ne lit pas un roman anglais. */
    const val MIN = 3000
    const val MAX = 30000

    private var map: ByteBuffer? = null
    private var tried = false
    private var blocks = 0
    private var pIndex = 0
    private var pBody = 0
    private var pRecs = 0
    private var pGBody = 0
    private val glosses = HashMap<Int, String>()

    // Catégories des lectures (fichier) et bit « transparent ».
    private const val NOUN = 1
    private const val VERB = 2
    private const val ADJ = 3
    private const val ADV = 4
    private const val TRANSPARENT = 8
    private const val LEMMA = 16         // lecture d'une expression qui ne traduit pas : sa glose est sa forme de base (give up)

    // Contexte qui écarte ou appelle un verbe : « the stalk » est un nom, « to stalk » un verbe.
    private val DET = hashSetOf(
        "a", "an", "the", "my", "your", "his", "her", "its", "our", "their", "this", "that", "these", "those",
        "some", "any", "no", "every", "each", "such", "what", "which", "whose", "of", "very", "too", "so", "more", "most",
    )
    // Particules des verbes à particule, seules admises après un pronom intercalé (gave it up).
    private val PARTICLES = hashSetOf(
        "up", "down", "out", "off", "in", "on", "over", "away", "back", "around", "round", "about",
        "through", "along", "apart", "aside", "together", "forward", "behind", "across", "by",
    )
    // Possessifs et réfléchis : ils tiennent la place de one's et oneself dans les expressions.
    private val POSS = hashSetOf("my", "your", "his", "her", "its", "our", "their")
    private val REFL = hashSetOf("myself", "yourself", "himself", "herself", "itself", "ourselves", "yourselves", "themselves")
    // Pronoms compléments, entre un verbe et sa particule : « gave it up ».
    private val OBJECT = hashSetOf("it", "him", "her", "them", "me", "us", "you", "this", "that")
    // Pas « and », ni « was » ou « is » : ils précèdent aussi bien un adjectif (« and pale », « was pale »).
    private val BEFORE_VERB = hashSetOf(
        "to", "will", "would", "can", "could", "shall", "should", "may", "might", "must", "i", "you", "he", "she",
        "we", "they", "did", "do", "does", "didn't", "don't", "doesn't", "won't", "can't", "couldn't", "wouldn't",
    )

    private var app: Context? = null

    /** Au lancement : la table ne sera ouverte qu'à son premier usage. */
    fun init(c: Context) {
        app = c.applicationContext
        known.addAll(Prefs.sp.getStringSet(KNOWN, emptySet())!!)
        always.addAll(Prefs.sp.getStringSet(ALWAYS, emptySet())!!)
    }

    /** Un livre dans la langue [lang] reçoit-il les traductions (réglage actif, anglais vers français, table présente) ? */
    fun applies(lang: String) = Prefs.wordwise && lang.lowercase().startsWith("en") && Prefs.target == "fr" && open()

    @Synchronized
    private fun open(): Boolean {
        if (tried) return map != null
        val c = app ?: return false
        tried = true
        try {
            c.assets.openFd(ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    val m = ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
                    m.order(ByteOrder.LITTLE_ENDIAN)
                    val magic = ByteArray(8) { m.get(it) }
                    if (String(magic, Charsets.US_ASCII) != "BIOWW003") return false
                    blocks = m.getInt(12)
                    pIndex = m.getInt(16)
                    pBody = m.getInt(20)
                    pRecs = m.getInt(24)
                    pGBody = m.getInt(28)
                    map = m
                }
            }
        } catch (e: Exception) {
            map = null
        }
        return map != null
    }

    // ---------------------------------------------------------------- lecture de la table

    private fun varint(m: ByteBuffer, at: IntArray): Int {
        var v = 0
        var s = 0
        while (true) {
            val x = m.get(at[0]++).toInt()
            v = v or ((x and 0x7F) shl s)
            if (x >= 0) return v
            s += 7
        }
    }

    /** Compare [key] à la première forme du bloc [i], écrite en entier (ordre des octets non signés). */
    private fun compare(m: ByteBuffer, i: Int, key: ByteArray): Int {
        val at = intArrayOf(pBody + m.getInt(pIndex + 4 * i) + 1)   // + 1 : son préfixe commun, toujours 0
        val n = varint(m, at)
        val p = at[0]
        val k = minOf(n, key.size)
        for (j in 0 until k) {
            val d = (key[j].toInt() and 0xFF) - (m.get(p + j).toInt() and 0xFF)
            if (d != 0) return d
        }
        return key.size - n
    }

    /**
     * Fiche d'une forme : rang (celui de sa lecture la plus facile : le lecteur connaît-il le mot ?),
     * puis lectures (catégorie et drapeaux, rang propre, glose). Une lecture a son propre rang : le
     * verbe de tower (« dominer ») est bien plus rare que le nom. [top] : le rang de la plus rare.
     * [head] : la forme commence au moins une expression (gave → gave up).
     */
    private class Rec(val rank: Int, val kinds: IntArray, val ranks: IntArray, val gloss: IntArray, val head: Boolean) {
        val top = ranks.maxOrNull() ?: rank
    }

    /**
     * Dichotomie sur la première forme de chaque bloc, puis parcours du bloc désigné. Chaque forme
     * n'y est écrite que par ce qui la distingue de la précédente (p octets en commun, puis la
     * suite) : le parcours n'a jamais à la reconstituer. Il suffit de suivre [same], le nombre
     * d'octets que la forme précédente partage avec [key], et de comparer la suite quand p l'égale.
     */
    private fun find(key: String): Rec? {
        if (!open()) return null
        val m = map ?: return null
        val k = key.toByteArray(Charsets.UTF_8)
        if (blocks == 0 || compare(m, 0, k) < 0) return null
        var lo = 0
        var hi = blocks - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (compare(m, mid, k) >= 0) lo = mid else hi = mid - 1
        }
        val at = intArrayOf(pBody + m.getInt(pIndex + 4 * lo))
        val end = if (lo + 1 < blocks) pBody + m.getInt(pIndex + 4 * lo + 4) else pRecs
        var same = 0
        while (at[0] < end) {
            val p = m.get(at[0]++).toInt() and 0xFF
            val n = varint(m, at)
            val s = at[0]
            at[0] += n
            val ref = varint(m, at)
            // p > same : elle garde l'octet où la précédente est plus petite que key ; elle l'est donc aussi.
            if (p > same) continue
            // p < same : elle quitte la précédente (vers le haut) à un octet où celle-ci égalait key : key est dépassée.
            if (p < same) return null
            var j = 0
            while (j < n && same + j < k.size && m.get(s + j) == k[same + j]) j++
            if (j == n && same + j == k.size) return record(m, pRecs + ref)
            // Forme plus courte que key et préfixe de key, ou premier octet différent plus petit : on continue.
            if (j < n && (same + j == k.size || (m.get(s + j).toInt() and 0xFF) > (k[same + j].toInt() and 0xFF))) return null
            same += j
        }
        return null
    }

    private fun record(m: ByteBuffer, from: Int): Rec {
        val at = intArrayOf(from)
        val rank = varint(m, at)
        val nb = m.get(at[0]++).toInt() and 0xFF
        val n = nb and 0x7F
        val kinds = IntArray(n)
        val ranks = IntArray(n)
        val gl = IntArray(n)
        for (r in 0 until n) {
            kinds[r] = m.get(at[0]++).toInt() and 0xFF
            ranks[r] = varint(m, at)
            gl[r] = varint(m, at)
        }
        return Rec(rank, kinds, ranks, gl, nb and 0x80 != 0)
    }

    /** Glose désignée par sa position dans le réservoir (0 : la glose vide). */
    @Synchronized
    private fun gloss(id: Int): String {
        glosses[id]?.let { return it }
        val m = map ?: return ""
        val at = intArrayOf(pGBody + id)
        val n = varint(m, at)
        val b = ByteArray(n)
        for (j in 0 until n) b[j] = m.get(at[0] + j)
        val s = String(b, Charsets.UTF_8)
        if (glosses.size > 4000) glosses.clear()
        glosses[id] = s
        return s
    }

    /** Fiches déjà lues, par forme (null : forme absente de la table). */
    private val recs = HashMap<String, Rec?>()

    @Synchronized
    private fun rec(key: String): Rec? {
        if (recs.containsKey(key)) return recs[key]
        val r = find(key)
        if (recs.size > 20000) recs.clear()
        recs[key] = r
        return r
    }

    /** Niveau européen correspondant, à peu près, à un vocabulaire de [n] mots sur cette échelle. */
    fun cefr(n: Int) = when {
        n < 4500 -> "B1"
        n < 8000 -> "B2"
        n < 14000 -> "C1"
        n < 22000 -> "C2"
        else -> "natif"
    }

    private fun key(w: String): String {
        var k = Dict.norm(w).trim('\'', '-')
        if (k.endsWith("'s")) k = k.dropLast(2)
        return k
    }

    // ---------------------------------------------------------------- repérage dans un paragraphe

    /**
     * Mots de [text], dans l'ordre : [each] reçoit le mot, sa place [i, j), s'il ouvre une phrase et
     * le mot qui le précède (en minuscules, vide après une ponctuation forte). Un mot composé absent
     * de la table (lodging-house) donne chacun de ses éléments.
     */
    private inline fun scan(text: CharSequence, each: (word: String, i: Int, j: Int, sentence: Boolean, prev: String) -> Unit) {
        val n = text.length
        var i = 0
        var sentence = true
        var prev = ""
        while (i < n) {
            val c = text[i]
            if (!Character.isLetter(c)) {
                if (c == '.' || c == '!' || c == '?' || c == '“' || c == '"' || c == ':' || c == '—') sentence = true
                if (!Character.isWhitespace(c) && c != '’' && c != '\'' && c != ',' && c != '“' && c != '"') prev = ""
                i++
                continue
            }
            var j = i + 1
            while (j < n) {
                val d = text[j]
                if (Character.isLetter(d) || d == '­') { j++; continue }
                // Apostrophe ou trait d'union entre deux lettres : le mot continue (don't, well-known).
                if ((d == '\'' || d == '’' || d == '-' || d == '‐') && j + 1 < n && Character.isLetter(text[j + 1])) { j += 2; continue }
                break
            }
            var word = text.subSequence(i, j).toString()
            val h = word.indexOfFirst { it == '-' || it == '‐' }
            if (h > 0 && rec(key(word)) == null) {
                j = i + h
                word = word.substring(0, h)
            }
            each(word, i, j, sentence, prev)
            prev = word.lowercase()
            sentence = false
            i = j
        }
    }

    /** Mots d'un paragraphe, relevés une fois pour pouvoir regarder ceux qui suivent (expressions). */
    private class Tok(val word: String, val key: String, val i: Int, val j: Int, val sentence: Boolean, val prev: String, val joined: Boolean) {
        val clipped = word.indexOf('\'') >= 0 || word.indexOf('’') >= 0
    }

    /**
     * Mots à traduire dans [text] pour le vocabulaire [level] : triplets (début, fin, glose) à plat.
     * Les noms propres (majuscule hors début de phrase) sont ignorés. Une expression (gave up)
     * est traduite d'un bloc, sous tous ses mots, et aucun d'eux n'est alors traduit seul.
     */
    fun marks(text: CharSequence, level: Int): Marks {
        val toks = ArrayList<Tok>()
        var end = 0
        scan(text) { word, i, j, sentence, prev ->
            // Rien d'autre que des blancs depuis le mot précédent : il peut former une expression avec lui.
            var joined = toks.isNotEmpty()
            for (x in end until i) if (!Character.isWhitespace(text[x])) { joined = false; break }
            toks.add(Tok(word, key(word), i, j, sentence, prev, joined))
            end = j
        }
        val out = ArrayList<Any>()
        var t = 0
        while (t < toks.size) {
            val tk = toks[t]
            val word = tk.word
            val k = tk.key
            val i = tk.i
            val j = tk.j
            val capital = Character.isUpperCase(word[0])
            // Majuscule en début de phrase sur un mot que le livre écrit ailleurs avec une majuscule
            // au milieu d'une phrase : un nom propre (Eve), pas le mot eve.
            val name = capital && tk.sentence && k in names
            if (capital && !tk.sentence || name) { t++; continue }
            val r = rec(k)
            // Un mot à apostrophe (what's, Harry's) n'entre dans aucune expression : « what's with » n'est pas « what with ».
            if (r != null && r.head && !tk.clipped) {
                val e = phrase(toks, t)
                if (e != null) {
                    val (last, x) = e
                    val g = shown(x, 0)
                    if (g >= 0 && x.ranks[0] > level && lemma(x) !in known) { out.add(i); out.add(toks[last].j); out.add(gloss(g)); out.add(lemma(x)) }
                    t = last + 1
                    continue
                }
            }
            t++
            if (obvious(k, level, 0)) continue
            // Repli sur une définition anglaise : mot absent de la table (très rare) ou mot plus
            // rare que le vocabulaire du lecteur sans traduction.
            val plain = !capital && word.length >= 5
            if (r != null && r.kinds.isNotEmpty() && r.top > level) {
                // La lecture que le contexte désigne, si elle est elle-même plus rare que le
                // vocabulaire du lecteur (« the tower » : non ; « towered over » : oui).
                val x = pick(r, tk.prev, word)
                val g = shown(r, x)
                if (g >= 0 && r.ranks[x] > level) {
                    if (known.isEmpty() || mine(known, k) == null) { out.add(i); out.add(j); out.add(gloss(g)); out.add(k) }
                } else if (g >= 0) forced(r, x, k)?.let { out.add(i); out.add(j); out.add(it); out.add(k) }
            } else if (r != null && r.kinds.isNotEmpty()) {
                forced(r, pick(r, tk.prev, word), k)?.let { out.add(i); out.add(j); out.add(it); out.add(k) }
            } else if (r != null && r.kinds.isEmpty() && r.rank > level) {
                if (plain) fallback(k)?.let { out.add(i); out.add(j); out.add(it); out.add(k) }
            } else if (r == null && plain) {
                fallback(k)?.let { out.add(i); out.add(j); out.add(it); out.add(k) }
            }
        }
        val n = out.size / 4
        val a = IntArray(2 * n)
        val s = arrayOfNulls<String>(n)
        val keys = arrayOfNulls<String>(n)
        for (q in 0 until n) {
            a[2 * q] = out[4 * q] as Int
            a[2 * q + 1] = out[4 * q + 1] as Int
            s[q] = out[4 * q + 2] as String
            keys[q] = out[4 * q + 3] as String
        }
        return Marks(level, gen, a, s, keys)
    }

    /** Glose d'un mot que le lecteur connaît d'après son vocabulaire, mais qu'il a demandé à voir toujours traduit. */
    private fun forced(r: Rec, x: Int, k: String): String? {
        if (always.isEmpty() || mine(always, k) == null) return null
        val g = shown(r, x).takeIf { it >= 0 } ?: r.kinds.indices.map { shown(r, it) }.firstOrNull { it >= 0 } ?: return null
        return gloss(g)
    }

    /**
     * Expression qui commence au mot [t] de [toks] : la plus longue (cinq mots au plus) que la table
     * connaisse, ses mots séparés par des blancs seulement. Un possessif peut tenir la place de
     * « one's » (lost her temper → lose one's temper), un réfléchi celle de « oneself » (pulled
     * himself together). Ou bien un verbe, un pronom, puis les particules de l'expression
     * (« gave it up »). Rend l'indice de son dernier mot et sa fiche.
     */
    private fun phrase(toks: List<Tok>, t: Int): Pair<Int, Rec>? {
        var best: Pair<Int, Rec>? = null
        fun go(q: Int, s: String, n: Int) {
            if (q >= toks.size || n >= 4) return
            val x = toks[q]
            if (!x.joined || x.clipped || Character.isUpperCase(x.word[0])) return
            val slot = if (x.key in POSS) "one's" else if (x.key in REFL) "oneself" else null
            for (o in if (slot == null) arrayOf(x.key) else arrayOf(x.key, slot)) {
                val s2 = "$s $o"
                expr(s2)?.let { r -> if (best.let { it == null || q > it.first }) best = q to r }
                go(q + 1, s2, n + 1)
            }
        }
        go(t + 1, toks[t].key, 0)
        best?.let { return it }
        if (t + 2 < toks.size && toks[t + 1].joined && toks[t + 1].key in OBJECT) {
            var s = toks[t].key
            for (q in t + 2 until minOf(toks.size, t + 4)) {
                val x = toks[q]
                // Seulement des particules après le pronom : « made her way » n'est pas « make way ».
                if (!x.joined || x.clipped || Character.isUpperCase(x.word[0]) || x.key !in PARTICLES) break
                s += " " + x.key
                expr(s)?.let { r -> if (r.kinds.any { it and 7 == VERB }) best = q to r }
            }
        }
        return best
    }

    /** Fiches d'expressions déjà cherchées (null : pas d'expression). */
    private val exprs = HashMap<String, Rec?>()

    @Synchronized
    private fun expr(key: String): Rec? {
        if (exprs.containsKey(key)) return exprs[key]
        val r = find(key)?.takeIf { it.kinds.isNotEmpty() }
        if (exprs.size > 5000) exprs.clear()
        exprs[key] = r
        return r
    }

    /** Forme de base d'une expression (give up pour gave up), celle que retient la liste des mots connus. */
    private fun lemma(r: Rec): String {
        for (x in r.kinds.indices) if (r.kinds[x] and LEMMA != 0) return gloss(r.gloss[x])
        return ""
    }

    // Nombres écrits en lettres : jamais traduits, seuls ou en composés (forty-five).
    private val NUMBERS = hashSetOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty",
        "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million", "billion",
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
    )

    // Suffixes qui ne changent pas le sens d'un mot connu, et la fin du mot de base :
    // hastily → hasty, surgically → surgical, regrettably → regrettable, lifeless → life…
    private val SUFFIXES = arrayOf(
        "ically" to arrayOf("ical", "ic"), "ally" to arrayOf("al", ""), "ably" to arrayOf("able"),
        "ibly" to arrayOf("ible"), "ily" to arrayOf("y", "ie"), "ly" to arrayOf("", "le", "e"),
        "iness" to arrayOf("y"), "ness" to arrayOf(""), "lessly" to arrayOf(""), "less" to arrayOf(""),
        "fully" to arrayOf(""), "ful" to arrayOf(""),
    )

    /**
     * Mot que le lecteur comprend sans traduction, quel que soit son rang, d'après sa forme :
     * contraction (who'd, ma'am), nombre (forty-five), composé dont tous les éléments sont connus
     * (jet-black, broad-shouldered), dérivé transparent d'un mot connu (silently ← silent,
     * unnatural ← natural, lifeless ← life). « Connu » se juge au vocabulaire [level] du lecteur.
     * Les préfixes re-, over-, out-, under- n'en sont pas : ils donnent des mots opaques (outright,
     * overlook).
     */
    private fun obvious(k: String, level: Int, depth: Int): Boolean {
        if (k.indexOf('\'') >= 0) return true
        if (k in NUMBERS) return true
        if (k.indexOf('-') > 0) {
            val parts = k.split('-').filter { it.isNotEmpty() }
            return parts.size > 1 && parts.all { it in NUMBERS || knows(it, level, depth + 1) }
        }
        if (depth >= 2) return false
        for (p in arrayOf("un", "non")) {
            if (k.length > p.length + 3 && k.startsWith(p) && knows(k.substring(p.length), level, depth + 1)) return true
        }
        for ((suf, ends) in SUFFIXES) {
            if (k.length < suf.length + 3 || !k.endsWith(suf)) continue
            val stem = k.substring(0, k.length - suf.length)
            for (e in ends) if (knows(stem + e, level, depth + 1)) return true
        }
        return false
    }

    /** Le lecteur connaît-il [w] : rang au plus [level], ou lisible par sa forme. */
    private fun knows(w: String, level: Int, depth: Int): Boolean {
        val r = rec(w)
        if (r != null && r.rank <= level) return true
        return obvious(w, level, depth)
    }

    /** Mots vus avec une majuscule au milieu d'une phrase dans le livre ouvert : des noms propres. */
    private val names: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Nouveau livre : ses noms propres sont à réapprendre. */
    fun forgetNames() = names.clear()


    /**
     * Relève les noms propres de [blocks] (un chapitre, sur le fil de composition) : les mots écrits
     * avec une majuscule au milieu d'une phrase. Une passe sur le texte, sans recherche dans la table.
     */
    fun learnNames(blocks: List<Block>) {
        for (b in blocks) {
            if (b.kind != Block.PARA) continue
            val t = b.text
            val n = t.length
            var i = 0
            var sentence = true
            while (i < n) {
                val c = t[i]
                if (!Character.isLetter(c)) {
                    if (c == '.' || c == '!' || c == '?' || c == '“' || c == '"' || c == ':' || c == '—' || c == '\n') sentence = true
                    i++
                    continue
                }
                var j = i + 1
                while (j < n && (Character.isLetter(t[j]) || ((t[j] == '\'' || t[j] == '’') && j + 1 < n && Character.isLetter(t[j + 1])))) j++
                // Mot de deux lettres au moins, en minuscules après sa majuscule (pas un sigle ni « I »).
                if (!sentence && j - i >= 2 && Character.isUpperCase(c) && Character.isLowerCase(t[i + 1])) {
                    names.add(key(t.subSequence(i, j).toString()))
                }
                sentence = false
                i = j
            }
        }
    }

    /** Définitions anglaises déjà cherchées, par forme (null : rien à montrer). */
    private val english = HashMap<String, String?>()

    /**
     * Mot rare sans traduction dans la table (headward) : s'il figure dans le dictionnaire installé
     * sans aucune traduction française, sa courte définition anglaise le remplace, marquée
     * [ENGLISH] (tracée en italique). La table connaît tous les mots courants, traduits ou non
     * (didn't) : un mot qui en est absent est très rare.
     */
    private fun fallback(k: String): String? {
        synchronized(english) { if (english.containsKey(k)) return english[k] }
        var out: String? = null
        try {
            val res = Dict.lookup(k, "en", false, false, "fr")
            if (res != null && res.sections.isNotEmpty() && res.sections.none { s -> s.groups.any { it.trCount > 0 } }) {
                var d = res.sections[0].groups.firstOrNull()?.first ?: ""
                d = d.replace(Regex("^\\([^)]*\\)\\s*"), "").substringBefore(';').trim().trimEnd('.')
                if (d.isNotEmpty() && d.length <= 32) out = ENGLISH + d.replaceFirstChar { it.lowercase() }
            }
        } catch (e: Exception) {
        }
        synchronized(english) {
            if (english.size > 5000) english.clear()
            english[k] = out
        }
        return out
    }

    /** Marque d'une définition anglaise (au lieu d'une traduction) dans [Marks.gloss]. */
    private const val ENGLISH = "\u0001"

    /** Glose de la lecture [k] ; -1 si elle est transparente. */
    private fun shown(r: Rec, k: Int) = if (r.kinds[k] and (TRANSPARENT or LEMMA) != 0 || r.gloss[k] == 0) -1 else r.gloss[k]

    /** La lecture que désigne le mot qui précède (son indice). */
    private fun pick(r: Rec, prev: String, word: String): Int {
        var best = 0
        if (r.kinds.size > 1) {
            val det = prev in DET
            val verb = !det && prev in BEFORE_VERB
            fun cat(k: Int) = r.kinds[k] and 7
            val w = word.lowercase()
            val want = when {
                verb -> r.kinds.indices.firstOrNull { cat(it) == VERB }
                det -> r.kinds.indices.firstOrNull { cat(it) == NOUN || cat(it) == ADJ }
                w.endsWith("ly") -> r.kinds.indices.firstOrNull { cat(it) == ADV }
                // Participe sans déterminant devant : un verbe (« muttering about », « he glared »).
                w.endsWith("ing") || w.endsWith("ed") -> r.kinds.indices.firstOrNull { cat(it) == VERB }
                else -> null
            }
            if (want != null) best = want
        }
        return best
    }

    /**
     * Mots à traduire d'un paragraphe : [at] = (début, fin) par mot, [gloss] leur traduction, [keys]
     * le mot ou l'expression traduits. [level] et [gen] : le réglage et la liste personnelle employés.
     */
    class Marks(val level: Int, val gen: Int, val at: IntArray, val gloss: Array<String?>, val keys: Array<String?>) {
        fun fresh(level: Int) = this.level == level && gen == WordWise.gen
    }

    // ---------------------------------------------------------------- mots du lecteur

    private const val KNOWN = "wwKnown"
    private const val ALWAYS = "wwAlways"

    /** Mots (formes de base) que le lecteur connaît : jamais traduits. */
    private val known: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Mots que le lecteur veut toujours voir traduits, même s'ils sont dans son vocabulaire. */
    private val always: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Change à chaque modification des listes : les mots repérés avant sont à refaire. */
    @Volatile var gen = 0
        private set

    /**
     * Formes de base possibles de [k] (stalked → stalk, stalke ; studies → study) : la liste retient
     * la forme de base, pour que toute la famille du mot suive (stalks, stalking).
     */
    private fun bases(k: String): List<String> {
        val out = ArrayList<String>(6)
        out.add(k)
        fun cut(suf: String, add: String = "") {
            if (k.length > suf.length + 2 && k.endsWith(suf)) {
                val st = k.substring(0, k.length - suf.length)
                out.add(st + add)
                // Consonne doublée : stopped → stop.
                if (add.isEmpty() && st.length > 3 && st[st.length - 1] == st[st.length - 2]) out.add(st.dropLast(1))
            }
        }
        cut("ies", "y"); cut("ied", "y"); cut("es"); cut("s"); cut("ed"); cut("ed", "e"); cut("ing"); cut("ing", "e")
        cut("er"); cut("est")
        return out
    }

    /** Celle des formes de base de [k] que contient la liste [set], ou null. */
    private fun mine(set: Set<String>, k: String): String? = bases(k).firstOrNull { it in set }

    /** Ce que l'appui long propose sur un mot : l'ajouter à une liste ([add]) ou l'en retirer. */
    class Choice(val label: String, val key: String, val list: Int, val add: Boolean)

    const val LIST_KNOWN = 1
    const val LIST_ALWAYS = 2

    /**
     * Choix offert par la feuille d'un mot [start, end) du paragraphe [b] : ne plus traduire un mot
     * ou une expression traduits ; traduire de nouveau un mot écarté ; toujours traduire un mot
     * que le réglage laisse sans traduction. Null si le mot n'a pas de traduction dans la table.
     */
    fun choice(b: Block, start: Int, end: Int): Choice? {
        if (!open()) return null
        val m = b.ww?.takeIf { it.fresh(Prefs.wwLevel) }
        if (m != null) for (q in m.gloss.indices) {
            if (m.at[2 * q] > start || m.at[2 * q + 1] < end) continue
            val k = m.keys[q] ?: return null
            if (m.gloss[q]?.startsWith(ENGLISH) == true) return null
            mine(always, k)?.let { return Choice("Ne plus forcer la traduction de « $it »", it, LIST_ALWAYS, false) }
            val base = if (' ' in k) k else family(k)
            return Choice("Je connais « $base » : ne plus le traduire", base, LIST_KNOWN, true)
        }
        val k = key(b.text.substring(start, end))
        mine(known, k)?.let { return Choice("Traduire de nouveau « $it »", it, LIST_KNOWN, false) }
        val r = rec(k) ?: return null
        if (r.kinds.indices.none { shown(r, it) >= 0 }) return null
        val base = family(k)
        return Choice("Toujours traduire « $base »", base, LIST_ALWAYS, true)
    }

    /** Forme de base de [k] dans la table : celle qui en partage une traduction (stalked → stalk), sinon [k]. */
    private fun family(k: String): String {
        val r = rec(k) ?: return k
        val gl = r.gloss.filter { it != 0 }.toSet()
        return bases(k).drop(1).filter { b -> rec(b)?.gloss?.any { it in gl } == true }.minByOrNull { it.length } ?: k
    }

    /** Applique le choix [c] et le garde dans les réglages. */
    fun apply(c: Choice) {
        val set = if (c.list == LIST_KNOWN) known else always
        if (c.add) set.add(c.key) else set.removeAll(bases(c.key).toSet() + c.key)
        gen++
        Prefs.sp.edit().putStringSet(if (c.list == LIST_KNOWN) KNOWN else ALWAYS, HashSet(set)).apply()
    }

    // ---------------------------------------------------------------- tracé

    /**
     * Trace les traductions des lignes [a, b) de [l] ([m] : mots du paragraphe), sous chaque
     * mot, sans chevauchement : de gauche à droite, chacune centrée sous son mot si la place le
     * permet, sinon décalée, et raccourcie (…) si elle ne tient pas avant le mot suivant.
     */
    fun draw(c: Canvas, l: Layout, a: Int, b: Int, m: Marks, p: TextPaint, cw: Int, drop: Float) {
        if (m.gloss.isEmpty()) return
        val gap = p.textSize * 0.5f
        var k = 0
        for (line in a until b) {
            val s = l.getLineStart(line)
            val e = l.getLineEnd(line)
            while (k < m.gloss.size && m.at[2 * k + 1] <= s) k++
            var right = -Float.MAX_VALUE   // bord droit de la traduction précédente sur la ligne
            var q = k
            val y = l.getLineBaseline(line) + drop
            while (q < m.gloss.size && m.at[2 * q] < e) {
                val ws = maxOf(m.at[2 * q], s)
                val we = minOf(m.at[2 * q + 1], e)
                // Positions après justification (voir lineX) : celles du système ignorent le blanc ajouté.
                val x0 = lineX(l, line, ws)
                val x1 = lineX(l, line, we)
                // Limite à droite : le début du mot suivant à traduire sur la même ligne.
                val next = if (q + 1 < m.gloss.size && m.at[2 * (q + 1)] < e) lineX(l, line, m.at[2 * (q + 1)]) - gap / 2 else cw.toFloat()
                val lo = maxOf(0f, right + gap)
                val room = next - lo
                var t = m.gloss[q] ?: ""
                val en = t.startsWith(ENGLISH)
                if (en) t = t.substring(1)
                p.textSkewX = if (en) -0.22f else 0f
                var w = p.measureText(t)
                if (w > room) {
                    t = TextUtils.ellipsize(t, p, maxOf(0f, room), TextUtils.TruncateAt.END).toString()
                    w = p.measureText(t)
                }
                if (t.isNotEmpty() && w > 0f) {
                    val x = ((x0 + x1) / 2 - w / 2).coerceAtMost(next - w).coerceAtLeast(lo)
                    c.drawText(t, x, y, p)
                    right = x + w
                    // Expression (gave up) : un filet de part et d'autre de la traduction, sur toute
                    // la largeur de ses mots, terminé par un petit crochet, montre le groupe traduit.
                    if (m.keys[q]?.indexOf(' ') ?: -1 > 0) span(c, p, x0, x1, x, x + w, y)
                }
                q++
            }
        }
        p.textSkewX = 0f
    }

    /** Filets d'une expression : de [x0] à la traduction [g0, g1), puis jusqu'à [x1], à mi-hauteur des minuscules. */
    private fun span(c: Canvas, p: TextPaint, x0: Float, x1: Float, g0: Float, g1: Float, y: Float) {
        val pad = p.textSize * 0.3f
        val mid = y - p.textSize * 0.28f
        val tick = p.textSize * 0.3f
        val sw = p.strokeWidth
        p.strokeWidth = maxOf(1f, p.textSize * 0.07f)
        if (g0 - pad - x0 > pad) {
            c.drawLine(x0, mid, g0 - pad, mid, p)
            c.drawLine(x0, mid, x0, mid - tick, p)
        }
        if (x1 - g1 - pad > pad) {
            c.drawLine(g1 + pad, mid, x1, mid, p)
            c.drawLine(x1, mid, x1, mid - tick, p)
        }
        p.strokeWidth = sw
    }

    /** Peinture des traductions : la police de l'interface, à un peu plus de la moitié du corps. */
    fun paint(f: Fonts, body: Float, density: Float): TextPaint = Theme.textPaint().apply {
        typeface = f.ui
        textSize = maxOf(body * 0.5f, 8.5f * density)
        color = if (Theme.eink) Theme.fg else Theme.ui
        Theme.hint(this)
        flags = flags or Paint.LINEAR_TEXT_FLAG
    }
}
