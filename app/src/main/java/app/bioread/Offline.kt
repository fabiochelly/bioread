package app.bioread

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Traduction hors-ligne : le moteur de Firefox Translations (bergamot-translator, Marian),
 * compilé en bibliothèque native (tools/build_bergamot.sh), avec les modèles de Mozilla.
 *
 * Un modèle traduit dans un seul sens, depuis ou vers l'anglais (environ 37 Mo). Anglais →
 * français est livré dans l'app (assets/mt/enfr, rangé sans compression) et lu directement
 * dans l'APK, sans copie ; les autres sens sont téléchargés une fois, au premier besoin, depuis
 * les serveurs de Firefox, puis rangés dans files/mt/<sens>. Entre deux langues autres que
 * l'anglais (français → allemand), la traduction passe par l'anglais.
 *
 * Tout ici est bloquant : appelé sur le fil de traduction, jamais sur le thread principal.
 */
object Offline {
    private const val RECORDS = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records"
    private const val CDN = "https://firefox-settings-attachments.cdn.mozilla.net/"
    private const val BUNDLED = "enfr"

    /** Modèles chargés en mémoire, par sens (enfr) : deux au plus (passage par l'anglais). */
    private val loaded = LinkedHashMap<String, Long>()

    @Volatile private var lib: Boolean? = null

    private fun native(): Boolean {
        lib?.let { return it }
        val ok = try { System.loadLibrary("bergamot"); true } catch (e: Throwable) { false }
        lib = ok
        return ok
    }

    /**
     * Traduit [texts] (des paragraphes) de [src] vers [tgt]. [progress] reçoit l'avancement d'un
     * téléchargement de modèle, s'il en faut un. Lève une exception en cas d'échec.
     */
    @Synchronized
    fun translate(c: Context, src: String, tgt: String, texts: List<String>, progress: (String) -> Unit): List<String> {
        if (!native()) throw IllegalStateException("moteur de traduction absent pour ce processeur")
        val legs = if (src == "en" || tgt == "en") listOf(src + tgt) else listOf(src + "en", "en" + tgt)
        val handles = legs.map { model(c, it, progress) }
        // Le passage d'un modèle à l'autre libère ceux dont on n'a plus besoin.
        for (k in loaded.keys.toList()) if (k !in legs) loaded.remove(k)?.let { Bergamot.free(it) }
        val input = Array(texts.size) { texts[it].toByteArray(Charsets.UTF_8) }
        val out = if (handles.size == 1) Bergamot.translate(handles[0], input) else Bergamot.pivot(handles[0], handles[1], input)
        return out.map { String(it, Charsets.UTF_8) }
    }

    /** Rend la mémoire des modèles (traduction coupée). */
    @Synchronized
    fun trim() {
        for (h in loaded.values) Bergamot.free(h)
        loaded.clear()
    }

    private fun model(c: Context, pair: String, progress: (String) -> Unit): Long {
        loaded[pair]?.let { return it }
        // Modèle livré absent de l'APK (construit sans tools/fetch_mt_model.py) : téléchargé comme les autres.
        val h = if (pair == BUNDLED && bundled(c)) fromApk(c) else fromFiles(c, pair, progress)
        loaded[pair] = h
        return h
    }

    private fun bundled(c: Context) = try { c.assets.openFd("mt/$BUNDLED/model.bin").close(); true } catch (e: Exception) { false }

    /** Modèle livré dans l'app : le code natif lit ses fichiers dans l'APK même (ni décompression, ni copie). */
    private fun fromApk(c: Context): Long {
        // Copie laissée par une version précédente de l'app : inutile désormais.
        File(c.filesDir, "mt/$BUNDLED").takeIf { it.exists() }?.deleteRecursively()
        val names = listOf("model.bin", "lex.bin", "vocab.spm", "vocab.spm")
        val afd = names.distinct().associateWith { c.assets.openFd("mt/$BUNDLED/$it") }
        try {
            val f = names.map { afd.getValue(it) }
            return Bergamot.load(config("int8shiftAlphaAll", names), IntArray(4) { f[it].parcelFileDescriptor.fd },
                LongArray(4) { f[it].startOffset }, LongArray(4) { f[it].length })
        } finally {
            afd.values.forEach(AssetFileDescriptor::close)
        }
    }

