package app.bioread

import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * Synchronisation de la position de lecture entre appareils, par le protocole de KOReader
 * (« kosync ») : un petit serveur, public (sync.koreader.rocks) ou hébergé chez soi, garde pour
 * chaque livre la dernière position envoyée. Bioread, KOReader et les autres lecteurs qui parlent
 * ce protocole se retrouvent ainsi à la même page d'un appareil à l'autre.
 *
 * - Le livre est reconnu par l'empreinte de son fichier, calculée comme le fait KOReader (MD5 de
 *   douze extraits de 1 Ko pris à des distances croissantes) : le même fichier sur deux
 *   appareils est le même livre, quel que soit son nom.
 * - La position est un chemin dans le XHTML du chapitre ([XPointer]), le format de KOReader pour
 *   les EPUB, doublé du pourcentage dans le livre, qui sert de repli quand le chemin ne se
 *   retrouve pas.
 * - Le mot de passe n'est jamais gardé ni envoyé : seulement son empreinte MD5, comme le veut
 *   le protocole.
 *
 * Le serveur peut aussi être une simple page PHP hébergée chez soi (server/bioread-sync/index.php
 * dans les sources) : mêmes comptes, mêmes empreintes, mêmes positions, mais des adresses en
 * « ?action=… » qui marchent sur n'importe quel hébergement, sans réécriture d'adresses. À la
 * connexion, l'app demande à l'adresse si elle est une page Bioread ; sinon, c'est du kosync.
 *
 * Tout le réseau se fait sur un fil à part ; les réponses reviennent sur le thread principal.
 * Sans réseau, rien ne se passe et rien n'est signalé : la lecture n'attend jamais le serveur.
 */
object KoSync {
    const val DEFAULT_SERVER = "https://sync.koreader.rocks"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Position gardée par le serveur. [percentage] : de 0 à 1. [timestamp] : secondes. */
    class Remote(val progress: String, val percentage: Double, val device: String, val deviceId: String, val timestamp: Long)

