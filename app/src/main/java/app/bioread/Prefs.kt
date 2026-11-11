package app.bioread

import android.content.Context
import android.content.SharedPreferences

/** Réglages, gardés en mémoire pour ne jamais lire les SharedPreferences sur le chemin chaud. */
object Prefs {
    lateinit var sp: SharedPreferences

    var fontSize = 18       // sp
    var lineSpacing = 135   // %
    var margin = 28         // dp
    /**
     * Allure de l'animation de page, de 0 (la plus lente) à 99 (la plus vive) ; 100 = sans animation.
     * La durée décroît linéairement : 400 → 50 ms pour le balayage sur liseuse, 300 → 100 ms
     * pour le glissement sur smartphone.
     */
    var pace = 82

    /** Durée du balayage sur liseuse (ms) et nombre de bandes : environ une bande par image d'écran. */
    val wipeMs get() = Math.round((400f - 350f * pace.coerceIn(0, 99) / 99f) / 10f) * 10f   // par pas de 10 ms
    val wipeSteps get() = Math.round(wipeMs / 27f).coerceIn(3, 10)

    /** Durée du glissement sur smartphone (ms). */
    val slideMs get() = Math.round((300f - 200f * pace.coerceIn(0, 99) / 99f) / 10f) * 10f
    var weight = 0          // graisse ajoutée au texte, par pas de 0,2 % du corps (0 = police d'origine)
    var spacing = 0         // espacement des lettres, en millièmes de cadratin
    var stretch = 100       // largeur des lettres, en %
    var epdWipe = -1        // essai Onyx : mode d'écran pendant le balayage (-1 = celui du système)
    var epdRead = -1        // essai Onyx : mode d'écran pendant la lecture (-1 = celui du système)
    var sharp = 0           // rendu des lettres : 0 = lissé (formes fidèles), 1 = net (hinting, calage au pixel)
    val anim get() = pace < 100
    var translate = false
    var target = "fr"
    var deeplKey = ""
    var engine = 0          // moteur de traduction : 0 = DeepL (si une clé existe), 1 = hors-ligne
    var lastBook = ""
    var warm = 60           // smartphone : force du réchauffement de l'écran le soir, 0 (aucun) à 100
    var sort = 0            // accueil : 0 = dernière lecture, 1 = titre, 2 = auteur
    var faithful = false    // « Fidèle au livre » : la mise en forme voulue par l'éditeur (CSS) est respectée

    fun init(c: Context) {
        sp = c.getSharedPreferences("bioread", 0)
        fontSize = sp.getInt("fontSize", fontSize)
        lineSpacing = sp.getInt("lineSpacing", lineSpacing)
        margin = sp.getInt("margin", margin)
        // Reprise des anciens niveaux : sans, douce, normale, rapide, ultra.
        pace = sp.getInt("pace", if (sp.contains("animSpeed")) intArrayOf(100, 45, 82, 90, 99)[sp.getInt("animSpeed", 2).coerceIn(0, 4)] else pace)
        // Par défaut, un léger renfort sur liseuse (l'anticrénelage y amaigrit les lettres).
        weight = sp.getInt("weight", if (Theme.eink) 7 else 0)
        spacing = sp.getInt("spacing", spacing)
        stretch = sp.getInt("stretch", stretch)
        sharp = 0   // le rendu « net » (hinting) a été retiré : toujours lissé
        epdWipe = sp.getInt("epdWipe", epdWipe)
        epdRead = sp.getInt("epdRead", epdRead)
        translate = sp.getBoolean("translate", translate)
        target = sp.getString("target", target)!!
        deeplKey = sp.getString("deeplKey", deeplKey)!!
        engine = sp.getInt("engine", engine)
        lastBook = sp.getString("lastBook", lastBook)!!
        sort = sp.getInt("sort", sort)
        warm = sp.getInt("warm100", if (sp.contains("warm")) sp.getInt("warm", 3) * 20 else warm)
        faithful = sp.getBoolean("faithful", faithful)
    }

    fun save() {
        sp.edit()
            .putInt("fontSize", fontSize)
            .putInt("lineSpacing", lineSpacing)
            .putInt("margin", margin)
            .putInt("pace", pace)
            .putInt("weight", weight)
            .putInt("spacing", spacing)
            .putInt("stretch", stretch)
            .putInt("sharp", sharp)
            .putInt("epdWipe", epdWipe)
            .putInt("epdRead", epdRead)
            .putBoolean("translate", translate)
            .putString("target", target)
            .putString("deeplKey", deeplKey)
            .putInt("engine", engine)
            .putString("lastBook", lastBook)
            .putInt("sort", sort)
            .putInt("warm100", warm)
            .putBoolean("faithful", faithful)
            .apply()
    }
}
