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

    // Contexte qui écarte ou appelle un verbe : « the stalk » est un nom, « to stalk » un verbe.
    private val DET = hashSetOf(
        "a", "an", "the", "my", "your", "his", "her", "its", "our", "their", "this", "that", "these", "those",
        "some", "any", "no", "every", "each", "such", "what", "which", "whose", "of", "very", "too", "so", "more", "most",
    )
    // Pas « and », ni « was » ou « is » : ils précèdent aussi bien un adjectif (« and pale », « was pale »).
    private val BEFORE_VERB = hashSetOf(
        "to", "will", "would", "can", "could", "shall", "should", "may", "might", "must", "i", "you", "he", "she",
        "we", "they", "did", "do", "does", "didn't", "don't", "doesn't", "won't", "can't", "couldn't", "wouldn't",
    )

    private var app: Context? = null

    /** Au lancement : la table ne sera ouverte qu'à son premier usage. */
    fun init(c: Context) {
        app = c.applicationContext
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
                    if (String(magic, Charsets.US_ASCII) != "BIOWW002") return false
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
     */
    private class Rec(val rank: Int, val kinds: IntArray, val ranks: IntArray, val gloss: IntArray) {
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
        val n = m.get(at[0]++).toInt() and 0xFF
        val kinds = IntArray(n)
        val ranks = IntArray(n)
        val gl = IntArray(n)
        for (r in 0 until n) {
            kinds[r] = m.get(at[0]++).toInt() and 0xFF
            ranks[r] = varint(m, at)
            gl[r] = varint(m, at)
        }
        return Rec(rank, kinds, ranks, gl)
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

    /**
     * Mots à traduire dans [text] pour le vocabulaire [level] : triplets (début, fin, glose) à plat.
     * Les noms propres (majuscule hors début de phrase) sont ignorés.
     */
    fun marks(text: CharSequence, level: Int): Marks {
        val out = ArrayList<Any>()
        scan(text) { word, i, j, sentence, prev ->
            val capital = Character.isUpperCase(word[0])
            val k = key(word)
            // Majuscule en début de phrase sur un mot que le livre écrit ailleurs avec une majuscule
            // au milieu d'une phrase : un nom propre (Eve), pas le mot eve.
            val name = capital && sentence && k in names
            if (capital && !sentence || name || obvious(k, level, 0)) return@scan
            val r = rec(k)
            // Repli sur une définition anglaise : mot absent de la table (très rare) ou mot plus
            // rare que le vocabulaire du lecteur sans traduction.
            val plain = !capital && word.length >= 5
            if (r != null && r.kinds.isNotEmpty() && r.top > level) {
                // La lecture que le contexte désigne, si elle est elle-même plus rare que le
                // vocabulaire du lecteur (« the tower » : non ; « towered over » : oui).
                val x = pick(r, prev, word)
                val g = shown(r, x)
                if (g >= 0 && r.ranks[x] > level) { out.add(i); out.add(j); out.add(gloss(g)) }
            } else if (r != null && r.kinds.isEmpty() && r.rank > level) {
                if (plain) fallback(k)?.let { out.add(i); out.add(j); out.add(it) }
            } else if (r == null && plain) {
                fallback(k)?.let { out.add(i); out.add(j); out.add(it) }
            }
        }
        val a = IntArray(out.size / 3 * 2)
        val s = arrayOfNulls<String>(out.size / 3)
        for (k in s.indices) {
            a[2 * k] = out[3 * k] as Int
            a[2 * k + 1] = out[3 * k + 1] as Int
            s[k] = out[3 * k + 2] as String
        }
        return Marks(level, a, s)
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
    private fun shown(r: Rec, k: Int) = if (r.kinds[k] and TRANSPARENT != 0 || r.gloss[k] == 0) -1 else r.gloss[k]

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

    /** Mots à traduire d'un paragraphe : [at] = (début, fin) par mot, [gloss] leur traduction. */
    class Marks(val level: Int, val at: IntArray, val gloss: Array<String?>)

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
                }
                q++
            }
        }
        p.textSkewX = 0f
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