    val configured get() = Prefs.syncUser.isNotEmpty() && Prefs.syncKey.isNotEmpty()

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(Character.forDigit((x.toInt() shr 4) and 15, 16)).append(Character.forDigit(x.toInt() and 15, 16))
        return sb.toString()
    }

    fun md5(s: String) = hex(MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)))

    private fun deviceId(): String {
        if (Prefs.syncDevice.isEmpty()) {
            Prefs.syncDevice = md5("${System.nanoTime()}-${Math.random()}").uppercase()
            Prefs.save()
        }
        return Prefs.syncDevice
    }

    /**
     * Empreinte du fichier, celle de KOReader (util.partialMD5) : 1024 octets lus aux positions
     * 0, 1 Ko, 4 Ko, 16 Ko… jusqu'à 1 Go, tant que le fichier va jusque-là. Gardée par livre.
     */
    private fun digest(b: BookEntry): String? {
        val saved = Prefs.pos.getString("md5_${b.id}", null)?.split('|')
        if (saved != null && saved.size == 3 && saved[0] == b.size.toString() && saved[1] == b.mtime.toString()) return saved[2]
        return try {
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(1024)
            RandomAccessFile(Library.file(b), "r").use { f ->
                for (i in -1..10) {
                    val at = if (i < 0) 0L else 1024L shl (2 * i)
                    if (at >= f.length()) break
                    f.seek(at)
                    val n = f.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            hex(md.digest()).also { Prefs.pos.edit().putString("md5_${b.id}", "${b.size}|${b.mtime}|$it").apply() }
        } catch (e: Exception) {
            null
        }
    }

    private class Reply(val code: Int, val json: JSONObject?)

    /**
     * Adresse d'une demande kosync ([path] : /users/auth, /syncs/progress…), ou sa traduction
     * pour une page Bioread : « dossier/?action=auth », « dossier/?action=progress&document=… ».
     */
    private fun endpoint(server: String, path: String, bio: Boolean): String {
        if (!bio) return server.trimEnd('/') + path
        val q = when {
            path == "/users/create" -> "create"
            path == "/users/auth" -> "auth"
            path.startsWith("/syncs/progress/") -> "progress&document=" + path.substringAfterLast('/')
            path == "/syncs/progress" -> "progress"
            else -> path.trim('/')
        }
        return "$server?action=$q"
    }

    /** Adresse d'une page Bioread : le fichier lui-même, ou son dossier terminé par « / ». */
    private fun page(url: String): String {
        val u = url.substringBefore('?').substringBefore('#')
        return if (u.endsWith(".php") || u.endsWith("/")) u else "$u/"
    }

    /** Vrai si [url] désigne une page Bioread (elle répond {"bioread": 1} à « ?action=hello »). */
    private fun isBioread(url: String): Boolean = try {
        call("GET", "hello", null, page(url), "", "", true).let { it.code == 200 && it.json?.optInt("bioread") == 1 }
    } catch (e: Exception) {
        false
    }

    private fun call(
        method: String, path: String, body: JSONObject?, server: String = Prefs.syncServer,
        user: String = Prefs.syncUser, key: String = Prefs.syncKey, bio: Boolean = Prefs.syncBioread,
    ): Reply {
        val c = URL(endpoint(server, path, bio)).openConnection() as HttpURLConnection
        try {
            // Une page PHP chez un hébergeur ordinaire n'accepte pas toujours PUT.
            c.requestMethod = if (bio && method == "PUT") "POST" else method
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.koreader.v1+json")
            if (user.isNotEmpty()) {
                c.setRequestProperty("x-auth-user", user)
                c.setRequestProperty("x-auth-key", key)
            }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = try { (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } } catch (e: Exception) { null }
            return Reply(code, try { JSONObject(text ?: "") } catch (e: Exception) { null })
        } finally {
            c.disconnect()
        }
    }

    /**
     * Vérifie le compte ([create] : le crée d'abord) puis l'enregistre. [done] reçoit null si tout
     * va bien, sinon le message à montrer.
     */
    fun login(server: String, user: String, password: String, create: Boolean, done: (String?) -> Unit) {
        val given = server.trim().ifEmpty { DEFAULT_SERVER }.let { if (it.contains("://")) it else "https://$it" }
        val key = md5(password)
        io.execute {
            // Page Bioread ou serveur KOReader : on demande à l'adresse elle-même.
            val bio = given != DEFAULT_SERVER && isBioread(given)
            val url = if (bio) page(given) else given
            val error: String? = try {
                var problem: String? = null
                if (create) {
                    val r = call("POST", "/users/create", JSONObject().put("username", user).put("password", key), url, "", "", bio)
                    if (r.code != 201 && r.code != 200) {
                        problem = when (r.code) {
                            402 -> "Cet identifiant est déjà pris sur ce serveur."
                            403 -> if (bio) "Inscriptions fermées : ajoutez le compte dans bioread-data.php, sur le serveur." else "Création refusée (403)."
                            else -> "Création refusée (${r.json?.optString("message")?.ifEmpty { null } ?: r.code})."
                        }
                    }
                }
                if (problem == null) {
                    val r = call("GET", "/users/auth", null, url, user, key, bio)
                    if (r.code == 401) problem = "Identifiant ou mot de passe refusé."
                    else if (r.code != 200) problem = "Réponse inattendue du serveur (${r.json?.optString("message")?.ifEmpty { null } ?: r.code})."
                }
                problem
            } catch (e: Exception) {
                "Serveur injoignable : ${e.message ?: e.javaClass.simpleName}"
            }
            main.post {
                if (error == null) {
                    Prefs.syncServer = url
                    Prefs.syncBioread = bio
                    Prefs.syncUser = user
                    Prefs.syncKey = key
                    Prefs.save()
                }
                done(error)
            }
        }
    }

    fun logout() {
        Prefs.syncUser = ""
        Prefs.syncKey = ""
        Prefs.save()
    }

    /**
     * Compte déposé dans Download/bioread-sync.txt (serveur, identifiant, mot de passe : une
     * ligne chacun) : la saisie sans clavier, comme pour la clé DeepL. Le fichier est supprimé.
     */
    fun importFile(f: File, done: (String?) -> Unit) {
        val lines = try { f.readLines().map { it.trim() }.filter { it.isNotEmpty() } } catch (e: Exception) { return }
        f.delete()
        if (lines.size == 3) login(lines[0], lines[1], lines[2], false, done)
        else if (lines.size == 2) login(DEFAULT_SERVER, lines[0], lines[1], false, done)
        else done("bioread-sync.txt : trois lignes attendues (serveur, identifiant, mot de passe).")
    }

    /** Demande la position gardée par le serveur pour [b] ; [done] reçoit null s'il n'y en a pas ou sans réseau. */
    fun pull(b: BookEntry, done: (Remote?) -> Unit) {
        if (!configured) return
        io.execute {
            val r: Remote? = try {
                val doc = digest(b)
                val j = if (doc == null) null else call("GET", "/syncs/progress/$doc", null).takeIf { it.code == 200 }?.json
                if (j == null || !j.has("progress")) null
                else Remote(j.optString("progress"), j.optDouble("percentage", 0.0), j.optString("device"), j.optString("device_id"), j.optLong("timestamp"))
            } catch (e: Exception) {
                null
            }
            main.post { done(r) }
        }
    }

    /** Vrai si la position [r] a été envoyée par cet appareil. */
    fun mine(r: Remote) = r.deviceId == deviceId()

    /**
     * Envoie la position de [b] : le caractère [offset] du chapitre [spine] (dont [ch] est
     * l'analyse et [path] le fichier dans l'EPUB), à [percentage] (0 à 1) du livre.
     */
    fun push(b: BookEntry, spine: Int, path: String, ch: Chapter, offset: Int, percentage: Double) {
        if (!configured) return
        val file = Library.file(b)
        val id = deviceId()
        io.execute {
            try {
                val doc = digest(b) ?: return@execute
                // Le fichier est rouvert ici : le livre a pu être fermé entre-temps sur le thread principal.
                val html = ZipFile(file).use { z -> z.getEntry(path)?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } } ?: return@execute
                val xp = XPointer.make(html, spine, ch.lettersBefore(offset))
                call(
                    "PUT", "/syncs/progress",
                    JSONObject().put("document", doc).put("progress", xp).put("percentage", percentage)
                        .put("device", Build.MODEL ?: "Android").put("device_id", id),
                )
            } catch (e: Exception) {
                // Hors ligne : la position partira à la prochaine occasion.
            }
        }
    }
}

