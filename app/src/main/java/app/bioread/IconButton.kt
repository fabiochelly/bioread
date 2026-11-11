package app.bioread

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Icône au trait épais (grille de 24), dessinée à la couleur du thème : nette sur encre
 * électronique. [box] = côté de la vue en dp, [glyph] = taille du dessin en dp. Sans [onClick],
 * c'est une simple vignette (elle laisse passer le toucher).
 */
class IconButton(
    c: Context,
    private val kind: Int,
    private val box: Int = 46,
    private val glyph: Int = 26,
    onClick: (() -> Unit)? = null,
) : View(c) {
    constructor(c: Context, kind: Int, onClick: () -> Unit) : this(c, kind, 46, 26, onClick)

    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.tint(kind)
        style = Paint.Style.STROKE
        strokeWidth = width(false)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.bg }
    private val path = Path()
    private val oval = RectF()

    /** Épaisseur du trait : fin partout (interface légère), sur smartphone comme sur liseuse. */
    private fun width(thin: Boolean): Float = if (kind == WEIGHT) 2.4f else 1.6f

    /** Trait fin (icônes discrètes de l'accueil). */
    var thin = false
        set(v) {
            field = v
            stroke.strokeWidth = width(v)
            invalidate()
        }

    /** Teinte imposée (les « − » et « + » d'un réglage prennent la couleur de ce réglage). */
    var tint: Int = Theme.tint(kind)
        set(v) {
            field = v
            if (!inverted) stroke.color = v
            invalidate()
        }

    /** Couleurs inversées (icône posée sur un fond plein). */
    var inverted = false
        set(v) {
            field = v
            stroke.color = if (v) Theme.bg else tint
            fill.color = if (v) Theme.ui else Theme.bg
            invalidate()
        }

    private fun m(x: Float, y: Float) = path.moveTo(x, y)
    private fun l(x: Float, y: Float) = path.lineTo(x, y)

    init {
        if (onClick != null) setOnClickListener { onClick() }
        when (kind) {
            HOME -> {
                m(3.5f, 11.5f); l(12f, 4f); l(20.5f, 11.5f)
                m(6f, 9.8f); l(6f, 20f); l(18f, 20f); l(18f, 9.8f)
                m(10f, 20f); l(10f, 14f); l(14f, 14f); l(14f, 20f)
            }
            BACK -> {   // retour : flèche qui revient sur ses pas
                m(9f, 4.5f); l(4.5f, 9f); l(9f, 13.5f)
                m(4.5f, 9f); l(14.5f, 9f); path.cubicTo(21.5f, 9f, 21.5f, 19.5f, 14.5f, 19.5f); l(8f, 19.5f)
            }
            CLOSE -> {
                m(6f, 6f); l(18f, 18f)
                m(18f, 6f); l(6f, 18f)
            }
            EXIT -> {   // déconnexion : une porte ouverte et une flèche qui en sort
                m(10f, 4f); l(4.5f, 4f); l(4.5f, 20f); l(10f, 20f)
                m(9.5f, 12f); l(20f, 12f)
                m(15.5f, 7.5f); l(20f, 12f); l(15.5f, 16.5f)
            }
            PLUS -> {
                m(12f, 5f); l(12f, 19f)
                m(5f, 12f); l(19f, 12f)
            }
            MINUS -> { m(5f, 12f); l(19f, 12f) }
            OPTIONS -> {   // réglages : deux curseurs
                m(4f, 8f); l(20f, 8f)
                m(4f, 16f); l(20f, 16f)
            }
            SIZE -> {      // petit A, grand A
                m(2.5f, 19f); l(6.2f, 10f); l(9.9f, 19f); m(3.9f, 15.8f); l(8.5f, 15.8f)
                m(11.5f, 19f); l(16.5f, 5f); l(21.5f, 19f); m(13.3f, 14.2f); l(19.7f, 14.2f)
            }
            LEADING -> {   // interligne : lignes de texte et double flèche verticale
                m(11f, 6f); l(21f, 6f); m(11f, 12f); l(21f, 12f); m(11f, 18f); l(21f, 18f)
                m(5f, 4.5f); l(5f, 19.5f)
                m(2.5f, 7f); l(5f, 4.5f); l(7.5f, 7f)
                m(2.5f, 17f); l(5f, 19.5f); l(7.5f, 17f)
            }
            MARGINS -> {   // page et bloc de texte en retrait
                m(4f, 3.5f); l(20f, 3.5f); l(20f, 20.5f); l(4f, 20.5f); path.close()
                m(8.5f, 8.5f); l(15.5f, 8.5f); m(8.5f, 12f); l(15.5f, 12f); m(8.5f, 15.5f); l(15.5f, 15.5f)
            }
            WEIGHT -> {    // B gras
                m(7f, 5f); l(7f, 19f)
                m(7f, 5f); l(12.5f, 5f); path.cubicTo(17.5f, 5f, 17.5f, 12f, 12.5f, 12f); l(7f, 12f)
                m(12.5f, 12f); path.cubicTo(18.5f, 12f, 18.5f, 19f, 12.5f, 19f); l(7f, 19f)
            }
            SPACING -> {   // deux jambages qui s'écartent
                m(4f, 5f); l(4f, 19f); m(20f, 5f); l(20f, 19f)
                m(8f, 12f); l(16f, 12f)
                m(10.5f, 9.5f); l(8f, 12f); l(10.5f, 14.5f)
                m(13.5f, 9.5f); l(16f, 12f); l(13.5f, 14.5f)
            }
            WIDTH -> {     // lettre étirée
                m(5f, 14.5f); l(12f, 3.5f); l(19f, 14.5f); m(8f, 10.5f); l(16f, 10.5f)
                m(3.5f, 20f); l(20.5f, 20f)
                m(6f, 17.7f); l(3.5f, 20f); l(6f, 22.3f)
                m(18f, 17.7f); l(20.5f, 20f); l(18f, 22.3f)
            }
            KEY -> {
                m(11.5f, 12f); l(21f, 12f); m(18f, 12f); l(18f, 15.5f); m(21f, 12f); l(21f, 14.5f)
            }
            TURN -> {      // page qui se tourne
                m(4.5f, 4.5f); l(13.5f, 4.5f); l(13.5f, 19.5f); l(4.5f, 19.5f); path.close()
                m(16f, 12f); l(21.5f, 12f)
                m(19f, 9.5f); l(21.5f, 12f); l(19f, 14.5f)
            }
            GLOBE -> { m(3.5f, 12f); l(20.5f, 12f) }
            MOON -> {           // croissant : chaleur du soir
                m(19.5f, 14.5f); path.cubicTo(13.5f, 17f, 7f, 11f, 9.8f, 4.5f)
                path.cubicTo(3f, 6.8f, 2.8f, 16.5f, 9.5f, 19.6f); path.cubicTo(13.8f, 21.5f, 18.2f, 18.8f, 19.5f, 14.5f)
            }
            SORT -> {           // tri : lignes de longueur décroissante
                m(4f, 6.5f); l(20f, 6.5f); m(4f, 12f); l(15f, 12f); m(4f, 17.5f); l(10f, 17.5f)
            }
            PREV_CHAPTER -> {   // double chevron vers la gauche
                m(11.5f, 5.5f); l(5f, 12f); l(11.5f, 18.5f)
                m(19f, 5.5f); l(12.5f, 12f); l(19f, 18.5f)
            }
            NEXT_CHAPTER -> {   // double chevron vers la droite
                m(5f, 5.5f); l(11.5f, 12f); l(5f, 18.5f)
                m(12.5f, 5.5f); l(19f, 12f); l(12.5f, 18.5f)
            }
            CONTENTS -> {       // sommaire : liste à puces
                m(9f, 6f); l(20.5f, 6f); m(9f, 12f); l(20.5f, 12f); m(9f, 18f); l(20.5f, 18f)
                m(4.2f, 6f); l(4.6f, 6f); m(4.2f, 12f); l(4.6f, 12f); m(4.2f, 18f); l(4.6f, 18f)
            }
            SEARCH -> { m(15.6f, 15.6f); l(20.5f, 20.5f) }
            EYE -> {       // aperçu
                m(2.5f, 12f); path.quadTo(12f, 2.5f, 21.5f, 12f); path.quadTo(12f, 21.5f, 2.5f, 12f)
            }
            FORWARD -> {   // y aller
                m(4f, 12f); l(19.5f, 12f)
                m(13f, 5.5f); l(19.5f, 12f); l(13f, 18.5f)
            }
            UP -> { m(5f, 15.5f); l(12f, 8.5f); l(19f, 15.5f) }
            DOWN -> { m(5f, 8.5f); l(12f, 15.5f); l(19f, 8.5f) }
            LEFT -> { m(15.5f, 5f); l(8.5f, 12f); l(15.5f, 19f) }
            RIGHT -> { m(8.5f, 5f); l(15.5f, 12f); l(8.5f, 19f) }
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = (box * d).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val k = glyph * d / 24f
        c.translate((width - 24 * k) / 2f, (height - 24 * k) / 2f)
        c.scale(k, k)
        c.drawPath(path, stroke)
        when (kind) {
            OPTIONS -> {
                c.drawCircle(9f, 8f, 2.6f, fill); c.drawCircle(9f, 8f, 2.6f, stroke)
                c.drawCircle(15f, 16f, 2.6f, fill); c.drawCircle(15f, 16f, 2.6f, stroke)
            }
            KEY -> c.drawCircle(7.5f, 12f, 4f, stroke)
            GLOBE -> {
                c.drawCircle(12f, 12f, 8.5f, stroke)
                oval.set(8.2f, 3.5f, 15.8f, 20.5f)
                c.drawOval(oval, stroke)
            }
            SEARCH -> c.drawCircle(10.5f, 10.5f, 7f, stroke)
            EYE -> c.drawCircle(12f, 12f, 3f, stroke)
        }
    }

    companion object {
        const val HOME = 0
        const val CLOSE = 1
        const val PLUS = 2
        const val OPTIONS = 3
        const val BACK = 4
        const val EXIT = 5
        const val MINUS = 6
        const val SIZE = 7
        const val LEADING = 8
        const val MARGINS = 9
        const val WEIGHT = 10
        const val SPACING = 11
        const val WIDTH = 12
        const val KEY = 13
        const val TURN = 14
        const val GLOBE = 15
        const val SORT = 33
        const val MOON = 34
        const val PREV_CHAPTER = 30
        const val NEXT_CHAPTER = 31
        const val CONTENTS = 32
        const val SEARCH = 16
        const val EYE = 17
        const val FORWARD = 18
        const val UP = 19
        const val DOWN = 20
        const val LEFT = 21
        const val RIGHT = 22
    }
}
