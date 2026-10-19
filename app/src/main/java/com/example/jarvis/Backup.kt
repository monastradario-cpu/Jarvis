package com.example.jarvis

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup della memoria di Jarvis in un file: ricordi, lezioni, routine, diario, note, promemoria,
 * luoghi, impostazioni, voce registrata. Le chiavi API e le password NON vengono copiate.
 * Il file si può salvare dove vuoi (anche su Google Drive) e ripristinare su un altro telefono.
 */
object Backup {

    private val ESCLUSE = setOf(
        "api_key", "chiave_claude", "posta_password", "casa_token", "pc_token",
        "backup_cartella", "backup_ultimo", "avvisi_fatti", "in_auto", "cuffie", "sempre_attivo"
    )
    private val FILE = listOf("diario.json", "conversazione_claude.json", "conversazione_gemini.json")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun nomeFile() = "jarvis-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.ITALIAN).format(Date()) + ".json"

    fun esporta(ctx: Context): String {
        val impostazioni = JSONObject()
        for ((chiave, valore) in prefs(ctx).all) {
            if (chiave in ESCLUSE || valore == null) continue
            val tipo = when (valore) {
                is Boolean -> "b"
                is Int -> "i"
                is Long -> "l"
                is Float -> "f"
                is Set<*> -> "set"
                else -> "s"
            }
            val v: Any = if (valore is Set<*>) JSONArray(valore.map { it.toString() }) else valore
            impostazioni.put(chiave, JSONObject().put("t", tipo).put("v", v))
        }
        val file = JSONObject()
        for (nome in FILE) {
            val f = File(ctx.filesDir, nome)
            if (f.exists()) file.put(nome, f.readText())
        }
        return JSONObject()
            .put("jarvis_backup", 1)
            .put("data", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ITALIAN).format(Date()))
            .put("impostazioni", impostazioni)
            .put("file", file)
            .toString()
    }

    /** Ripristina un backup. Restituisce cosa dire all'utente. */
    fun importa(ctx: Context, testo: String): String {
        val radice = try { JSONObject(testo) } catch (e: Exception) { return "Il file scelto non è un backup di ${Nome.attuale}." }
        if (!radice.has("jarvis_backup")) return "Il file scelto non è un backup di ${Nome.attuale}."
        val modifica = prefs(ctx).edit()
        val impostazioni = radice.optJSONObject("impostazioni") ?: JSONObject()
        var quante = 0
        for (chiave in impostazioni.keys()) {
            if (chiave in ESCLUSE) continue
            val voce = impostazioni.optJSONObject(chiave) ?: continue
            when (voce.optString("t")) {
                "b" -> modifica.putBoolean(chiave, voce.optBoolean("v"))
                "i" -> modifica.putInt(chiave, voce.optInt("v"))
                "l" -> modifica.putLong(chiave, voce.optLong("v"))
                "f" -> modifica.putFloat(chiave, voce.optDouble("v").toFloat())
                "set" -> {
                    val a = voce.optJSONArray("v") ?: JSONArray()
                    modifica.putStringSet(chiave, (0 until a.length()).map { a.optString(it) }.toSet())
                }
                else -> modifica.putString(chiave, voce.optString("v"))
            }
            quante++
        }
        modifica.commit()
        val file = radice.optJSONObject("file") ?: JSONObject()
        for (nome in FILE) {
            if (file.has(nome)) File(ctx.filesDir, nome).writeText(file.optString(nome))
        }
        Cervello.prendi(ctx).ricarica()
        try { Promemoria.riprogrammaTutto(ctx) } catch (e: Exception) { }
        return "Backup del ${radice.optString("data")} ripristinato: $quante elementi. Le chiavi API e le password vanno reinserite."
    }

    /** Backup automatico una volta alla settimana nella cartella scelta dall'utente. */
    fun automatico(ctx: Context) {
        val p = prefs(ctx)
        val cartella = p.getString("backup_cartella", "") ?: ""
        if (cartella.isEmpty()) return
        if (System.currentTimeMillis() - p.getLong("backup_ultimo", 0L) < 7 * 24 * 3600_000L) return
        salvaInCartella(ctx, Uri.parse(cartella))
    }

    fun salvaInCartella(ctx: Context, albero: Uri): String = try {
        val documento = DocumentsContract.buildDocumentUriUsingTree(albero, DocumentsContract.getTreeDocumentId(albero))
        val nuovo = DocumentsContract.createDocument(ctx.contentResolver, documento, "application/json", nomeFile())
            ?: throw Exception("cartella non scrivibile")
        ctx.contentResolver.openOutputStream(nuovo)?.use { it.write(esporta(ctx).toByteArray(Charsets.UTF_8)) }
        prefs(ctx).edit().putLong("backup_ultimo", System.currentTimeMillis()).apply()
        "Backup salvato nella cartella scelta."
    } catch (e: Exception) {
        "Non sono riuscito a salvare il backup: ${e.message}"
    }
}
