package app.bioread

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.concurrent.Executors

/**
 * Carnet des mots consultés : chaque appui long sur un mot (définition, traduction) ajoute une
 * ligne à Download/bioread-mots.csv, lisible par USB et dans un tableur (séparateur « ; »,
 * UTF-8). Colonnes : date, mot, langue, livre, auteur, phrase où le mot a été rencontré.
 * L'écriture se fait hors du thread principal ; un échec est sans conséquence pour la lecture.
 */
object WordLog {
    private val io = Executors.newSingleThreadExecutor()
    private const val NAME = "bioread-mots.csv"

    private fun field(s: String) = "\"" + s.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ').trim() + "\""

    fun add(c: Context, word: String, lang: String, title: String, author: String, sentence: String) {
        val app = c.applicationContext
        val now = System.currentTimeMillis()
        io.execute {
            try {
                @Suppress("DEPRECATION")
                val shared = File(Environment.getExternalStorageDirectory(), "Download")
                // Sans accès au stockage partagé : dossier de l'app (Android/data/app.bioread/files).
                val dir = if (Library.hasAccess(app) && (shared.isDirectory || shared.mkdirs())) shared else app.getExternalFilesDir(null) ?: return@execute
                val f = File(dir, NAME)
                val fresh = !f.exists() || f.length() == 0L
                val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(now))
                val line = listOf(date, word, lang, title, author, sentence).joinToString(";") { field(it) } + "\r\n"
                java.io.FileOutputStream(f, true).use { out ->
                    // Marque d'encodage en tête : les tableurs lisent alors les accents correctement.
                    if (fresh) out.write(("﻿" + "date;mot;langue;livre;auteur;phrase\r\n").toByteArray(Charsets.UTF_8))
                    out.write(line.toByteArray(Charsets.UTF_8))
                }
                // Sans ce signalement, le fichier (ou sa nouvelle taille) reste invisible par USB.
                android.media.MediaScannerConnection.scanFile(app, arrayOf(f.path), null, null)
            } catch (e: Throwable) {
            }
        }
    }
}
