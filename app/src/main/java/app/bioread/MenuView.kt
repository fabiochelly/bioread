package app.bioread

import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Options, ouvertes d'un tap au centre du livre ou depuis l'accueil. Feuille basse en trois
 * groupes (texte, traduction, page) : réglages numériques en grille de pavés « − valeur + »
 * repérés par une icône, choix exclusifs en barres segmentées, bascule pour la traduction.
 * Uniquement des traits fins et des aplats pleins, sans animation : net sur encre électronique.
 */
class MenuView(private val host: MainActivity, private val reader: ReaderView, inReader: Boolean) : FrameLayout(host) {
    private val d = resources.displayMetrics.density
    private val panel = LinearLayout(host)

    init {
        setOnClickListener { host.hideMenu() }
        if (inReader) {
            // Dans le livre : barre haute avec le retour à l'accueil. Le texte reste visible
            // entre les deux panneaux pour juger les réglages en direct.
            val bar = LinearLayout(host)
            bar.gravity = Gravity.CENTER_VERTICAL
            bar.setBackgroundColor(Theme.bg)
            bar.isClickable = true
            bar.setPadding(dp(10), reader.topInset + dp(12), dp(20), dp(12))
            bar.addView(IconButton(host, IconButton.BACK) { host.showLibrary() })
            bar.addView(label(reader.title, true, 18f), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            // Batterie : icône et pourcentage, en haut à droite.
            val st = try {
                host.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            } catch (e: Exception) {
                null
            }
            val scale = st?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val level = (st?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1).let { if (it < 0 || scale <= 0) -1 else it * 100 / scale }
            val status = st?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
            if (level in 0..100) {
                bar.addView(Battery(level, charging), LinearLayout.LayoutParams(dp(28), dp(15)).apply { leftMargin = dp(12) })
                bar.addView(label("$level %", false, 14f), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
            }
            val top = LinearLayout(host)
            top.orientation = LinearLayout.VERTICAL
            top.addView(bar, LinearLayout.LayoutParams(-1, -2))
            top.addView(line(2), LinearLayout.LayoutParams(-1, dp(2)))
            addView(top, LayoutParams(-1, -2, Gravity.TOP))
        } else setPadding(0, 0, 0, host.insetBottom)   // accueil : au-dessus de la barre de navigation

        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Theme.bg)
        panel.isClickable = true
        panel.setPadding(dp(20), dp(4), dp(20), dp(16))
        // La feuille défile si elle dépasse 64 % de l'écran : le texte reste visible au-dessus.
        val scroll = object : ScrollView(host) {
            override fun onMeasure(w: Int, h: Int) {
                val max = (resources.displayMetrics.heightPixels * 0.64f).toInt()
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
            }
        }
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(panel)
        val wrap = LinearLayout(host)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.addView(line(2), LinearLayout.LayoutParams(-1, dp(2)))
        wrap.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        addView(wrap, LayoutParams(-1, -2, Gravity.BOTTOM))
        build()
    }

    // ---------------------------------------------------------------- briques

    private fun dp(v: Int) = (v * d).toInt()

    private fun line(weight: Int, color: Int = Theme.ui) = View(host).apply { setBackgroundColor(color) }

    private fun outline(radius: Int = 9, filled: Boolean = false, color: Int = Theme.ui) = GradientDrawable().apply {
        setColor(if (filled) color else Theme.bg)
        setStroke(maxOf(1, if (color == Theme.ui) dp(1) else dp(2) * 3 / 4), color)
        cornerRadius = radius * d
    }

    private fun label(text: String, bold: Boolean = false, size: Float = 15f) = TextView(host).apply {
        this.text = text
        typeface = if (bold) host.fonts.uiBold else host.fonts.ui
        textSize = size
        setTextColor(Theme.ui)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** Titre de groupe : icône, petites capitales espacées, filet qui court jusqu'au bord. */
    private fun section(icon: Int, title: String, right: View? = null) {
        val row = LinearLayout(host)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(12), 0, dp(8))
        row.addView(IconButton(host, icon, 24, 20))
        row.addView(label(title.uppercase(), true, 11.5f).apply { letterSpacing = 0.12f },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(7); rightMargin = dp(12) })
        row.addView(line(1), LinearLayout.LayoutParams(0, maxOf(1, dp(1)), 1f))
        if (right != null) row.addView(right, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        panel.addView(row)
    }

    /** Boîte de choix : la valeur à gauche, un chevron fin à droite ; un toucher ouvre la liste. */
    private fun select(value: String, size: Float, onClick: () -> Unit) = LinearLayout(host).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = outline()
        setPadding(dp(16), 0, dp(10), 0)
        addView(label(value, false, size), LinearLayout.LayoutParams(0, -2, 1f))
        addView(IconButton(host, IconButton.DOWN, 22, 16), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        setOnClickListener { onClick() }
    }

    /** Pavé de réglage : icône et nom au-dessus, pastille « − valeur + » d'un seul tenant. */
    private fun stepper(icon: Int, name: String, get: () -> Int, set: (Int) -> Unit, step: Int, min: Int, max: Int, fmt: (Int) -> String): View {
        val value = label(fmt(get()), true, 15f).apply { gravity = Gravity.CENTER }
        val color = Theme.tint(icon)   // contour, séparateurs et signes à la couleur du réglage
        fun change(by: Int) {
            val v = (get() + by).coerceIn(min, max)
            if (v == get()) return
            set(v)
            value.text = fmt(v)
            Prefs.save()
            reader.reflow()
        }
        // Une seule zone, sans séparateurs : la moitié gauche diminue, la moitié droite augmente.
        fun sign(kind: Int) = FrameLayout(host).apply {
            addView(IconButton(host, kind, 26, 16).apply { tint = color }, LayoutParams(-2, -2, Gravity.CENTER))
        }
        val pill = object : LinearLayout(host) {
            override fun onInterceptTouchEvent(e: android.view.MotionEvent) = true

            override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP && e.y >= 0 && e.y <= height) {
                    change(if (e.x < width / 2f) -step else step)
                }
                return true
            }
        }
        pill.background = outline(color = color)
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.addView(sign(IconButton.MINUS), LinearLayout.LayoutParams(0, -1, 1f))
        pill.addView(value, LinearLayout.LayoutParams(0, -2, 1.5f))
        pill.addView(sign(IconButton.PLUS), LinearLayout.LayoutParams(0, -1, 1f))

