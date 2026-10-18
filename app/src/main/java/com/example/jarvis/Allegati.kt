package com.example.jarvis

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Legge i file che l'utente carica (foto, PDF, Word, Excel, testi, audio, video)
 * e li trasforma in contenuti che il cervello di Jarvis può capire.
 */
object Allegati {

    const val MASSIMO_BYTE = 19_000_000

    fun nome(ctx: Context, uri: Uri): String {
        var nome = "file"
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) nome = it.getString(0) ?: nome
            }
        } catch (e: Exception) { }
        return nome
    }

    /** Legge un file. Restituisce null se è troppo grande o illeggibile. */
    fun leggi(ctx: Context, uri: Uri, tipoSuggerito: String? = null): Cervello.Contenuto? {
        val nome = nome(ctx, uri)
        var tipo = ctx.contentResolver.getType(uri) ?: tipoSuggerito ?: tipoDaNome(nome)
        if (tipo == "application/octet-stream") tipo = tipoDaNome(nome)
        val dati = try {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) { null } ?: return null
        return daByte(nome, tipo, dati)
    }

    fun daByte(nome: String, tipo: String, dati: ByteArray): Cervello.Contenuto? {
        if (dati.isEmpty() || dati.size > MASSIMO_BYTE) return null
        val minuscolo = nome.lowercase()
        return when {
            tipo.startsWith("text/") || minuscolo.endsWith(".txt") || minuscolo.endsWith(".csv") ||
                minuscolo.endsWith(".md") || minuscolo.endsWith(".json") ->
                Cervello.Contenuto("testo", nome, String(dati, Charsets.UTF_8), null, "text/plain")
            minuscolo.endsWith(".docx") || tipo.contains("wordprocessingml") ->
                Cervello.Contenuto("testo", nome, testoDaZip(dati, Regex("word/document\\.xml")), null, "text/plain")
            minuscolo.endsWith(".xlsx") || tipo.contains("spreadsheetml") ->
                Cervello.Contenuto("testo", nome, testoDaZip(dati, Regex("xl/sharedStrings\\.xml|xl/worksheets/sheet\\d+\\.xml")), null, "text/plain")
            minuscolo.endsWith(".pptx") || tipo.contains("presentationml") ->
                Cervello.Contenuto("testo", nome, testoDaZip(dati, Regex("ppt/slides/slide\\d+\\.xml")), null, "text/plain")
            minuscolo.endsWith(".odt") || minuscolo.endsWith(".ods") || tipo.contains("opendocument") ->
                Cervello.Contenuto("testo", nome, testoDaZip(dati, Regex("content\\.xml")), null, "text/plain")
            else -> Cervello.Contenuto(
                when {
                    tipo.startsWith("image/") -> "immagine"
                    tipo.startsWith("audio/") -> "audio"
                    tipo == "application/pdf" -> "pdf"
                    tipo.startsWith("video/") -> "video"
                    else -> "file"
                },
                nome, "", Base64.encodeToString(dati, Base64.NO_WRAP), tipo
            )
        }
    }

    private fun tipoDaNome(nome: String): String = when (nome.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "mp3" -> "audio/mpeg"
        "ogg", "opus" -> "audio/ogg"
        "m4a", "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        else -> "application/octet-stream"
    }

    /** Word, Excel, PowerPoint e OpenDocument sono archivi zip di XML: ne estraggo il testo. */
    private fun testoDaZip(dati: ByteArray, quali: Regex): String {
        val testo = StringBuilder()
        try {
            ZipInputStream(ByteArrayInputStream(dati)).use { zip ->
                while (true) {
                    val voce = zip.nextEntry ?: break
                    if (!quali.matches(voce.name)) continue
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    testo.append(
                        xml.replace(Regex("</(w:p|a:p|text:p|row)>"), "\n")
                            .replace(Regex("<(w:tab|text:tab)/>"), "\t")
                            .replace(Regex("<[^>]+>"), " ")
                            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                            .replace(Regex("[ \\t]+"), " ")
                            .replace(Regex("\\n\\s*\\n+"), "\n")
                    ).append("\n")
                    if (testo.length > 200_000) break
                }
            }
        } catch (e: Exception) { }
        return testo.toString().trim().ifEmpty { "(documento vuoto o non leggibile)" }
    }
}
