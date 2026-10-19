package com.example.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Le azioni sul telefono: app, sveglie, torcia, chiamate, messaggi, volume, musica, note e meteo. */
class AzioniTelefono(private val c: Cervello) {

    private val ctx: Context get() = c.contesto()
    private val uscita get() = c.uscita
    private val prefs get() = c.prefs
    private val principale get() = c.principale
    private fun lanciatore(): Context = c.lanciatore()
    private fun leggiLista(chiave: String): MutableList<String> = c.leggiLista(chiave)
    private fun salvaLista(chiave: String, lista: List<String>) = c.salvaLista(chiave, lista)

    // ---------- utilità ----------

    /** Esegue un'azione sul thread dell'interfaccia e aspetta il risultato. */
    internal fun suUi(azione: () -> String): String {
        var risultato = "Fatto."
        val attesa = CountDownLatch(1)
        principale.post {
            risultato = try {
                azione()
            } catch (e: Exception) {
                "Errore: ${e.message}"
            }
            attesa.countDown()
        }
        attesa.await(5, TimeUnit.SECONDS)
        return risultato
    }


    internal fun avvia(intento: Intent, messaggioOk: String): String = suUi {
        intento.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            lanciatore().startActivity(intento)
            messaggioOk
        } catch (e: Exception) {
            "Non ho trovato un'app in grado di farlo."
        }
    }


    internal fun codifica(testo: String): String = URLEncoder.encode(testo, "UTF-8")


    internal fun normalizzaUrl(url: String): String {
        val u = url.trim()
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    }


    internal fun httpGet(indirizzo: String): String {
        val connessione = URL(indirizzo).openConnection() as HttpURLConnection
        connessione.connectTimeout = 10000
        connessione.readTimeout = 15000
        return try {
            connessione.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connessione.disconnect()
        }
    }


    // ---------- app e web ----------

    internal fun apriApp(nome: String): String = suUi {
        val cercato = nome.lowercase(Locale.ITALIAN).trim()
        val pm = ctx.packageManager
        val lanciabili = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val trovata = lanciabili.firstOrNull {
            it.loadLabel(pm).toString().lowercase(Locale.ITALIAN) == cercato
        } ?: lanciabili.firstOrNull {
            it.loadLabel(pm).toString().lowercase(Locale.ITALIAN).contains(cercato)
        }
        if (trovata == null || cercato.isEmpty()) {
            "Non ho trovato nessuna app chiamata $nome."
        } else {
            val lancio = pm.getLaunchIntentForPackage(trovata.activityInfo.packageName)
            if (lancio == null) {
                "Non riesco ad aprire $nome."
            } else {
                lancio.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                lanciatore().startActivity(lancio)
                "Ho aperto ${trovata.loadLabel(pm)}. Se l'utente voleva fare qualcosa nell'app, continua: usa leggi_schermo e poi tocca o scrivi."
            }
        }
    }


    internal fun apriUrl(url: String, messaggioOk: String): String =
        avvia(Intent(Intent.ACTION_VIEW, Uri.parse(url)), messaggioOk)


    // ---------- sveglie e timer ----------

    internal fun impostaSveglia(ora: Int, minuti: Int, etichetta: String): String {
        if (ora !in 0..23 || minuti !in 0..59) return "Orario non valido."
        val intento = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, ora)
            .putExtra(AlarmClock.EXTRA_MINUTES, minuti)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (etichetta.isNotBlank()) intento.putExtra(AlarmClock.EXTRA_MESSAGE, etichetta)
        return avvia(intento, "Sveglia impostata alle %02d:%02d.".format(ora, minuti))
    }


    internal fun impostaTimer(secondi: Int, etichetta: String): String {
        if (secondi !in 1..86400) return "Durata non valida."
        val intento = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secondi)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (etichetta.isNotBlank()) intento.putExtra(AlarmClock.EXTRA_MESSAGE, etichetta)
        val descrizione = if (secondi >= 60 && secondi % 60 == 0) "${secondi / 60} minuti" else "$secondi secondi"
        return avvia(intento, "Timer di $descrizione avviato.")
    }


    // ---------- torcia ----------

    internal fun torcia(accendi: Boolean): String {
        if (Build.VERSION.SDK_INT < 23) return "Il telefono non permette di controllare la torcia."
        val camere = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val idCamera = camere.cameraIdList.firstOrNull {
            camere.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Questo telefono non ha il flash."
        camere.setTorchMode(idCamera, accendi)
        return if (accendi) "Torcia accesa." else "Torcia spenta."
    }


    // ---------- chiamate e SMS ----------

    internal fun permessoContattiMancante(): Boolean =
        Build.VERSION.SDK_INT >= 23 &&
            ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED


    internal fun eUnNumero(contatto: String) =
        contatto.replace(Regex("[\\s.-]"), "").matches(Regex("\\+?[0-9]{3,}"))


    /** Restituisce il numero, oppure null se non trovato. */
    internal fun trovaNumero(contatto: String): String? {
        val compatto = contatto.replace(Regex("[\\s.-]"), "")
        if (compatto.matches(Regex("\\+?[0-9]{3,}"))) return compatto
        val cursore = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%${contatto.trim()}%"),
            null
        )
        cursore?.use { if (it.moveToFirst()) return it.getString(1) }
        return null
    }


    internal fun chiediPermessoContatti(): String {
        val schermata = uscita
            ?: return "Mi serve il permesso di leggere la rubrica: apri l'app Jarvis e ripeti la richiesta da lì."
        principale.post { schermata.chiediPermesso(Manifest.permission.READ_CONTACTS) }
        return "Mi serve il permesso di leggere la rubrica. Concedilo e ripeti la richiesta."
    }


    internal fun chiama(contatto: String): String {
        if (!eUnNumero(contatto) && permessoContattiMancante()) return chiediPermessoContatti()
        val numero = trovaNumero(contatto) ?: return "Non ho trovato $contatto in rubrica."
        val puoChiamare = Build.VERSION.SDK_INT < 23 ||
            ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (puoChiamare) {
            return avvia(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(numero))), "Chiamo $contatto.")
        }
        // Senza permesso: lo chiedo e intanto preparo la chiamata
        uscita?.let { schermata -> principale.post { schermata.chiediPermesso(Manifest.permission.CALL_PHONE) } }
        return avvia(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(numero))),
            "Ho preparato la chiamata a $contatto: premi il tasto verde. Se mi dai il permesso per le telefonate, la prossima volta chiamo io."
        )
    }


    internal fun scriviSms(contatto: String, testo: String): String {
        if (!eUnNumero(contatto) && permessoContattiMancante()) return chiediPermessoContatti()
        val numero = trovaNumero(contatto) ?: return "Non ho trovato $contatto in rubrica."
        val intento = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(numero)))
            .putExtra("sms_body", testo)
        return avvia(intento, "Ho preparato il messaggio per $contatto: controllalo e premi invia.")
    }


    internal fun inviaWhatsapp(contatto: String, testo: String): String {
        if (testo.isBlank()) return "Il messaggio è vuoto."
        if (!eUnNumero(contatto) && permessoContattiMancante()) return chiediPermessoContatti()
        val numero = trovaNumero(contatto) ?: return "Non ho trovato $contatto in rubrica."
        var cifre = numero.replace(Regex("[^0-9+]"), "")
        cifre = when {
            cifre.startsWith("+") -> cifre.substring(1)
            cifre.startsWith("00") -> cifre.substring(2)
            cifre.length == 10 && cifre.startsWith("3") -> "39$cifre"   // cellulare italiano senza prefisso
            else -> cifre
        }
        val intento = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$cifre?text=" + codifica(testo)))
        val esito = avvia(Intent(intento).setPackage("com.whatsapp"), "ok")
        if (esito != "ok") {
            val alternativo = avvia(intento, "ok")
            if (alternativo != "ok") return "Non riesco ad aprire WhatsApp."
        }
        val acc = AccessibilitaJarvis.istanza
            ?: return "Ho preparato il messaggio su WhatsApp per $contatto: premi invia. Attiva il controllo delle app e lo invierò io."
        return if (acc.toccaInvioWhatsapp()) "Messaggio WhatsApp inviato a $contatto." else
            "Ho preparato il messaggio su WhatsApp per $contatto ma non trovo il tasto invia: premilo tu."
    }


    /** Indirizzo email: scritto direttamente oppure cercato in rubrica per nome. */
    internal fun trovaEmail(chi: String): String? {
        val c = chi.trim()
        if (c.contains("@")) return c.replace(" ", "")
        if (permessoContattiMancante()) return null
        val cursore = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%$c%"),
            null
        )
        cursore?.use { if (it.moveToFirst()) return it.getString(1) }
        return null
    }


    // ---------- volume in percentuale e batteria (comandi rapidi) ----------

    internal fun volumePercento(percento: Int): String = suUi {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val massimo = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val livello = (percento.coerceIn(0, 100) * massimo + 50) / 100
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, livello, AudioManager.FLAG_SHOW_UI)
        "Volume al ${percento.coerceIn(0, 100)} per cento."
    }


    internal fun batteria(): String {
        val gestore = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val livello = gestore.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val inCarica = Build.VERSION.SDK_INT >= 23 && gestore.isCharging
        return "Batteria al $livello per cento" + (if (inCarica) ", in carica." else ".")
    }


    // ---------- volume e musica ----------

    internal fun regolaVolume(azione: String, passi: Int): String = suUi {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val flusso = AudioManager.STREAM_MUSIC
        val a = azione.lowercase(Locale.ITALIAN)
        val n = passi.coerceIn(1, 15)
        when {
            a.contains("mut") || a.contains("silenz") -> {
                if (Build.VERSION.SDK_INT >= 23) {
                    audio.adjustStreamVolume(flusso, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                } else {
                    audio.setStreamVolume(flusso, 0, AudioManager.FLAG_SHOW_UI)
                }
            }
            a.contains("mass") -> audio.setStreamVolume(flusso, audio.getStreamMaxVolume(flusso), AudioManager.FLAG_SHOW_UI)
            a.contains("abbass") || a.contains("giù") || a.contains("giu") -> repeat(n) {
                audio.adjustStreamVolume(flusso, AudioManager.ADJUST_LOWER, if (it == n - 1) AudioManager.FLAG_SHOW_UI else 0)
            }
            else -> repeat(n) {
                audio.adjustStreamVolume(flusso, AudioManager.ADJUST_RAISE, if (it == n - 1) AudioManager.FLAG_SHOW_UI else 0)
            }
        }
        val percentuale = audio.getStreamVolume(flusso) * 100 / audio.getStreamMaxVolume(flusso).coerceAtLeast(1)
        "Volume al $percentuale per cento."
    }


    internal val APP_MUSICA = linkedMapOf(
        "spotify" to "com.spotify.music",
        "youtube music" to "com.google.android.apps.youtube.music",
        "youtube" to "com.google.android.youtube",
        "amazon music" to "com.amazon.mp3",
        "apple music" to "com.apple.android.music",
        "deezer" to "deezer.android.app"
    )


    internal fun installata(pacchetto: String) = try {
        ctx.packageManager.getLaunchIntentForPackage(pacchetto) != null
    } catch (e: Exception) {
        false
    }


    /** L'app musicale da usare: quella chiesta, quella preferita, oppure la prima installata. */
    internal fun appMusica(richiesta: String): Pair<String, String>? {
        val r = richiesta.lowercase(Locale.ITALIAN).trim()
        APP_MUSICA.entries.firstOrNull { r.isNotEmpty() && r.contains(it.key) && installata(it.value) }?.let { return it.key to it.value }
        val preferita = prefs.getString("app_musica", "") ?: ""
        APP_MUSICA.entries.firstOrNull { it.key == preferita && installata(it.value) }?.let { return it.key to it.value }
        return APP_MUSICA.entries.firstOrNull { installata(it.value) }?.let { it.key to it.value }
    }


    internal fun premiTastoMusica(tasto: Int) {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, tasto))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, tasto))
    }


    internal fun musicaInRiproduzione() = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive


    /** Fa partire una canzone, un artista, un album, una playlist o un genere nell'app musicale. */
    internal fun riproduci(cosa: String, app: String): String {
        val (nomeApp, pacchetto) = appMusica(app) ?: return "Non trovo app musicali installate, come Spotify o YouTube Music."
        prefs.edit().putString("app_musica", nomeApp).apply()
        val ricerca = cosa.trim()
        if (ricerca.isEmpty()) {
            // solo "metti la musica": apro l'app e premo play
            val lancio = ctx.packageManager.getLaunchIntentForPackage(pacchetto) ?: return "Non riesco ad aprire $nomeApp."
            avvia(lancio, "ok")
            Thread.sleep(3000)
            premiTastoMusica(KeyEvent.KEYCODE_MEDIA_PLAY)
            Thread.sleep(1500)
            return if (musicaInRiproduzione()) "Musica avviata su $nomeApp." else "Ho aperto $nomeApp ma la musica non è partita: dimmi cosa vuoi ascoltare."
        }
        // Richiesta standard di Android "riproduci da ricerca", supportata da Spotify, YouTube Music e altre
        val intento = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, ricerca)
            .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .setPackage(pacchetto)
        val esito = avvia(intento, "ok")
        if (esito != "ok") {
            // l'app non supporta la ricerca: apro la ricerca nell'app
            val ricercaWeb = when (nomeApp) {
                "spotify" -> "spotify:search:" + Uri.encode(ricerca)
                "youtube music" -> "https://music.youtube.com/search?q=" + Uri.encode(ricerca)
                else -> "https://www.youtube.com/results?search_query=" + Uri.encode(ricerca)
            }
            avvia(Intent(Intent.ACTION_VIEW, Uri.parse(ricercaWeb)).setPackage(pacchetto), "ok")
        }
        Thread.sleep(3500)
        if (!musicaInRiproduzione()) premiTastoMusica(KeyEvent.KEYCODE_MEDIA_PLAY)
        Thread.sleep(1500)
        if (!musicaInRiproduzione()) {
            // ultimo tentativo: tocco il pulsante di riproduzione sullo schermo
            AccessibilitaJarvis.istanza?.let { acc ->
                for (pulsante in listOf("Riproduci", "Play", "Riproduzione casuale", "Shuffle", "Ascolta")) {
                    if (acc.tocca(pulsante).startsWith("Ho toccato")) break
                }
            }
            Thread.sleep(1500)
        }
        return if (musicaInRiproduzione()) "Metto $ricerca su $nomeApp."
        else "Ho cercato $ricerca su $nomeApp: scegli il brano da far partire, oppure attiva il controllo delle app e lo faccio io."
    }


    internal fun controllaMusica(azione: String): String {
        val a = azione.lowercase(Locale.ITALIAN)
        val vuolePlay = !(a.contains("paus") || a.contains("stop") || a.contains("ferma") || a.contains("avanti") ||
            a.contains("success") || a.contains("prossim") || a.contains("indietro") || a.contains("precedent"))
        // "play" senza musica attiva: apro l'app musicale e faccio partire
        if (vuolePlay && !musicaInRiproduzione()) {
            premiTastoMusica(KeyEvent.KEYCODE_MEDIA_PLAY)
            Thread.sleep(1200)
            if (!musicaInRiproduzione()) return riproduci("", "")
            return "Riprendo la riproduzione."
        }
        return controllaMusicaTasti(azione)
    }


    internal fun controllaMusicaTasti(azione: String): String = suUi {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val a = azione.lowercase(Locale.ITALIAN)
        val (tasto, messaggio) = when {
            a.contains("paus") || a.contains("stop") || a.contains("ferma") -> KeyEvent.KEYCODE_MEDIA_PAUSE to "Pausa."
            a.contains("avanti") || a.contains("success") || a.contains("prossim") -> KeyEvent.KEYCODE_MEDIA_NEXT to "Brano successivo."
            a.contains("indietro") || a.contains("precedent") -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Brano precedente."
            else -> KeyEvent.KEYCODE_MEDIA_PLAY to "Riprendo la riproduzione."
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, tasto))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, tasto))
        messaggio
    }


    // ---------- note ----------

    internal fun prendiNota(testo: String): String {
        if (testo.isBlank()) return "La nota è vuota."
        val data = SimpleDateFormat("dd/MM HH:mm", Locale.ITALIAN).format(Date())
        val note = leggiLista("note")
        note.add("$data - ${testo.trim()}")
        salvaLista("note", note)
        return "Nota salvata."
    }


    internal fun leggiNote(quante: Int): String {
        val note = leggiLista("note")
        if (note.isEmpty()) return "Non ci sono note."
        return note.takeLast(quante.coerceIn(1, 20)).joinToString("\n")
    }


    // ---------- meteo (Open-Meteo, gratuito e senza chiave) ----------

    internal fun meteo(cittaRichiesta: String, giorno: String): String {
        val citta = cittaRichiesta.trim().ifBlank {
            prefs.getString("citta", Cervello.CITTA_PREDEFINITA) ?: Cervello.CITTA_PREDEFINITA
        }
        val geo = JSONObject(
            httpGet("https://geocoding-api.open-meteo.com/v1/search?count=1&language=it&name=" + codifica(citta))
        )
        val risultati = geo.optJSONArray("results")
        if (risultati == null || risultati.length() == 0) return "Non trovo la città $citta."
        val luogo = risultati.getJSONObject(0)

        val dati = JSONObject(
            httpGet(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${luogo.getDouble("latitude")}&longitude=${luogo.getDouble("longitude")}" +
                    "&current=temperature_2m,weather_code" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=3"
            )
        )

        val g = giorno.lowercase(Locale.ITALIAN)
        val indice = when {
            g.contains("dopodomani") -> 2
            g.contains("domani") -> 1
            else -> 0
        }
        val giornaliero = dati.getJSONObject("daily")
        val descrizione = descriviMeteo(giornaliero.getJSONArray("weather_code").getInt(indice))
        val minima = giornaliero.getJSONArray("temperature_2m_min").getDouble(indice).roundToInt()
        val massima = giornaliero.getJSONArray("temperature_2m_max").getDouble(indice).roundToInt()
        val pioggia = giornaliero.getJSONArray("precipitation_probability_max").optInt(indice, 0)
        val nomeGiorno = listOf("oggi", "domani", "dopodomani")[indice]

        var testo = "${luogo.getString("name")}, $nomeGiorno: $descrizione, minima $minima gradi, " +
            "massima $massima gradi, probabilità di pioggia $pioggia per cento."
        if (indice == 0) {
            dati.optJSONObject("current")?.let {
                testo += " Adesso ci sono ${it.getDouble("temperature_2m").roundToInt()} gradi."
            }
        }
        return testo
    }


    internal fun descriviMeteo(codice: Int): String = when (codice) {
        0 -> "sereno"
        1 -> "prevalentemente sereno"
        2 -> "parzialmente nuvoloso"
        3 -> "coperto"
        45, 48 -> "nebbia"
        51, 53, 55 -> "pioggerella"
        56, 57 -> "pioggerella gelata"
        61 -> "pioggia debole"
        63 -> "pioggia"
        65 -> "pioggia forte"
        66, 67 -> "pioggia gelata"
        71 -> "neve debole"
        73 -> "neve"
        75 -> "neve forte"
        77 -> "nevischio"
        80, 81 -> "rovesci"
        82 -> "rovesci violenti"
        85, 86 -> "rovesci di neve"
        95 -> "temporale"
        96, 99 -> "temporale con grandine"
        else -> "tempo variabile"
    }

}