/** Nombre de lettres et de chiffres du chapitre avant la position [offset]. */
fun Chapter.lettersBefore(offset: Int): Int {
    var n = 0
    for (b in blocks) {
        if (b.start >= offset) break
        if (b.kind == Block.IMG) continue
        val t = b.text
        val to = minOf(offset - b.start, t.length)
        for (i in 0 until to) if (Character.isLetterOrDigit(t[i])) n++
    }
    return n
}

/** Position, dans le chapitre, de sa lettre numéro [n] (à partir de 0). */
fun Chapter.offsetOfLetter(n: Int): Int {
    var left = n
    for (b in blocks) {
        if (b.kind == Block.IMG) continue
        val t = b.text
        for (i in 0 until t.length) if (Character.isLetterOrDigit(t[i])) {
            if (left == 0) return b.start + i
            left--
        }
    }
    return maxOf(0, length - 1)
}

/**
 * Positions de KOReader dans un EPUB : « /body/DocFragment[12]/body/div/p[3]/text().42 », soit
 * le chapitre (numéroté à partir de 1), le chemin d'un élément dans son XHTML, puis un nœud de
 * texte et un caractère. Bioread compte, lui, en caractères du texte analysé. Le pont entre les
 * deux est le nombre de lettres qui précèdent la position : il ne dépend ni des espaces, ni des
 * entités, ni de la façon dont chaque lecteur découpe le texte.
 */
