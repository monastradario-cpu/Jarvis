package com.example.jarvis

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Automazioni "se succede questo, fai quello", create a voce:
 * "quando arrivo a casa accendi le luci e leggimi i messaggi",
 * "ogni sera alle 23 metti il non disturbare", "quando la batteria scende al 15% abbassa la luminosità".
 * Le istruzioni sono in italiano: quando scatta, il cervello di Jarvis le esegue con i suoi strumenti.
 */
object Automazioni {

    const val AZIONE_ORA = "com.example.jarvis.AUTOMAZIONE_ORA"
    const val AZIONE_LUOGO = "com.example.jarvis.AUTOMAZIONE_LUOGO"
    private const val BASE_ID = 600_000

    /** Tipi di evento che fanno partire un'automazione. */
    val TIPI = mapOf(
        "orario" to "a un orario",
        "arrivo" to "quando arrivi in un luogo",
        "uscita" to "quando esci da un luogo",
        "batteria" to "quando la batteria scende sotto una soglia",
        "auto" to "quando ti colleghi all'auto",
        "fine_auto" to "quando scendi dall'auto",
        "cuffie" to "quando colleghi le cuffie",
        "carica" to "quando metti in carica",
        "fine_carica" to "quando stacchi il caricatore"
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun elenco(ctx: Context): JSONArray = try {
        JSONArray(prefs(ctx).getString("automazioni", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    private fun salva(ctx: Context, lista: JSONArray) = prefs(ctx).edit().putString("automazioni", lista.toString()).apply()

    private fun nuovoId(ctx: Context): Int {
        val id = prefs(ctx).getInt("automazioni_contatore", 1)
        prefs(ctx).edit().putInt("automazioni_contatore", id + 1).apply()
        return id
    }

    fun normalizzaTipo(t: String): String? {
        val x = t.lowercase(Locale.ITALIAN).trim()
        return when {
            x in TIPI -> x
            x.contains("ora") || x.contains("ogni") || x.contains("sera") || x.contains("mattin") -> "orario"
            x.contains("arriv") || x.contains("entr") -> "arrivo"
            x.contains("usc") || x.contains("esc") || x.contains("lasc") -> "uscita"
            x.contains("batter") -> "batteria"
            x.contains("scend") && x.contains("auto") -> "fine_auto"
            x.contains("auto") || x.contains("macchin") -> "auto"
            x.contains("cuffi") || x.contains("aurical") -> "cuffie"
            x.contains("stacc") -> "fine_carica"
            x.contains("caric") -> "carica"
            else -> null
        }
    }

    /**
     * @param valore orario HH:MM, oppure soglia di batteria in percentuale
     * @param giorni per gli orari: tutti, feriali, weekend, oppure giorni separati da virgola (lun, mar...)
     */
    fun crea(
        ctx: Context, nome: String, tipo: String, valore: String, giorni: String, istruzioni: String,
        luogo: Triple<String, Double, Double>?
    ): String {
        if (istruzioni.isBlank()) return "Dimmi cosa devo fare quando scatta l'automazione."
        val a = JSONObject()
            .put("id", nuovoId(ctx))
            .put("nome", nome.ifBlank { istruzioni.take(30) })
            .put("tipo", tipo)
            .put("istruzioni", istruzioni.trim())
            .put("attiva", true)
        when (tipo) {
            "orario" -> {
                val m = Regex("(\\d{1,2})(?:[:.](\\d{2}))?").find(valore) ?: return "Non ho capito l'orario \"$valore\"."
                val h = m.groupValues[1].toInt()
                val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                if (h !in 0..23 || min !in 0..59) return "Orario non valido."
                a.put("ora", h).put("minuti", min).put("giorni", normalizzaGiorni(giorni))
            }
            "arrivo", "uscita" -> {
                val (n, lat, lon) = luogo ?: return "Non conosco quel luogo: salvalo prima, per esempio dicendo \"salva questo posto come casa\"."
                a.put("luogo", n).put("lat", lat).put("lon", lon)
            }
            "batteria" -> {
                val soglia = Regex("\\d{1,2}").find(valore)?.value?.toIntOrNull() ?: 20
                a.put("soglia", soglia.coerceIn(5, 95))
            }
        }
        val lista = elenco(ctx)
        lista.put(a)
        salva(ctx, lista)
        programma(ctx, a)
        return "Automazione creata: ${descrivi(a)}."
    }

    private fun normalizzaGiorni(g: String): String {
        val x = g.lowercase(Locale.ITALIAN)
        return when {
            x.isBlank() || x.contains("tutti") || x.contains("ogni giorno") -> "tutti"
            x.contains("ferial") || x.contains("lavorativ") -> "feriali"
            x.contains("weekend") || x.contains("fine settimana") || x.contains("festiv") -> "weekend"
            else -> x
        }
    }

    private val NOMI_GIORNI = mapOf(
        "lun" to Calendar.MONDAY, "mar" to Calendar.TUESDAY, "mer" to Calendar.WEDNESDAY, "gio" to Calendar.THURSDAY,
        "ven" to Calendar.FRIDAY, "sab" to Calendar.SATURDAY, "dom" to Calendar.SUNDAY
    )

    private fun giornoValido(giorni: String, c: Calendar): Boolean {
        val g = c.get(Calendar.DAY_OF_WEEK)
        val weekend = g == Calendar.SATURDAY || g == Calendar.SUNDAY
        return when (giorni) {
            "tutti" -> true
            "feriali" -> !weekend
            "weekend" -> weekend
            else -> NOMI_GIORNI.filterKeys { giorni.contains(it) }.values.let { it.isEmpty() || g in it }
        }
    }

    fun descrivi(a: JSONObject): String {
        val quando = when (a.optString("tipo")) {
            "orario" -> "alle %02d:%02d".format(a.optInt("ora"), a.optInt("minuti")) +
                when (val g = a.optString("giorni", "tutti")) { "tutti" -> " ogni giorno"; "feriali" -> " nei giorni feriali"; "weekend" -> " nel weekend"; else -> " ($g)" }
            "arrivo" -> "quando arrivi a ${a.optString("luogo")}"
            "uscita" -> "quando esci da ${a.optString("luogo")}"
            "batteria" -> "quando la batteria scende sotto il ${a.optInt("soglia")}%"
            else -> TIPI[a.optString("tipo")] ?: a.optString("tipo")
        }
        return "\"${a.optString("nome")}\": $quando, ${a.optString("istruzioni")}"
    }

    fun descriviTutte(ctx: Context): String {
        val l = elenco(ctx)
        if (l.length() == 0) return "Non ci sono automazioni. Prova: \"quando arrivo a casa accendi le luci\"."
        return (0 until l.length()).joinToString("\n") { "• " + descrivi(l.getJSONObject(it)) }
    }

    fun elimina(ctx: Context, parola: String): String {
        val p = parola.lowercase(Locale.ITALIAN).trim()
        val l = elenco(ctx)
        val nuova = JSONArray()
        var tolte = 0
        for (i in 0 until l.length()) {
            val a = l.getJSONObject(i)
            if (p == "tutte" || p == "tutto" || a.toString().lowercase(Locale.ITALIAN).contains(p)) {
                tolte++
                annulla(ctx, a)
            } else nuova.put(a)
        }
        salva(ctx, nuova)
        return if (tolte > 0) "Ho eliminato $tolte automazioni." else "Non trovo automazioni su \"$parola\"."
    }

    // ================================================================
    //  PROGRAMMAZIONE
    // ================================================================

    private fun intentoOra(ctx: Context, id: Int) = PendingIntent.getBroadcast(
        ctx, BASE_ID + id,
        Intent(ctx, Promemoria.RicevitorePromemoria::class.java).setAction(AZIONE_ORA).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun intentoLuoghi(ctx: Context): PendingIntent {
        val flag = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, BASE_ID, Intent(ctx, Promemoria.RicevitorePromemoria::class.java).setAction(AZIONE_LUOGO), flag)
    }

    private fun prossimo(a: JSONObject): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, a.optInt("ora"))
            set(Calendar.MINUTE, a.optInt("minuti"))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val giorni = a.optString("giorni", "tutti")
        var tentativi = 0
        while ((c.timeInMillis <= System.currentTimeMillis() || !giornoValido(giorni, c)) && tentativi < 10) {
            c.add(Calendar.DAY_OF_MONTH, 1)
            tentativi++
        }
        return c.timeInMillis
    }

    @SuppressLint("MissingPermission")
    private fun programma(ctx: Context, a: JSONObject) {
        when (a.optString("tipo")) {
            "orario" -> {
                val allarmi = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val quando = prossimo(a)
                val intento = intentoOra(ctx, a.optInt("id"))
                val esatto = Build.VERSION.SDK_INT < 31 || allarmi.canScheduleExactAlarms()
                try {
                    if (esatto) allarmi.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, intento)
                    else allarmi.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, intento)
                } catch (e: SecurityException) {
                    allarmi.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, intento)
                }
            }
            "arrivo", "uscita" -> {
                if (Promemoria.permessiPosizioneMancanti(ctx) != null) return
                val recinto = Geofence.Builder()
                    .setRequestId("a" + a.optInt("id"))
                    .setCircularRegion(a.optDouble("lat"), a.optDouble("lon"), 150f)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(if (a.optString("tipo") == "uscita") Geofence.GEOFENCE_TRANSITION_EXIT else Geofence.GEOFENCE_TRANSITION_ENTER)
                    .build()
                try {
                    LocationServices.getGeofencingClient(ctx)
                        .addGeofences(GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(recinto).build(), intentoLuoghi(ctx))
                } catch (e: Exception) { }
            }
        }
    }

    private fun annulla(ctx: Context, a: JSONObject) {
        when (a.optString("tipo")) {
            "orario" -> (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(intentoOra(ctx, a.optInt("id")))
            "arrivo", "uscita" -> try {
                LocationServices.getGeofencingClient(ctx).removeGeofences(listOf("a" + a.optInt("id")))
            } catch (e: Exception) { }
        }
    }

    fun programmaTutte(ctx: Context) {
        val l = elenco(ctx)
        for (i in 0 until l.length()) programma(ctx, l.getJSONObject(i))
    }

    // ================================================================
    //  EVENTI
    // ================================================================

    fun scattaOra(ctx: Context, id: Int) {
        val a = trova(ctx, id) ?: return
        programma(ctx, a)               // la prossima volta
        esegui(ctx, a)
    }

    fun scattaLuogo(ctx: Context, intento: Intent) {
        val evento = GeofencingEvent.fromIntent(intento) ?: return
        if (evento.hasError()) return
        val ids = evento.triggeringGeofences?.mapNotNull { it.requestId.removePrefix("a").toIntOrNull() } ?: return
        for (id in ids) trova(ctx, id)?.let { esegui(ctx, it) }
    }

    /** auto, fine_auto, cuffie, carica, fine_carica */
    fun evento(ctx: Context, tipo: String) {
        val l = elenco(ctx)
        for (i in 0 until l.length()) {
            val a = l.getJSONObject(i)
            if (a.optString("tipo") == tipo) esegui(ctx, a)
        }
    }

    /** Controllo della batteria: scatta una volta quando scende sotto la soglia, si riarma sopra. */
    fun batteria(ctx: Context, livello: Int, inCarica: Boolean) {
        if (livello < 0) return
        val l = elenco(ctx)
        var cambiato = false
        for (i in 0 until l.length()) {
            val a = l.getJSONObject(i)
            if (a.optString("tipo") != "batteria") continue
            val soglia = a.optInt("soglia", 20)
            val scattata = a.optBoolean("scattata", false)
            if (!scattata && !inCarica && livello <= soglia) {
                a.put("scattata", true)
                cambiato = true
                esegui(ctx, a)
            } else if (scattata && (inCarica || livello > soglia + 5)) {
                a.put("scattata", false)
                cambiato = true
            }
        }
        if (cambiato) salva(ctx, l)
    }

    private fun trova(ctx: Context, id: Int): JSONObject? {
        val l = elenco(ctx)
        for (i in 0 until l.length()) if (l.getJSONObject(i).optInt("id") == id) return l.getJSONObject(i)
        return null
    }

    private fun esegui(ctx: Context, a: JSONObject) {
        if (!a.optBoolean("attiva", true)) return
        val app = ctx.applicationContext
        Thread {
            val cervello = Cervello.prendi(app)
            cervello.chiParla = ""
            val esito = if (cervello.chiaveAttiva().isEmpty()) {
                // senza chiave provo almeno i comandi istantanei
                cervello.rispondi(a.optString("istruzioni")).testo
            } else {
                cervello.rispondi(
                    "[Automazione \"${a.optString("nome")}\" partita da sola: l'utente non sta parlando con te. " +
                        "Esegui queste istruzioni con gli strumenti, senza fare domande, poi riassumi in una frase cosa hai fatto.] " +
                        a.optString("istruzioni")
                ).testo
            }
            Proattivo.avvisa(app, "auto_${a.optInt("id")}_${System.currentTimeMillis() / 60_000}", "Automazione ${a.optString("nome")}: $esito")
        }.start()
    }
}
