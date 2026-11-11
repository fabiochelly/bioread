package app.bioread

import android.util.Log

/**
 * Chronométrage des étapes coûteuses, inactif par défaut (un seul test booléen).
 * Activation : adb shell setprop log.tag.bioperf DEBUG, puis adb logcat -s bioperf
 * ou, sans adb, le fichier témoin du diagnostic (voir [Diag]) : les mesures sont alors
 * aussi gardées en mémoire pour être écrites dans le rapport.
 */
object Perf {
    private val logcat = Log.isLoggable("bioperf", Log.DEBUG)

    @JvmField
    @Volatile
    var on = logcat

    private var buffer: StringBuilder? = null
    private val t0 = System.nanoTime()

    /** Garde les mesures de la session en mémoire (diagnostic sans adb). */
    fun record() {
        synchronized(this) { if (buffer == null) buffer = StringBuilder(8192) }
        on = true
    }

    /** Vrai si les mesures sont gardées pour le rapport de diagnostic. */
    val recording get() = buffer != null

    /** Les mesures gardées depuis [record]. */
    fun dump(): String = synchronized(this) { buffer?.toString() ?: "" }

    fun log(msg: String) {
        if (!on) return
        if (logcat) Log.d("bioperf", msg)
        if (buffer != null) synchronized(this) {
            val b = buffer ?: return
            // Borne : une longue séance de lecture ne doit pas faire grossir le rapport sans fin.
            if (b.length < 400_000) b.append((System.nanoTime() - t0) / 1_000_000).append(" ms  ").append(msg).append('\n')
        }
    }

    inline fun <T> time(name: String, f: () -> T): T {
        if (!on) return f()
        val s = System.nanoTime()
        val r = f()
        log("$name ${(System.nanoTime() - s) / 1000} us")
        return r
    }

    fun mark(name: String, sinceNanos: Long) {
        if (on) log("$name ${(System.nanoTime() - sinceNanos) / 1000} us")
    }
}
