package com.example.jarvis

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.text.Html
import android.util.Xml
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Integrazioni gratuite di Jarvis: notizie (ANSA e Google News), informazioni (Wikipedia),
 * calendario del telefono, navigazione con Google Maps, luoghi preferiti e posizione.
 *
 * @param avvia apre un'altra app (gestito dal Cervello, anche in background)
 * @param chiediPermesso chiede un permesso all'utente e restituisce il messaggio da dire
 */
class Integrazioni(
    private val ctx: Context,
    private val prefs: SharedPreferences,
    private val avvia: (Intent, String) -> String,
    private val chiediPermesso: (String, String) -> String
) {

    private val italiano = Locale.ITALIAN

    private fun haPermesso(permesso: String) =
        Build.VERSION.SDK_INT < 23 || ctx.checkSelfPermission(permesso) == PackageManager.PERMISSION_GRANTED

    private fun codifica(testo: String) = URLEncoder.encode(testo, "UTF-8")

    private fun scarica(indirizzo: String): String {
        val connessione = URL(indirizzo).openConnection() as HttpURLConnection
        connessione.connectTimeout = 10000
        connessione.readTimeout = 15000
        connessione.setRequestProperty("User-Agent", "JarvisAssistente/1.0 (app personale Android)")
        return try {
            connessione.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connessione.disconnect()
        }
    }

    // ================================================================
    //  NOTIZIE (ANSA e Google News, gratuiti)
    // ================================================================

    class Notizia(val titolo: String, val descrizione: String, val data: String)

    private val FEED_ANSA = mapOf(
        "principali" to "https://www.ansa.it/sito/notizie/topnews/topnews_rss.xml",
        "cronaca" to "https://www.ansa.it/sito/notizie/cronaca/cronaca_rss.xml",
        "politica" to "https://www.ansa.it/sito/notizie/politica/politica_rss.xml",
        "mondo" to "https://www.ansa.it/sito/notizie/mondo/mondo_rss.xml",
        "economia" to "https://www.ansa.it/sito/notizie/economia/economia_rss.xml",
        "sport" to "https://www.ansa.it/sito/notizie/sport/sport_rss.xml",
        "tecnologia" to "https://www.ansa.it/canale_tecnologia/notizie/tecnologia_rss.xml"
    )

    private fun pulisci(html: String): String {
        val testo = if (Build.VERSION.SDK_INT >= 24) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html).toString()
        }
        return testo.replace(Regex("\\s+"), " ").trim()
    }

    fun leggiRss(indirizzo: String, quante: Int): List<Notizia> {
        val notizie = mutableListOf<Notizia>()
        val xml = scarica(indirizzo)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.reader())
        var dentroItem = false
        var titolo = ""
        var descrizione = ""
        var data = ""
        var evento = parser.eventType
        while (evento != XmlPullParser.END_DOCUMENT && notizie.size < quante) {
            if (evento == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        dentroItem = true
                        titolo = ""; descrizione = ""; data = ""
                    }
                    "title" -> if (dentroItem) titolo = parser.nextText()
                    "description" -> if (dentroItem) descrizione = parser.nextText()
                    "pubDate" -> if (dentroItem) data = parser.nextText()
                }
            } else if (evento == XmlPullParser.END_TAG && parser.name == "item") {
                if (titolo.isNotBlank()) notizie.add(Notizia(pulisci(titolo), pulisci(descrizione).take(220), data))
                dentroItem = false
            }
            evento = parser.next()
        }
        return notizie
    }

    /** Notizie per categoria (feed ANSA) oppure su un argomento o una città (Google News). */
    fun notizie(categoria: String, argomento: String, quante: Int): String {
        val n = quante.coerceIn(3, 10)
        return try {
            if (argomento.isNotBlank()) {
                val elenco = leggiRss(
                    "https://news.google.com/rss/search?q=${codifica(argomento)}&hl=it&gl=IT&ceid=IT:it", n
                )
                if (elenco.isEmpty()) "Non trovo notizie recenti su $argomento."
                else "Fonte: Google News, notizie su \"$argomento\":\n" +
                    elenco.mapIndexed { i, x -> "${i + 1}) ${x.titolo}" }.joinToString("\n")
            } else {
                val chiave = FEED_ANSA.keys.firstOrNull { categoria.lowercase(italiano).contains(it) } ?: "principali"
                val elenco = leggiRss(FEED_ANSA.getValue(chiave), n)
                if (elenco.isEmpty()) "Non riesco a leggere le notizie in questo momento."
                else "Fonte: ANSA, sezione $chiave:\n" + elenco.mapIndexed { i, x ->
                    "${i + 1}) ${x.titolo}" + (if (x.descrizione.isNotBlank()) ". ${x.descrizione}" else "")
                }.joinToString("\n")
            }
        } catch (e: Exception) {
            "Non riesco a raggiungere le notizie: controlla la connessione."
        }
    }

    // ================================================================
    //  INFORMAZIONI (Wikipedia + Google News, gratuiti; ricerca Google facoltativa)
    // ================================================================

    fun cercaInformazioni(domanda: String): String {
        if (domanda.isBlank()) return "Dimmi cosa cercare."
        val parti = mutableListOf<String>()

        // Ricerca Google di Gemini: solo se l'utente l'ha attivata (richiede il piano a pagamento di Gemini)
        if (prefs.getBoolean("ricerca_google", false)) {
            ricercaGoogleGemini(domanda)?.let { return "Risultato della ricerca Google:\n$it" }
        }

        try {
            val ricerca = JSONObject(
                scarica("https://it.wikipedia.org/w/rest.php/v1/search/page?limit=3&q=${codifica(domanda)}")
            ).optJSONArray("pages") ?: JSONArray()
            if (ricerca.length() > 0) {
                val chiave = ricerca.getJSONObject(0).optString("key")
                val riassunto = JSONObject(
                    scarica("https://it.wikipedia.org/api/rest_v1/page/summary/${codifica(chiave).replace("+", "%20")}")
                )
                val estratto = riassunto.optString("extract")
                if (estratto.isNotBlank()) parti.add("Wikipedia, voce \"${riassunto.optString("title")}\": ${estratto.take(1200)}")
            }
        } catch (e: Exception) {
            // Wikipedia non raggiungibile: si continua con le notizie
        }

        try {
            val recenti = leggiRss("https://news.google.com/rss/search?q=${codifica(domanda)}&hl=it&gl=IT&ceid=IT:it", 5)
            if (recenti.isNotEmpty()) {
                parti.add("Notizie recenti (Google News):\n" + recenti.joinToString("\n") { "- ${it.titolo}" })
            }
        } catch (e: Exception) {
            // nessuna notizia
        }

        return if (parti.isEmpty()) "Non ho trovato informazioni su \"$domanda\"." else parti.joinToString("\n\n")
    }

    private fun ricercaGoogleGemini(domanda: String): String? {
        val chiave = (prefs.getString("api_key", "") ?: "").trim()
        if (chiave.isEmpty()) return null
        val modello = (prefs.getString("modello", Cervello.MODELLO_PREDEFINITO) ?: Cervello.MODELLO_PREDEFINITO).trim()
        return try {
            val corpo = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", "Rispondi in italiano, in modo conciso e aggiornato: $domanda")
                ))))
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            val connessione = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/$modello:generateContent"
            ).openConnection() as HttpURLConnection
            connessione.requestMethod = "POST"
            connessione.connectTimeout = 15000
            connessione.readTimeout = 60000
            connessione.doOutput = true
            connessione.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connessione.setRequestProperty("x-goog-api-key", chiave)
            connessione.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
            if (connessione.responseCode !in 200..299) {
                connessione.disconnect()
                return null
            }
            val risposta = JSONObject(connessione.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            connessione.disconnect()
            val parti = risposta.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return null
            val testo = (0 until parti.length()).joinToString(" ") { parti.optJSONObject(it)?.optString("text").orEmpty() }.trim()
            testo.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    // ================================================================
    //  DATE E ORARI DETTI A VOCE
    // ================================================================

    private val giorniSettimana = listOf("domenica", "lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato")

    /** "oggi", "domani", "dopodomani", "giovedì", "2026-10-23", "23/10", "23/10/2026" → mezzanotte di quel giorno. */
    fun interpretaData(testo: String): Calendar? {
        val t = testo.lowercase(italiano).trim().replace("lunedi", "lunedì").replace("martedi", "martedì")
            .replace("mercoledi", "mercoledì").replace("giovedi", "giovedì").replace("venerdi", "venerdì")
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        when {
            t.isEmpty() || t == "oggi" -> return c
            t == "domani" -> return c.apply { add(Calendar.DAY_OF_MONTH, 1) }
            t == "dopodomani" -> return c.apply { add(Calendar.DAY_OF_MONTH, 2) }
        }
        giorniSettimana.indexOfFirst { t.contains(it) }.takeIf { it >= 0 }?.let { indice ->
            var differenza = (indice + 1) - c.get(Calendar.DAY_OF_WEEK)
            if (differenza <= 0) differenza += 7
            return c.apply { add(Calendar.DAY_OF_MONTH, differenza) }
        }
        for (formato in listOf("yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "dd/MM")) {
            try {
                val f = SimpleDateFormat(formato, italiano).apply { isLenient = false }
                val d = f.parse(t) ?: continue
                val risultato = Calendar.getInstance().apply { time = d }
                if (formato == "dd/MM") {
                    risultato.set(Calendar.YEAR, c.get(Calendar.YEAR))
                    if (risultato.before(c)) risultato.add(Calendar.YEAR, 1)
                }
                return risultato
            } catch (e: Exception) {
                // prova il formato successivo
            }
        }
        return null
    }

    /** "18:30", "18.30", "18" → (ore, minuti) */
    fun interpretaOra(testo: String): Pair<Int, Int>? {
        val parti = testo.trim().replace(".", ":").split(":")
        val ore = parti.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val minuti = parti.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return if (ore in 0..23 && minuti in 0..59) ore to minuti else null
    }

    // ================================================================
    //  CALENDARIO DEL TELEFONO
    // ================================================================

    fun leggiCalendario(giorno: String, giorni: Int): String {
        if (!haPermesso(Manifest.permission.READ_CALENDAR)) {
            return chiediPermesso(Manifest.permission.READ_CALENDAR, "leggere il calendario")
        }
        val inizio = interpretaData(giorno) ?: return "Non ho capito il giorno \"$giorno\"."
        val quantiGiorni = giorni.coerceIn(1, 31)
        val fine = (inizio.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, quantiGiorni) }
        val costruttore = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(costruttore, inizio.timeInMillis)
        ContentUris.appendId(costruttore, fine.timeInMillis)
        val formatoGiorno = SimpleDateFormat("EEEE d MMMM", italiano)
        val formatoOra = SimpleDateFormat("HH:mm", italiano)
        val righe = mutableListOf<String>()
        ctx.contentResolver.query(
            costruttore.build(),
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION
            ),
            null, null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext() && righe.size < 30) {
                val titolo = c.getString(0) ?: "Senza titolo"
                val da = Date(c.getLong(1))
                val a = Date(c.getLong(2))
                val tuttoIlGiorno = c.getInt(3) == 1
                val luogo = c.getString(4).orEmpty()
                val quando = if (tuttoIlGiorno) "${formatoGiorno.format(da)}, tutto il giorno"
                else "${formatoGiorno.format(da)}, ${formatoOra.format(da)}-${formatoOra.format(a)}"
                righe.add("$quando: $titolo" + if (luogo.isNotBlank()) " ($luogo)" else "")
            }
        }
        val periodo = if (quantiGiorni == 1) formatoGiorno.format(inizio.time) else "i prossimi $quantiGiorni giorni da ${formatoGiorno.format(inizio.time)}"
        return if (righe.isEmpty()) "Nessun impegno per $periodo." else "Impegni per $periodo:\n" + righe.joinToString("\n")
    }

    fun creaEvento(titolo: String, giorno: String, ora: String, durataMinuti: Int, luogo: String): String {
        if (titolo.isBlank()) return "Dimmi il titolo dell'appuntamento."
        val data = interpretaData(giorno) ?: return "Non ho capito il giorno \"$giorno\"."
        val orario = if (ora.isBlank()) null else interpretaOra(ora) ?: return "Non ho capito l'ora \"$ora\"."
        val durata = durataMinuti.coerceIn(5, 24 * 60).let { if (durataMinuti <= 0) 60 else it }

        if (!haPermesso(Manifest.permission.WRITE_CALENDAR) || !haPermesso(Manifest.permission.READ_CALENDAR)) {
            return chiediPermesso(Manifest.permission.WRITE_CALENDAR, "scrivere nel calendario")
        }
        val idCalendario = calendarioJarvis() ?: ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        val valori = ContentValues()
        if (orario == null) {
            // tutto il giorno: in UTC, come richiede Android
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(data.get(Calendar.YEAR), data.get(Calendar.MONTH), data.get(Calendar.DAY_OF_MONTH))
            }
            valori.put(CalendarContract.Events.DTSTART, utc.timeInMillis)
            valori.put(CalendarContract.Events.DTEND, utc.timeInMillis + 24 * 3600 * 1000L)
            valori.put(CalendarContract.Events.ALL_DAY, 1)
            valori.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        } else {
            data.set(Calendar.HOUR_OF_DAY, orario.first)
            data.set(Calendar.MINUTE, orario.second)
            valori.put(CalendarContract.Events.DTSTART, data.timeInMillis)
            valori.put(CalendarContract.Events.DTEND, data.timeInMillis + durata * 60_000L)
            valori.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        valori.put(CalendarContract.Events.TITLE, titolo.trim())
        if (luogo.isNotBlank()) valori.put(CalendarContract.Events.EVENT_LOCATION, luogo.trim())

        val quando = SimpleDateFormat("EEEE d MMMM", italiano).format(data.time) +
            (if (orario != null) " alle %02d:%02d".format(orario.first, orario.second) else ", tutto il giorno")

        if (idCalendario == null) {
            // Nessun calendario scrivibile: apro l'app Calendario già compilata
            val intento = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, titolo)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, valori.getAsLong(CalendarContract.Events.DTSTART))
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, valori.getAsLong(CalendarContract.Events.DTEND))
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, orario == null)
                .putExtra(CalendarContract.Events.EVENT_LOCATION, luogo)
            return avvia(intento, "Ho preparato l'appuntamento \"$titolo\" per $quando: conferma nel calendario.")
        }
        valori.put(CalendarContract.Events.CALENDAR_ID, idCalendario)
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, valori)
            ?: return "Non sono riuscito a salvare l'appuntamento."
        // promemoria 30 minuti prima
        try {
            ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri))
                put(CalendarContract.Reminders.MINUTES, if (orario == null) 9 * 60 else 30)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        } catch (e: Exception) {
            // l'evento c'è comunque
        }
        return "Appuntamento \"${titolo.trim()}\" salvato per $quando."
    }

    // ---------- calendario creato da Jarvis ----------

    /** Il calendario creato con crea_calendario, se esiste ancora. */
    private fun calendarioJarvis(): Long? {
        val id = prefs.getLong("calendario_jarvis", -1L)
        if (id < 0) return null
        val esiste = ctx.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id),
            arrayOf(CalendarContract.Calendars._ID), null, null, null
        )?.use { it.moveToFirst() } ?: false
        if (!esiste) prefs.edit().remove("calendario_jarvis").apply()
        return if (esiste) id else null
    }

    /** Crea un calendario nuovo sul telefono: da qui in poi i nuovi appuntamenti finiscono lì. */
    fun creaCalendario(nome: String): String {
        if (!haPermesso(Manifest.permission.WRITE_CALENDAR) || !haPermesso(Manifest.permission.READ_CALENDAR)) {
            return chiediPermesso(Manifest.permission.WRITE_CALENDAR, "creare un calendario")
        }
        val titolo = nome.trim().ifBlank { "Jarvis" }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "Jarvis")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val valori = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, "Jarvis")
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, titolo)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, titolo)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFFD4A84B.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, "Jarvis")
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        val nuovo = try {
            ctx.contentResolver.insert(uri, valori)
        } catch (e: Exception) {
            null
        } ?: return "Non sono riuscito a creare il calendario."
        prefs.edit().putLong("calendario_jarvis", ContentUris.parseId(nuovo)).apply()
        return "Ho creato il calendario \"$titolo\" sul telefono: da ora i nuovi appuntamenti li metto lì."
    }

    /** Un evento trovato nel calendario. */
    private class Evento(val id: Long, val titolo: String, val inizio: Long, val fine: Long, val tuttoIlGiorno: Boolean)

    /** Cerca eventi per parola del titolo (o "tutti") in un periodo: senza giorno, da oggi ai prossimi 60 giorni. */
    private fun trovaEventi(cerca: String, giorno: String, giorni: Int): List<Evento> {
        val inizio = if (giorno.isBlank()) interpretaData("oggi")!! else interpretaData(giorno) ?: return emptyList()
        val durata = if (giorno.isBlank()) 60 else giorni.coerceIn(1, 366)
        val fine = (inizio.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, durata) }
        val costruttore = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(costruttore, inizio.timeInMillis)
        ContentUris.appendId(costruttore, fine.timeInMillis)
        val c = cerca.lowercase(italiano).trim()
        val tutti = c.isEmpty() || c == "tutti" || c == "tutto" || c == "tutti gli eventi"
        val trovati = LinkedHashMap<Long, Evento>()
        ctx.contentResolver.query(
            costruttore.build(),
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY
            ),
            null, null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                val titolo = cur.getString(1) ?: ""
                if (tutti || titolo.lowercase(italiano).contains(c)) {
                    val id = cur.getLong(0)
                    if (!trovati.containsKey(id)) trovati[id] = Evento(id, titolo, cur.getLong(2), cur.getLong(3), cur.getInt(4) == 1)
                }
            }
        }
        return trovati.values.toList()
    }

    /** Quanti eventi corrispondono ai criteri (per chiedere conferma senza elencarli). */
    fun contaEventi(cerca: String, giorno: String, giorni: Int): Int =
        if (!haPermesso(Manifest.permission.READ_CALENDAR) || !haPermesso(Manifest.permission.WRITE_CALENDAR)) -1
        else trovaEventi(cerca, giorno, giorni).size

    private fun descriviEvento(e: Evento): String {
        val f = SimpleDateFormat(if (e.tuttoIlGiorno) "EEEE d MMMM" else "EEEE d MMMM 'alle' HH:mm", italiano)
        return "\"${e.titolo}\" di ${f.format(Date(e.inizio))}"
    }

    /** Cancella gli eventi trovati (massimo 100 alla volta). Da usare solo dopo la conferma dell'utente. */
    fun cancellaEventi(cerca: String, giorno: String, giorni: Int): String {
        if (!haPermesso(Manifest.permission.WRITE_CALENDAR) || !haPermesso(Manifest.permission.READ_CALENDAR)) {
            return chiediPermesso(Manifest.permission.WRITE_CALENDAR, "modificare il calendario")
        }
        val eventi = trovaEventi(cerca, giorno, giorni)
        if (eventi.isEmpty()) return "Non trovo eventi da cancellare."
        var cancellati = 0
        for (e in eventi.take(100)) {
            try {
                if (ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id), null, null) > 0) cancellati++
            } catch (ex: Exception) {
                // evento di un calendario in sola lettura
            }
        }
        val nonCancellati = eventi.size.coerceAtMost(100) - cancellati
        return "Ho cancellato $cancellati eventi." +
            (if (nonCancellati > 0) " $nonCancellati non si possono cancellare perché appartengono a calendari in sola lettura." else "") +
            (if (eventi.size > 100) " Ce ne sono altri: ripeti la richiesta per continuare." else "")
    }

    /** Sposta o modifica un evento esistente. */
    fun modificaEvento(cerca: String, giorno: String, nuovoTitolo: String, nuovoGiorno: String, nuovaOra: String, durataMinuti: Int, luogo: String): String {
        if (!haPermesso(Manifest.permission.WRITE_CALENDAR) || !haPermesso(Manifest.permission.READ_CALENDAR)) {
            return chiediPermesso(Manifest.permission.WRITE_CALENDAR, "modificare il calendario")
        }
        if (cerca.isBlank()) return "Dimmi quale appuntamento modificare."
        val eventi = trovaEventi(cerca, giorno, 1)
        if (eventi.isEmpty()) return "Non trovo l'appuntamento \"$cerca\"."
        if (eventi.size > 1) {
            return "Ho trovato più appuntamenti: " + eventi.take(5).joinToString("; ") { descriviEvento(it) } +
                ". Chiedi all'utente quale e indica il giorno."
        }
        val e = eventi[0]
        val valori = ContentValues()
        if (nuovoTitolo.isNotBlank()) valori.put(CalendarContract.Events.TITLE, nuovoTitolo.trim())
        if (luogo.isNotBlank()) valori.put(CalendarContract.Events.EVENT_LOCATION, luogo.trim())
        if (nuovoGiorno.isNotBlank() || nuovaOra.isNotBlank() || durataMinuti > 0) {
            val vecchioInizio = Calendar.getInstance().apply { timeInMillis = e.inizio }
            val nuovoInizio = if (nuovoGiorno.isNotBlank()) {
                interpretaData(nuovoGiorno) ?: return "Non ho capito il nuovo giorno \"$nuovoGiorno\"."
            } else {
                (vecchioInizio.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }
            }
            val ora = if (nuovaOra.isNotBlank()) interpretaOra(nuovaOra) ?: return "Non ho capito la nuova ora \"$nuovaOra\"."
            else vecchioInizio.get(Calendar.HOUR_OF_DAY) to vecchioInizio.get(Calendar.MINUTE)
            nuovoInizio.set(Calendar.HOUR_OF_DAY, ora.first)
            nuovoInizio.set(Calendar.MINUTE, ora.second)
            val durata = if (durataMinuti > 0) durataMinuti * 60_000L else (e.fine - e.inizio).coerceAtLeast(15 * 60_000L)
            valori.put(CalendarContract.Events.DTSTART, nuovoInizio.timeInMillis)
            valori.put(CalendarContract.Events.DTEND, nuovoInizio.timeInMillis + durata)
            if (e.tuttoIlGiorno && nuovaOra.isNotBlank()) {
                valori.put(CalendarContract.Events.ALL_DAY, 0)
                valori.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        if (valori.size() == 0) return "Dimmi cosa cambiare dell'appuntamento."
        return try {
            val n = ctx.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id), valori, null, null)
            if (n > 0) "Ho aggiornato ${descriviEvento(e)}." else "Non sono riuscito a modificarlo."
        } catch (ex: Exception) {
            "Questo appuntamento è in un calendario in sola lettura e non si può modificare."
        }
    }

    // ================================================================
    //  LUOGHI, POSIZIONE E NAVIGAZIONE
    // ================================================================

    fun leggiLuoghi(): JSONArray = try {
        JSONArray(prefs.getString("luoghi", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    fun trovaLuogo(nome: String): JSONObject? {
        val n = nome.lowercase(italiano).trim().removePrefix("a ").removePrefix("al ").removePrefix("alla ")
        val luoghi = leggiLuoghi()
        for (i in 0 until luoghi.length()) {
            val l = luoghi.getJSONObject(i)
            if (l.optString("nome").lowercase(italiano) == n) return l
        }
        return null
    }

    fun descriviLuoghi(): String {
        val luoghi = leggiLuoghi()
        return (0 until luoghi.length()).joinToString("; ") {
            val l = luoghi.getJSONObject(it)
            "${l.optString("nome")} = ${l.optString("indirizzo")}"
        }
    }

    /** Posizione attuale (latitudine, longitudine), oppure null. Va chiamata in background. */
    fun posizioneAttuale(): Pair<Double, Double>? {
        if (!haPermesso(Manifest.permission.ACCESS_FINE_LOCATION) && !haPermesso(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        return try {
            val cliente = LocationServices.getFusedLocationProviderClient(ctx)
            val posizione = Tasks.await(
                cliente.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null), 15, TimeUnit.SECONDS
            ) ?: Tasks.await(cliente.lastLocation, 5, TimeUnit.SECONDS)
            posizione?.let { it.latitude to it.longitude }
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun indirizzoDi(lat: Double, lon: Double): String? = try {
        Geocoder(ctx, Locale.ITALY).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
            (0..a.maxAddressLineIndex).joinToString(", ") { a.getAddressLine(it) }
        }
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun coordinateDi(indirizzo: String): Pair<Double, Double>? = try {
        Geocoder(ctx, Locale.ITALY).getFromLocationName(indirizzo, 1)?.firstOrNull()?.let { it.latitude to it.longitude }
    } catch (e: Exception) {
        null
    }

    fun doveSono(): String {
        if (!haPermesso(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return chiediPermesso(Manifest.permission.ACCESS_FINE_LOCATION, "conoscere la tua posizione")
        }
        val (lat, lon) = posizioneAttuale() ?: return "Non riesco a trovare la posizione: controlla che il GPS sia acceso."
        val indirizzo = indirizzoDi(lat, lon)
        return if (indirizzo != null) "Sei in $indirizzo." else "Sei a latitudine %.4f, longitudine %.4f.".format(lat, lon)
    }

    fun salvaLuogo(nome: String, indirizzo: String): String {
        if (nome.isBlank()) return "Dimmi come chiamare questo luogo, ad esempio casa o lavoro."
        val qui = indirizzo.isBlank() || indirizzo.lowercase(italiano).let { it.contains("qui") || it.contains("posizione") }
        val coordinate: Pair<Double, Double>
        val testoIndirizzo: String
        if (qui) {
            if (!haPermesso(Manifest.permission.ACCESS_FINE_LOCATION)) {
                return chiediPermesso(Manifest.permission.ACCESS_FINE_LOCATION, "salvare la posizione attuale")
            }
            coordinate = posizioneAttuale() ?: return "Non riesco a trovare la posizione attuale."
            testoIndirizzo = indirizzoDi(coordinate.first, coordinate.second) ?: "posizione salvata"
        } else {
            coordinate = coordinateDi(indirizzo) ?: return "Non trovo l'indirizzo \"$indirizzo\"."
            testoIndirizzo = indirizzo.trim()
        }
        val luoghi = leggiLuoghi()
        val nuovi = JSONArray()
        for (i in 0 until luoghi.length()) {
            val l = luoghi.getJSONObject(i)
            if (!l.optString("nome").equals(nome.trim(), ignoreCase = true)) nuovi.put(l)
        }
        nuovi.put(
            JSONObject()
                .put("nome", nome.trim().lowercase(italiano))
                .put("indirizzo", testoIndirizzo)
                .put("lat", coordinate.first)
                .put("lon", coordinate.second)
        )
        prefs.edit().putString("luoghi", nuovi.toString()).apply()
        return "Ho salvato \"${nome.trim()}\": $testoIndirizzo."
    }

    fun naviga(destinazione: String, mezzo: String): String {
        if (destinazione.isBlank()) return "Dimmi dove vuoi andare."
        val salvato = trovaLuogo(destinazione)
        val dove = if (salvato != null) "${salvato.optDouble("lat")},${salvato.optDouble("lon")}" else destinazione.trim()
        val m = mezzo.lowercase(italiano)
        val modo = when {
            m.contains("pied") || m.contains("cammin") -> "walking"
            m.contains("bici") -> "bicycling"
            m.contains("mezz") || m.contains("bus") || m.contains("treno") || m.contains("metro") || m.contains("pubblic") -> "transit"
            else -> "driving"
        }
        val indirizzo = "https://www.google.com/maps/dir/?api=1&destination=${codifica(dove)}&travelmode=$modo" +
            if (modo != "transit") "&dir_action=navigate" else ""
        val intento = Intent(Intent.ACTION_VIEW, Uri.parse(indirizzo))
        val nome = salvato?.optString("nome") ?: destinazione
        val esito = avvia(Intent(intento).setPackage("com.google.android.apps.maps"), "ok")
        if (esito == "ok") return "Ho avviato il percorso verso $nome su Google Maps."
        return avvia(intento, "Ho aperto il percorso verso $nome.")
    }

    /** Coordinate di un luogo salvato o di un indirizzo, per i promemoria di posizione. */
    fun coordinate(luogo: String): Triple<String, Double, Double>? {
        trovaLuogo(luogo)?.let { return Triple(it.optString("nome"), it.optDouble("lat"), it.optDouble("lon")) }
        return coordinateDi(luogo)?.let { Triple(luogo.trim(), it.first, it.second) }
    }
}
