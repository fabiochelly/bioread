package app.bioread

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Curseur de position dans le livre, dessiné à la main pour rester net sur liseuse : la partie
 * lue est une barre pleine et épaisse, le reste un trait fin, tous deux en couleur pleine (le
 * curseur du système dessine l'une des deux en transparence, que l'écran à encre efface).
 * La poignée est une pastille verticale cerclée de la couleur du fond, avec deux stries.
 */
class PageSlider(c: Context) : View(c) {
    var max = 1
        set(v) { field = maxOf(1, v); if (progress > field) progress = field; invalidate() }
    var progress = 0
        set(v) { val n = v.coerceIn(0, max); if (n != field) { field = n; invalidate() } }

    /** (position, vient du doigt) à chaque changement ; début et fin du glissement. */
    var onChange: ((Int, Boolean) -> Unit)? = null
    var onStart: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    /** Marge latérale (px) : la poignée ne déborde jamais. */
    var pad = 26 * d

    /** Couleur de la partie lue et de la poignée (celle de l'interface par défaut). */
    var tint = Theme.ui
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (52 * d).toInt())

    private fun xOf(p: Int) = pad + (width - 2 * pad) * p / max

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val x = xOf(progress)
        paint.style = Paint.Style.FILL
        paint.color = Theme.ui
        // Reste du livre : trait fin.
        r.set(x, cy - 1 * d, width - pad, cy + 1 * d)
        c.drawRoundRect(r, d, d, paint)
        // Partie lue : barre pleine.
        paint.color = tint
        r.set(pad, cy - 3 * d, x, cy + 3 * d)
        c.drawRoundRect(r, 3 * d, 3 * d, paint)
        // Poignée : liseré de la couleur du fond (détache la pastille des deux barres), pastille, stries.
        val hw = 8 * d
        val hh = 15 * d
        paint.color = Theme.bg
        r.set(x - hw - 3 * d, cy - hh - 3 * d, x + hw + 3 * d, cy + hh + 3 * d)
        c.drawRoundRect(r, hw + 3 * d, hw + 3 * d, paint)
        paint.color = tint
        r.set(x - hw, cy - hh, x + hw, cy + hh)
        c.drawRoundRect(r, hw, hw, paint)
        paint.color = Theme.bg
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * d
        paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(x - 2.5f * d, cy - 5 * d, x - 2.5f * d, cy + 5 * d, paint)
        c.drawLine(x + 2.5f * d, cy - 5 * d, x + 2.5f * d, cy + 5 * d, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onStart?.invoke()
                move(e.x)
            }
            MotionEvent.ACTION_MOVE -> move(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onStop?.invoke()
        }
        return true
    }

    private fun move(x: Float) {
        val span = width - 2 * pad
        if (span <= 0) return
        val p = Math.round((x - pad) * max / span).coerceIn(0, max)
        if (p == progress) return
        progress = p
        onChange?.invoke(p, true)
    }
}
