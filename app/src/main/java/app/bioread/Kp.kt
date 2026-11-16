package app.bioread

/**
 * Coupure optimale des lignes d'un paragraphe (Knuth et Plass, « Breaking Paragraphs into
 * Lines », 1981 : l'algorithme de TeX). Le paragraphe est une suite de boîtes (morceaux de mots,
 * de largeur fixe), de blancs (espaces, qui s'étirent et se resserrent) et de pénalités (coupures
 * possibles ailleurs qu'à une espace : césure, trait d'union, fin imposée). Parmi toutes les façons
 * de couper, on garde celle dont le total des « démérites » est le plus faible : lignes ni trop
 * lâches ni trop serrées, peu de césures et jamais deux de suite si on peut l'éviter, pas de ligne
 * très lâche à côté d'une ligne très serrée. Le paragraphe est donc composé d'un bloc, et non
 * ligne après ligne : une ligne peut être un peu moins bonne pour que la suivante soit meilleure.
 *
 * Aucune dépendance au système : des tableaux de nombres en entrée, des indices de coupure en
 * sortie. Un objet sert à un fil à la fois ; il est réutilisé de paragraphe en paragraphe.
 */
class KpBreaker {
    // ---- éléments du paragraphe
    var n = 0
        private set
    private var type = ByteArray(256)
    private var w = FloatArray(256)        // largeur (pénalité : celle du trait d'union ajouté si on coupe là)
    private var y = FloatArray(256)        // étirement possible
    private var z = FloatArray(256)        // resserrement possible
    private var pen = IntArray(256)
    private var flag = BooleanArray(256)   // coupure avec trait d'union
    private var rp = FloatArray(256)       // débord à droite si la ligne finit ici (marge optique)
    private var lp = FloatArray(256)       // débord à gauche de la ligne qui commence après cette coupure
    /** Fin du texte visible de la ligne coupée à cet élément, et début de la ligne suivante. */
    var end = IntArray(256)
        private set
    var next = IntArray(256)
        private set
    // Sommes des largeurs, étirements et resserrements avant chaque élément (boîtes et blancs).
    private var sw = FloatArray(257)
    private var sy = FloatArray(257)
    private var sz = FloatArray(257)

    // ---- nœuds : coupures retenues comme fins de ligne possibles
    private var nPos = IntArray(256)
    private var nFit = ByteArray(256)
    private var nPrev = IntArray(256)
    private var nW = FloatArray(256)
    private var nY = FloatArray(256)
    private var nZ = FloatArray(256)
    private var nLp = FloatArray(256)
    private var nDem = DoubleArray(256)
    private var nLine = IntArray(256)
    private var nodes = 0
    private var active = IntArray(64)
    private var actives = 0
    private var maxRight = 0f              // plus grand débord à droite du paragraphe

    fun reset() {
        n = 0
        maxRight = 0f
    }

    private fun grow() {
        val c = type.size * 2
        type = type.copyOf(c); w = w.copyOf(c); y = y.copyOf(c); z = z.copyOf(c); pen = pen.copyOf(c)
        flag = flag.copyOf(c); rp = rp.copyOf(c); lp = lp.copyOf(c); end = end.copyOf(c); next = next.copyOf(c)
        sw = sw.copyOf(c + 1); sy = sy.copyOf(c + 1); sz = sz.copyOf(c + 1)
    }

    fun box(width: Float) {
        if (n == type.size) grow()
        type[n] = BOX; w[n] = width; y[n] = 0f; z[n] = 0f; pen[n] = 0; flag[n] = false
        n++
    }

    /** Blanc entre deux mots : la ligne coupée ici finit en [lineEnd] et la suivante commence en [nextStart]. */
    fun glue(width: Float, stretch: Float, shrink: Float, lineEnd: Int, nextStart: Int, right: Float, left: Float) {
        if (n == type.size) grow()
        type[n] = GLUE; w[n] = width; y[n] = stretch; z[n] = shrink; pen[n] = 0; flag[n] = false
        end[n] = lineEnd; next[n] = nextStart; rp[n] = right; lp[n] = left
        if (right > maxRight) maxRight = right
        n++
    }

    /** Coupure possible sans espace. [width] : largeur du trait d'union ajouté ([hyphen] vrai), sinon 0. */
    fun penalty(width: Float, cost: Int, hyphen: Boolean, lineEnd: Int, nextStart: Int, right: Float, left: Float) {
        if (n == type.size) grow()
        type[n] = PENALTY; w[n] = width; y[n] = 0f; z[n] = 0f; pen[n] = cost; flag[n] = hyphen
        end[n] = lineEnd; next[n] = nextStart; rp[n] = right; lp[n] = left
        if (right > maxRight) maxRight = right
        n++
    }

    /** La coupure à l'élément [i] ajoute-t-elle un trait d'union ? */
    fun hyphenAt(i: Int) = flag[i]

    private fun node(pos: Int, line: Int, fit: Int, prev: Int, dem: Double): Int {
        if (nodes == nPos.size) {
            val c = nodes * 2
            nPos = nPos.copyOf(c); nFit = nFit.copyOf(c); nPrev = nPrev.copyOf(c); nW = nW.copyOf(c); nY = nY.copyOf(c)
            nZ = nZ.copyOf(c); nLp = nLp.copyOf(c); nDem = nDem.copyOf(c); nLine = nLine.copyOf(c)
        }
        val k = nodes++
        nPos[k] = pos; nLine[k] = line; nFit[k] = fit.toByte(); nPrev[k] = prev; nDem[k] = dem
        // La ligne suivante commence à la première boîte : les blancs et pénalités qui suivent la
        // coupure sont abandonnés (sauf une fin imposée, qui donnerait une ligne vide).
        var i = if (pos < 0) 0 else pos
        if (pos >= 0) {
            while (i < n) {
                if (type[i] == BOX) break
                if (type[i] == PENALTY && pen[i] == FORCED && i > pos) break
                i++
            }
        }
        nW[k] = sw[i]; nY[k] = sy[i]; nZ[k] = sz[i]
        nLp[k] = if (pos < 0) 0f else lp[pos]
        return k
    }

