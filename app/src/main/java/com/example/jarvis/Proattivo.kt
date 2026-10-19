package com.example.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.provider.CalendarContract
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Jarvis proattivo: ogni 15 minuti controlla se c'è qualcosa da dirti, senza che tu chieda.
 * - un appuntamento si avvicina (e, se ha un indirizzo, quando conviene partire)
 * - qualcuno ti ha scritto più volte di fila
 * - domani piove (la sera) o oggi piove (la mattina)
 * Avvisa con una notifica e, se Jarvis è sempre attivo, anche a voce (mai di notte).
 */
object Proattivo {

    const val AZIONE_CONTROLLO = "com.example.jarvis.CONTROLLO"
    private const val CANALE = "jarvis_avvisi"
    private const val ID_ALLARME = 700_000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun attivo(ctx: Context) = prefs(ctx).getBoolean("proattivo", true)

    fun programma(ctx: Context) {
        val allarmi = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intento = PendingIntent.getBroadcast(
            ctx, ID_ALLARME,
            Intent(ctx, Promemoria.RicevitorePromemoria::class.java).setAction(AZIONE_CONTROLLO),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        allarmi.cancel(intento)
        if (!attivo(ctx)) return
        allarmi.setInexactRepeating(
            AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000L,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES, intento
        )
    }

    /** Chiamato dall'allarme, in un thread in background. */
    fun controlla(ctx: Context) {
        try { WidgetJarvis.aggiorna(ctx) } catch (e: Exception) { }
        try { Backup.automatico(ctx) } catch (e: Exception) { }
        if (!attivo(ctx)) return
        try { appuntamenti(ctx) } catch (e: Exception) { }
        try { resoconto(ctx) } catch (e: Exception) { }
        try { FaseNotturna.esegui(ctx) } catch (e: Exception) { }
        try { Aggiornamenti.controllaInBackground(ctx) } catch (e: Exception) { }
        try {
            // batteria per le automazioni, anche quando Jarvis non è sempre attivo
            val b = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null) {
                val livello = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 /
                    b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                Automazioni.batteria(ctx, livello, b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0)
            }
        } catch (e: Exception) { }
        try { preparaCitta(ctx) } catch (e: Exception) { }
        try { pioggia(ctx) } catch (e: Exception) { }
        pulisciGiaDetti(ctx)
    }

    // ================================================================
    //  APPUNTAMENTI IN ARRIVO
    // ================================================================

