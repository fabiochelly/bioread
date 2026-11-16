package app.bioread

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Césure des mots par motifs (algorithme de Liang, celui de TeX), avec les motifs du projet
 * hyph-utf8 livrés dans assets/hyph (un fichier par langue, voir tools/fetch_hyph.sh). Le
 * compositeur de paragraphes ([KpLayout]) a besoin de connaître tous les points de coupure d'un
 * mot, ce que le système ne dit pas ; et les mêmes motifs donnent les mêmes coupures sur tous
 * les appareils.
 *
 * Les motifs sont rangés dans un arbre de préfixes tenu dans des tableaux (une table de hachage
 * ouverte « nœud, lettre → nœud ») : aucune allocation par mot, lecture seule une fois construit.
 */
class Hyphen(src: String, private val leftMin: Int, private val rightMin: Int) {
    private var keys = LongArray(1 shl 12) { -1L }
    private var vals = IntArray(1 shl 12)
    private var used = 0
    private var nodes = 1
    private var levels = arrayOfNulls<ByteArray>(1 shl 10)   // par nœud : niveaux du motif qui s'y termine
    private val exceptions = HashMap<String, BooleanArray>()
    private var longest = 0

    init {
        var i = 0
        val n = src.length
        val letters = StringBuilder()
        val digits = ArrayList<Byte>()
        while (i < n) {
            var e = src.indexOf('\n', i)
            if (e < 0) e = n
            var a = i
            var b = e
            while (a < b && src[a] <= ' ') a++
            while (b > a && src[b - 1] <= ' ') b--
            if (b > a) {
                var hasDigit = false
                for (k in a until b) if (src[k] in '0'..'9') { hasDigit = true; break }
                if (hasDigit || src.indexOf('-', a).let { it < 0 || it >= b }) {
                    // Motif : « a1b2c » = lettres abc, niveaux 0 1 2 0 (un niveau devant chaque lettre, un après).
                    letters.setLength(0)
                    digits.clear()
                    digits.add(0)
                    for (k in a until b) {
                        val c = src[k]
                        if (c in '0'..'9') digits[digits.size - 1] = (c - '0').toByte()
                        else { letters.append(c); digits.add(0) }
                    }
                    var node = 0
                    for (k in 0 until letters.length) node = child(node, letters[k], true)
                    if (node >= levels.size) levels = levels.copyOf(maxOf(levels.size * 2, node + 1))
                    levels[node] = ByteArray(digits.size) { digits[it] }
                    if (letters.length > longest) longest = letters.length
                } else {
                    // Exception : le mot avec ses coupures (as-so-ciate).
                    val word = StringBuilder()
                    val cuts = ArrayList<Int>()
                    for (k in a until b) if (src[k] == '-') cuts.add(word.length) else word.append(src[k])
                    val at = BooleanArray(word.length + 1)
                    for (c in cuts) at[c] = true
                    exceptions[word.toString()] = at
                }
            }
            i = e + 1
        }
    }

    private fun slot(key: Long): Int {
        val mask = keys.size - 1
        var h = ((key * -0x61c8864680b583ebL) ushr 40).toInt() and mask
        while (true) {
            val k = keys[h]
            if (k == key || k == -1L) return h
            h = (h + 1) and mask
        }
    }

    private fun child(node: Int, c: Char, create: Boolean): Int {
        val key = (node.toLong() shl 16) or c.code.toLong()
        var s = slot(key)
        if (keys[s] == key) return vals[s]
        if (!create) return -1
        if (used * 2 >= keys.size) {
            val ok = keys
            val ov = vals
            keys = LongArray(ok.size * 2) { -1L }
            vals = IntArray(ok.size * 2)
            for (k in ok.indices) if (ok[k] != -1L) { val t = slot(ok[k]); keys[t] = ok[k]; vals[t] = ov[k] }
            s = slot(key)
        }
        keys[s] = key
        vals[s] = nodes
        used++
        return nodes++
    }

    /**
     * Points de coupure du mot text[start, end) : met out[i] à vrai quand le mot peut être coupé
     * devant son caractère d'indice absolu i. Rend le nombre de coupures. Les mots trop courts,
     * ou avec des lettres inconnues des motifs, n'en ont simplement aucune.
     */
    fun points(text: CharArray, start: Int, end: Int, out: BooleanArray): Int {
        val n = end - start
        if (n < leftMin + rightMin || n > 64) return 0
        // Le mot en minuscules, encadré de points : « .mot. ».
        val w = CharArray(n + 2)
        w[0] = '.'
        for (k in 0 until n) w[k + 1] = Character.toLowerCase(text[start + k])
        w[n + 1] = '.'
        var count = 0
        if (exceptions.isNotEmpty()) {
            val ex = exceptions[String(w, 1, n)]
            if (ex != null) {
                for (k in leftMin..n - rightMin) if (ex[k]) { out[start + k] = true; count++ }
                return count
            }
        }
        val score = ByteArray(n + 3)
        for (i in 0 until n + 2) {
            var node = 0
            var j = i
            while (j < n + 2) {
                node = child(node, w[j], false)
                if (node < 0) break
                j++
                val lv = if (node < levels.size) levels[node] else null
                if (lv != null) for (k in lv.indices) if (lv[k] > score[i + k]) score[i + k] = lv[k]
            }
        }
        // score[p] : devant le caractère p de « .mot. », soit devant le caractère p - 1 du mot.
        for (k in leftMin..n - rightMin) if (score[k + 1].toInt() and 1 == 1) { out[start + k] = true; count++ }
        return count
    }

    companion object {
        private var app: Context? = null
        private val loaded = ConcurrentHashMap<String, Any>()
        private val NONE = Any()
        // Lettres à garder au moins avant et après une coupure : celles des motifs de chaque langue,
        // portées à trois de chaque côté en anglais et en français (ni « ha-bitude », ni « égali-té »).
        private val MINS = mapOf(
            "en" to intArrayOf(3, 3), "fr" to intArrayOf(3, 3), "de" to intArrayOf(2, 2), "es" to intArrayOf(2, 2),
            "it" to intArrayOf(2, 2), "pt" to intArrayOf(2, 3), "nl" to intArrayOf(2, 2),
        )

        fun init(c: Context) {
            app = c.applicationContext
        }

        /** Motifs de la langue [lang] (code à deux lettres), lus à la première demande ; null s'il n'y en a pas. */
        fun of(lang: String): Hyphen? {
            val code = lang.take(2).lowercase()
            val mins = MINS[code] ?: return null
            loaded[code]?.let { return it as? Hyphen }
            synchronized(loaded) {
                loaded[code]?.let { return it as? Hyphen }
                val h = try {
                    val src = app!!.assets.open("hyph/$code.txt").use { String(it.readBytes(), Charsets.UTF_8) }
                    Perf.time("hyphen.load $code") { Hyphen(src, mins[0], mins[1]) }
                } catch (e: Throwable) {
                    null
                }
                loaded[code] = h ?: NONE
                return h
            }
        }
    }
}
