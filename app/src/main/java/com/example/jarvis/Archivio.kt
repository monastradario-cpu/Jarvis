package com.example.jarvis

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Archivio dei documenti caricati: Jarvis li conserva sul telefono con un breve riassunto,
 * così più avanti puoi chiedere "cosa diceva il contratto d'affitto?" o "quando scade la garanzia?".
 */
object Archivio {

    private val IT = Locale.ITALIAN

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private fun cartella(ctx: Context) = File(ctx.filesDir, "archivio").apply { mkdirs() }

    fun elenco(ctx: Context): JSONArray = try {
        JSONArray(prefs(ctx).getString("archivio", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    private fun salvaElenco(ctx: Context, a: JSONArray) = prefs(ctx).edit().putString("archivio", a.toString()).apply()

    fun numero(ctx: Context) = elenco(ctx).length()

    /** Salva un documento con il suo riassunto. Restituisce l'id. */
    fun salva(ctx: Context, c: Cervello.Contenuto, riassunto: String): Int {
        val elenco = elenco(ctx)
        // stesso nome: lo sostituisco
        val nuovo = JSONArray()
        for (i in 0 until elenco.length()) {
            val v = elenco.getJSONObject(i)
            if (v.optString("nome") == c.titolo) File(cartella(ctx), v.optString("file")).delete() else nuovo.put(v)
        }
        val id = prefs(ctx).getInt("archivio_contatore", 1)
        prefs(ctx).edit().putInt("archivio_contatore", id + 1).apply()
        val file = "doc_$id"
        val dati = if (c.base64 != null) Base64.decode(c.base64, Base64.NO_WRAP) else c.testo.toByteArray(Charsets.UTF_8)
        File(cartella(ctx), file).writeBytes(dati)
        nuovo.put(
            JSONObject()
                .put("id", id)
                .put("nome", c.titolo)
                .put("tipo", c.tipo)
                .put("mime", c.mime)
                .put("testuale", c.base64 == null)
                .put("file", file)
                .put("data", SimpleDateFormat("yyyy-MM-dd", IT).format(Date()))
                .put("riassunto", riassunto.take(1500))
        )
        salvaElenco(ctx, nuovo)
        return id
    }

    fun carica(ctx: Context, v: JSONObject): Cervello.Contenuto? {
        val f = File(cartella(ctx), v.optString("file"))
        if (!f.exists()) return null
        val dati = f.readBytes()
        return if (v.optBoolean("testuale")) {
            Cervello.Contenuto("testo", v.optString("nome"), String(dati, Charsets.UTF_8), null, "text/plain")
        } else {
            Cervello.Contenuto(v.optString("tipo"), v.optString("nome"), "", Base64.encodeToString(dati, Base64.NO_WRAP), v.optString("mime"))
        }
    }

    /** Il documento il cui nome o riassunto assomiglia di più a quello cercato. */
    fun trova(ctx: Context, cercato: String): JSONObject? {
        val c = cercato.lowercase(IT).trim()
        val elenco = elenco(ctx)
        val voci = (0 until elenco.length()).map { elenco.getJSONObject(it) }
        voci.firstOrNull { it.optString("nome").lowercase(IT) == c }?.let { return it }
        val parole = c.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }
        return voci.map { v ->
            val dove = (v.optString("nome") + " " + v.optString("riassunto")).lowercase(IT)
            v to parole.count { dove.contains(it.take(5)) }
        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    fun cerca(ctx: Context, testo: String): String {
        val elenco = elenco(ctx)
        if (elenco.length() == 0) return "L'archivio è vuoto: l'utente può caricare documenti con il tasto + accanto al campo di testo."
        val parole = testo.lowercase(IT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }
        val trovati = (0 until elenco.length()).map { elenco.getJSONObject(it) }.filter { v ->
            val dove = (v.optString("nome") + " " + v.optString("riassunto")).lowercase(IT)
            parole.isEmpty() || parole.any { dove.contains(it.take(5)) }
        }.take(8)
        if (trovati.isEmpty()) return "Nessun documento trovato per \"$testo\"."
        return trovati.joinToString("\n") { "${it.optString("nome")} (${it.optString("data")}): ${it.optString("riassunto")}" }
    }

    fun elimina(ctx: Context, nome: String): String {
        val v = trova(ctx, nome) ?: return "Non trovo il documento \"$nome\"."
        val elenco = elenco(ctx)
        val nuovo = JSONArray()
        for (i in 0 until elenco.length()) {
            val x = elenco.getJSONObject(i)
            if (x.optInt("id") == v.optInt("id")) File(cartella(ctx), x.optString("file")).delete() else nuovo.put(x)
        }
        salvaElenco(ctx, nuovo)
        return "Ho tolto ${v.optString("nome")} dall'archivio."
    }

    fun descrivi(ctx: Context): String {
        val elenco = elenco(ctx)
        if (elenco.length() == 0) return "Vuoto. Carica documenti e foto con il tasto + accanto al campo di testo."
        return (0 until elenco.length()).joinToString("\n") {
            val v = elenco.getJSONObject(it)
            "• ${v.optString("nome")} (${v.optString("data")}): ${v.optString("riassunto").take(120)}"
        }
    }
}
