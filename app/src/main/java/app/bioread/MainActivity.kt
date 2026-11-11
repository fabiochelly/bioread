package app.bioread

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.util.concurrent.Executors

class MainActivity : Activity() {
    lateinit var fonts: Fonts
    lateinit var root: FrameLayout
        private set
    private lateinit var reader: ReaderView
    private var library: View? = null
    private var menu: MenuView? = null
    private var nav: NavView? = null
    // Recherche : la vue est gardée entre deux ouvertures (requête, résultats, défilement).
    private var search: SearchView? = null
    private var mark: View? = null
    private var sheet: View? = null   // fenêtre de note ou feuille de mot, posée sur la page
    private var message: View? = null
    private var messageTitle: TextView? = null
    private var messageBody: TextView? = null
    private var dismissed = ""   // titre du message que l'utilisateur a fermé : on ne le rouvre pas
    private var toast: Toast? = null
    private var shownAccess = false
    private var insetTop = 0
    var insetBottom = 0
        private set
    private val coverCache = LruCache<String, Bitmap>(80)
    private val coverExec = Executors.newSingleThreadExecutor()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Perf.time("start.prefs") { Prefs.init(this) }
        Diag.init(this)
        fonts = Perf.time("start.fonts") { Fonts(this) }
        Perf.time("start.library") { Library.init(this) }
        Translator.init(this, fonts)
        importKey()
        Translator.box = { title, details -> if (title == null) hideMessage() else showMessage(title, details) }
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        // Fond de fenêtre et barres système à la couleur du thème (aucun flash blanc au lancement).
        window.setBackgroundDrawable(ColorDrawable(Theme.bg))
        @Suppress("DEPRECATION")
        run { window.statusBarColor = Theme.bg; window.navigationBarColor = Theme.bg }
        root = object : FrameLayout(this) {
            private val tickR = Runnable { invalidate() }

            // Le réchauffement du soir se dessine par-dessus tout le contenu, à chaque image.
            override fun dispatchDraw(c: android.graphics.Canvas) {
                super.dispatchDraw(c)
                if (Theme.eink || Prefs.warm <= 0) return
                Warm.draw(c)
                // La teinte évolue lentement : une image par minute suffit (plus vite pendant l'aperçu).
                removeCallbacks(tickR)
                postDelayed(tickR, if (Warm.previewing) 300 else 60_000)
            }
        }
        root.setWillNotDraw(false)
        reader = ReaderView(this)
        root.addView(reader)
        root.addView(reader.wipe)
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        if (!openFromIntent(intent)) {
            // Reprise directe du dernier livre : aucun écran intermédiaire.
            val last = Library.find(Prefs.lastBook)
            if (last != null) openBook(last) else showLibrary()
        }
    }

    private var repairPosted = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!repairPosted) {
            repairPosted = true
            window.decorView.postDelayed({ Epd.repair() }, 3000)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromIntent(intent)
    }

    private fun openFromIntent(i: Intent?): Boolean {
        val uri = i?.data ?: return false
        if (i.action != Intent.ACTION_VIEW) return false
        showLibrary()
        import(listOf(uri))
        return true
    }

    // ---------------------------------------------------------------- navigation

    fun openBook(b: BookEntry) {
        hideMenu()
        library?.let { root.removeView(it) }
        library = null
        reader.visibility = View.VISIBLE
        immersive(true)
        // Le livre en cours reste chargé sous l'accueil : y revenir est instantané.
        if (reader.book?.id != b.id) {
            Prefs.lastBook = b.id
            Prefs.save()
            reader.open(b)
        }
    }

    /** Retour au livre en cours depuis l'accueil (après un choix dans le sommaire). */
    fun resumeBook() {
        reader.book?.let { openBook(it) }
    }

    fun showLibrary() {
        hideMenu()
        hideSheet()
        hideSearch()
        hideMark()
        reader.visibility = View.GONE
        immersive(false)
        renderLibrary()
        rescan()
    }

    private fun renderLibrary() {
        library?.let { root.removeView(it) }
        Library.sort()
        shownAccess = Library.hasAccess(this)
        library = buildLibrary().also { root.addView(it); it.requestApplyInsets() }
    }

    /** Parcours de l'appareil en arrière-plan ; l'accueil n'est redessiné que si la liste change. */
    private fun rescan() {
        Library.scan(this) { changed ->
            if (library != null && (changed || shownAccess != Library.hasAccess(this))) renderLibrary()
        }
    }

    /**
     * Saisie de la clé DeepL sans clavier : un fichier Download/bioread-deepl.txt déposé sur
     * l'appareil est lu une fois, la clé enregistrée dans l'app, puis le fichier supprimé.
     */
    private fun importKey() {
        try {
            if (!Library.hasAccess(this)) return
            @Suppress("DEPRECATION")
            val f = java.io.File(android.os.Environment.getExternalStorageDirectory(), "Download/bioread-deepl.txt")
            if (!f.exists()) return
            val key = f.readText().trim()
            if (key.length in 20..80 && !key.contains(' ')) {
                Prefs.deeplKey = key
                Prefs.save()
                toast("Clé DeepL importée")
            }
            f.delete()
            android.media.MediaScannerConnection.scanFile(applicationContext, arrayOf(f.path), null, null)
        } catch (e: Exception) {
        }
    }

    override fun onPause() {
        // La position est écrite avec un léger différé pendant la lecture : on la fixe ici.
        reader.flushPosition()
        Diag.flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        importKey()
        // Retour des réglages (autorisation accordée) ou fichiers copiés entre-temps.
        if (library != null) {
            if (shownAccess != Library.hasAccess(this)) renderLibrary()
            rescan()
        }
    }

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    toast("Réglage introuvable : utilisez « Ajouter »")
                }
            }
        } else requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 2)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        if (code == 2 && library != null) { renderLibrary(); rescan() }
    }

    fun showOptions() {
        if (menu != null) return
        hideNav()
        menu = MenuView(this, reader, library == null).also { root.addView(it) }
    }

    fun hideMenu() {
        menu?.let { root.removeView(it) }
        menu = null
        hideNav()
    }

    /** Panneau de navigation, ouvert en touchant le pied de page. */
    fun showNav() {
        if (nav != null || library != null || reader.book == null) return
        nav = NavView(this, reader).also { root.addView(it) }
    }

    fun hideNav() {
        nav?.let { root.removeView(it); it.close() }
        nav = null
    }

    /** Recherche dans le livre, ouverte par la loupe du pied de page. */
    fun showSearch(query: String? = null) {
        val b = reader.book ?: return
        if (library != null || search?.parent != null) return
        hideMenu()
        hideMark()
        var s = search
        if (s == null || s.bookId != b.id) {
            s?.dispose()
            s = SearchView(this, reader)
            search = s
        }
        root.addView(s)
        if (query != null) s.search(query)   // mot choisi sur la page : la recherche part aussitôt
        s.shown()
    }

    /** Fenêtre de note ou feuille de mot (une seule à la fois), par-dessus la page du livre. */
    fun showSheet(v: View) {
        hideSheet()
        if (library != null || search?.parent != null || menu != null || nav != null) return
        hideMark()
        sheet = v
        root.addView(v)
    }

    fun hideSheet() {
        sheet?.let { root.removeView(it) }
        sheet = null
    }

    fun hideSearch() {
        val s = search ?: return
        if (s.parent == null) return
        s.hidden()
        root.removeView(s)
        if (library == null) immersive(true)   // le clavier a pu faire revenir les barres système
    }

    /** Trait sous le passage trouvé, sur la page du livre ; retiré au premier toucher. */
    fun showMark(v: MarkView) {
        hideMark()
        if (library != null || search?.parent != null) return
        mark = v
        root.addView(v, v.params())
    }

    private fun hideMark() {
        mark?.let { root.removeView(it) }
        mark = null
    }

    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (mark != null && e.actionMasked == android.view.MotionEvent.ACTION_DOWN) hideMark()
        return super.dispatchTouchEvent(e)
    }

    /**
     * Message au centre de l'écran, avec tout le détail (avancement d'un téléchargement,
     * erreur complète). Un nouvel appel avec le même titre met simplement le texte à jour.
     */
    fun showMessage(title: String, details: String) {
        if (title == dismissed) return
        if (message == null) {
            val d = resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(16))
                background = GradientDrawable().apply { setColor(Theme.bg); setStroke(dp(2), Theme.ui); cornerRadius = 8 * d }
                isClickable = true
            }
            messageTitle = text("", 17f, true).apply { maxLines = 3 }
            messageBody = text("", 14f, false).apply {
                maxLines = 30
                setLineSpacing(0f, 1.25f)
                setPadding(0, dp(10), 0, dp(14))
            }
            box.addView(messageTitle)
            box.addView(messageBody)
            box.addView(text("Fermer", 15f, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = GradientDrawable().apply { setColor(Theme.bg); setStroke(dp(1), Theme.ui); cornerRadius = 6 * d }
                setOnClickListener { dismissed = messageTitle?.text.toString(); hideMessage() }
            }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
            val w = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            message = FrameLayout(this).apply {
                addView(box, FrameLayout.LayoutParams(w, -2, Gravity.CENTER))
            }
        }
        messageTitle?.text = title
        messageBody?.text = details
        val m = message!!
        if (m.parent == null) root.addView(m) else m.bringToFront()
    }

    fun hideMessage() {
        message?.let { root.removeView(it) }
    }

    private fun back() {
        when {
            message?.parent != null -> { dismissed = messageTitle?.text.toString(); hideMessage() }
            search?.parent != null -> if (search?.back() != true) hideSearch()
            sheet != null -> hideSheet()
            menu != null -> hideMenu()
            nav != null -> hideNav()
            library == null -> showLibrary()
            else -> finish()
        }
    }

    @Deprecated("Utilisé avant Android 13")
    override fun onBackPressed() = back()

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        hideMark()
        if (library == null && menu == null && nav == null && search?.parent == null && sheet == null) when (code) {
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_SPACE -> { reader.turn(1); return true }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> { reader.turn(-1); return true }
            KeyEvent.KEYCODE_MENU -> { showOptions(); return true }
        }
        return super.onKeyDown(code, e)
    }

    fun toast(msg: String) {
        toast?.cancel()
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    @Suppress("DEPRECATION")
    private fun immersive(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            val bars = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            if (on) {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(bars)
            } else c.show(bars)
        } else {
            window.decorView.systemUiVisibility = if (on) {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && library == null) immersive(true)
    }

    // ---------------------------------------------------------------- import

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/octet-stream"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 1)
        } catch (e: Exception) {
            toast("Aucun sélecteur de fichiers disponible")
        }
    }

    @Deprecated("API Activity sans AndroidX")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req != 1 || res != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { for (k in 0 until it.itemCount) uris.add(it.getItemAt(k).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        import(uris)
    }

    private fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Thread {
            val files = uris.mapNotNull { Library.import(this, it) }
            runOnUiThread {
                if (files.isEmpty()) toast("Fichier EPUB illisible")
                else Library.scan(this) {
                    // Le livre importé, ou l'exemplaire identique déjà présent sur l'appareil.
                    val f = files[0]
                    val b = Library.books.firstOrNull { it.path == f.path } ?: Library.books.firstOrNull { it.size == f.length() }
                    if (files.size == 1 && b != null) openBook(b) else if (library != null) renderLibrary()
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- bibliothèque

    private fun text(s: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = s
        textSize = size
        typeface = if (bold) fonts.uiBold else fonts.ui
        setTextColor(Theme.ui)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    /** Cadre de couverture au format 2:3, quelle que soit la largeur de la colonne. */
    private class CoverBox(c: MainActivity) : FrameLayout(c) {
        override fun onMeasure(w: Int, h: Int) {
            val width = View.MeasureSpec.getSize(w)
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(width * 3 / 2, View.MeasureSpec.EXACTLY))
        }
    }

    /** Barre de progression au trait : contour fin, partie lue en aplat plein. */
    private class ProgressBar(c: MainActivity) : View(c) {
        private val p = android.graphics.Paint()
        var value = 0f
            set(v) { field = v; invalidate() }

        override fun onDraw(c: android.graphics.Canvas) {
            val s = maxOf(1f, resources.displayMetrics.density)
            p.color = Theme.ui
            p.style = android.graphics.Paint.Style.STROKE
            p.strokeWidth = s
            c.drawRect(s / 2, s / 2, width - s / 2, height - s / 2, p)
            p.style = android.graphics.Paint.Style.FILL
            c.drawRect(0f, 0f, width * value, height.toFloat(), p)
        }
    }

    private fun loadCover(b: BookEntry, iv: ImageView) {
        iv.tag = b.id
        val cached = coverCache.get(b.id)
        iv.setImageBitmap(cached)
        if (cached != null) return
        coverExec.execute {
            val bmp = BitmapFactory.decodeFile(Library.coverFile(b).path) ?: return@execute
            runOnUiThread {
                coverCache.put(b.id, bmp)
                if (iv.tag == b.id) iv.setImageBitmap(bmp)
            }
        }
    }

    private fun buildLibrary(): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun outline() = GradientDrawable().apply { setColor(Theme.bg); setStroke(dp(1), Theme.ui); cornerRadius = 6 * d }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Theme.bg)
        // Les marges système connues sont appliquées d'emblée : l'accueil est souvent reconstruit.
        col.setPadding(0, insetTop, 0, insetBottom)
        col.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            if (insets.systemWindowInsetTop > 0 || insets.systemWindowInsetBottom > 0) {
                insetTop = insets.systemWindowInsetTop
                insetBottom = insets.systemWindowInsetBottom
            }
            v.setPadding(0, insetTop, 0, insetBottom)
            insets
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(8), dp(8))
            addView(text("Bibliothèque", 24f, true), LinearLayout.LayoutParams(0, -2, 1f))
            // Tri, ajouter un EPUB, options, quitter.
            addView(IconButton(this@MainActivity, IconButton.SORT) {
                Prefs.sort = (Prefs.sort + 1) % 3
                Prefs.save()
                toast("Tri : " + arrayOf("dernière lecture", "titre", "auteur")[Prefs.sort])
                renderLibrary()
            }.apply { thin = true })
            addView(IconButton(this@MainActivity, IconButton.PLUS) { pick() }.apply { thin = true })
            addView(IconButton(this@MainActivity, IconButton.OPTIONS) { showOptions() }.apply { thin = true })
            addView(IconButton(this@MainActivity, IconButton.EXIT) { finishAndRemoveTask() }.apply { thin = true })
        }
        col.addView(header)
        col.addView(View(this).apply { setBackgroundColor(Theme.ui) }, LinearLayout.LayoutParams(-1, dp(1)))

        if (!shownAccess) {
            // Sans cette autorisation, seuls les livres ajoutés à la main sont visibles.
            val grant = text("Autoriser", 15f, true).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = outline()
                setOnClickListener { askAccess() }
            }
            col.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(14))
                addView(
                    text("Autorisez l'accès aux fichiers pour afficher tous les EPUB de l'appareil.", 14f, false)
                        .apply { maxLines = 3; setLineSpacing(0f, 1.2f) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) },
                )
                addView(grant)
            })
            col.addView(View(this).apply { setBackgroundColor(Theme.ui) }, LinearLayout.LayoutParams(-1, dp(1)))
        }

        val books = Library.books
        if (books.isEmpty()) {
            val msg = if (shownAccess) "Aucun EPUB trouvé sur l'appareil.\nCopiez des livres dans un dossier (Books, Download…) : ils apparaîtront ici."
            else "Aucun livre pour l'instant."
            col.addView(text(msg, 15f, false).apply {
                maxLines = 6
                setPadding(dp(20), dp(28), dp(20), 0)
                setLineSpacing(0f, 1.3f)
            })
            return col
        }
        val grid = GridView(this)
        grid.numColumns = (resources.displayMetrics.widthPixels / d / 125).toInt().coerceAtLeast(2)
        grid.setPadding(dp(12), dp(10), dp(12), dp(10))
        grid.clipToPadding = false
        grid.selector = ColorDrawable(Color.TRANSPARENT)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = books.size
            override fun getItem(i: Int) = books[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, parent: ViewGroup): View {
                val cell = (v as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(10))
                    val box = CoverBox(this@MainActivity).apply {
                        // Titre affiché à la place de la couverture quand le livre n'en a pas.
                        addView(text("", 13f, true).apply {
                            gravity = Gravity.CENTER
                            maxLines = 5
                            setPadding(dp(8), dp(8), dp(8), dp(8))
                        }, FrameLayout.LayoutParams(-1, -1))
                        addView(ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
                        foreground = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1), Theme.ui) }
                    }
                    addView(box, LinearLayout.LayoutParams(-1, -2))
                    addView(ProgressBar(this@MainActivity), LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(6) })
                    addView(text("", 12f, true).apply { maxLines = 1; setPadding(0, dp(4), 0, 0) })
                    addView(text("", 13f, true).apply { setPadding(0, dp(5), 0, 0) })
                    addView(text("", 12f, false).apply { maxLines = 1; setPadding(0, dp(2), 0, 0) })
                }
                val b = books[i]
                val box = cell.getChildAt(0) as FrameLayout
                val placeholder = box.getChildAt(0) as TextView
                val image = box.getChildAt(1) as ImageView
                if (b.cover) {
                    placeholder.text = ""
                    image.visibility = View.VISIBLE
                    loadCover(b, image)
                } else {
                    placeholder.text = b.title
                    image.visibility = View.GONE
                }
                // Progression : barre (vide = non lu, pleine = lu) et état en toutes lettres.
                val state = Library.state(b)
                (cell.getChildAt(1) as ProgressBar).value = when (state) { 0 -> 0f; 2 -> 1f; else -> maxOf(0.03f, b.percent / 100f) }
                (cell.getChildAt(2) as TextView).text = when (state) { 0 -> "Non lu"; 2 -> "✓ Lu"; else -> "En cours · ${b.percent} %" }
                (cell.getChildAt(3) as TextView).text = b.title
                (cell.getChildAt(4) as TextView).apply {
                    text = b.author
                    visibility = if (b.author.isEmpty()) View.GONE else View.VISIBLE
                }
                return cell
            }
        }
        grid.setOnItemClickListener { _, _, i, _ -> openBook(books[i]) }
        grid.setOnItemLongClickListener { _, _, i, _ ->
            val b = books[i]
            val what = if (Library.isImported(b)) "Supprimer « ${b.title} » de la bibliothèque ?"
            else "Supprimer définitivement ce fichier de l'appareil ?\n\n${b.path}"
            AlertDialog.Builder(this).setMessage(what)
                .setPositiveButton("Supprimer") { _, _ ->
                    if (reader.book?.id == b.id) {
                        reader.close()
                        Prefs.lastBook = ""
                        Prefs.save()
                    }
                    Library.delete(b)
                    renderLibrary()
                }
                .setNegativeButton("Annuler", null).show()
            true
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, -1))
        return col
    }
}
