package app.bioread

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Diagnostic sans adb : si le fichier témoin Download/bioread-diag existe sur le stockage
 * partagé, l'app écrit Download/bioread-diag.txt (appareil, écran, processeur, méthodes
 * propres à l'écran à encre, mesures de la séance). Sans le témoin, le seul coût est un
 * test d'existence au lancement.
 */
object Diag {
    private var out: File? = null
    private var static = ""
    private var ctx: Context? = null

    fun init(a: Activity) {
        try {
            if (!Library.hasAccess(a)) return
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStorageDirectory(), "Download")
            if (!File(dir, "bioread-diag").exists()) return
            out = File(dir, "bioread-diag.txt")
            Perf.record()
            val app = a.applicationContext
            val decor = a.window.decorView
            ctx = app
            @Suppress("DEPRECATION")
            val d = a.windowManager.defaultDisplay
            val size = android.graphics.Point().also { @Suppress("DEPRECATION") d.getRealSize(it) }
            val dm = a.resources.displayMetrics
            val modes = d.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" }
            val screen = "écran : ${size.x}x${size.y} px, densité ${dm.densityDpi} dpi (x${dm.density}), " +
                "xdpi ${dm.xdpi}, ydpi ${dm.ydpi}\nrafraîchissement : ${d.refreshRate} Hz ; modes : $modes\n"
            Thread {
                static = try { screen + device(app) } catch (e: Throwable) { screen + "erreur : $e\n" }
                static += try { Epd.report(app, decor) } catch (e: Throwable) { "Epd : $e\n" } +
                    "essai en cours : lecture ${Prefs.epdRead} (${Epd.name(Prefs.epdRead)}), balayage ${Prefs.epdWipe} (${Epd.name(Prefs.epdWipe)})\n"
                write()
            }.start()
        } catch (e: Throwable) {
        }
    }

    /** Réécrit le rapport avec les mesures prises jusqu'ici (mise en arrière-plan de l'app). */
    fun flush() {
        if (out == null) return
        Thread { write() }.start()
    }

    /** Mémoire du processus à cet instant : objets Java, mémoire native (images comprises), total réel. */
    fun memory(): String {
        val rt = Runtime.getRuntime()
        val java = (rt.totalMemory() - rt.freeMemory()) shr 20
        val native = android.os.Debug.getNativeHeapAllocatedSize() shr 20
        return "mémoire : Java $java Mo, native $native Mo, totale ${android.os.Debug.getPss() shr 10} Mo"
    }

    @Synchronized
    private fun write() {
        try {
            out?.writeText(
                "Bioread — diagnostic du ${java.util.Date()}\n\n" + static +
                    "\n== Mesures de la séance (ms depuis le lancement) ==\n" + Perf.dump() +
                    "à l'écriture du rapport : " + memory() + "\n"
            )
            // Sans ce signalement, le fichier reste invisible par USB (MTP ne voit que l'index des médias).
            val f = out
            val c = ctx
            if (f != null && c != null) android.media.MediaScannerConnection.scanFile(c, arrayOf(f.path), null, null)
        } catch (e: Throwable) {
        }
    }

    private fun device(c: Context): String {
        val sb = StringBuilder()
        sb.append("appareil : ${Build.MANUFACTURER} / ${Build.BRAND} / ${Build.MODEL} (${Build.DEVICE}, ${Build.PRODUCT})\n")
        sb.append("plateforme : ${Build.HARDWARE} / ${Build.BOARD}")
        if (Build.VERSION.SDK_INT >= 31) sb.append(" / ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        sb.append("\nAndroid ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}\n")
        sb.append("ABI : ${Build.SUPPORTED_ABIS.joinToString()}\n")
        sb.append("mode liseuse : ${Theme.eink}\n")

        val n = Runtime.getRuntime().availableProcessors()
        sb.append("\n== Processeur : $n cœurs ==\n")
        for (i in 0 until n) {
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq/"
            sb.append("cpu$i : max ${read(base + "cpuinfo_max_freq")} kHz, min ${read(base + "cpuinfo_min_freq")} kHz, ")
            sb.append("actuelle ${read(base + "scaling_cur_freq")} kHz, régulateur ${read(base + "scaling_governor")}\n")
        }
        sb.append("en ligne : ${read("/sys/devices/system/cpu/online")}\n")

        val rt = Runtime.getRuntime()
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        sb.append("\n== Mémoire ==\n")
        sb.append("tas Java : max ${rt.maxMemory() shr 20} Mo, alloué ${rt.totalMemory() shr 20} Mo, libre ${rt.freeMemory() shr 20} Mo\n")
        sb.append("classe mémoire : ${am.memoryClass} Mo (grande : ${am.largeMemoryClass} Mo), peu de RAM : ${am.isLowRamDevice}\n")
        sb.append("RAM : ${mi.totalMem shr 20} Mo au total, ${mi.availMem shr 20} Mo disponibles\n")

        sb.append("\n== Méthodes liées à l'écran à encre ==\n")
        val words = arrayOf("epd", "onyx", "waveform", "updatemode", "update_mode", "refresh", "regal", "dither")
        for (name in arrayOf("android.view.View", "android.view.Window", "android.view.Surface")) {
            sb.append("$name :\n")
            try {
                val k = Class.forName(name)
                val seen = sortedSetOf<String>()
                for (m in k.methods.asList() + k.declaredMethods.asList()) {
                    val low = m.name.lowercase()
                    if (words.any { low.contains(it) }) seen.add("  $m")
                }
                if (seen.isEmpty()) sb.append("  (aucune)\n") else for (m in seen) sb.append(m).append('\n')
            } catch (e: Throwable) {
                sb.append("  erreur : $e\n")
            }
        }

        sb.append("\n== Classes du constructeur ==\n")
        for (name in arrayOf(
            "android.onyx.ViewUpdateHelper", "android.onyx.optimization.EInkHelper", "android.onyx.AndroidSettingsHelper",
            "android.onyx.hardware.DeviceController", "android.onyx.utils.ReflectUtil",
            "com.onyx.android.sdk.api.device.epd.EpdController", "com.onyx.android.sdk.device.Device",
            "com.onyx.android.sdk.api.device.epd.UpdateMode", "android.view.EinkManager", "android.os.EinkManager",
        )) {
            val r = try {
                val k = Class.forName(name)
                "présente (${k.declaredMethods.size} méthodes)\n" + k.declaredMethods.map { "    $it" }.sorted().joinToString("\n")
            } catch (e: Throwable) {
                "absente (${e.javaClass.simpleName})"
            }
            sb.append("$name : $r\n")
        }
        sb.append("\n== Propriétés système ==\n")
        for (p in arrayOf(
            "ro.board.platform", "ro.hardware", "ro.product.cpu.abi", "ro.sf.lcd_density", "ro.build.version.release",
            "ro.build.fingerprint", "debug.hwui.renderer", "ro.hwui.use_vulkan", "sys.eink.mode", "persist.sys.eink.mode",
        )) sb.append("$p = ${prop(p)}\n")
        return sb.toString()
    }

    private fun read(path: String): String = try { File(path).readText().trim() } catch (e: Throwable) { "?" }

    private fun prop(name: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    } catch (e: Throwable) {
        "?"
    }
}