    /** Modèle téléchargé (le premier besoin le télécharge), lu dans files/mt/<sens>. */
    private fun fromFiles(c: Context, pair: String, progress: (String) -> Unit): Long {
        val dir = File(c.filesDir, "mt/$pair")
        if (!File(dir, "ready").exists()) download(pair, dir, progress)
        val v = if (File(dir, "vocab.spm").exists()) listOf("vocab.spm", "vocab.spm") else listOf("srcvocab.spm", "trgvocab.spm")
        val names = listOf("model.bin", "lex.bin", v[0], v[1])
        val pfd = names.distinct().associateWith { ParcelFileDescriptor.open(File(dir, it), ParcelFileDescriptor.MODE_READ_ONLY) }
        try {
            val precision = File(dir, "precision").takeIf { it.exists() }?.readText()?.trim() ?: "int8shiftAlphaAll"
            return Bergamot.load(config(precision, names), IntArray(4) { pfd.getValue(names[it]).fd },
                LongArray(4), LongArray(4) { File(dir, names[it]).length() })
        } finally {
            pfd.values.forEach(ParcelFileDescriptor::close)
        }
    }

    /**
     * Télécharge un modèle depuis les serveurs de Firefox : la liste des fichiers (modèle,
     * vocabulaires, liste de traductions probables) vient de leur catalogue, chaque fichier
     * est vérifié par son empreinte SHA-256.
     */
    private fun download(pair: String, dir: File, progress: (String) -> Unit) {
        val from = pair.substring(0, 2)
        val to = pair.substring(2)
        progress("Recherche du modèle $from → $to…")
        val json = get("$RECORDS?fromLang=$from&toLang=$to")
        val data = JSONObject(String(json, Charsets.UTF_8)).getJSONArray("data")
        val recs = (0 until data.length()).map { data.getJSONObject(it) }.filter { it.optString("fromLang") == from && it.optString("toLang") == to }
        // La version la plus récente du modèle, et les fichiers de cette même version.
        val version = recs.filter { it.optString("fileType") == "model" }.maxByOrNull { ver(it.optString("version")) }?.optString("version")
            ?: throw IllegalStateException("aucun modèle $from → $to chez Firefox")
        val files = recs.filter { it.optString("version") == version }.associateBy { it.optString("fileType") }
        dir.deleteRecursively()
        dir.mkdirs()
        val total = files.values.sumOf { it.getJSONObject("attachment").optLong("size") }
        var done = 0L
        for ((type, r) in files) {
            val a = r.getJSONObject("attachment")
            val name = when (type) {
                "model" -> "model.bin"; "lex" -> "lex.bin"; "vocab" -> "vocab.spm"
                "srcvocab" -> "srcvocab.spm"; "trgvocab" -> "trgvocab.spm"; else -> continue
            }
            val base = done
            val bytes = get(CDN + a.getString("location")) { got ->
                progress("Modèle $from → $to : ${(base + got) / (1 shl 20)} Mo sur ${total / (1 shl 20)} Mo")
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (a.optString("hash").isNotEmpty() && hash != a.optString("hash")) throw IllegalStateException("fichier $name abîmé (empreinte différente)")
            File(dir, name).writeBytes(bytes)
            if (type == "model") File(dir, "precision").writeText(if ("alphas" in r.optString("name")) "int8shiftAlphaAll" else "int8shiftAll")
            done += bytes.size
        }
        File(dir, "ready").writeText("")
    }

    private fun ver(v: String) = v.split('.', 'a', 'b').mapNotNull { it.toIntOrNull() }.fold(0L) { s, x -> s * 1000 + x }

    private fun get(url: String, progress: ((Long) -> Unit)? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode} pour $url")
            val out = java.io.ByteArrayOutputStream(maxOf(c.contentLength, 1 shl 16))
            val buf = ByteArray(1 shl 16)
            var last = 0L
            c.inputStream.use { i ->
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (progress != null && out.size() - last > (1 shl 20)) { last = out.size().toLong(); progress(last) }
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    /**
     * Réglages de Marian, ceux de Firefox (recherche gloutonne, calcul en entiers 8 bits). Les
     * fichiers sont fournis en mémoire ; Marian veut tout de même le nom des vocabulaires ([names] :
     * modèle, liste, vocabulaire source, cible).
     */
    private fun config(precision: String, names: List<String>) = """
        vocabs:
          - ${names[2]}
          - ${names[3]}
        beam-size: 1
        normalize: 1.0
        word-penalty: 0
        max-length-break: 128
        mini-batch-words: 1024
        workspace: 128
        max-length-factor: 2.0
        skip-cost: true
        cpu-threads: 0
        quiet: true
        quiet-translation: true
        gemm-precision: $precision
        alignment: soft
    """.trimIndent()
}

/** Points d'entrée de libbergamot.so (tools/bergamot/bergamot_jni.cpp). Textes en UTF-8. */
internal object Bergamot {
    external fun load(yaml: String, fds: IntArray, offsets: LongArray, lengths: LongArray): Long
    external fun free(model: Long)
    external fun translate(model: Long, texts: Array<ByteArray>): Array<ByteArray>
    external fun pivot(first: Long, second: Long, texts: Array<ByteArray>): Array<ByteArray>
}
