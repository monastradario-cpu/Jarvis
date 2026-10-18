package com.example.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Liste a voce: la spesa, le cose da fare, i regali... Si possono mandare su WhatsApp. */
object Liste {

    private val IT = Locale.ITALIAN

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private fun tutte(ctx: Context): JSONObject = try {
        JSONObject(prefs(ctx).getString("liste", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun salva(ctx: Context, o: JSONObject) = prefs(ctx).edit().putString("liste", o.toString()).apply()

    /** "la lista della spesa" → "spesa" */
    fun nome(n: String): String = n.lowercase(IT).trim()
        .replace(Regex("^(la |le |nella |alla |dalla |della )"), "")
        .replace(Regex("^(lista|liste)( (della|delle|dei|degli|di|del|da))? ?"), "")
        .trim().ifEmpty { "spesa" }

    private fun elementi(ctx: Context, lista: String): MutableList<String> {
        val a = tutte(ctx).optJSONArray(nome(lista)) ?: JSONArray()
        return MutableList(a.length()) { a.optString(it) }
    }

    private fun scrivi(ctx: Context, lista: String, voci: List<String>) {
        val o = tutte(ctx)
        if (voci.isEmpty()) o.remove(nome(lista)) else o.put(nome(lista), JSONArray(voci))
        salva(ctx, o)
    }

    fun aggiungi(ctx: Context, lista: String, cosa: String): String {
        val nuove = cosa.split(Regex(",| e (?!mezzo)|;")).map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
        if (nuove.isEmpty()) return "Dimmi cosa aggiungere."
        val voci = elementi(ctx, lista)
        val aggiunte = nuove.filter { n -> voci.none { it.equals(n, ignoreCase = true) } }
        voci.addAll(aggiunte)
        scrivi(ctx, lista, voci)
        return if (aggiunte.isEmpty()) "C'era già nella lista ${nome(lista)}."
        else "Aggiunto alla lista ${nome(lista)}: ${aggiunte.joinToString(", ")}."
    }

    fun togli(ctx: Context, lista: String, cosa: String): String {
        val c = cosa.lowercase(IT).trim()
        if (c.isEmpty() || c == "tutto" || c == "tutti") {
            scrivi(ctx, lista, emptyList())
            return "Lista ${nome(lista)} svuotata."
        }
        val voci = elementi(ctx, lista)
        val prima = voci.size
        voci.removeAll { it.lowercase(IT).contains(c) || c.contains(it.lowercase(IT)) }
        scrivi(ctx, lista, voci)
        return if (voci.size < prima) "Tolto dalla lista ${nome(lista)}." else "Nella lista ${nome(lista)} non c'è $cosa."
    }

    fun leggi(ctx: Context, lista: String): String {
        if (lista.isBlank()) {
            val o = tutte(ctx)
            if (o.length() == 0) return "Non ci sono liste."
            return o.keys().asSequence().joinToString("; ") { k -> "$k: " + (0 until o.optJSONArray(k)!!.length()).joinToString(", ") { o.optJSONArray(k)!!.optString(it) } }
        }
        val voci = elementi(ctx, lista)
        return if (voci.isEmpty()) "La lista ${nome(lista)} è vuota." else "Lista ${nome(lista)}: ${voci.joinToString(", ")}."
    }

    /** Il testo da mandare a qualcuno. */
    fun testo(ctx: Context, lista: String): String? {
        val voci = elementi(ctx, lista)
        if (voci.isEmpty()) return null
        return "Lista ${nome(lista)}:\n" + voci.joinToString("\n") { "• $it" }
    }

    fun descriviTutte(ctx: Context): String {
        val o = tutte(ctx)
        if (o.length() == 0) return "Nessuna. Prova: \"aggiungi il latte alla lista della spesa\"."
        return o.keys().asSequence().joinToString("\n") { k ->
            val a = o.optJSONArray(k) ?: JSONArray()
            "• $k: " + (0 until a.length()).joinToString(", ") { a.optString(it) }
        }
    }
}