object XPointer {
    private val VOID = hashSetOf("br", "hr", "img", "image", "meta", "link", "input", "col", "wbr", "area", "base", "source", "embed", "param", "track")
    private val PREFIX = Regex("^/body/DocFragment(?:\\[(\\d+)])?")

    private class Frame(val name: String, val index: Int) {
        val counts = HashMap<String, Int>()
        var texts = 0
        var match = false   // lecture d'un chemin : l'élément est sur le chemin cherché
    }

    private fun letters(s: CharSequence, to: Int = s.length): Int {
        var n = 0
        for (i in 0 until to) if (Character.isLetterOrDigit(s[i])) n++
        return n
    }

    private fun blank(s: CharSequence): Boolean {
        for (i in 0 until s.length) if (s[i] > ' ') return false
        return true
    }

    /** Chapitre (à partir de 0) désigné par [xp], ou -1. */
    fun spine(xp: String): Int {
        val m = PREFIX.find(xp) ?: return -1
        return (m.groupValues[1].toIntOrNull() ?: 1) - 1
    }

    /** Chemin du nœud de texte de [html] (chapitre [spine]) où se trouve la lettre numéro [target]. */
    fun make(html: String, spine: Int, target: Int): String {
        val root = "/body/DocFragment[${spine + 1}]/body"
        val t = Tok(html)
        val stack = ArrayList<Frame>()
        val sb = StringBuilder()
        var seen = 0
        var skip = 0
        while (true) {
            when (t.next()) {
                Tok.END -> return root
                Tok.OPEN -> {
                    val nm = t.name
                    if (skip > 0) { if (!t.selfClosing && (nm == "head" || nm == "style" || nm == "script")) skip++; continue }
                    if (nm == "head" || nm == "style" || nm == "script") { if (!t.selfClosing) skip++; continue }
                    if (stack.isEmpty()) { if (nm == "body") stack.add(Frame(nm, 1)); continue }
                    val parent = stack[stack.size - 1]
                    val k = (parent.counts[nm] ?: 0) + 1
                    parent.counts[nm] = k
                    if (!t.selfClosing && nm !in VOID) stack.add(Frame(nm, k))
                }
                Tok.CLOSE -> {
                    val nm = t.name
                    if (skip > 0) { if (nm == "head" || nm == "style" || nm == "script") skip--; continue }
                    // Balise fermante sans ouvrante (XHTML bancal) : ignorée.
                    val at = stack.indexOfLast { it.name == nm }
                    if (at >= 0) while (stack.size > at) stack.removeAt(stack.size - 1)
                }
                Tok.TEXT -> if (skip == 0 && stack.isNotEmpty()) {
                    sb.setLength(0)
                    appendDecoded(sb, t.s, t.textStart, t.textEnd, false)
                    if (blank(sb)) continue
                    val f = stack[stack.size - 1]
                    f.texts++
                    val n = letters(sb)
                    if (seen + n > target) {
                        val out = StringBuilder(root)
                        for (i in 1 until stack.size) out.append('/').append(stack[i].name).append('[').append(stack[i].index).append(']')
                        out.append("/text()")
                        if (f.texts > 1) out.append('[').append(f.texts).append(']')
                        // Caractère du nœud où tombe la lettre cherchée.
                        var left = target - seen
                        var i = 0
                        while (i < sb.length) {
                            if (Character.isLetterOrDigit(sb[i])) { if (left == 0) break; left-- }
                            i++
                        }
                        return out.append('.').append(i).toString()
                    }
                    seen += n
                }
            }
        }
    }

