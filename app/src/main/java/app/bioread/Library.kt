package app.bioread

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BookEntry(
    val id: String,
    val path: String,
    val title: String,
    val author: String,
    val lang: String,
    val size: Long,
    val mtime: Long,
    val cover: Boolean,
) {
    // Position de lecture : élément du spine, caractère dans le chapitre, pourcentage global.
    var spine = 0
    var offset = 0
    var percent = 0
    var lastRead = 0L
}

/**
 * Tous les EPUB de l'appareil, lus directement là où ils sont (aucune copie). L'index
 * (métadonnées + vignettes de couverture) est mis en cache : l'accueil s'affiche tout de
 * suite, puis un parcours en arrière-plan ne relit que les fichiers nouveaux ou modifiés.
 */
object Library {
    var books = ArrayList<BookEntry>()
        private set
    private lateinit var own: File        // livres importés par « Ajouter » ou « Ouvrir avec »
    private lateinit var covers: File
    private lateinit var indexFile: File
    private val main = Handler(Looper.getMainLooper())
    private var scanning = false
    private var dirty = false
    private val waiting = ArrayList<(Boolean) -> Unit>()

    private val SKIP = hashSetOf(
        "Android", "DCIM", "Pictures", "Movies", "Music", "Alarms", "Notifications", "Ringtones", "Podcasts",
    )

