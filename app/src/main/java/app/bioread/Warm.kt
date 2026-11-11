package app.bioread

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Réchauffement de l'écran le soir, sur smartphone uniquement : à l'approche du coucher du
 * soleil, tout l'affichage est multiplié par une teinte ambrée (le noir reste noir, le fond
 * et les images jaunissent), de plus en plus jusqu'à une heure après le coucher, et jusqu'au
 * lever. Les heures du soleil sont calculées d'après la date ; faute d'autorisation de
 * localisation, la position est estimée d'après le fuseau horaire de l'appareil.
 */
object Warm {
    private var previewUntil = 0L

    /** Montre l'effet à pleine force pendant quelques secondes (changement du réglage en plein jour). */
    fun preview() {
        previewUntil = System.currentTimeMillis() + 2500
    }

    val previewing get() = System.currentTimeMillis() < previewUntil

    /** Position approximative (latitude, longitude) déduite du fuseau horaire. */
    private fun place(tz: TimeZone): DoubleArray {
        val id = tz.id
        val lon = when (id) {
            "Europe/Paris", "Europe/Brussels", "Europe/Luxembourg", "Europe/Amsterdam" -> 3.0
            "Europe/Madrid" -> -3.7
            "Europe/London", "Europe/Lisbon", "Europe/Dublin" -> -3.0
            else -> tz.rawOffset / 3600000.0 * 15
        }
        val south = id.startsWith("Australia/") || id == "Pacific/Auckland" || id.startsWith("America/Argentina") ||
            id == "America/Sao_Paulo" || id == "America/Santiago" || id == "Africa/Johannesburg"
        val lat = if (south) -34.0 else if (id.startsWith("Europe/")) 47.0 else 40.0
        return doubleArrayOf(lat, lon)
    }

    /** Heures légales du lever et du coucher du soleil pour la date de [cal] (heures décimales). */
    fun sun(cal: Calendar): DoubleArray {
        val tz = cal.timeZone
        val p = place(tz)
        val n = cal.get(Calendar.DAY_OF_YEAR)
        val decl = Math.toRadians(23.44) * sin(2 * PI * (284 + n) / 365.0)
        val lat = Math.toRadians(p[0])
        val cosH = ((sin(Math.toRadians(-0.833)) - sin(lat) * sin(decl)) / (cos(lat) * cos(decl))).coerceIn(-1.0, 1.0)
        val half = Math.toDegrees(acos(cosH)) / 15                      // demi-durée du jour, en heures
        val b = 2 * PI * (n - 81) / 364.0
        val eot = 9.87 * sin(2 * b) - 7.53 * cos(b) - 1.5 * sin(b)      // équation du temps, en minutes
        val offset = tz.getOffset(cal.timeInMillis) / 3600000.0         // heure d'été comprise
        val noon = 12 + offset - p[1] / 15 - eot / 60
        return doubleArrayOf(noon - half, noon + half)
    }

    /** 0 en plein jour, 1 la nuit ; montée d'une heure avant à une heure après le coucher. */
    fun phase(cal: Calendar = Calendar.getInstance()): Float {
        val s = sun(cal)
        val h = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        val up = ((h - (s[1] - 1)) / 2).coerceIn(0.0, 1.0)               // soir
        val down = (((s[0] + 0.5) - h) / 1).coerceIn(0.0, 1.0)           // fin de nuit, autour du lever
        return maxOf(up, down).toFloat()
    }

    /** « 17 h 15 » */
    private fun clock(h: Double): String {
        val m = Math.round(h * 60).toInt()
        return "${m / 60 % 24} h ${(m % 60).toString().padStart(2, '0')}"
    }

    /** État du mode automatique, en clair, pour les Options. */
    fun status(): String {
        val cal = Calendar.getInstance()
        val s = sun(cal)
        val now = Math.round(phase(cal) * Prefs.warm)
        return "Automatique · coucher du soleil ${clock(s[1])}, lever ${clock(s[0])} · en ce moment $now %"
    }

    /** À appeler après le dessin de tout le contenu de la fenêtre. */
    fun draw(c: Canvas) {
        if (Theme.eink || Prefs.warm <= 0) return
        val k = (if (previewing) 1f else phase()) * Prefs.warm / 100f
        if (k <= 0.01f) return
        // À pleine force : le blanc devient un ambre doux (255, 208, 142).
        c.drawColor(Color.rgb(255, (255 - 47 * k).toInt(), (255 - 113 * k).toInt()), PorterDuff.Mode.MULTIPLY)
    }
}