    /**
     * Numéro de la lettre de [html] que désigne [xp], ou -1 si le chemin ne s'y retrouve pas du
     * tout. Un chemin retrouvé en partie (KOReader ajoute parfois des éléments de son cru) donne
     * le début du dernier élément reconnu.
     */
    fun read(html: String, xp: String): Int {
        var rest = PREFIX.replace(xp, "")
        var textIndex = 1
        var textOffset = 0
        var wantsText = false
        val ti = rest.indexOf("/text()")
        if (ti >= 0) {
            wantsText = true
            val tail = rest.substring(ti + 7)
            rest = rest.substring(0, ti)
            Regex("^\\[(\\d+)]").find(tail)?.let { textIndex = it.groupValues[1].toInt() }
            tail.substringAfterLast('.', "").toIntOrNull()?.let { textOffset = it }
        } else {
            // « …/p[3].0 » : position dans un élément, sans nœud de texte.
            val dot = rest.lastIndexOf('.')
            if (dot > rest.lastIndexOf('/')) rest = rest.substring(0, dot)
        }
        val names = ArrayList<String>()
        val indexes = ArrayList<Int>()
        for (seg in rest.split('/')) {
            if (seg.isEmpty()) continue
            val b = seg.indexOf('[')
            names.add(if (b < 0) seg else seg.substring(0, b))
            indexes.add(if (b < 0) 1 else seg.substring(b + 1).trimEnd(']').toIntOrNull() ?: 1)
        }
        if (names.isEmpty() || names[0] != "body") return -1

        val t = Tok(html)
        val stack = ArrayList<Frame>()
        val sb = StringBuilder()
        var seen = 0
        var skip = 0
        var best = -1          // lettres avant le dernier élément reconnu du chemin
        var bestDepth = 0
        var target: Frame? = null
        while (true) {
            when (t.next()) {
                Tok.END -> return best
                Tok.OPEN -> {
                    val nm = t.name
                    if (skip > 0) { if (!t.selfClosing && (nm == "head" || nm == "style" || nm == "script")) skip++; continue }
                    if (nm == "head" || nm == "style" || nm == "script") { if (!t.selfClosing) skip++; continue }
                    val f: Frame
                    if (stack.isEmpty()) {
                        if (nm != "body") continue
                        f = Frame(nm, 1)
                        f.match = true
                    } else {
                        val parent = stack[stack.size - 1]
                        val k = (parent.counts[nm] ?: 0) + 1
                        parent.counts[nm] = k
                        f = Frame(nm, k)
                        val d = stack.size
                        f.match = parent.match && d < names.size && names[d] == nm && indexes[d] == k
                    }
                    if (f.match && stack.size + 1 > bestDepth) {
                        bestDepth = stack.size + 1
                        best = seen
                        if (bestDepth == names.size) {
                            if (!wantsText) return seen
                            target = f
                        }
                    }
                    if (!t.selfClosing && nm !in VOID) stack.add(f)
                }
                Tok.CLOSE -> {
                    val nm = t.name
                    if (skip > 0) { if (nm == "head" || nm == "style" || nm == "script") skip--; continue }
                    val at = stack.indexOfLast { it.name == nm }
                    if (at >= 0) while (stack.size > at) {
                        // L'élément visé se referme sans le nœud de texte demandé : son début fait l'affaire.
                        if (stack.removeAt(stack.size - 1) === target) return best
                    }
                }
                Tok.TEXT -> if (skip == 0 && stack.isNotEmpty()) {
                    sb.setLength(0)
                    appendDecoded(sb, t.s, t.textStart, t.textEnd, false)
                    if (blank(sb)) continue
                    val f = stack[stack.size - 1]
                    f.texts++
                    if (f === target && f.texts == textIndex) return seen + letters(sb, minOf(textOffset, sb.length))
                    seen += letters(sb)
                }
            }
        }
    }
}