    /**
     * Cherche les coupures. [first] : largeur de la première ligne ; [rest] : largeur des
     * suivantes. [left0] : débord à gauche de la première ligne. [tolerance] : taux d'étirement admis (1 = les blancs
     * s'étirent de tout leur étirement prévu ; infini pour accepter n'importe quelle ligne
     * réalisable). Rend les indices des éléments où couper, dans l'ordre, ou null si aucune
     * composition ne respecte la tolérance (ou si un mot dépasse la largeur de la ligne).
     */
    fun run(first: Float, rest: Float, left0: Float, tolerance: Float): IntArray? {
        if (n == 0) return null
        for (i in 0 until n) {
            val b = type[i] != PENALTY
            sw[i + 1] = sw[i] + if (b) w[i] else 0f
            sy[i + 1] = sy[i] + if (b) y[i] else 0f
            sz[i + 1] = sz[i] + if (b) z[i] else 0f
        }
        nodes = 0
        actives = 0
        val start = node(-1, 0, 1, -1, 0.0)
        nLp[start] = left0
        active[actives++] = start
        val bestDem = DoubleArray(4)
        val bestFrom = IntArray(4)
        for (b in 0 until n) {
            val t = type[b]
            val legal = if (t == GLUE) b > 0 && type[b - 1] == BOX else t == PENALTY && pen[b] < INFINITE
            if (!legal) continue
            val forced = t == PENALTY && pen[b] == FORCED
            for (c in 0..3) bestDem[c] = Double.MAX_VALUE
            var kept = 0
            for (ai in 0 until actives) {
                val a = active[ai]
                val base = (if (nLine[a] == 0) first else rest) + nLp[a]
                val lw = base + rp[b]
                var len = sw[b] - nW[a]
                // Trop plein même resserré au maximum, sans compter le trait d'union ni le débord
                // propres à cette coupure : ce départ ne servira plus à aucune coupure suivante.
                if (!forced && len - (sz[b] - nZ[a]) <= base + maxRight) active[kept++] = a
                if (t == PENALTY) len += w[b]
                val r: Float = if (len < lw) {
                    val st = sy[b] - nY[a]
                    if (st > 0f) (lw - len) / st else HUGE
                } else if (len > lw) {
                    val sh = sz[b] - nZ[a]
                    if (sh > 0f) (lw - len) / sh else -HUGE
                } else 0f
                if (r < -1f || r > tolerance) continue
                val bad = 100.0 * Math.abs(r).toDouble().let { it * it * it }
                var d = (LINE_PENALTY + bad).let { it * it }
                val p = pen[b]
                if (t == PENALTY) {
                    if (p >= 0) d += p.toDouble() * p else if (p != FORCED) d -= p.toDouble() * p
                }
                // Deux césures de suite ; césure sur l'avant-dernière ligne.
                val ap = nPos[a]
                if (ap >= 0 && flag[ap]) {
                    if (t == PENALTY && flag[b]) d += DOUBLE_HYPHEN
                    else if (forced && b == n - 1) d += FINAL_HYPHEN
                }
                val fit = if (r < -0.5f) 0 else if (r <= 0.5f) 1 else if (r <= 1f) 2 else 3
                if (Math.abs(fit - nFit[a]) > 1) d += ADJACENT
                d += nDem[a]
                if (d < bestDem[fit]) { bestDem[fit] = d; bestFrom[fit] = a }
            }
            actives = kept
            var min = Double.MAX_VALUE
            for (c in 0..3) if (bestDem[c] < min) min = bestDem[c]
            if (min < Double.MAX_VALUE) {
                // Un nœud par classe de serrage, tant qu'il peut encore mener à la meilleure suite.
                for (c in 0..3) if (bestDem[c] <= min + ADJACENT) {
                    val k = node(b, nLine[bestFrom[c]] + 1, c, bestFrom[c], bestDem[c])
                    if (actives == active.size) active = active.copyOf(actives * 2)
                    active[actives++] = k
                }
            }
            if (actives == 0) return null
        }
        var best = -1
        for (ai in 0 until actives) {
            val a = active[ai]
            if (nPos[a] == n - 1 && (best < 0 || nDem[a] < nDem[best])) best = a
        }
        if (best < 0) return null
        val out = IntArray(nLine[best])
        var k = best
        var i = out.size
        while (k != start && i > 0) { out[--i] = nPos[k]; k = nPrev[k] }
        return if (i == 0 && k == start) out else null
    }

    companion object {
        const val BOX: Byte = 0
        const val GLUE: Byte = 1
        const val PENALTY: Byte = 2
        /** Coupure imposée (fin de paragraphe, retour à la ligne) et coupure interdite. */
        const val FORCED = -10000
        const val INFINITE = 10000
        /** Étirement « infini » du blanc qui termine un paragraphe. */
        const val FILL = 1e5f
        private const val HUGE = 1e9f
        // Réglages de TeX (\linepenalty, \doublehyphendemerits, \finalhyphendemerits, \adjdemerits).
        private const val LINE_PENALTY = 10.0
        private const val DOUBLE_HYPHEN = 10000.0
        private const val FINAL_HYPHEN = 5000.0
        private const val ADJACENT = 10000.0
    }
}
