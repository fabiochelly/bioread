package app.bioread

import android.content.Context
import android.view.View
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Pilotage expérimental de l'écran à encre des liseuses Onyx, par réflexion sur le service
 * d'optimisation de leur firmware (android.onyx.optimization.EInkHelper). Les numéros de mode
 * ne sont jamais devinés : seuls ceux que le firmware déclare lui-même (constantes
 * UPDATE_MODE_* de android.onyx.optimization.Constant) sont proposés. Sur tout autre appareil,
 * [modes] est vide et rien n'est appelé.
 */
object Epd {
    class Mode(val name: String, val value: Int)

    private val helper: Class<*>? = try { Class.forName("android.onyx.optimization.EInkHelper") } catch (e: Throwable) { null }
    private val setScope: Method? = try { helper?.getMethod("setAppScopeRefreshMode", Int::class.javaPrimitiveType) } catch (e: Throwable) { null }
    private val getScope: Method? = try { helper?.getMethod("getAppScopeRefreshMode") } catch (e: Throwable) { null }
    private val getMode: Method? = try { View::class.java.getMethod("getDefaultUpdateMode") } catch (e: Throwable) { null }

    /** Modes de rafraîchissement déclarés par le firmware (NORMAL, DU, A2, REGAL…). */
    val modes: List<Mode> by lazy {
        val out = ArrayList<Mode>()
        if (setScope == null) return@lazy out
        try {
            val seen = HashSet<Int>()
            for (f in Class.forName("android.onyx.optimization.Constant").declaredFields) try {
                if (!f.name.startsWith("UPDATE_MODE_") || f.type != Int::class.javaPrimitiveType) continue
                if (f.name == "UPDATE_MODE_DEFAULT" || f.name == "UPDATE_MODE_UNKNOWN") continue
                f.isAccessible = true
                val v = f.getInt(null)
                if (v >= 0 && seen.add(v)) out.add(Mode(f.name.removePrefix("UPDATE_MODE_"), v))
            } catch (e: Throwable) {
            }
        } catch (e: Throwable) {
        }
        out.sortBy { it.value }
        out
    }

    fun name(value: Int) = modes.firstOrNull { it.value == value }?.name

    fun current(): Int = try { getScope?.invoke(null) as? Int ?: -1 } catch (e: Throwable) { -1 }

    /** Mode en vigueur avant notre première intervention : c'est lui qu'on rétablit. */
    private var original = -2
    private var applied = -2

    /** Demande le mode [mode] pour l'app (-1 = celui d'origine). Sans effet s'il est déjà en place. */
    fun apply(mode: Int) {
        val set = setScope ?: return
        if (original == -2) original = current()
        val want = if (mode >= 0) mode else original
        if (want < 0 || want == applied) return
        val t = System.nanoTime()
        try {
            val before = current()
            set.invoke(null, want)
            applied = want
            note("mode $want demandé (avant : $before, origine : $original), relu ${current()}, ${(System.nanoTime() - t) / 1000} us")
        } catch (e: Throwable) {
            note("mode $want refusé : ${e.cause ?: e}")
        }
    }

    /**
     * Journal des demandes, conservé d'une séance à l'autre (les 60 dernières) : le rapport de
     * diagnostic est réécrit à chaque lancement, et les essais se font rarement dans la dernière séance.
     */
    private fun note(msg: String) {
        if (Perf.on) Perf.log("écran : $msg")
        try {
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
            val lines = (Prefs.sp.getString("epdLog", "") ?: "").split('\n').filter { it.isNotEmpty() }.takeLast(59)
            Prefs.sp.edit().putString("epdLog", (lines + "$stamp  $msg").joinToString("\n")).apply()
        } catch (e: Throwable) {
        }
    }

    /**
     * Réparation, une seule fois : l'essai de pilotage (retiré depuis) a laissé le mode de l'app
     * à 0 (NORMAL) alors que le firmware la faisait tourner en 2 avant toute intervention. On
     * remet 2, puis l'app ne touche plus jamais à ce réglage.
     */
    fun repair() {
        try {
            if (setScope == null || Prefs.sp.getBoolean("epdRepaired", false)) return
            Prefs.sp.edit().putBoolean("epdRepaired", true).apply()
            if (current() == 0) apply(2)
        } catch (e: Throwable) {
        }
    }

    // ---------------------------------------------------------------- diagnostic

