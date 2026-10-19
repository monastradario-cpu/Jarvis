package com.example.jarvis

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Statistiche d'uso, tutte sul telefono: richieste al giorno, comandi istantanei,
 * strumenti più usati, tempi di risposta, tempo di ascolto e batteria.
 */
object Statistiche {

    private val formatoGiorno = SimpleDateFormat("yyyy-MM-dd", Locale.ITALIAN)
    private val blocco = Any()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private fun leggi(ctx: Context): JSONObject = try {
        JSONObject(prefs(ctx).getString("statistiche", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun salva(ctx: Context, dati: JSONObject) {
        // tiene solo gli ultimi 35 giorni
        val giorni = dati.optJSONObject("g")
        if (giorni != null && giorni.length() > 35) {
            val chiavi = giorni.keys().asSequence().toList().sorted()
            for (k in chiavi.take(chiavi.size - 35)) giorni.remove(k)
        }
        prefs(ctx).edit().putString("statistiche", dati.toString()).apply()
    }

    private fun oggi(dati: JSONObject): JSONObject {
        val giorni = dati.optJSONObject("g") ?: JSONObject().also { dati.put("g", it) }
        val chiave = formatoGiorno.format(Date())
        return giorni.optJSONObject(chiave) ?: JSONObject().also { giorni.put(chiave, it) }
    }

    private fun aggiungi(o: JSONObject, chiave: String, quanto: Long) = o.put(chiave, o.optLong(chiave, 0) + quanto)

    /** @param tipo rapida, ia, offline, errore oppure vista */
    fun richiesta(ctx: Context, tipo: String, millisecondi: Long, strumenti: List<String>) = synchronized(blocco) {
        val dati = leggi(ctx)
        if (!dati.has("inizio")) dati.put("inizio", formatoGiorno.format(Date()))
        val g = oggi(dati)
        aggiungi(g, "richieste", 1)
        aggiungi(g, tipo, 1)
        if (tipo == "ia" || tipo == "vista") aggiungi(g, "ms_ia", millisecondi)
        if (tipo == "rapida") aggiungi(g, "ms_rapida", millisecondi)
        val s = dati.optJSONObject("strumenti") ?: JSONObject().also { dati.put("strumenti", it) }
        for (nome in strumenti.distinct()) aggiungi(s, nome, 1)
        salva(ctx, dati)
        // punti e trofei; una vibrazione leggera quando un comando è stato eseguito davvero
        try { Trofei.richiesta(ctx, tipo, strumenti.distinct()) } catch (e: Throwable) { }
        if (strumenti.isNotEmpty()) try { Suoni.esegue(ctx) } catch (e: Throwable) { }
    }

    /** Dal servizio sempre attivo: secondi di ascolto, secondi di analisi dell'audio, punti di batteria persi. */
    fun ascolto(ctx: Context, secondiAscolto: Long, secondiAnalisi: Long, batteriaPersa: Int) = synchronized(blocco) {
        if (secondiAscolto <= 0 && secondiAnalisi <= 0 && batteriaPersa <= 0) return@synchronized
        val dati = leggi(ctx)
        val g = oggi(dati)
        aggiungi(g, "ascolto_s", secondiAscolto)
        aggiungi(g, "analisi_s", secondiAnalisi)
        aggiungi(g, "batteria", batteriaPersa.toLong())
        salva(ctx, dati)
    }

    fun attivazione(ctx: Context) = synchronized(blocco) {
        val dati = leggi(ctx)
        aggiungi(oggi(dati), "attivazioni", 1)
        salva(ctx, dati)
    }

    private val NOMI_STRUMENTI = mapOf(
        "apri_app" to "aprire app", "riproduci" to "musica", "meteo" to "meteo", "notizie" to "notizie",
        "chiama" to "chiamate", "invia_whatsapp" to "WhatsApp", "leggi_notifiche" to "leggere messaggi",
        "promemoria" to "promemoria", "leggi_calendario" to "calendario", "crea_appuntamento" to "nuovi appuntamenti",
        "imposta_sveglia" to "sveglie", "imposta_timer" to "timer", "torcia" to "torcia", "regola_volume" to "volume",
        "cerca_informazioni" to "informazioni", "naviga" to "navigazione", "tocca" to "controllo app",
        "leggi_schermo" to "lettura schermo", "ricorda" to "memoria", "guarda" to "foto", "vista_dal_vivo" to "vista dal vivo",
        "casa_comando" to "casa", "leggi_email" to "email", "invia_email" to "email inviate", "pc" to "PC"
    )

    private fun durata(secondi: Long): String = when {
        secondi >= 3600 -> "${secondi / 3600} h ${(secondi % 3600) / 60} min"
        secondi >= 60 -> "${secondi / 60} min"
        else -> "$secondi s"
    }

    /** Il testo della pagina STATISTICHE. */
    fun descrivi(ctx: Context): String {
        val dati = leggi(ctx)
        val giorni = dati.optJSONObject("g") ?: JSONObject()
        val oggiChiave = formatoGiorno.format(Date())
        val g = giorni.optJSONObject(oggiChiave) ?: JSONObject()
        val t = StringBuilder()

        t.append("OGGI\n")
        t.append("Richieste: ${g.optLong("richieste")}  (istantanee ${g.optLong("rapida")}, con l'IA ${g.optLong("ia")}")
        if (g.optLong("offline") > 0) t.append(", offline ${g.optLong("offline")}")
        if (g.optLong("errore") > 0) t.append(", errori ${g.optLong("errore")}")
        t.append(")\n")
        val risparmiateOggi = g.optLong("rapida") + g.optLong("offline")
        if (g.optLong("richieste") > 0) t.append("Richieste all'IA evitate oggi: $risparmiateOggi su ${g.optLong("richieste")}\n")
        if (g.optLong("attivazioni") > 0) t.append("Attivazioni con \"Ehi ${Nome.attuale}\": ${g.optLong("attivazioni")}\n")
        if (g.optLong("ia") > 0) t.append("Tempo medio di risposta dell'IA: ${"%.1f".format(Locale.ITALIAN, g.optLong("ms_ia") / 1000.0 / g.optLong("ia"))} s\n")
        if (g.optLong("rapida") > 0) t.append("Comandi istantanei: ${g.optLong("ms_rapida") / g.optLong("rapida").coerceAtLeast(1)} ms in media\n")
        if (g.optLong("ascolto_s") > 0) {
            t.append("In ascolto: ${durata(g.optLong("ascolto_s"))}, di cui audio analizzato ${durata(g.optLong("analisi_s"))}\n")
        }
        if (g.optLong("batteria") > 0) t.append("Batteria del telefono scesa di ${g.optLong("batteria")}% mentre ${Nome.attuale} ascoltava\n")

        t.append("\nULTIMI 7 GIORNI\n")
        val cal = Calendar.getInstance()
        val righe = mutableListOf<Pair<String, Long>>()
        repeat(7) {
            val chiave = formatoGiorno.format(cal.time)
            val nome = SimpleDateFormat("EEE d", Locale.ITALIAN).format(cal.time)
            righe.add(nome to (giorni.optJSONObject(chiave)?.optLong("richieste") ?: 0L))
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        val massimo = righe.maxOf { it.second }.coerceAtLeast(1)
        for ((nome, n) in righe.reversed()) {
            val barra = "█".repeat(((n * 16) / massimo).toInt()).ifEmpty { if (n > 0) "▏" else "" }
            t.append("${nome.padEnd(7)} $barra $n\n")
        }
        var totale = 0L
        var rapide = 0L
        var risparmiate = 0L
        for (k in giorni.keys()) {
            val x = giorni.optJSONObject(k) ?: continue
            totale += x.optLong("richieste")
            rapide += x.optLong("rapida")
            risparmiate += x.optLong("rapida") + x.optLong("offline")
        }
        t.append("\nIN TOTALE\n")
        var notturne = 0L
        var ia = 0L
        for (k in giorni.keys()) giorni.optJSONObject(k)?.let { notturne += it.optLong("notturna"); ia += it.optLong("ia") }
        t.append("$totale richieste dal ${dati.optString("inizio", oggiChiave)}; $rapide fatte all'istante senza usare l'IA.\n")
        t.append("Richieste all'IA: $ia, più $notturne elaborazioni notturne (riassunto, apprendimento, archivio).\n")

        val strumenti = dati.optJSONObject("strumenti")
        if (strumenti != null && strumenti.length() > 0) {
            t.append("\nCOSA USI DI PIÙ\n")
            val ordinati = strumenti.keys().asSequence().map { it to strumenti.optLong(it) }.sortedByDescending { it.second }.take(8)
            for ((nome, n) in ordinati) t.append("• ${NOMI_STRUMENTI[nome] ?: nome.replace('_', ' ')}: $n\n")
        }
        return t.toString()
    }

    fun azzera(ctx: Context) = prefs(ctx).edit().remove("statistiche").apply()
}
