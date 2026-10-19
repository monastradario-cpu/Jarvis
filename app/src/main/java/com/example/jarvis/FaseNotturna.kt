package com.example.jarvis

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Fase notturna: invece di chiedere a Gemini piccole elaborazioni durante il giorno,
 * Jarvis le raccoglie e le fa tutte insieme una volta per notte (meglio se in carica e in Wi-Fi):
 * riassunto delle conversazioni, cose imparate, routine e riassunti dei documenti caricati.
 */
object FaseNotturna {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private fun oggi() = SimpleDateFormat("yyyy-MM-dd", Locale.ITALIAN).format(Date())

    private fun inCarica(ctx: Context): Boolean = try {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    } catch (e: Exception) {
        false
    }

    /** È il momento giusto? Tra l'1 e le 6, oppure dalle 23 se il telefono è in carica. Una volta al giorno. */
    fun dovuta(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean("risparmio_richieste", true)) return false
        if (p.getString("notturna_ultima", "") == oggi()) return false
        val ora = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return ora in 1..5 || (ora >= 23 && inCarica(ctx))
    }

    /** Da chiamare in un thread in background (lo fa il controllo periodico). */
    fun esegui(ctx: Context, forzata: Boolean = false): String {
        if (!forzata && !dovuta(ctx)) return ""
        val cervello = Cervello.prendi(ctx)
        if (cervello.chiaveAttiva().isEmpty() || !Orecchie.online(ctx) || cervello.privata) return "Non posso elaborare adesso."
        prefs(ctx).edit().putString("notturna_ultima", oggi()).apply()
        val esiti = mutableListOf<String>()
        var richieste = 0
        try {
            esiti.add(cervello.memoria.riflettiNotte())
            richieste++
        } catch (e: Exception) {
            esiti.add("Riflessione non riuscita: ${e.message}")
        }
        // documenti caricati di giorno: riassunto vero adesso
        for (id in Archivio.inAttesa(ctx).take(8)) {
            try {
                val voce = Archivio.perId(ctx, id) ?: continue
                val doc = Archivio.carica(ctx, voce) ?: continue
                Archivio.aggiornaRiassunto(ctx, id, cervello.riassuntoPerArchivio(doc))
                richieste++
            } catch (e: Exception) { }
        }
        if (richieste > 0) esiti.add("Archivio: ${Archivio.inAttesa(ctx).size} documenti ancora in attesa.")
        Statistiche.richiesta(ctx, "notturna", 0, emptyList())
        val testo = esiti.joinToString(" ")
        prefs(ctx).edit().putString("notturna_esito", SimpleDateFormat("d MMM HH:mm", Locale.ITALIAN).format(Date()) + ": " + testo).apply()
        return testo
    }
}