    private fun describe(c: Class<*>, sb: StringBuilder) {
        sb.append("${c.name} :\n")
        try {
            for (f in c.declaredFields) try {
                f.isAccessible = true
                val v = if (Modifier.isStatic(f.modifiers)) " = ${f.get(null)}" else ""
                sb.append("  champ ${f.type.simpleName} ${f.name}$v\n")
            } catch (e: Throwable) {
                sb.append("  champ ${f.name} (illisible)\n")
            }
        } catch (e: Throwable) {
            sb.append("  champs illisibles : $e\n")
        }
        try {
            for (m in c.declaredMethods) sb.append("  ${m.toGenericString()}\n")
        } catch (e: Throwable) {
            sb.append("  méthodes illisibles : $e\n")
        }
    }

    /** Tout ce qu'on peut lire sans rien modifier : constantes, modes en vigueur, réglages du firmware. */
    fun report(c: Context, v: View): String {
        val sb = StringBuilder("\n== Écran Onyx : état et constantes ==\n")
        sb.append("mode de la fenêtre : ").append(try { getMode?.invoke(v) } catch (e: Throwable) { "erreur $e" }).append('\n')
        sb.append("mode de l'app (EInkHelper) : ${current()}\n")
        sb.append("journal des demandes de mode (séances précédentes comprises) :\n")
        sb.append((Prefs.sp.getString("epdLog", "") ?: "").ifEmpty { "(aucune demande)" }.prependIndent("  ")).append('\n')
        sb.append("modes proposés (${modes.size}) :\n")
        for (m in modes) sb.append("  ${m.name} = ${m.value}\n")
        // Toutes les constantes entières de View absentes d'un Android standard sont suspectes : on liste large.
        try {
            sb.append("autres constantes de View contenant MODE/GC/DU/A2/ONYX :\n")
            val all = View::class.java.fields.toMutableList()
            try { all.addAll(View::class.java.declaredFields) } catch (e: Throwable) {}
            val seen = HashSet<String>()
            for (f in all) try {
                if (!Modifier.isStatic(f.modifiers) || f.type != Int::class.javaPrimitiveType || !seen.add(f.name)) continue
                val n = f.name.uppercase()
                if (n.contains("ONYX") || n.contains("_GC") || n.contains("_DU") || n.contains("A2") || n.contains("UI_") ||
                    (n.contains("MODE") && !n.contains("SCROLL") && !n.contains("LAYOUT") && !n.contains("AUTOFILL"))
                ) {
                    f.isAccessible = true
                    sb.append("  ${f.name} = ${f.getInt(null)}\n")
                }
            } catch (e: Throwable) {
            }
        } catch (e: Throwable) {
            sb.append("  illisible : $e\n")
        }
        // Réglages du firmware pour cette app : tous les accesseurs sans effet de bord.
        try {
            val h = Class.forName("android.onyx.optimization.EInkHelper")
            sb.append("EInkHelper :\n")
            for (m in h.methods.sortedBy { it.name }) {
                if (!Modifier.isStatic(m.modifiers)) continue
                if (!(m.name.startsWith("get") || m.name.startsWith("is") || m.name.startsWith("allow"))) continue
                val p = m.parameterTypes
                val args: Array<Any?> = when {
                    p.isEmpty() -> arrayOf()
                    p.size == 1 && p[0] == Context::class.java -> arrayOf(c)
                    p.size == 1 && p[0] == String::class.java -> arrayOf(c.packageName)
                    p.size == 2 && p[0] == Context::class.java && p[1] == String::class.java -> arrayOf(c, c.packageName)
                    else -> continue
                }
                val r = try { m.invoke(null, *args).toString().take(600) } catch (e: Throwable) { "erreur ${e.cause ?: e}" }
                sb.append("  ${m.name}(${p.joinToString { it.simpleName }}) = $r\n")
            }
        } catch (e: Throwable) {
            sb.append("EInkHelper : $e\n")
        }
        for (n in arrayOf(
            "android.onyx.ViewUpdateHelper", "android.onyx.hardware.DeviceController",
            "android.onyx.optimization.data.v2.EACRefreshConfig", "android.onyx.optimization.data.v2.EACAppConfig",
            "android.onyx.optimization.Constant", "android.onyx.optimization.EInkHelper\$RefreshMode",
            "android.onyx.optimization.data.v2.EACRefreshConfig\$RefreshMode", "android.onyx.optimization.data.RefreshMode",
            "android.onyx.UpdateMode", "android.onyx.EpdController", "android.view.ViewUpdateHelper",
        )) try {
            describe(Class.forName(n), sb)
        } catch (e: Throwable) {
            sb.append("$n : absente\n")
        }
        return sb.toString()
    }
}