    fun init(c: Context) {
        own = File(c.filesDir, "books").apply { mkdirs() }
        covers = File(c.filesDir, "covers").apply { mkdirs() }
        indexFile = File(c.filesDir, "index.json")
        val list = ArrayList<BookEntry>()
        try {
            val arr = JSONArray(indexFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    BookEntry(
                        o.getString("id"), o.getString("path"), o.getString("title"), o.optString("author"),
                        o.optString("lang"), o.optLong("size"), o.optLong("mtime"), o.optBoolean("cover"),
                    ).also { loadPosition(it) }
                )
            }
        } catch (e: Exception) {
            // Pas encore d'index : le premier parcours le crée.
        }
        books = list
        sort()
    }

    private val collator = java.text.Collator.getInstance(java.util.Locale.FRENCH).apply { strength = java.text.Collator.PRIMARY }

    /** Nom de famille d'abord : « Jane Austen » se classe à A. */
    private fun authorKey(b: BookEntry): String {
        val a = b.author.trim()
        if (a.isEmpty()) return "\uFFFF"   // sans auteur : à la fin
        val i = a.lastIndexOf(' ')
        return if (i < 0) a else a.substring(i + 1) + " " + a.substring(0, i)
    }

    /** Tri de l'accueil selon [Prefs.sort] : dernière lecture (puis ajout récent), titre ou auteur. */
    fun sort() {
        val byTitle = Comparator<BookEntry> { x, y -> collator.compare(x.title, y.title) }
        when (Prefs.sort) {
            1 -> books.sortWith(byTitle)
            2 -> books.sortWith(Comparator<BookEntry> { x, y -> collator.compare(authorKey(x), authorKey(y)) }.then(byTitle))
            else -> books.sortWith(compareByDescending<BookEntry> { it.lastRead }.thenByDescending { it.mtime }.then(byTitle))
        }
    }

    /** 0 = non lu, 1 = en cours, 2 = lu (97 % et plus : les dernières pages sont souvent des annexes). */
    fun state(b: BookEntry) = if (b.lastRead == 0L) 0 else if (b.percent >= 97) 2 else 1

    fun file(b: BookEntry) = File(b.path)

    fun coverFile(b: BookEntry) = File(covers, "${b.id}.jpg")

    fun find(id: String) = books.firstOrNull { it.id == id }

    fun isImported(b: BookEntry) = b.path.startsWith(own.path + "/")

    private fun loadPosition(b: BookEntry) {
        val pos = Prefs.sp.getString("pos_${b.id}", null)?.split(',') ?: return
        if (pos.size != 4) return
        b.spine = pos[0].toInt(); b.offset = pos[1].toInt(); b.percent = pos[2].toInt(); b.lastRead = pos[3].toLong()
    }

    fun savePosition(b: BookEntry) {
        b.lastRead = System.currentTimeMillis()
        Prefs.sp.edit().putString("pos_${b.id}", "${b.spine},${b.offset},${b.percent},${b.lastRead}").apply()
    }

    /** Vrai si l'app peut parcourir le stockage partagé. */
    fun hasAccess(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- parcours

    /** Parcourt l'appareil en arrière-plan ; [done] reçoit true si la liste a changé (thread principal). */
    fun scan(c: Context, done: (Boolean) -> Unit) {
        waiting.add(done)
        if (scanning) {
            dirty = true
            return
        }
        scanning = true
        val app = c.applicationContext
        val old = HashMap<String, BookEntry>()
        for (b in books) old[b.path] = b
        Thread {
            val found = try { scanNow(app, old) } catch (e: Exception) { null }
            main.post {
                scanning = false
                var changed = false
                if (found != null) {
                    changed = found.size != books.size || found.any { old[it.path] !== it }
                    books = found
                    sort()
                }
                if (dirty) {
                    // Une demande est arrivée pendant le parcours (import) : on repart.
                    dirty = false
                    val again = ArrayList(waiting)
                    waiting.clear()
                    scan(app) { r -> for (w in again) w(r || changed) }
                } else {
                    val w = ArrayList(waiting)
                    waiting.clear()
                    for (x in w) x(changed)
                }
            }
        }.start()
    }

    private fun scanNow(c: Context, old: Map<String, BookEntry>): ArrayList<BookEntry> {
        val files = ArrayList<File>()
        // Les fichiers de l'appareil d'abord : en cas de doublon, c'est eux qu'on garde.
        if (hasAccess(c)) for (r in roots(c)) walk(r, 0, files)
        own.listFiles()?.filterTo(files) { it.name.endsWith(".epub", true) }
        val out = ArrayList<BookEntry>()
        val seen = HashSet<String>()
        for (f in files) {
            val size = f.length()
            val mtime = f.lastModified()
            val o = old[f.path]
            val e = if (o != null && o.size == size && o.mtime == mtime) o else read(f, size, mtime) ?: continue
            // Un même livre présent à deux endroits n'apparaît qu'une fois.
            if (seen.add(e.id) && seen.add("${e.title}|${e.author}|${e.size}")) out.add(e)
        }
        val arr = JSONArray()
        for (b in out) arr.put(
            JSONObject().put("id", b.id).put("path", b.path).put("title", b.title).put("author", b.author)
                .put("lang", b.lang).put("size", b.size).put("mtime", b.mtime).put("cover", b.cover)
        )
        indexFile.writeText(arr.toString())
        return out
    }

    private fun roots(c: Context): List<File> {
        val r = ArrayList<File>()
        @Suppress("DEPRECATION")
        r.add(Environment.getExternalStorageDirectory())
        // Autres volumes (carte microSD) : on remonte depuis leur dossier Android/data.
        for (d in c.getExternalFilesDirs(null)) {
            val p = d?.path ?: continue
            val i = p.indexOf("/Android/")
            if (i > 0) r.add(File(p.substring(0, i)))
        }
        return r.distinctBy { it.path }
    }

    private fun walk(dir: File, depth: Int, out: ArrayList<File>) {
        if (depth > 7) return
        val list = dir.listFiles() ?: return
        for (f in list) {
            val n = f.name
            if (n.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth == 0 && n in SKIP) continue
                walk(f, depth + 1, out)
            } else if (n.endsWith(".epub", true)) out.add(f)
        }
    }

    private fun read(f: File, size: Long, mtime: Long): BookEntry? {
        // Identifiant stable si le fichier est déplacé ; les imports gardent leur nom comme identifiant.
        val id = if (f.parentFile == own) f.nameWithoutExtension
        else "f" + java.lang.Long.toString(((f.name.hashCode().toLong() shl 32) xor size) and Long.MAX_VALUE, 36)
        return try {
            val e = Epub(f)
            try {
                if (e.spine.isEmpty()) return null
                BookEntry(
                    id, f.path, e.title.ifEmpty { f.nameWithoutExtension }, e.author, e.lang.take(2).lowercase(),
                    size, mtime, makeCover(e, id),
                ).also { loadPosition(it) }
            } finally {
                e.close()
            }
        } catch (x: Exception) {
            null
        }
    }

    /** Vignette de couverture (400 px de large au plus) enregistrée une fois pour toutes. */
    private fun makeCover(e: Epub, id: String): Boolean {
        val p = e.coverPath ?: return false
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            e.open(p)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0 || o.outHeight <= 0) return false
            var sample = 1
            while (o.outWidth / (sample * 2) >= 400) sample *= 2
            val full = e.open(p)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return false
            val small = if (full.width > 400) Bitmap.createScaledBitmap(full, 400, full.height * 400 / full.width, true) else full
            File(covers, "$id.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            true
        } catch (x: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------- import / suppression

    /** Copie un EPUB reçu par « Ouvrir avec » ou le sélecteur. Hors du thread principal. */
    fun import(c: Context, uri: Uri): File? {
        val f = File(own, java.lang.Long.toString(System.nanoTime(), 36) + ".epub")
        return try {
            (c.contentResolver.openInputStream(uri) ?: return null).use { input ->
                f.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            val e = Epub(f)
            val ok = e.spine.isNotEmpty()
            e.close()
            if (ok) f else { f.delete(); null }
        } catch (e: Exception) {
            f.delete()
            null
        }
    }

    fun delete(b: BookEntry) {
        books.remove(b)
        file(b).delete()
        coverFile(b).delete()
        Prefs.sp.edit().remove("pos_${b.id}").apply()
    }
}