    private fun permesso(ctx: Context, p: String) =
        Build.VERSION.SDK_INT < 23 || ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun appuntamenti(ctx: Context) {
        if (!prefs(ctx).getBoolean("proattivo_calendario", true)) return
        if (!permesso(ctx, Manifest.permission.READ_CALENDAR)) return
        val adesso = System.currentTimeMillis()
        val costruttore = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(costruttore, adesso)
        ContentUris.appendId(costruttore, adesso + 100 * 60_000L)
        val cursore = ctx.contentResolver.query(
            costruttore.build(),
            arrayOf(
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY
            ),
            null, null, CalendarContract.Instances.BEGIN + " ASC"
        ) ?: return
        cursore.use {
            while (it.moveToNext()) {
                if (it.getInt(4) == 1) continue                     // eventi di tutto il giorno: no
                val id = it.getLong(0)
                val inizio = it.getLong(1)
                val titolo = it.getString(2) ?: "un impegno"
                val luogo = it.getString(3) ?: ""
                val minuti = ((inizio - adesso) / 60_000L).toInt()
                val chiave = "ev_${id}_$inizio"
                if (giaDetto(ctx, chiave)) continue
                if (luogo.isNotBlank()) {
                    val viaggio = minutiDiViaggio(ctx, luogo)
                    if (viaggio != null) {
                        val margine = (viaggio * 1.25).roundToInt() + 10      // traffico e parcheggio
                        val partenza = minuti - margine
                        if (partenza <= 15) {
                            val quando = if (partenza <= 0) "conviene partire adesso" else "conviene partire tra $partenza minuti"
                            avvisa(ctx, chiave, "Tra $minuti minuti c'è $titolo, a $luogo. In auto ci vogliono circa $viaggio minuti: $quando.")
                        }
                        continue
                    }
                }
                if (minuti in 1..30) avvisa(ctx, chiave, "Tra $minuti minuti c'è $titolo" + (if (luogo.isNotBlank()) ", a $luogo." else "."))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun posizione(ctx: Context): Pair<Double, Double>? {
        if (!permesso(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val ultima = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
            .maxByOrNull { it.time } ?: return null
        if (System.currentTimeMillis() - ultima.time > 3 * 3600_000L) return null
        return ultima.latitude to ultima.longitude
    }

    /** Minuti di viaggio in auto (senza traffico) con OSRM, gratuito e senza chiave. */
    private fun minutiDiViaggio(ctx: Context, luogo: String): Int? {
        val (lat, lon) = posizione(ctx) ?: return null
        val destinazione = try {
            @Suppress("DEPRECATION")
            Geocoder(ctx, Locale.ITALIAN).getFromLocationName(luogo, 1)?.firstOrNull()
        } catch (e: Exception) {
            null
        } ?: return null
        val url = "https://router.project-osrm.org/route/v1/driving/$lon,$lat;${destinazione.longitude},${destinazione.latitude}?overview=false"
        val json = JSONObject(scarica(url) ?: return null)
        val secondi = json.optJSONArray("routes")?.optJSONObject(0)?.optDouble("duration", -1.0) ?: return null
        if (secondi < 0) return null
        return (secondi / 60).roundToInt().coerceAtLeast(1)
    }

    private fun scarica(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "Jarvis-Android")
        val t = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        t
    } catch (e: Exception) {
        null
    }

    // ================================================================
    //  RESOCONTO DELLA SETTIMANA (domenica sera)
    // ================================================================

    private fun resoconto(ctx: Context) {
        if (!prefs(ctx).getBoolean("resoconto", true)) return
        val c = Calendar.getInstance()
        if (c.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY || c.get(Calendar.HOUR_OF_DAY) !in 19..21) return
        val chiave = "resoconto_${c.get(Calendar.YEAR)}_${c.get(Calendar.WEEK_OF_YEAR)}"
        if (giaDetto(ctx, chiave)) return
        val cervello = Cervello.prendi(ctx)
        if (cervello.chiaveAttiva().isEmpty()) return
        segnaDetto(ctx, chiave)
        val testo = cervello.resocontoSettimana()
        avvisa(ctx, chiave, testo)
    }

    // ================================================================
    //  PIOGGIA
    // ================================================================

    private fun pioggia(ctx: Context) {
        if (!prefs(ctx).getBoolean("proattivo_meteo", true)) return
        val ora = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val sera = ora in 19..21
        val mattina = ora in 7..8 && !prefs(ctx).getBoolean("mattino_attivo", false)
        if (!sera && !mattina) return
        val giorno = Calendar.getInstance().apply { if (sera) add(Calendar.DAY_OF_MONTH, 1) }
        val chiave = "pioggia_${giorno.get(Calendar.YEAR)}_${giorno.get(Calendar.DAY_OF_YEAR)}"
        if (giaDetto(ctx, chiave)) return
        val (lat, lon) = posizione(ctx) ?: coordinateCitta() ?: return
        val json = JSONObject(
            scarica("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&daily=precipitation_probability_max,precipitation_sum&timezone=auto&forecast_days=2")
                ?: return
        )
        val indice = if (sera) 1 else 0
        val probabilita = json.optJSONObject("daily")?.optJSONArray("precipitation_probability_max")?.optInt(indice, 0) ?: 0
        if (probabilita >= 60) {
            val quando = if (sera) "Domani" else "Oggi"
            avvisa(ctx, chiave, "$quando è prevista pioggia, al $probabilita per cento: meglio prendere l'ombrello.")
        } else {
            segnaDetto(ctx, chiave)
        }
    }

    private var cittaCache: Pair<Double, Double>? = null
    private fun coordinateCitta(): Pair<Double, Double>? = cittaCache

    // ================================================================
    //  MESSAGGI INSISTENTI
    // ================================================================

    private val APP_MESSAGGI = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging",
        "com.facebook.orca", "org.thoughtcrime.securesms", "com.instagram.android", "com.android.mms", "com.samsung.android.messaging"
    )
    private val arrivi = HashMap<String, MutableList<Long>>()

    /** Da NotificheJarvis per ogni messaggio in arrivo. */
    fun messaggioRicevuto(ctx: Context, pacchetto: String, app: String, mittente: String) {
        if (!attivo(ctx) || !prefs(ctx).getBoolean("proattivo_messaggi", true)) return
        if (pacchetto !in APP_MESSAGGI) return
        val adesso = System.currentTimeMillis()
        val volte = synchronized(arrivi) {
            val elenco = arrivi.getOrPut(mittente) { mutableListOf() }
            elenco.add(adesso)
            elenco.removeAll { adesso - it > 15 * 60_000L }
            elenco.size
        }
        if (volte >= 3) {
            val chiave = "msg_${mittente}_${adesso / (30 * 60_000L)}"
            if (!giaDetto(ctx, chiave)) {
                avvisa(ctx, chiave, "$mittente ha scritto $volte volte su $app negli ultimi minuti.")
                synchronized(arrivi) { arrivi.remove(mittente) }
            }
        }
    }

    // ================================================================
    //  AVVISO: notifica e voce
    // ================================================================

    private fun giaDetto(ctx: Context, chiave: String): Boolean =
        (prefs(ctx).getString("avvisi_fatti", "") ?: "").split("|").any { it.substringBefore('@') == chiave }

    private fun segnaDetto(ctx: Context, chiave: String) {
        val p = prefs(ctx)
        val elenco = (p.getString("avvisi_fatti", "") ?: "").split("|").filter { it.isNotBlank() }.toMutableList()
        elenco.add("$chiave@${System.currentTimeMillis()}")
        p.edit().putString("avvisi_fatti", elenco.takeLast(80).joinToString("|")).apply()
    }

    private fun pulisciGiaDetti(ctx: Context) {
        val p = prefs(ctx)
        val limite = System.currentTimeMillis() - 3 * 24 * 3600_000L
        val elenco = (p.getString("avvisi_fatti", "") ?: "").split("|")
            .filter { it.isNotBlank() && (it.substringAfter('@').toLongOrNull() ?: 0L) > limite }
        p.edit().putString("avvisi_fatti", elenco.joinToString("|")).apply()
    }

    private fun notte(): Boolean {
        val ora = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return ora >= 23 || ora < 7
    }

    fun avvisa(ctx: Context, chiave: String, testo: String) {
        segnaDetto(ctx, chiave)
        val gestore = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            gestore.createNotificationChannel(NotificationChannel(CANALE, "Avvisi di Jarvis", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val apri = PendingIntent.getActivity(
            ctx, chiave.hashCode(), Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val costruttore = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CANALE) else Notification.Builder(ctx)
        try {
            gestore.notify(
                chiave.hashCode(), costruttore
                    .setSmallIcon(R.drawable.ic_notifica)
                    .setContentTitle("Jarvis")
                    .setContentText(testo)
                    .setStyle(Notification.BigTextStyle().bigText(testo))
                    .setContentIntent(apri)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Exception) { }
        if (prefs(ctx).getBoolean("proattivo_voce", true) && !notte()) {
            ServizioJarvis.dici(ctx, testo, proattivo = true)
        }
    }

    /** Coordinate della città predefinita (se la posizione non è disponibile), calcolate una volta. */
    fun preparaCitta(ctx: Context) {
        if (cittaCache != null) return
        val citta = prefs(ctx).getString("citta", Cervello.CITTA_PREDEFINITA) ?: return
        val json = scarica("https://geocoding-api.open-meteo.com/v1/search?count=1&language=it&name=" + URLEncoder.encode(citta, "UTF-8")) ?: return
        val r = JSONObject(json).optJSONArray("results")?.optJSONObject(0) ?: return
        cittaCache = r.optDouble("latitude") to r.optDouble("longitude")
    }
}
