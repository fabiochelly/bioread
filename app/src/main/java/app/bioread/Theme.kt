package app.bioread

import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.text.TextPaint

/**
 * Deux habillages, choisis d'après l'appareil : sable clair et texte brun sur smartphone
 * (les teintes sépia de Google Play Livres), noir pur sur blanc pur sur liseuse.
 */
object Theme {
    val eink = Build.MANUFACTURER.equals("ONYX", true) || Build.BRAND.equals("ONYX", true) ||
        Build.BRAND.equals("BOOX", true) || forced()

    /** Test du mode liseuse sur un autre appareil : adb shell setprop debug.bioread.eink 1 */
    private fun forced(): Boolean = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "debug.bioread.eink") == "1"
    } catch (e: Throwable) {
        false
    }

    val bg = if (eink) Color.WHITE else Color.rgb(0xFB, 0xF0, 0xD9)
    val fg = if (eink) Color.BLACK else Color.rgb(0x5F, 0x4B, 0x32)

    /** Couleur de l'interface (options, accueil, panneaux, pied de page) : noir. Le brun [fg] est réservé au texte du livre. */
    val ui = if (eink) Color.BLACK else Color.rgb(0x45, 0x45, 0x45)   // gris anthracite, moins dur que le noir sur le sable

    // Arc-en-ciel doux mais soutenu, lisible en trait fin sur le fond sable.
    private const val RED = 0xFFE5484D.toInt()
    private const val ORANGE = 0xFFF0722B.toInt()
    private const val AMBER = 0xFFD9960A.toInt()
    private const val GREEN = 0xFF2FA36B.toInt()
    private const val TEAL = 0xFF12A3A8.toInt()
    private const val BLUE = 0xFF3B82F6.toInt()
    private const val INDIGO = 0xFF6366F1.toInt()
    private const val VIOLET = 0xFF9B5DE5.toInt()
    private const val PINK = 0xFFE0559A.toInt()

    /**
     * Couleur propre à chaque icône sur smartphone, dans l'ordre de l'arc-en-ciel là où les
     * icônes se suivent (réglages, accueil, navigation) : on retrouve un bouton à sa couleur.
     * Le contour du bouton prend la même teinte. Sur liseuse, tout reste noir.
     */
    fun tint(kind: Int): Int = if (eink) ui else when (kind) {
        // Réglages du texte, dans l'ordre de la grille
        IconButton.SIZE -> RED
        IconButton.LEADING -> ORANGE
        IconButton.MARGINS -> AMBER
        IconButton.WEIGHT -> GREEN
        IconButton.SPACING -> BLUE
        IconButton.WIDTH -> VIOLET
        IconButton.GLOBE -> TEAL
        IconButton.KEY -> AMBER
        IconButton.TURN -> INDIGO
        // Accueil : tout en noir, sauf « quitter » en rouge
        IconButton.EXIT -> 0xFF8A8A8A.toInt()   // gris
        IconButton.MOON -> ORANGE
        // Navigation
        IconButton.BACK, IconButton.HOME -> 0xFF8A8A8A.toInt()   // gris, comme « quitter »
        IconButton.CLOSE -> RED
        IconButton.PREV_CHAPTER -> ORANGE
        IconButton.CONTENTS -> GREEN
        IconButton.NEXT_CHAPTER -> BLUE
        // Recherche
        IconButton.SEARCH -> PINK
        IconButton.EYE -> BLUE
        IconButton.FORWARD -> GREEN
        IconButton.UP -> ORANGE
        IconButton.DOWN -> TEAL
        IconButton.LEFT -> ORANGE
        IconButton.RIGHT -> BLUE
        IconButton.MINUS -> PINK
        else -> ui
    }

    /** Icônes du pied de page (loupe, globe) : terre cuite, dans la gamme du sable et du brun. */
    val footIcon = if (eink) Color.BLACK else Color.rgb(0xC4, 0x62, 0x2D)

    /** Peinture de texte réglée pour l'écran : voir [tune]. */
    fun textPaint() = TextPaint(
        // Rendu « net » : chaque lettre est calée sur un pixel entier (fûts identiques d'une lettre à l'autre).
        if (Prefs.sharp == 1) Paint.ANTI_ALIAS_FLAG else Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
    ).also { it.color = fg }

    /** Sur encre électronique, pas de hinting : formes fidèles au dessin de la police à 300 ppp. */
    fun hint(p: TextPaint) {
        // Rendu « net » : on applique les instructions de hinting de la police.
        if (Prefs.sharp == 1) p.hinting = Paint.HINTING_ON else if (eink) p.hinting = Paint.HINTING_OFF
    }

    /**
     * Texte courant, à appeler une fois la taille fixée : graisse (trait épaissi par pas très
     * fins), espacement des lettres et largeur des lettres, tous réglables dans les Options.
     * La graisse n'élargit pas les lignes ; l'espacement et la largeur, si.
     */
    fun tune(p: TextPaint) {
        hint(p)
        if (Prefs.weight > 0) {
            p.style = Paint.Style.FILL_AND_STROKE
            p.strokeWidth = p.textSize * 0.002f * Prefs.weight
            p.strokeJoin = Paint.Join.ROUND
        }
        p.letterSpacing = Prefs.spacing / 1000f
        p.textScaleX = Prefs.stretch / 100f
    }
}