        val head = LinearLayout(host)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(IconButton(host, icon, 24, 20))
        head.addView(label(name, false, 13f), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })

        val cell = LinearLayout(host)
        cell.orientation = LinearLayout.VERTICAL
        cell.addView(head)
        cell.addView(pill, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(5) })
        return cell
    }

    /** Range les pavés sur trois colonnes (deux sur un écran étroit). */
    private fun grid(cells: List<View>) {
        val columns = if (resources.displayMetrics.widthPixels / d >= 340) 3 else 2
        var i = 0
        while (i < cells.size) {
            val row = LinearLayout(host)
            for (k in 0 until columns) {
                val lp = LinearLayout.LayoutParams(0, -2, 1f)
                if (k > 0) lp.leftMargin = dp(10)
                row.addView(if (i + k < cells.size) cells[i + k] else View(host), lp)
            }
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            i += columns
        }
    }

    /** Barre segmentée : un seul choix actif, affiché en aplat plein. [onSelect] renvoie false pour refuser. */
    private inner class Segmented(options: Array<String>, selected: Int, private val onSelect: (Int) -> Boolean) : LinearLayout(host) {
        private val items = ArrayList<TextView>()
        var selected = selected
            set(v) {
                field = v
                for ((i, t) in items.withIndex()) {
                    val on = i == v
                    t.setBackgroundColor(if (on) Theme.ui else Theme.bg)
                    t.setTextColor(if (on) Theme.bg else Theme.ui)
                    t.typeface = if (on) host.fonts.uiBold else host.fonts.ui
                }
            }

        init {
            background = outline()
            clipToOutline = true
            // Le contour est redessiné par-dessus les segments, sinon l'aplat du choix actif le recouvre.
            foreground = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(maxOf(1, dp(1)), Theme.ui)
                cornerRadius = 9 * d
            }
            val pad = maxOf(1, dp(1))
            for ((i, o) in options.withIndex()) {
                if (i > 0) addView(View(host).apply { setBackgroundColor(Theme.ui) }, LayoutParams(pad, -1))
                val t = label(o, false, 13f).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { if (onSelect(i)) this@Segmented.selected = i }
                }
                items.add(t)
                addView(t, LayoutParams(0, -1, 1f))
            }
            this.selected = selected
        }
    }

    /** Pile au trait fin : remplie à hauteur de la charge, avec un éclair quand l'appareil est branché. */
    private inner class Battery(private val level: Int, private val charging: Boolean) : View(host) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val bolt = android.graphics.Path()

        override fun onDraw(c: Canvas) {
            val s = maxOf(1f, 1.2f * d)
            val nub = 2.5f * d
            // Rouge sous 15 % sur smartphone ; toujours noir sur liseuse.
            val color = if (!Theme.eink && level <= 15 && !charging) Theme.tint(IconButton.CLOSE) else Theme.ui
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = s
            r.set(s / 2, s / 2, width - nub - s / 2, height - s / 2)
            c.drawRoundRect(r, 2.5f * d, 2.5f * d, p)
            p.style = Paint.Style.FILL
            c.drawRect(width - nub, height * 0.3f, width.toFloat(), height * 0.7f, p)
            val pad = s + 1.5f * d
            val inner = r.width() - 2 * pad + s
            c.drawRect(r.left + pad - s / 2, r.top + pad - s / 2, r.left + pad - s / 2 + inner * level / 100f, r.bottom - pad + s / 2, p)
            if (charging) {
                // Éclair évidé au centre, cerné pour rester lisible sur le plein comme sur le vide.
                val cx = r.centerX()
                val h = r.height()
                bolt.rewind()
                bolt.moveTo(cx + h * 0.12f, r.top + h * 0.08f); bolt.lineTo(cx - h * 0.3f, r.top + h * 0.56f)
                bolt.lineTo(cx, r.top + h * 0.56f); bolt.lineTo(cx - h * 0.12f, r.top + h * 0.92f)
                bolt.lineTo(cx + h * 0.3f, r.top + h * 0.44f); bolt.lineTo(cx, r.top + h * 0.44f); bolt.close()
                p.color = Theme.bg
                p.style = Paint.Style.FILL_AND_STROKE
                p.strokeWidth = 2.2f * d
                c.drawPath(bolt, p)
                p.color = color
                p.style = Paint.Style.FILL
                c.drawPath(bolt, p)
            }
        }
    }

    /** Bascule marche / arrêt, dessinée au trait : pleine quand elle est active. */
    private inner class Switch(on: Boolean, private val onChange: (Boolean) -> Unit) : View(host) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        var on = on
            set(v) { field = v; invalidate() }

        init {
            setOnClickListener {
                this.on = !this.on
                onChange(this.on)
            }
        }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(54), dp(30))

        override fun onDraw(c: Canvas) {
            val s = maxOf(1f, 1.5f * d)
            r.set(s, s, width - s, height - s)
            val rad = r.height() / 2
            p.color = Theme.ui
            p.strokeWidth = s
            p.style = if (on) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            c.drawRoundRect(r, rad, rad, p)
            val knob = rad - 4 * d
            val cx = if (on) r.right - rad else r.left + rad
            p.style = Paint.Style.FILL
            p.color = if (on) Theme.bg else Theme.ui
            c.drawCircle(cx, r.centerY(), knob, p)
        }
    }

    // ---------------------------------------------------------------- contenu

    private fun build() {
        panel.removeAllViews()
        val book = reader.book

        // Ni titre ni bouton de fermeture : un tap en dehors de la feuille la referme.
        panel.addView(View(host), LinearLayout.LayoutParams(-1, dp(12)))
        grid(
            listOf(
                stepper(IconButton.SIZE, "Taille", { Prefs.fontSize }, { Prefs.fontSize = it }, 1, 10, 40) { "$it" },
                stepper(IconButton.LEADING, "Interligne", { Prefs.lineSpacing }, { Prefs.lineSpacing = it }, 5, 100, 200) {
                    "${it / 100},${(it % 100).toString().padStart(2, '0')}"
                },
                stepper(IconButton.MARGINS, "Marges", { Prefs.margin }, { Prefs.margin = it }, 4, 4, 72) { "$it" },
                // 0 = police d'origine ; chaque pas épaissit le trait de 0,2 % du corps.
                stepper(IconButton.WEIGHT, "Graisse", { Prefs.weight }, { Prefs.weight = it }, 1, 0, 20) { "$it" },
                stepper(IconButton.SPACING, "Espacement", { Prefs.spacing }, { Prefs.spacing = it }, 5, -30, 100) {
                    if (it > 0) "+$it" else "$it"
                },
                stepper(IconButton.WIDTH, "Largeur", { Prefs.stretch }, { Prefs.stretch = it }, 1, 90, 120) { "$it %" },
            )
        )

        // --- Mise en forme de l'éditeur (lettres, vers, épigraphes…) : la bascule est dans le titre
        // Sur la même ligne, pour rester compact : le choix de la police, puis la bascule.
        val fonts = host.fonts
        lateinit var fontName: TextView
        val font = select(fonts.family.label, 15f) {
            val list = fonts.families
            AlertDialog.Builder(host).setItems(list.map { it.label }.toTypedArray()) { _, i ->
                if (list[i] !== fonts.family) {
                    Prefs.font = list[i].id
                    Prefs.save()
                    fonts.select(list[i].id)
                    fontName.text = fonts.family.label
                    fontName.typeface = fonts.regular
                    reader.restyle()
                }
            }.show()
        }
        fontName = font.getChildAt(0) as TextView
        fontName.typeface = fonts.regular        // le nom est écrit dans la police elle-même
        val faithful = LinearLayout(host)
        faithful.gravity = Gravity.CENTER_VERTICAL
        faithful.setPadding(0, dp(12), 0, dp(8))
        faithful.addView(font, LinearLayout.LayoutParams(0, dp(44), 1f))
        // Écran large (liseuse) : la police occupe la première moitié, la bascule la seconde.
        // Écran étroit : la bascule prend sa place, la police le reste.
        val wide = resources.displayMetrics.widthPixels / d >= 480
        val right = LinearLayout(host)
        right.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        right.addView(label("FIDÈLE AU LIVRE", true, 11.5f).apply { letterSpacing = 0.12f },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16); rightMargin = dp(12) })
        right.addView(Switch(Prefs.faithful) { on ->
            Prefs.faithful = on
            Prefs.save()
            reader.restyle()
        })
        faithful.addView(right, if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-2, -2))
        panel.addView(faithful)

        // --- Traduction : moteur, langue et clé. L'activation se fait par l'icône du pied de page.
        section(IconButton.GLOBE, "Traduction")
        // Moteur : choix libre, même quand une clé DeepL est enregistrée.
        lateinit var engine: Segmented
        engine = Segmented(arrayOf("DeepL", "Hors-ligne"), if (Prefs.engine == 1 || Prefs.deeplKey.isBlank()) 1 else 0) { i ->
            if (i == 0 && Prefs.deeplKey.isBlank()) {
                askKey { if (Prefs.deeplKey.isNotBlank()) { setEngine(0); engine.selected = 0 } }
                false
            } else {
                setEngine(i)
                true
            }
        }
        lateinit var languageName: TextView
        val language = select(Translator.label(Prefs.target), 14f) {
            AlertDialog.Builder(host).setItems(Translator.LABELS) { _, i ->
                if (Prefs.target != Translator.LANGS[i]) {
                    Prefs.target = Translator.LANGS[i]
                    Prefs.save()
                    languageName.text = Translator.label(Prefs.target)
                    reader.translationChanged(true)
                }
            }.show()
        }
        languageName = language.getChildAt(0) as TextView
        val key = FrameLayout(host).apply {
            background = outline(filled = Prefs.deeplKey.isNotBlank(), color = Theme.tint(IconButton.KEY))
            val icon = IconButton(host, IconButton.KEY, 30, 20)
            icon.inverted = Prefs.deeplKey.isNotBlank()   // clé pleine = clé enregistrée
            addView(icon, LayoutParams(-2, -2, Gravity.CENTER))
            setOnClickListener {
                askKey {
                    val has = Prefs.deeplKey.isNotBlank()
                    background = outline(filled = has, color = Theme.tint(IconButton.KEY))
                    icon.inverted = has
                    if (!has) engine.selected = 1
                    reader.translationChanged(true)
                }
            }
        }
        val row = LinearLayout(host)
        row.addView(engine, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(language, LinearLayout.LayoutParams(-2, -1).apply { leftMargin = dp(10) })
        row.addView(key, LinearLayout.LayoutParams(dp(44), -1).apply { leftMargin = dp(10) })
        panel.addView(row, LinearLayout.LayoutParams(-1, dp(44)))
        words()

        // --- Page
        val paceLabel = label("", true, 13f)
        section(IconButton.TURN, "Animation de page", paceLabel)
        // Curseur continu : de très douce (à gauche) à ultra, puis « sans animation » tout à droite.
        fun paceText(v: Int) = when {
            v >= 100 -> "Sans"
            v >= 95 -> "Ultra"
            v >= 86 -> "Rapide"
            v >= 70 -> "Normale"
            v >= 30 -> "Douce"
            else -> "Très douce"
        } + if (v < 100) "  ·  ${Math.round(if (Theme.eink) Prefs.wipeMs else Prefs.slideMs)} ms" else ""
        paceLabel.text = paceText(Prefs.pace)
        val tc = Theme.tint(IconButton.TURN)
        val pace = PageSlider(host)
        // Un cran = 10 ms, du plus lent au plus vif ; le dernier cran coupe l'animation.
        val slow = if (Theme.eink) 400 else 300
        val fast = if (Theme.eink) 50 else 100
        val steps = (slow - fast) / 10
        pace.max = steps + 1
        pace.progress = if (Prefs.pace >= 100) steps + 1 else (slow - Math.round(if (Theme.eink) Prefs.wipeMs else Prefs.slideMs)) / 10
        pace.tint = tc
        pace.pad = 12 * d
        pace.onChange = { v, _ ->
            Prefs.pace = if (v > steps) 100 else Math.round(v * 10f / (slow - fast) * 99f)
            paceLabel.text = paceText(Prefs.pace)
        }
        pace.onStop = { Prefs.save() }
        panel.addView(pace, LinearLayout.LayoutParams(-1, -2))

        // --- Chaleur du soir (smartphone) : l'écran jaunit à l'approche du coucher du soleil.
        if (!Theme.eink) {
            // Curseur : force maximale atteinte la nuit. Le déclenchement est automatique,
            // calé sur le coucher et le lever du soleil du jour.
            val amount = label("${Prefs.warm} %", true, 13f)
            section(IconButton.MOON, "Chaleur du soir", amount)
            val status = label(Warm.status(), false, 12f)
            val c = Theme.tint(IconButton.MOON)
            val bar = PageSlider(host)
            bar.max = 100
            bar.progress = Prefs.warm
            bar.tint = c
            bar.pad = 12 * d
            bar.onChange = { v, _ ->
                Prefs.warm = v
                amount.text = "$v %"
                Warm.preview()   // aperçu à pleine force pendant le réglage, même en plein jour
                host.root.invalidate()
            }
            bar.onStop = {
                Prefs.save()
                status.text = Warm.status()
            }
            panel.addView(bar, LinearLayout.LayoutParams(-1, -2))
            panel.addView(status)
        }
    }

    /**
     * Traduction sous les mots difficiles (livres anglais) : une bascule, et le vocabulaire du
     * lecteur sur un curseur, de 3 000 à 30 000 mots connus (échelle logarithmique). Sous le
     * curseur, le nombre de mots du chapitre ouvert qui seront traduits à ce réglage.
     */
    private fun words() {
        val head = LinearLayout(host)
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(0, dp(14), 0, dp(4))
        val level = label("", true, 13f).apply { gravity = Gravity.END }
        head.addView(label("MOTS DIFFICILES", true, 11.5f).apply { letterSpacing = 0.12f })
        head.addView(level, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        val bar = PageSlider(host)
        val count = label("", false, 12.5f)
        fun visible(on: Boolean) {
            bar.visibility = if (on) VISIBLE else GONE
            count.visibility = if (on) VISIBLE else GONE
            level.visibility = if (on) VISIBLE else INVISIBLE
        }
        head.addView(Switch(Prefs.wordwise) { on ->
            Prefs.wordwise = on
            Prefs.save()
            visible(on)
            if (on && reader.book != null && !reader.glossable()) host.toast("Pour les livres en anglais, traduits en français")
            reader.reflow()   // l'interligne change
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        panel.addView(head)

        // Mots du chapitre ouvert qui ont une traduction : leur rang, une fois par occurrence.
        val ranks = ArrayList<Int>()
        val lc = reader.shownChapter()
        if (lc != null && reader.book?.let { it.lang.startsWith("en") && Prefs.target == "fr" } == true) {
            val re = Regex("[A-Za-z][A-Za-z'’-]*[A-Za-z]")
            val seen = HashMap<String, Int>()
            for (b in lc.chapter.blocks) {
                if (b.kind != Block.PARA) continue
                for (m in re.findAll(b.text)) {
                    val w = m.value
                    if (w[0].isUpperCase()) continue   // noms propres et débuts de phrase : le décompte s'en passe
                    val r = seen.getOrPut(w) { WordWise.preview(w)?.first ?: 0 }
                    if (r > 0) ranks.add(r)
                }
            }
        }
        val min = WordWise.MIN.toDouble()
        val span = Math.log10(WordWise.MAX / min)
        fun levelOf(v: Int) = (Math.round(min * Math.pow(10.0, span * v / 100) / 500) * 500).toInt()
        fun show(n: Int) {
            level.text = "${String.format("%,d", n).replace(',', ' ')} mots connus – ${WordWise.cefr(n)}"
            count.text = if (lc == null) "" else when (val k = ranks.count { it > n }) {
                0 -> "Aucun mot traduit dans ce chapitre"
                1 -> "1 mot traduit dans ce chapitre"
                else -> "$k mots traduits dans ce chapitre"
            }
        }
        bar.max = 100
        bar.progress = Math.round(Math.log10(Prefs.wwLevel / min) / span * 100).toInt().coerceIn(0, 100)
        bar.tint = Theme.tint(IconButton.GLOBE)
        bar.pad = 12 * d
        bar.onChange = { v, _ ->
            Prefs.wwLevel = levelOf(v)
            show(Prefs.wwLevel)
        }
        bar.onStop = {
            Prefs.save()
            reader.glossChanged()
        }
        panel.addView(bar, LinearLayout.LayoutParams(-1, -2))
        panel.addView(count)
        show(Prefs.wwLevel)
        visible(Prefs.wordwise)
    }

    private fun setEngine(i: Int) {
        if (Prefs.engine == i) return
        Prefs.engine = i
        Prefs.save()
        reader.translationChanged(true)   // les pages déjà traduites sont refaites avec le nouveau moteur
    }

    private fun askKey(done: () -> Unit) {
        val input = EditText(host).apply {
            setText(Prefs.deeplKey)
            hint = "Clé API DeepL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        val box = FrameLayout(host).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(host).setTitle("Clé API DeepL").setView(box)
            .setPositiveButton("OK") { _, _ ->
                Prefs.deeplKey = input.text.toString().trim()
                Prefs.save()
                done()
            }
            .setNegativeButton("Annuler", null).show()
    }
}
