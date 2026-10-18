package com.example.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Promemoria intelligenti di Jarvis:
 * - a un'ora precisa, anche ripetuti (ogni giorno, nei feriali, ogni settimana, ogni mese)
 * - quando arrivi o esci da un luogo (casa, lavoro...)
 * - il riepilogo del mattino a un orario fisso
 * Restano attivi anche dopo un riavvio del telefono.
 */
object Promemoria {

    const val AZIONE_PROMEMORIA = "com.example.jarvis.PROMEMORIA"
    const val AZIONE_RIEPILOGO = "com.example.jarvis.RIEPILOGO_MATTINO"
    const val AZIONE_LUOGO = "com.example.jarvis.LUOGO"
    private const val CANALE = "jarvis_promemoria"
    private const val ID_RIEPILOGO = 900_000

    private val italiano = Locale.ITALIAN

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private fun elenco(ctx: Context): JSONArray = try {
        JSONArray(prefs(ctx).getString("promemoria", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    private fun salva(ctx: Context, lista: JSONArray) {
        prefs(ctx).edit().putString("promemoria", lista.toString()).apply()
        try { WidgetJarvis.aggiorna(ctx) } catch (e: Exception) { }
    }

    private fun nuovoId(ctx: Context): Int {
        val id = prefs(ctx).getInt("promemoria_contatore", 1)
        prefs(ctx).edit().putInt("promemoria_contatore", id + 1).apply()
        return id
    }

    // ================================================================
    //  PROMEMORIA A ORARIO
    // ================================================================

    fun aggiungiOrario(ctx: Context, testo: String, quando: Calendar, ripeti: String): String {
        if (testo.isBlank()) return "Dimmi cosa devo ricordarti."
        val r = normalizzaRipetizione(ripeti)
        val adesso = System.currentTimeMillis()
        while (quando.timeInMillis <= adesso) {
            if (r == "no") return "Quell'orario è già passato."
            avanza(quando, r)
        }
        val voce = JSONObject()
            .put("id", nuovoId(ctx))
            .put("tipo", "ora")
            .put("testo", testo.trim())
            .put("quando", quando.timeInMillis)
            .put("ripeti", r)
        val lista = elenco(ctx)
        lista.put(voce)
        salva(ctx, lista)
        programmaOrario(ctx, voce)
        val formato = SimpleDateFormat("EEEE d MMMM 'alle' HH:mm", italiano)
        val ripetizione = when (r) {
            "giorno" -> ", ogni giorno"
            "feriali" -> ", dal lunedì al venerdì"
            "settimana" -> ", ogni settimana"
            "mese" -> ", ogni mese"
            else -> ""
        }
        return "Promemoria impostato per ${formato.format(Date(quando.timeInMillis))}$ripetizione: ${testo.trim()}."
    }

    private fun normalizzaRipetizione(r: String): String {
        val x = r.lowercase(italiano)
        return when {
            x.contains("ferial") || x.contains("lavorativ") -> "feriali"
            x.contains("giorn") || x.contains("quotidian") -> "giorno"
            x.contains("settiman") -> "settimana"
            x.contains("mes") -> "mese"
            else -> "no"
        }
    }

    private fun avanza(c: Calendar, ripeti: String) {
        when (ripeti) {
            "giorno" -> c.add(Calendar.DAY_OF_MONTH, 1)
            "settimana" -> c.add(Calendar.WEEK_OF_YEAR, 1)
            "mese" -> c.add(Calendar.MONTH, 1)
            "feriali" -> {
                do { c.add(Calendar.DAY_OF_MONTH, 1) } while (
                    c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
                )
            }
            else -> c.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    private fun intentoAllarme(ctx: Context, azione: String, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, RicevitorePromemoria::class.java).setAction(azione).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun impostaAllarme(ctx: Context, quando: Long, intento: PendingIntent) {
        val allarmi = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val esatto = Build.VERSION.SDK_INT < 31 || allarmi.canScheduleExactAlarms()
        try {
            if (esatto) allarmi.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, intento)
            else allarmi.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, intento)
        } catch (e: SecurityException) {
            allarmi.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, intento)
        }
    }

    private fun programmaOrario(ctx: Context, voce: JSONObject) {
        impostaAllarme(ctx, voce.getLong("quando"), intentoAllarme(ctx, AZIONE_PROMEMORIA, voce.getInt("id")))
    }

    /** Il promemoria è scattato: notifica, voce di Jarvis e, se si ripete, prossima volta. */
    fun scatta(ctx: Context, id: Int) {
        val lista = elenco(ctx)
        val nuova = JSONArray()
        var testo: String? = null
        for (i in 0 until lista.length()) {
            val v = lista.getJSONObject(i)
            if (v.optInt("id") == id && v.optString("tipo") == "ora") {
                testo = v.optString("testo")
                val ripeti = v.optString("ripeti", "no")
                if (ripeti != "no") {
                    val prossimo = Calendar.getInstance().apply { timeInMillis = v.getLong("quando") }
                    do { avanza(prossimo, ripeti) } while (prossimo.timeInMillis <= System.currentTimeMillis())
                    v.put("quando", prossimo.timeInMillis)
                    programmaOrario(ctx, v)
                    nuova.put(v)
                }
            } else {
                nuova.put(v)
            }
        }
        salva(ctx, nuova)
        testo?.let { avvisa(ctx, id, "Promemoria", it) }
    }

    // ================================================================
    //  PROMEMORIA DI POSIZIONE (geofence)
    // ================================================================

    private fun haPermesso(ctx: Context, permesso: String) =
        Build.VERSION.SDK_INT < 23 || ctx.checkSelfPermission(permesso) == PackageManager.PERMISSION_GRANTED

    fun permessiPosizioneMancanti(ctx: Context): String? = when {
        !haPermesso(ctx, Manifest.permission.ACCESS_FINE_LOCATION) -> Manifest.permission.ACCESS_FINE_LOCATION
        Build.VERSION.SDK_INT >= 29 && !haPermesso(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        else -> null
    }

    fun aggiungiLuogo(ctx: Context, testo: String, nomeLuogo: String, lat: Double, lon: Double, evento: String): String {
        if (testo.isBlank()) return "Dimmi cosa devo ricordarti."
        val uscita = evento.lowercase(italiano).let { it.contains("usc") || it.contains("lasc") || it.contains("part") }
        val voce = JSONObject()
            .put("id", nuovoId(ctx))
            .put("tipo", "luogo")
            .put("testo", testo.trim())
            .put("luogo", nomeLuogo)
            .put("lat", lat)
            .put("lon", lon)
            .put("evento", if (uscita) "uscita" else "arrivo")
        val lista = elenco(ctx)
        lista.put(voce)
        salva(ctx, lista)
        programmaLuogo(ctx, voce)
        return "Te lo ricorderò quando ${if (uscita) "esci da" else "arrivi a"} $nomeLuogo: ${testo.trim()}."
    }

    private fun intentoLuoghi(ctx: Context): PendingIntent {
        val flag = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(
            ctx, 800_000, Intent(ctx, RicevitorePromemoria::class.java).setAction(AZIONE_LUOGO), flag
        )
    }

    @SuppressLint("MissingPermission")
    private fun programmaLuogo(ctx: Context, voce: JSONObject) {
        if (permessiPosizioneMancanti(ctx) != null) return
        val recinto = Geofence.Builder()
            .setRequestId(voce.getInt("id").toString())
            .setCircularRegion(voce.getDouble("lat"), voce.getDouble("lon"), 150f)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                if (voce.optString("evento") == "uscita") Geofence.GEOFENCE_TRANSITION_EXIT else Geofence.GEOFENCE_TRANSITION_ENTER
            )
            .build()
        val richiesta = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(recinto).build()
        try {
            LocationServices.getGeofencingClient(ctx).addGeofences(richiesta, intentoLuoghi(ctx))
        } catch (e: Exception) {
            // servizi di posizione non disponibili
        }
    }

    /** Arrivo o uscita da un luogo: avvisa per ogni promemoria collegato e lo rimuove. */
    fun luogoRaggiunto(ctx: Context, intento: Intent) {
        val evento = GeofencingEvent.fromIntent(intento) ?: return
        if (evento.hasError()) return
        val ids = evento.triggeringGeofences?.mapNotNull { it.requestId.toIntOrNull() } ?: return
        val lista = elenco(ctx)
        val nuova = JSONArray()
        for (i in 0 until lista.length()) {
            val v = lista.getJSONObject(i)
            if (v.optString("tipo") == "luogo" && v.optInt("id") in ids) {
                avvisa(ctx, v.optInt("id"), "Promemoria: ${v.optString("luogo")}", v.optString("testo"))
                try {
                    LocationServices.getGeofencingClient(ctx).removeGeofences(listOf(v.optInt("id").toString()))
                } catch (e: Exception) {
                    // già rimosso
                }
            } else {
                nuova.put(v)
            }
        }
        salva(ctx, nuova)
    }

    // ================================================================
    //  ELENCO E CANCELLAZIONE
    // ================================================================

    fun descrivi(ctx: Context): String {
        val lista = elenco(ctx)
        if (lista.length() == 0) return "Non ci sono promemoria attivi."
        val formato = SimpleDateFormat("EEE d MMM HH:mm", italiano)
        return (0 until lista.length()).joinToString("\n") { i ->
            val v = lista.getJSONObject(i)
            if (v.optString("tipo") == "luogo") {
                "Quando ${if (v.optString("evento") == "uscita") "esci da" else "arrivi a"} ${v.optString("luogo")}: ${v.optString("testo")}"
            } else {
                val ripeti = v.optString("ripeti", "no").let { if (it == "no") "" else " (ripetuto: $it)" }
                "${formato.format(Date(v.optLong("quando")))}$ripeti: ${v.optString("testo")}"
            }
        }
    }

    fun promemoriaDiOggi(ctx: Context): List<String> {
        val inizio = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
        val fine = inizio + 24 * 3600 * 1000L
        val formato = SimpleDateFormat("HH:mm", italiano)
        val lista = elenco(ctx)
        return (0 until lista.length()).map { lista.getJSONObject(it) }
            .filter { it.optString("tipo") == "ora" && it.optLong("quando") in inizio until fine }
            .map { "${formato.format(Date(it.optLong("quando")))} ${it.optString("testo")}" }
    }

    fun elimina(ctx: Context, parola: String): String {
        val p = parola.lowercase(italiano).trim()
        val lista = elenco(ctx)
        val nuova = JSONArray()
        var tolti = 0
        for (i in 0 until lista.length()) {
            val v = lista.getJSONObject(i)
            if (p == "tutti" || p == "tutto" || v.optString("testo").lowercase(italiano).contains(p) ||
                v.optString("luogo").lowercase(italiano).contains(p)
            ) {
                tolti++
                val id = v.optInt("id")
                if (v.optString("tipo") == "luogo") {
                    try {
                        LocationServices.getGeofencingClient(ctx).removeGeofences(listOf(id.toString()))
                    } catch (e: Exception) {
                        // già rimosso
                    }
                } else {
                    (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(intentoAllarme(ctx, AZIONE_PROMEMORIA, id))
                }
            } else {
                nuova.put(v)
            }
        }
        salva(ctx, nuova)
        return if (tolti > 0) "Ho cancellato $tolti promemoria." else "Non trovo promemoria su \"$parola\"."
    }

    // ================================================================
    //  RIEPILOGO DEL MATTINO
    // ================================================================

    fun programmaRiepilogo(ctx: Context) {
        val p = prefs(ctx)
        val intento = intentoAllarme(ctx, AZIONE_RIEPILOGO, ID_RIEPILOGO)
        val allarmi = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        allarmi.cancel(intento)
        if (!p.getBoolean("mattino_attivo", false)) return
        val ora = (p.getString("mattino_ora", "07:30") ?: "07:30").split(":")
        val prossimo = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, ora.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 7)
            set(Calendar.MINUTE, ora.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        impostaAllarme(ctx, prossimo.timeInMillis, intento)
    }

    private fun riepilogoScattato(ctx: Context) {
        programmaRiepilogo(ctx)   // programma quello di domani
        if (ServizioJarvis.attivo) {
            // Jarvis è sempre attivo: legge il riepilogo a voce
            ServizioJarvis.invia(ctx, ServizioJarvis.AZIONE_RIEPILOGO)
        } else {
            avvisa(
                ctx, ID_RIEPILOGO, "Buongiorno", "Il tuo riepilogo del mattino è pronto: tocca per ascoltarlo.",
                Intent(ctx, MainActivity::class.java).putExtra("riepilogo", true)
            )
        }
    }

    /** Dopo un riavvio: riprogramma tutto. */
    fun riprogrammaTutto(ctx: Context) {
        val lista = elenco(ctx)
        for (i in 0 until lista.length()) {
            val v = lista.getJSONObject(i)
            if (v.optString("tipo") == "luogo") programmaLuogo(ctx, v)
            else if (v.optLong("quando") > System.currentTimeMillis() || v.optString("ripeti", "no") != "no") {
                if (v.optLong("quando") <= System.currentTimeMillis()) {
                    val c = Calendar.getInstance().apply { timeInMillis = v.optLong("quando") }
                    while (c.timeInMillis <= System.currentTimeMillis()) avanza(c, v.optString("ripeti"))
                    v.put("quando", c.timeInMillis)
                }
                programmaOrario(ctx, v)
            }
        }
        salva(ctx, lista)
        programmaRiepilogo(ctx)
    }

    // ================================================================
    //  NOTIFICA (e voce di Jarvis, se è sempre attivo)
    // ================================================================

    private fun avvisa(ctx: Context, id: Int, titolo: String, testo: String, apri: Intent? = null) {
        val gestore = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            gestore.createNotificationChannel(
                NotificationChannel(CANALE, "Promemoria di Jarvis", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val intento = PendingIntent.getActivity(
            ctx, id, apri ?: Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val costruttore = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CANALE) else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH)
        }
        val notifica = costruttore
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(titolo)
            .setContentText(testo)
            .setStyle(Notification.BigTextStyle().bigText(testo))
            .setContentIntent(intento)
            .setAutoCancel(true)
            .build()
        try {
            gestore.notify(id, notifica)
        } catch (e: Exception) {
            // notifiche disattivate
        }
        if (apri == null && ServizioJarvis.attivo) {
            ServizioJarvis.dici(ctx, "$titolo: $testo")
        }
    }

    /** Riceve allarmi, arrivi e uscite dai luoghi e il riavvio del telefono. */
    class RicevitorePromemoria : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                AZIONE_PROMEMORIA -> scatta(ctx, intent.getIntExtra("id", -1))
                AZIONE_RIEPILOGO -> riepilogoScattato(ctx)
                AZIONE_LUOGO -> luogoRaggiunto(ctx, intent)
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                    riprogrammaTutto(ctx)
                    Proattivo.programma(ctx)
                }
                Proattivo.AZIONE_CONTROLLO -> {
                    val attesa = goAsync()
                    Thread {
                        try { Proattivo.controlla(ctx) } finally { attesa.finish() }
                    }.start()
                }
            }
        }
    }
}
