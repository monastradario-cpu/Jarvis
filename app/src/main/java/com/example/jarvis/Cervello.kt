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

/** Errore restituito dalle API, con il codice HTTP. */
class ErroreApi(val codice: Int, messaggio: String, val ripetibile: Boolean = false) : Exception(messaggio)

/**
 * Il cervello di Jarvis, condiviso tra la schermata e il servizio sempre attivo:
 * conversazione con Claude o Gemini, plugin, memoria e auto-miglioramento.
 */
class Cervello private constructor(private val ctx: Context) {

    companion object {
        const val URL_CLAUDE = "https://api.anthropic.com/v1/messages"
        const val MODELLO_CLAUDE_PREDEFINITO = "claude-haiku-5-5"
        const val URL_PREDEFINITO = "https://generativelanguage.googleapis.com/v1beta/openai"
        const val MODELLO_PREDEFINITO = "gemini-3.5-flash-lite"
        const val CITTA_PREDEFINITA = "Roma"

        private const val MAX_RICORDI = 80
        private const val MAX_LEZIONI = 40
        private const val MAX_DIARIO = 300
        private const val RIFLESSIONE_OGNI = 12

        @Volatile private var istanza: Cervello? = null

        fun prendi(contesto: Context): Cervello =
            istanza ?: synchronized(this) {
                istanza ?: Cervello(contesto.applicationContext).also { istanza = it }
            }
    }

    /** La schermata di Jarvis, quando è aperta. */
    interface Uscita {
        fun notaMemoria(testo: String)
        fun chiediPermesso(permesso: String)
        fun nuovoScambio(domanda: String, risposta: String, fonti: List<String>)
        /** Apre la fotocamera, salva la foto in [file] e chiama [alTermine] con l'esito. */
        fun scattaFoto(file: File, alTermine: (Boolean) -> Unit)
    }

    class Plugin(val id: String, val nome: String, val strumenti: List<String>)
    class Risposta(val testo: String, val fonti: List<String>)

    @Volatile var uscita: Uscita? = null
    val prefs: SharedPreferences = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private val principale = Handler(Looper.getMainLooper())
    private val blocco = Any()
    private val messaggi = JSONArray()        // conversazione con Gemini (formato OpenAI)
    private val messaggiClaude = JSONArray()  // conversazione con Claude (formato Anthropic)
    private val strumentiUsati = mutableListOf<String>()
    private val fontiUltimaRisposta = mutableListOf<String>()

    val PLUGIN = listOf(
        Plugin("telefono", "Telefono: app, sveglie, timer, torcia",
            listOf("apri_app", "imposta_sveglia", "imposta_timer", "torcia")),
        Plugin("audio", "Volume e musica",
            listOf("regola_volume", "controlla_musica", "riproduci")),
        Plugin("comunicazione", "Chiamate e SMS",
            listOf("chiama", "scrivi_sms")),
        Plugin("notifiche", "Notifiche: leggere e rispondere ai messaggi",
            listOf("leggi_notifiche", "rispondi_messaggio")),
        Plugin("controllo", "Controllo delle app e WhatsApp (accessibilità)",
            listOf("leggi_schermo", "tocca", "scrivi_testo", "scorri", "azione_sistema", "invia_whatsapp")),
        Plugin("web", "Web: siti, Google, YouTube",
            listOf("apri_sito", "cerca_sul_web", "cerca_su_youtube")),
        Plugin("ricerca", "Ricerca web integrata (solo con Claude, a pagamento)",
            listOf("web_search")),
        Plugin("meteo", "Meteo",
            listOf("meteo")),
        Plugin("notizie", "Notizie e informazioni (ANSA, Google News, Wikipedia)",
            listOf("notizie", "cerca_informazioni")),
        Plugin("calendario", "Calendario: impegni e appuntamenti",
            listOf("leggi_calendario", "crea_appuntamento", "modifica_appuntamento", "cancella_appuntamenti", "crea_calendario")),
        Plugin("navigazione", "Navigazione, luoghi e posizione",
            listOf("naviga", "salva_luogo", "dove_sono")),
        Plugin("promemoria", "Promemoria a orario, ripetuti e di posizione",
            listOf("promemoria", "promemoria_luogo", "elenca_promemoria", "cancella_promemoria")),
        Plugin("vista", "Vista: foto descritte, testi letti e tradotti",
            listOf("guarda")),
        Plugin("mattino", "Riepilogo del mattino",
            listOf("riepilogo_giornata")),
        Plugin("note", "Note",
            listOf("prendi_nota", "leggi_note"))
    )
    // Gli strumenti della memoria (ricorda, lezioni, routine, diario) sono sempre attivi.

    init {
        caricaConversazioni()
    }

    fun pluginAttivo(id: String) = prefs.getBoolean("plugin_$id", true)

    private fun strumentoAttivo(nome: String): Boolean {
        val plugin = PLUGIN.firstOrNull { nome in it.strumenti } ?: return true
        return pluginAttivo(plugin.id)
    }

    fun usaClaude() = prefs.getString("fornitore", "gemini") == "claude"

    fun chiaveAttiva(): String =
        (if (usaClaude()) prefs.getString("chiave_claude", "") else prefs.getString("api_key", ""))
            ?.trim() ?: ""

    private val integrazioni by lazy { Integrazioni(ctx, prefs, ::avvia, ::chiediPermessoGenerico) }

    /** Chiede un permesso dalla schermata (se è aperta) e restituisce cosa dire all'utente. */
    private fun chiediPermessoGenerico(permesso: String, motivo: String): String {
        val schermata = uscita
            ?: return "Mi serve il permesso per $motivo: apri l'app Jarvis e ripeti la richiesta da lì."
        principale.post { schermata.chiediPermesso(permesso) }
        return if (permesso == Manifest.permission.ACCESS_BACKGROUND_LOCATION) {
            "Per i promemoria di posizione mi serve la posizione sempre: scegli \"Consenti sempre\" e ripeti la richiesta."
        } else {
            "Mi serve il permesso per $motivo. Concedilo e ripeti la richiesta."
        }
    }

    /** Da dove aprire le altre app: il servizio di accessibilità può farlo anche in background. */
    private fun lanciatore(): Context = AccessibilitaJarvis.istanza ?: ctx

    // ================================================================
    //  FUNZIONI PER LA SCHERMATA E IL SERVIZIO
    // ================================================================

    /** Risponde a una richiesta. Va chiamata da un thread in background. */
    fun rispondi(testo: String): Risposta = synchronized(blocco) {
        strumentiUsati.clear()
        fontiUltimaRisposta.clear()
        val r = try {
            if (usaClaude()) conversaClaudeConRecupero(testo) else conversaGeminiConRecupero(testo)
        } catch (e: Exception) {
            "Scusa, c'è stato un problema: ${e.message}"
        }
        val fonti = fontiUltimaRisposta.toList()
        scriviNelDiario(testo, r)
        salvaConversazioni()
        Risposta(r, fonti)
    }

    /** Dopo ogni risposta: riordina la memoria e, ogni tanto, impara dal diario. */
    fun manutenzione() {
        try {
            manutenzioneMemoria()
        } catch (e: Exception) {
            // la manutenzione non deve mai bloccare Jarvis
        }
    }

    fun haConversazione() = synchronized(blocco) { messaggiClaude.length() > 0 || messaggi.length() > 1 }

    fun nuovaConversazione() = synchronized(blocco) {
        svuota(messaggiClaude)
        svuota(messaggi)
        salvaConversazioni()
    }

    fun cancellaTutto() = synchronized(blocco) {
        prefs.edit()
            .remove("memoria").remove("lezioni").remove("routine")
            .remove("riassunto").remove("contatore")
            .apply()
        scriviFile("diario.json", "[]")
        svuota(messaggiClaude)
        svuota(messaggi)
        salvaConversazioni()
    }

    fun numeroRichieste() = leggiDiario().length()

    /** Le ultime richieste del diario, dalla più recente, per la schermata Cronologia. */
    fun ultimeRichieste(quante: Int): String {
        val diario = leggiDiario()
        if (diario.length() == 0) return "Ancora nessuna richiesta."
        val righe = mutableListOf<String>()
        for (i in diario.length() - 1 downTo maxOf(0, diario.length() - quante)) {
            val v = diario.optJSONObject(i) ?: continue
            righe.add("${v.optString("t")}\n› ${v.optString("d")}\n${v.optString("r")}")
        }
        return righe.joinToString("\n\n")
    }

    /** Il carattere di Jarvis, scelto nelle impostazioni. */
    private fun personalita(): String {
        val stile = prefs.getString("personalita", "maggiordomo") ?: "maggiordomo"
        val appellativo = (prefs.getString("appellativo", "signore") ?: "").trim()
        val nome = if (appellativo.isNotEmpty()) " e chiamalo \"$appellativo\"" else ""
        return when (stile) {
            "formale" ->
                "Sei formale, preciso ed essenziale, come un assistente professionale. " +
                    "Dai del lei all'utente$nome. Niente battute: vai dritto al punto con cortesia."
            "amichevole" ->
                "Sei amichevole, spontaneo e caloroso, come un amico molto in gamba. " +
                    "Dai del tu all'utente" + (if (appellativo.isNotEmpty()) " e ogni tanto chiamalo \"$appellativo\"" else "") +
                    ". Puoi scherzare in modo leggero."
            else ->
                "Hai la personalità di un maggiordomo digitale all'inglese: elegante, calmo, impeccabilmente " +
                    "cortese e con un umorismo asciutto e sottile. Dai del lei all'utente$nome. " +
                    "Ogni tanto, quando viene naturale, aggiungi una breve battuta ironica e garbata, " +
                    "per esempio commentando con eleganza un orario insolito o una richiesta curiosa. " +
                    "Mai battute su cose serie, tristi o urgenti, e mai più di una per risposta. " +
                    "Frasi brevi, precise e raffinate."
        }
    }

    // ================================================================
    //  PROMPT DI SISTEMA: personalità + memoria di fondo
    // ================================================================

    private fun promptDiSistema(): String {
        val adesso = SimpleDateFormat("EEEE d MMMM yyyy, 'ore' HH:mm", Locale.ITALIAN).format(Date())
        val citta = prefs.getString("citta", CITTA_PREDEFINITA) ?: CITTA_PREDEFINITA
        val ricordi = leggiLista("memoria")
        val lezioni = leggiLista("lezioni")
        val routine = leggiRoutine()
        val riassunto = prefs.getString("riassunto", "") ?: ""
        val pluginAttivi = PLUGIN.filter { pluginAttivo(it.id) && (it.id != "ricerca" || usaClaude()) }

        val p = StringBuilder()
        p.append("Sei Jarvis, l'assistente vocale personale dell'utente, sul suo telefono Android. ")
        p.append("Rispondi sempre in italiano, con frasi brevi e naturali da leggere ad alta voce: ")
        p.append("niente elenchi, niente asterischi, niente emoji, niente link. ")
        p.append("Usa gli strumenti quando servono per agire sul telefono e non inventare risultati. ")
        p.append("Adesso è $adesso. La città predefinita per il meteo è $citta.\n\n")
        p.append("PERSONALITÀ\n").append(personalita()).append("\n\n")

        p.append("COME MIGLIORARE DA SOLO\n")
        p.append("- Quando scopri un fatto stabile o una preferenza dell'utente (nome, persone care, gusti, ")
        p.append("abitudini, luoghi, app preferite), salvalo subito con ricorda, senza chiedere e senza annunciarlo.\n")
        p.append("- Quando l'utente ti corregge, o uno strumento fallisce e trovi il modo giusto, ")
        p.append("salva una regola pratica con impara_lezione.\n")
        p.append("- Se l'utente ripete spesso la stessa sequenza di azioni, proponigli una routine ")
        p.append("e, se accetta, creala con crea_routine.\n")
        p.append("- Quando l'utente dice la frase di una routine, esegui le sue istruzioni con gli strumenti.\n")
        p.append("- Per domande su cosa ti ha chiesto in passato usa cerca_nel_diario.\n")
        if (usaClaude() && pluginAttivo("ricerca")) {
            p.append("- Per notizie, fatti recenti, prezzi, risultati sportivi o cose che possono essere cambiate, ")
            p.append("usa la ricerca web.\n")
        }
        if (pluginAttivo("notifiche")) {
            p.append("- Per i messaggi ricevuti usa leggi_notifiche; per rispondere a un messaggio usa rispondi_messaggio.\n")
        }
        if (pluginAttivo("controllo")) {
            if (AccessibilitaJarvis.istanza != null) {
                p.append("- Per fare qualcosa in un'altra app: aprila con apri_app e NON fermarti lì. Usa leggi_schermo, poi tocca, ")
                p.append("scrivi_testo e scorri, rileggendo lo schermo dopo ogni passo, finché la richiesta è completata. ")
                p.append("Per WhatsApp usa invia_whatsapp.\n")
            } else {
                p.append("- Il controllo delle app NON è attivo: puoi aprire le app ma non toccare o scrivere al loro interno. ")
                p.append("Se serve, spiega all'utente di attivarlo da SISTEMA, ATTIVO, Controllo delle app.\n")
            }
        }
        if (pluginAttivo("audio")) {
            p.append("- Per mettere musica (\"metti Vasco\", \"fammi sentire jazz\") usa riproduci, non controlla_musica.\n")
        }
        if (pluginAttivo("calendario")) {
            p.append("- Prima di cancellare appuntamenti ripeti all'utente cosa cancellerai e aspetta che confermi. ")
            p.append("Per ricominciare da zero puoi creare un calendario nuovo con crea_calendario.\n")
        }
        if (pluginAttivo("notizie")) {
            p.append("- Per le notizie usa notizie (categoria cronaca, politica, mondo, economia, sport, tecnologia, oppure un argomento o una città). ")
            p.append("Riassumile a voce in modo discorsivo, al massimo 5, citando la fonte (\"secondo l'ANSA\"), senza leggere i titoli parola per parola. ")
            p.append("Per fatti, persone, luoghi o cose che possono essere cambiate usa cerca_informazioni.\n")
        }
        if (pluginAttivo("calendario")) {
            p.append("- Per gli impegni usa leggi_calendario e crea_appuntamento; calcola tu la data esatta a partire da oggi.\n")
        }
        if (pluginAttivo("promemoria")) {
            p.append("- Per \"ricordamelo\" usa promemoria (orario, anche ripetuto) o promemoria_luogo (quando arrivo o esco da un posto).\n")
        }
        if (pluginAttivo("vista")) {
            p.append("- Se l'utente ti chiede di guardare, leggere o tradurre qualcosa che ha davanti, usa guarda.\n")
        }
        if (pluginAttivo("mattino")) {
            p.append("- Per il riepilogo del mattino usa riepilogo_giornata e raccontalo come un buongiorno, in poche frasi.\n")
        }
        p.append("- Prima di inviare messaggi, pubblicare, comprare, cancellare o cambiare impostazioni importanti, ")
        p.append("ripeti all'utente cosa stai per fare e chiedi conferma, a meno che non ti abbia già detto ")
        p.append("chiaramente destinatario e testo.\n")
        p.append("- Non salvare mai password, codici o dati bancari e non scriverli nelle app.\n\n")

        p.append("MEMORIA DI FONDO (usala come contesto, in modo naturale, senza ripeterla)\n")
        if (ricordi.isNotEmpty()) p.append("Cose che sai sull'utente: ").append(ricordi.joinToString("; ")).append(".\n")
        if (lezioni.isNotEmpty()) p.append("Lezioni imparate: ").append(lezioni.joinToString("; ")).append(".\n")
        if (routine.length() > 0) {
            p.append("Routine dell'utente:\n")
            for (i in 0 until routine.length()) {
                val r = routine.getJSONObject(i)
                p.append("- ${r.optString("nome")}: quando dice \"${r.optString("frase")}\" fai questo: ${r.optString("istruzioni")}\n")
            }
        }
        if (riassunto.isNotBlank()) p.append("Riassunto delle conversazioni passate: $riassunto\n")
        val luoghi = integrazioni.descriviLuoghi()
        if (luoghi.isNotBlank()) p.append("Luoghi salvati: $luoghi.\n")
        if (ricordi.isEmpty() && lezioni.isEmpty() && routine.length() == 0 && riassunto.isBlank()) {
            p.append("Ancora vuota: è una delle prime conversazioni.\n")
        }

        p.append("\nPlugin attivi: ").append(pluginAttivi.joinToString(", ") { it.nome }).append(".")
        return p.toString()
    }

    // ================================================================
    //  CERVELLO CLAUDE (API nativa di Anthropic)
    // ================================================================

    private fun modelloClaude() =
        (prefs.getString("modello_claude", MODELLO_CLAUDE_PREDEFINITO) ?: MODELLO_CLAUDE_PREDEFINITO).trim()

    /** Se la conversazione salvata non è più valida, la azzera e riprova una volta. */
    private fun conversaClaudeConRecupero(testo: String): String = try {
        conversaClaude(testo)
    } catch (e: ErroreApi) {
        if (e.ripetibile && messaggiClaude.length() > 0) {
            svuota(messaggiClaude)
            conversaClaude(testo)
        } else {
            throw e
        }
    }

    private fun conversaClaude(testo: String): String {
        val lunghezzaIniziale = messaggiClaude.length()
        val testi = StringBuilder()
        try {
            messaggiClaude.put(JSONObject().put("role", "user").put("content", testo))

            repeat(14) {
                val risposta = chiamaClaude()
                val contenuto = risposta.optJSONArray("content") ?: JSONArray()
                if (contenuto.length() == 0) {
                    contenuto.put(JSONObject().put("type", "text").put("text", "Fatto."))
                }
                // Il messaggio di Claude va rimandato così com'è (ragionamento e risultati di ricerca inclusi)
                messaggiClaude.put(JSONObject().put("role", "assistant").put("content", contenuto))

                val risultati = JSONArray()
                for (i in 0 until contenuto.length()) {
                    val blocco = contenuto.getJSONObject(i)
                    when (blocco.optString("type")) {
                        "text" -> {
                            testi.append(blocco.optString("text")).append(' ')
                            raccogliFonti(blocco)
                        }
                        "server_tool_use" -> strumentiUsati.add("ricerca web")
                        "tool_use" -> {
                            val nome = blocco.optString("name")
                            strumentiUsati.add(nome)
                            val argomenti = blocco.optJSONObject("input") ?: JSONObject()
                            val risultato = try {
                                eseguiStrumento(nome, argomenti)
                            } catch (e: Exception) {
                                "Errore durante $nome: ${e.message}"
                            }
                            risultati.put(
                                JSONObject()
                                    .put("type", "tool_result")
                                    .put("tool_use_id", blocco.optString("id"))
                                    .put("content", risultato)
                            )
                        }
                    }
                }

                if (risultati.length() > 0) {
                    messaggiClaude.put(JSONObject().put("role", "user").put("content", risultati))
                    return@repeat   // Claude deve vedere i risultati e continuare
                }
                if (risposta.optString("stop_reason") == "pause_turn") {
                    return@repeat   // la ricerca web è lunga: si continua dalla pausa
                }
                return testi.toString().trim().replace(Regex("\\s+"), " ").ifBlank { "Fatto." }
            }
            return testi.toString().trim().ifBlank { "Ci ho messo troppi passaggi, prova a chiedermelo in modo più semplice." }
        } catch (e: Exception) {
            // Annulla la parte di conversazione non riuscita
            while (messaggiClaude.length() > lunghezzaIniziale) messaggiClaude.remove(messaggiClaude.length() - 1)
            throw e
        }
    }

    private fun raccogliFonti(bloccoTesto: JSONObject) {
        val citazioni = bloccoTesto.optJSONArray("citations") ?: return
        for (i in 0 until citazioni.length()) {
            val c = citazioni.optJSONObject(i) ?: continue
            val titolo = c.optString("title").ifBlank { c.optString("url") }
            if (titolo.isNotBlank() && titolo !in fontiUltimaRisposta && fontiUltimaRisposta.size < 4) {
                fontiUltimaRisposta.add(titolo)
            }
        }
    }

    private fun strumentiPerClaude(): JSONArray {
        val strumenti = JSONArray()
        val definizioni = definizioniStrumenti()
        for (i in 0 until definizioni.length()) {
            val funzione = definizioni.getJSONObject(i).getJSONObject("function")
            strumenti.put(
                JSONObject()
                    .put("name", funzione.getString("name"))
                    .put("description", funzione.getString("description"))
                    .put("input_schema", funzione.getJSONObject("parameters"))
            )
        }
        // Plugin di ricerca web eseguito direttamente dai server di Anthropic
        if (pluginAttivo("ricerca")) {
            val citta = prefs.getString("citta", CITTA_PREDEFINITA) ?: CITTA_PREDEFINITA
            strumenti.put(
                JSONObject()
                    .put("type", "web_search_20250305")
                    .put("name", "web_search")
                    .put("max_uses", 3)
                    .put(
                        "user_location",
                        JSONObject()
                            .put("type", "approximate")
                            .put("city", citta)
                            .put("country", "IT")
                            .put("timezone", "Europe/Rome")
                    )
            )
        }
        return strumenti
    }

    private fun chiamaClaude(): JSONObject {
        val modello = modelloClaude()
        val corpo = JSONObject()
            .put("model", modello)
            .put("max_tokens", 1024)
            .put("system", promptDiSistema())
            .put("messages", messaggiClaude)
            .put("tools", strumentiPerClaude())
        // Su Haiku spegniamo il ragionamento lungo: risposte più rapide, ideali per la voce
        if (modello.contains("haiku")) corpo.put("thinking", JSONObject().put("type", "disabled"))
        return postClaude(corpo)
    }

    /** Domanda singola a Claude senza strumenti (usata per riassumere e riflettere). */
    private fun chiamaClaudeSemplice(richiesta: String, maxToken: Int): String {
        val modello = modelloClaude()
        val corpo = JSONObject()
            .put("model", modello)
            .put("max_tokens", maxToken)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", richiesta)))
        if (modello.contains("haiku")) corpo.put("thinking", JSONObject().put("type", "disabled"))
        val contenuto = postClaude(corpo).optJSONArray("content") ?: return ""
        val testo = StringBuilder()
        for (i in 0 until contenuto.length()) {
            val b = contenuto.getJSONObject(i)
            if (b.optString("type") == "text") testo.append(b.optString("text"))
        }
        return testo.toString().trim()
    }

    private fun postClaude(corpo: JSONObject): JSONObject {
        val chiave = (prefs.getString("chiave_claude", "") ?: "").trim()
        val connessione = URL(URL_CLAUDE).openConnection() as HttpURLConnection
        connessione.requestMethod = "POST"
        connessione.connectTimeout = 15000
        connessione.readTimeout = 90000
        connessione.doOutput = true
        connessione.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connessione.setRequestProperty("x-api-key", chiave)
        connessione.setRequestProperty("anthropic-version", "2023-06-01")
        connessione.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }

        val codice = connessione.responseCode
        val flusso = if (codice in 200..299) connessione.inputStream else connessione.errorStream
        val risposta = flusso?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        connessione.disconnect()

        if (codice !in 200..299) {
            val dettaglio = estraiErrore(risposta)
            val credito = dettaglio.contains("credit balance", ignoreCase = true)
            val messaggio = when {
                credito -> "il credito API di Claude è esaurito, ricaricalo su platform.claude.com."
                codice == 401 || codice == 403 -> "la chiave API di Claude non è valida, controllala nelle impostazioni."
                codice == 404 -> "modello Claude non trovato, controlla il nome nelle impostazioni."
                codice == 429 -> "troppe richieste in poco tempo, riprova tra qualche secondo."
                codice == 529 || codice >= 500 -> "i server di Claude sono occupati, riprova tra poco."
                else -> "errore $codice. $dettaglio"
            }
            val ripetibile = codice == 400 && !credito && dettaglio.contains("messages", ignoreCase = true)
            throw ErroreApi(codice, messaggio, ripetibile)
        }
        return JSONObject(risposta)
    }

    // ================================================================
    //  CERVELLO GEMINI (formato compatibile OpenAI)
    // ================================================================

    /** Se la conversazione salvata non è più valida, la azzera e riprova una volta. */
    private fun conversaGeminiConRecupero(testo: String): String = try {
        conversa(testo)
    } catch (e: ErroreApi) {
        if (e.codice == 400 && messaggi.length() > 1) {
            svuota(messaggi)
            conversa(testo)
        } else {
            throw e
        }
    }

    private fun conversa(testo: String): String {
        val sistema = JSONObject().put("role", "system").put("content", promptDiSistema())
        if (messaggi.length() == 0) messaggi.put(sistema) else messaggi.put(0, sistema)

        val lunghezzaIniziale = messaggi.length()
        try {
            messaggi.put(JSONObject().put("role", "user").put("content", testo))

            repeat(14) {
                val messaggio = chiamaApi()
                messaggi.put(messaggio)

                val chiamate = messaggio.optJSONArray("tool_calls")
                if (chiamate == null || chiamate.length() == 0) {
                    val contenuto = if (messaggio.isNull("content")) "" else messaggio.getString("content")
                    return contenuto.trim().ifBlank { "Fatto." }
                }

                for (i in 0 until chiamate.length()) {
                    val chiamata = chiamate.getJSONObject(i)
                    val funzione = chiamata.getJSONObject("function")
                    val nome = funzione.getString("name")
                    strumentiUsati.add(nome)
                    val argomenti = try {
                        JSONObject(funzione.optString("arguments", "{}").ifBlank { "{}" })
                    } catch (e: Exception) {
                        JSONObject()
                    }
                    val risultato = try {
                        eseguiStrumento(nome, argomenti)
                    } catch (e: Exception) {
                        "Errore durante $nome: ${e.message}"
                    }
                    messaggi.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", chiamata.optString("id"))
                            .put("name", nome)
                            .put("content", risultato)
                    )
                }
            }
            return "Ci ho messo troppi passaggi, prova a chiedermelo in modo più semplice."
        } catch (e: Exception) {
            while (messaggi.length() > lunghezzaIniziale) messaggi.remove(messaggi.length() - 1)
            throw e
        }
    }

    private fun chiamaApi(): JSONObject {
        val modello = prefs.getString("modello", MODELLO_PREDEFINITO) ?: MODELLO_PREDEFINITO
        val corpo = JSONObject()
            .put("model", modello)
            .put("messages", messaggi)
            .put("tools", definizioniStrumenti())
            .put("tool_choice", "auto")

        val messaggio = postGemini(corpo)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message")

        // Teniamo solo i campi utili (inclusi quelli speciali di Gemini)
        val pulito = JSONObject().put("role", "assistant")
        pulito.put("content", if (messaggio.isNull("content")) JSONObject.NULL else messaggio.get("content"))
        if (messaggio.has("tool_calls")) pulito.put("tool_calls", messaggio.get("tool_calls"))
        if (messaggio.has("extra_content")) pulito.put("extra_content", messaggio.get("extra_content"))
        return pulito
    }

    /** Domanda singola a Gemini senza strumenti (usata per riassumere e riflettere). */
    private fun chiamaGeminiSemplice(richiesta: String): String {
        val modello = prefs.getString("modello", MODELLO_PREDEFINITO) ?: MODELLO_PREDEFINITO
        val corpo = JSONObject()
            .put("model", modello)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", richiesta)))
        val messaggio = postGemini(corpo).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return if (messaggio.isNull("content")) "" else messaggio.getString("content").trim()
    }

    private fun postGemini(corpo: JSONObject): JSONObject {
        val base = (prefs.getString("base_url", URL_PREDEFINITO) ?: URL_PREDEFINITO).trim().trimEnd('/')
        val chiave = (prefs.getString("api_key", "") ?: "").trim()
        val connessione = URL("$base/chat/completions").openConnection() as HttpURLConnection
        connessione.requestMethod = "POST"
        connessione.connectTimeout = 15000
        connessione.readTimeout = 60000
        connessione.doOutput = true
        connessione.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connessione.setRequestProperty("Authorization", "Bearer $chiave")
        connessione.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }

        val codice = connessione.responseCode
        val flusso = if (codice in 200..299) connessione.inputStream else connessione.errorStream
        val risposta = flusso?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        connessione.disconnect()

        if (codice !in 200..299) {
            throw ErroreApi(
                codice,
                when (codice) {
                    400 -> "richiesta non valida. ${estraiErrore(risposta)}"
                    401, 403 -> "la chiave API non è valida, controllala nelle impostazioni."
                    404 -> "modello o indirizzo non trovato, controlla le impostazioni."
                    429 -> "ho raggiunto il limite di richieste gratuite, riprova tra un po'."
                    else -> "errore $codice. ${estraiErrore(risposta)}"
                }
            )
        }
        return JSONObject(risposta)
    }

    /** Domanda semplice al cervello attivo (Claude o Gemini). */
    private fun chiamaSemplice(richiesta: String, maxToken: Int): String =
        if (usaClaude()) chiamaClaudeSemplice(richiesta, maxToken) else chiamaGeminiSemplice(richiesta)

    private fun estraiErrore(testo: String): String = try {
        val radiceJson = JSONObject(testo)
        val errore = radiceJson.opt("error")
        when (errore) {
            is JSONObject -> errore.optString("message", testo)
            is JSONArray -> errore.optJSONObject(0)?.optJSONObject("error")?.optString("message") ?: testo
            else -> testo
        }
    } catch (e: Exception) {
        testo.take(200)
    }

    // ================================================================
    //  MEMORIA: archivio sul telefono
    // ================================================================

    private fun svuota(array: JSONArray) {
        while (array.length() > 0) array.remove(array.length() - 1)
    }

    private fun leggiFile(nome: String): String? = try {
        val file = File(ctx.filesDir, nome)
        if (file.exists()) file.readText() else null
    } catch (e: Exception) {
        null
    }

    private fun scriviFile(nome: String, testo: String) {
        try {
            File(ctx.filesDir, nome).writeText(testo)
        } catch (e: Exception) {
            // spazio pieno o file non scrivibile: si continua senza salvare
        }
    }

    fun leggiLista(chiave: String): MutableList<String> {
        val array = try {
            JSONArray(prefs.getString(chiave, "[]") ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        return MutableList(array.length()) { array.optString(it) }
    }

    private fun salvaLista(chiave: String, lista: List<String>) {
        prefs.edit().putString(chiave, JSONArray(lista).toString()).apply()
    }

    /** Aggiunge una voce se non c'è già. Restituisce true se è nuova. */
    private fun aggiungiSenzaDoppioni(chiave: String, voce: String, massimo: Int): Boolean {
        val pulita = voce.trim().trimEnd('.')
        if (pulita.isEmpty()) return false
        val lista = leggiLista(chiave)
        if (lista.any { it.equals(pulita, ignoreCase = true) }) return false
        lista.add(pulita)
        while (lista.size > massimo) lista.removeAt(0)
        salvaLista(chiave, lista)
        return true
    }

    private fun caricaConversazioni() {
        for ((nome, array) in listOf("conversazione_claude.json" to messaggiClaude, "conversazione_gemini.json" to messaggi)) {
            val testo = leggiFile(nome) ?: continue
            try {
                val salvata = JSONArray(testo)
                for (i in 0 until salvata.length()) array.put(salvata.get(i))
            } catch (e: Exception) {
                svuota(array)
            }
        }
    }

    private fun salvaConversazioni() {
        scriviFile("conversazione_claude.json", messaggiClaude.toString())
        scriviFile("conversazione_gemini.json", messaggi.toString())
    }

    // ---------- diario delle richieste ----------

    private fun leggiDiario(): JSONArray = try {
        JSONArray(leggiFile("diario.json") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    private fun scriviNelDiario(domanda: String, risposta: String) {
        val voce = JSONObject()
            .put("t", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ITALIAN).format(Date()))
            .put("d", domanda)
            .put("r", risposta.take(300))
            .put("s", JSONArray(strumentiUsati.distinct()))
        val diario = leggiDiario()
        diario.put(voce)
        val inizio = (diario.length() - MAX_DIARIO).coerceAtLeast(0)
        val accorciato = JSONArray()
        for (i in inizio until diario.length()) accorciato.put(diario.get(i))
        scriviFile("diario.json", accorciato.toString())
    }

    private fun cercaNelDiario(testo: String): String {
        val diario = leggiDiario()
        if (diario.length() == 0) return "Il diario è vuoto."
        val parole = testo.lowercase(Locale.ITALIAN).split(Regex("\\s+")).filter { it.length > 2 }
        val trovate = mutableListOf<String>()
        for (i in diario.length() - 1 downTo 0) {
            val v = diario.optJSONObject(i) ?: continue
            val tutto = (v.optString("t") + " " + v.optString("d") + " " + v.optString("r")).lowercase(Locale.ITALIAN)
            if (parole.isEmpty() || parole.any { tutto.contains(it) }) {
                trovate.add("${v.optString("t")}: ha chiesto \"${v.optString("d")}\", risposta: ${v.optString("r").take(120)}")
            }
            if (trovate.size >= 10) break
        }
        return if (trovate.isEmpty()) "Nessuna richiesta trovata per \"$testo\"." else trovate.joinToString("\n")
    }

    // ---------- routine ----------

    fun leggiRoutine(): JSONArray = try {
        JSONArray(prefs.getString("routine", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    private fun creaRoutine(nome: String, frase: String, istruzioni: String): String {
        if (nome.isBlank() || istruzioni.isBlank()) return "Per creare una routine servono un nome e le istruzioni."
        val routine = leggiRoutine()
        val nuove = JSONArray()
        for (i in 0 until routine.length()) {
            val r = routine.getJSONObject(i)
            if (!r.optString("nome").equals(nome.trim(), ignoreCase = true)) nuove.put(r)
        }
        nuove.put(
            JSONObject()
                .put("nome", nome.trim())
                .put("frase", frase.trim().ifBlank { nome.trim() })
                .put("istruzioni", istruzioni.trim())
        )
        prefs.edit().putString("routine", nuove.toString()).apply()
        return "Routine ${nome.trim()} salvata."
    }

    private fun eliminaRoutine(nome: String): String {
        val routine = leggiRoutine()
        val nuove = JSONArray()
        for (i in 0 until routine.length()) {
            val r = routine.getJSONObject(i)
            if (!r.optString("nome").equals(nome.trim(), ignoreCase = true)) nuove.put(r)
        }
        prefs.edit().putString("routine", nuove.toString()).apply()
        return if (nuove.length() < routine.length()) "Routine eliminata." else "Non esiste una routine chiamata $nome."
    }

    // ================================================================
    //  MEMORIA DI FONDO E AUTO-MIGLIORAMENTO
    // ================================================================

    private fun eDomandaUtente(m: JSONObject) = m.optString("role") == "user" && m.opt("content") is String

    /**
     * Dopo ogni risposta:
     * 1. se la conversazione è lunga, le parti vecchie diventano un riassunto permanente;
     * 2. ogni RIFLESSIONE_OGNI richieste Jarvis rilegge il diario e impara.
     */
    private fun manutenzioneMemoria() {
        val conChiave = chiaveAttiva().isNotEmpty()

        synchronized(blocco) {
            // Gemini tiene il messaggio di sistema in prima posizione: non va toccato
            val primoGemini = if (messaggi.length() > 0 && messaggi.getJSONObject(0).optString("role") == "system") 1 else 0
            val vecchi = riduciConversazione(messaggiClaude, 0) + riduciConversazione(messaggi, primoGemini)
            if (vecchi.isNotEmpty()) {
                salvaConversazioni()
                val testoVecchio = testoDellaConversazione(vecchi)
                if (conChiave && testoVecchio.isNotBlank()) {
                    try {
                        aggiornaRiassunto(testoVecchio)
                    } catch (e: Exception) {
                        // riproverà la prossima volta
                    }
                }
            }
        }

        val contatore = prefs.getInt("contatore", 0) + 1
        prefs.edit().putInt("contatore", contatore).apply()
        if (conChiave && contatore % RIFLESSIONE_OGNI == 0) {
            try {
                rifletti()
            } catch (e: Exception) {
                // riproverà al prossimo giro
            }
        }
    }

    /**
     * Se la conversazione è lunga, toglie i messaggi vecchi (tranne i primi `primo`)
     * e li restituisce, così diventano parte del riassunto permanente.
     */
    private fun riduciConversazione(array: JSONArray, primo: Int): List<JSONObject> {
        if (array.length() - primo <= 30) return emptyList()
        var inizio = array.length() - 16
        while (inizio < array.length() && !eDomandaUtente(array.getJSONObject(inizio))) inizio++
        if (inizio <= primo || inizio >= array.length()) return emptyList()
        val vecchi = (primo until inizio).map { array.getJSONObject(it) }
        val tenuti = (0 until primo).map { array.get(it) } + (inizio until array.length()).map { array.get(it) }
        svuota(array)
        tenuti.forEach { array.put(it) }
        return vecchi
    }

    /** Trasforma messaggi API in un testo leggibile "Utente: ... / Jarvis: ...". */
    private fun testoDellaConversazione(messaggi: List<JSONObject>): String {
        val righe = mutableListOf<String>()
        for (m in messaggi) {
            val ruolo = m.optString("role")
            val contenuto = m.opt("content")
            if (ruolo == "user" && contenuto is String) {
                righe.add("Utente: $contenuto")
            } else if (ruolo == "assistant" && contenuto is String) {
                // formato Gemini
                val azioni = m.optJSONArray("tool_calls")?.let { c ->
                    (0 until c.length()).joinToString(" ") { "[azione: ${c.optJSONObject(it)?.optJSONObject("function")?.optString("name")}]" }
                } ?: ""
                righe.add("Jarvis: $contenuto $azioni".trim())
            } else if (ruolo == "assistant" && contenuto == JSONObject.NULL) {
                val azioni = m.optJSONArray("tool_calls")?.let { c ->
                    (0 until c.length()).joinToString(" ") { "[azione: ${c.optJSONObject(it)?.optJSONObject("function")?.optString("name")}]" }
                } ?: ""
                if (azioni.isNotBlank()) righe.add("Jarvis: $azioni")
            } else if (ruolo == "assistant" && contenuto is JSONArray) {
                val testo = StringBuilder()
                for (i in 0 until contenuto.length()) {
                    val b = contenuto.optJSONObject(i) ?: continue
                    when (b.optString("type")) {
                        "text" -> testo.append(b.optString("text")).append(' ')
                        "tool_use" -> testo.append("[azione: ${b.optString("name")}] ")
                        "server_tool_use" -> testo.append("[ricerca web] ")
                    }
                }
                if (testo.isNotBlank()) righe.add("Jarvis: ${testo.toString().trim()}")
            }
        }
        return righe.joinToString("\n").take(6000)
    }

    private fun aggiornaRiassunto(testoNuovo: String) {
        val vecchio = prefs.getString("riassunto", "") ?: ""
        val richiesta = "Questo è il riassunto delle conversazioni passate tra l'utente e Jarvis, il suo assistente vocale:\n" +
            vecchio.ifBlank { "(vuoto)" } +
            "\n\nQueste sono conversazioni più recenti da aggiungere:\n$testoNuovo\n\n" +
            "Scrivi il riassunto aggiornato in italiano, al massimo 1000 caratteri. " +
            "Conserva cosa interessa all'utente, cosa chiede spesso, le cose in sospeso e le sue preferenze. " +
            "Togli i dettagli inutili. Non includere password, codici o dati bancari. " +
            "Rispondi solo con il riassunto."
        val nuovo = chiamaSemplice(richiesta, 600)
        if (nuovo.isNotBlank()) prefs.edit().putString("riassunto", nuovo.take(1500)).apply()
    }

    /** Jarvis rilegge le ultime richieste e decide da solo cosa imparare. */
    private fun rifletti() {
        val diario = leggiDiario()
        if (diario.length() < 3) return
        val inizio = (diario.length() - 20).coerceAtLeast(0)
        val righe = StringBuilder()
        for (i in inizio until diario.length()) {
            val v = diario.optJSONObject(i) ?: continue
            val strumenti = v.optJSONArray("s")?.let { s -> (0 until s.length()).joinToString(",") { s.optString(it) } } ?: ""
            righe.append("${v.optString("t")} | ${v.optString("d")} | ${strumenti.ifBlank { "-" }} | ${v.optString("r").take(150)}\n")
        }
        val routine = leggiRoutine()
        val nomiRoutine = (0 until routine.length()).joinToString(", ") { routine.getJSONObject(it).optString("nome") }

        val richiesta = "Sei il modulo di auto-miglioramento di Jarvis, un assistente vocale su telefono Android.\n" +
            "Cose che Jarvis sa già dell'utente: ${leggiLista("memoria").joinToString("; ").ifBlank { "niente" }}\n" +
            "Lezioni già imparate: ${leggiLista("lezioni").joinToString("; ").ifBlank { "nessuna" }}\n" +
            "Routine esistenti: ${nomiRoutine.ifBlank { "nessuna" }}\n\n" +
            "Ultime richieste (data | richiesta | strumenti usati | risposta):\n$righe\n" +
            "Individua cosa imparare per servire meglio l'utente. Rispondi SOLO con un JSON valido in questo formato:\n" +
            "{\"ricordi\": [\"fatti stabili e nuovi detti dall'utente\"], " +
            "\"lezioni\": [\"regole pratiche per rispondere meglio, ad esempio preferenze o errori da evitare\"], " +
            "\"routine\": [{\"nome\": \"...\", \"frase\": \"frase che l'utente dirà\", \"istruzioni\": \"cosa fare passo per passo\"}]}\n" +
            "Regole: al massimo 3 elementi per lista; niente che sia già presente; " +
            "una routine solo se l'utente ha ripetuto la stessa sequenza di azioni almeno 3 volte; " +
            "liste vuote se non c'è niente di nuovo; non dedurre dati sensibili come salute, religione, " +
            "politica o orientamento; mai password, codici o dati bancari."

        val risposta = chiamaSemplice(richiesta, 800)
        val da = risposta.indexOf('{')
        val a = risposta.lastIndexOf('}')
        if (da < 0 || a <= da) return
        val json = JSONObject(risposta.substring(da, a + 1))

        val imparato = mutableListOf<String>()
        json.optJSONArray("ricordi")?.let { lista ->
            for (i in 0 until minOf(lista.length(), 3)) {
                val voce = lista.optString(i)
                if (aggiungiSenzaDoppioni("memoria", voce, MAX_RICORDI)) imparato.add(voce)
            }
        }
        json.optJSONArray("lezioni")?.let { lista ->
            for (i in 0 until minOf(lista.length(), 3)) {
                val voce = lista.optString(i)
                if (aggiungiSenzaDoppioni("lezioni", voce, MAX_LEZIONI)) imparato.add(voce)
            }
        }
        json.optJSONArray("routine")?.let { lista ->
            val esistenti = leggiRoutine()
            for (i in 0 until minOf(lista.length(), 2)) {
                val r = lista.optJSONObject(i) ?: continue
                val nome = r.optString("nome")
                val giaPresente = (0 until esistenti.length()).any {
                    esistenti.getJSONObject(it).optString("nome").equals(nome, ignoreCase = true)
                }
                if (nome.isNotBlank() && !giaPresente) {
                    creaRoutine(nome, r.optString("frase"), r.optString("istruzioni"))
                    imparato.add("nuova routine \"$nome\" (di' \"${r.optString("frase")}\")")
                }
            }
        }

        if (imparato.isNotEmpty()) {
            val testo = "Ho imparato: " + imparato.joinToString("; ") + ". Puoi vedere tutto con 🧠."
            principale.post { uscita?.notaMemoria(testo) }
        }
    }

    // ================================================================
    //  STRUMENTI (i poteri dei plugin)
    // ================================================================

    private fun p(nome: String, tipo: String, descrizione: String) = arrayOf(nome, tipo, descrizione)

    private fun strumento(
        nome: String,
        descrizione: String,
        parametri: List<Array<String>>,
        obbligatori: List<String> = emptyList()
    ): JSONObject {
        val proprieta = JSONObject()
        for (par in parametri) {
            proprieta.put(par[0], JSONObject().put("type", par[1]).put("description", par[2]))
        }
        val schema = JSONObject().put("type", "object").put("properties", proprieta)
        if (obbligatori.isNotEmpty()) schema.put("required", JSONArray(obbligatori))
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject().put("name", nome).put("description", descrizione).put("parameters", schema)
            )
    }

    private fun definizioniStrumenti(): JSONArray {
        val tutti = listOf(
            // --- Telefono ---
            strumento("apri_app", "Apre un'app installata sul telefono.",
                listOf(p("nome", "string", "Nome dell'app, es. WhatsApp, Spotify, Fotocamera")), listOf("nome")),
            strumento("imposta_sveglia", "Imposta una sveglia a un orario preciso.",
                listOf(
                    p("ora", "integer", "Ora da 0 a 23"),
                    p("minuti", "integer", "Minuti da 0 a 59"),
                    p("etichetta", "string", "Nome della sveglia, facoltativo")
                ), listOf("ora", "minuti")),
            strumento("imposta_timer", "Avvia un conto alla rovescia.",
                listOf(
                    p("secondi", "integer", "Durata in secondi, es. 600 per 10 minuti"),
                    p("etichetta", "string", "Nome del timer, facoltativo")
                ), listOf("secondi")),
            strumento("torcia", "Accende o spegne la torcia del telefono.",
                listOf(p("accendi", "boolean", "true per accendere, false per spegnere")), listOf("accendi")),
            // --- Audio ---
            strumento("regola_volume", "Alza, abbassa o silenzia il volume multimediale.",
                listOf(
                    p("azione", "string", "alza, abbassa, muto oppure massimo"),
                    p("passi", "integer", "Di quanti scatti cambiare il volume, di solito 3")
                ), listOf("azione")),
            strumento("riproduci", "Fa partire musica: una canzone, un artista, un album, una playlist o un genere, su Spotify, YouTube Music o altre app.",
                listOf(
                    p("cosa", "string", "Cosa ascoltare, es. Vasco Rossi, musica rilassante; vuoto per riprendere"),
                    p("app", "string", "App da usare, es. Spotify; vuoto per quella preferita")
                )),
            strumento("controlla_musica", "Controlla la musica già in riproduzione: pausa, play, avanti, indietro.",
                listOf(p("azione", "string", "play, pausa, avanti oppure indietro")), listOf("azione")),
            // --- Comunicazione ---
            strumento("chiama", "Prepara una telefonata a un contatto o a un numero. L'utente preme poi il tasto verde.",
                listOf(p("contatto", "string", "Nome del contatto in rubrica oppure numero di telefono")), listOf("contatto")),
            strumento("scrivi_sms", "Prepara un SMS a un contatto o a un numero. L'utente preme poi invio.",
                listOf(
                    p("contatto", "string", "Nome del contatto in rubrica oppure numero di telefono"),
                    p("testo", "string", "Testo del messaggio")
                ), listOf("contatto", "testo")),
            // --- Web ---
            strumento("apri_sito", "Apre un sito web nel browser.",
                listOf(p("url", "string", "Indirizzo del sito, es. https://www.wikipedia.org")), listOf("url")),
            strumento("cerca_sul_web", "Apre una ricerca Google sul telefono per mostrare i risultati all'utente.",
                listOf(p("testo", "string", "Cosa cercare")), listOf("testo")),
            strumento("cerca_su_youtube", "Cerca un video o una canzone su YouTube.",
                listOf(p("testo", "string", "Cosa cercare su YouTube")), listOf("testo")),
            // --- Meteo ---
            strumento("meteo", "Previsioni del tempo per oggi, domani o dopodomani.",
                listOf(
                    p("citta", "string", "Città; se vuoto si usa quella predefinita"),
                    p("giorno", "string", "oggi, domani oppure dopodomani")
                )),
            // --- Note ---
            strumento("prendi_nota", "Salva una nota.",
                listOf(p("testo", "string", "Testo della nota")), listOf("testo")),
            strumento("leggi_note", "Legge le ultime note salvate.",
                listOf(p("quante", "integer", "Quante note leggere, di solito 5"))),
            // --- Notifiche ---
            strumento("leggi_notifiche", "Legge gli ultimi messaggi arrivati nelle notifiche (WhatsApp, Telegram, SMS, email...).",
                listOf(p("app", "string", "Filtra per app o persona, es. WhatsApp o Marco; vuoto per tutti"))),
            strumento("rispondi_messaggio",
                "Risponde a un messaggio ricevuto direttamente dalla sua notifica, senza aprire l'app.",
                listOf(
                    p("contatto", "string", "Nome di chi ha scritto, come appare nella notifica"),
                    p("testo", "string", "Testo della risposta"),
                    p("app", "string", "App del messaggio, facoltativa")
                ), listOf("contatto", "testo")),
            // --- Controllo delle app ---
            strumento("leggi_schermo", "Legge cosa c'è sullo schermo: testi, pulsanti e campi dell'app aperta.",
                listOf(p("motivo", "string", "Cosa stai cercando, facoltativo"))),
            strumento("tocca", "Tocca un pulsante o un elemento sullo schermo cercandolo per testo.",
                listOf(p("testo", "string", "Testo o descrizione dell'elemento da toccare")), listOf("testo")),
            strumento("scrivi_testo", "Scrive un testo in un campo dell'app aperta.",
                listOf(
                    p("testo", "string", "Testo da scrivere"),
                    p("campo", "string", "Nome o suggerimento del campo, facoltativo")
                ), listOf("testo")),
            strumento("scorri", "Scorre lo schermo.",
                listOf(p("direzione", "string", "giù oppure su")), listOf("direzione")),
            strumento("azione_sistema", "Usa i tasti di sistema del telefono.",
                listOf(p("azione", "string", "indietro, home, recenti, notifiche, impostazioni rapide, blocca schermo oppure screenshot")),
                listOf("azione")),
            strumento("invia_whatsapp", "Manda un messaggio WhatsApp a un contatto della rubrica o a un numero.",
                listOf(
                    p("contatto", "string", "Nome in rubrica oppure numero di telefono"),
                    p("testo", "string", "Testo del messaggio")
                ), listOf("contatto", "testo")),
            // --- Notizie e informazioni ---
            strumento("notizie", "Ultime notizie italiane: per categoria (feed ANSA) oppure su un argomento o una città (Google News).",
                listOf(
                    p("categoria", "string", "principali, cronaca, politica, mondo, economia, sport oppure tecnologia"),
                    p("argomento", "string", "Argomento o città, es. cronaca di Roma; vuoto per usare la categoria"),
                    p("quante", "integer", "Quante notizie, di solito 5")
                )),
            strumento("cerca_informazioni", "Cerca informazioni su fatti, persone, luoghi o argomenti (Wikipedia e notizie recenti).",
                listOf(p("domanda", "string", "Cosa cercare")), listOf("domanda")),
            // --- Calendario ---
            strumento("leggi_calendario", "Legge gli impegni del calendario del telefono.",
                listOf(
                    p("giorno", "string", "oggi, domani, un giorno della settimana oppure una data AAAA-MM-GG"),
                    p("giorni", "integer", "Quanti giorni a partire da quello, di solito 1")
                )),
            strumento("modifica_appuntamento", "Sposta o modifica un appuntamento esistente.",
                listOf(
                    p("cerca", "string", "Parola del titolo dell'appuntamento da modificare"),
                    p("giorno", "string", "Giorno attuale dell'appuntamento, se noto"),
                    p("nuovo_titolo", "string", "Nuovo titolo, facoltativo"),
                    p("nuovo_giorno", "string", "Nuovo giorno, facoltativo"),
                    p("nuova_ora", "string", "Nuova ora HH:MM, facoltativa"),
                    p("durata_minuti", "integer", "Nuova durata, facoltativa"),
                    p("luogo", "string", "Nuovo luogo, facoltativo")
                ), listOf("cerca")),
            strumento("cancella_appuntamenti",
                "Cancella appuntamenti dal calendario. Usalo SOLO dopo che l'utente ha confermato esplicitamente cosa cancellare.",
                listOf(
                    p("cerca", "string", "Parola del titolo, oppure tutti"),
                    p("giorno", "string", "Giorno di inizio; vuoto per i prossimi 60 giorni"),
                    p("giorni", "integer", "Quanti giorni a partire da quello, di solito 1")
                ), listOf("cerca")),
            strumento("crea_calendario", "Crea un calendario nuovo sul telefono, dove andranno i nuovi appuntamenti.",
                listOf(p("nome", "string", "Nome del calendario, es. Jarvis o Lavoro")), listOf("nome")),
            strumento("crea_appuntamento", "Aggiunge un appuntamento al calendario del telefono.",
                listOf(
                    p("titolo", "string", "Titolo, es. Dentista"),
                    p("giorno", "string", "Data AAAA-MM-GG, oppure domani o un giorno della settimana"),
                    p("ora", "string", "Ora HH:MM; vuota per un evento di tutto il giorno"),
                    p("durata_minuti", "integer", "Durata in minuti, di solito 60"),
                    p("luogo", "string", "Luogo, facoltativo")
                ), listOf("titolo", "giorno")),
            // --- Navigazione ---
            strumento("naviga", "Avvia il percorso su Google Maps verso un indirizzo o un luogo salvato (es. casa).",
                listOf(
                    p("destinazione", "string", "Indirizzo, nome di un posto o luogo salvato"),
                    p("mezzo", "string", "auto, a piedi, bici oppure mezzi pubblici")
                ), listOf("destinazione")),
            strumento("salva_luogo", "Salva un luogo preferito, es. casa o lavoro, con un indirizzo oppure la posizione attuale.",
                listOf(
                    p("nome", "string", "Nome del luogo, es. casa"),
                    p("indirizzo", "string", "Indirizzo; vuoto o \"qui\" per la posizione attuale")
                ), listOf("nome")),
            strumento("dove_sono", "Dice dove si trova l'utente in questo momento.",
                listOf(p("dettaglio", "string", "Facoltativo"))),
            // --- Promemoria ---
            strumento("promemoria", "Promemoria a una data e ora, anche ripetuto. Jarvis avvisa con una notifica e a voce.",
                listOf(
                    p("testo", "string", "Cosa ricordare"),
                    p("giorno", "string", "oggi, domani, un giorno della settimana o una data AAAA-MM-GG"),
                    p("ora", "string", "Ora HH:MM"),
                    p("ripeti", "string", "no, ogni giorno, feriali, ogni settimana oppure ogni mese")
                ), listOf("testo", "ora")),
            strumento("promemoria_luogo", "Promemoria che scatta quando l'utente arriva o esce da un luogo.",
                listOf(
                    p("testo", "string", "Cosa ricordare"),
                    p("luogo", "string", "Luogo salvato (es. casa) o indirizzo"),
                    p("quando", "string", "arrivo oppure uscita")
                ), listOf("testo", "luogo")),
            strumento("elenca_promemoria", "Elenca i promemoria attivi.",
                listOf(p("filtro", "string", "Facoltativo"))),
            strumento("cancella_promemoria", "Cancella i promemoria che contengono una parola, oppure tutti.",
                listOf(p("parola", "string", "Parola da cercare, oppure tutti")), listOf("parola")),
            // --- Vista ---
            strumento("guarda", "Apre la fotocamera: l'utente scatta una foto e Jarvis la descrive, legge i testi o li traduce.",
                listOf(p("domanda", "string", "Cosa vuole sapere l'utente, es. cosa vedi, leggimi questo, traduci")), listOf("domanda")),
            // --- Mattino ---
            strumento("riepilogo_giornata", "Raccoglie meteo, impegni, promemoria, messaggi e notizie principali di oggi.",
                listOf(p("tipo", "string", "mattino, facoltativo"))),
            // --- Memoria (sempre attiva) ---
            strumento("ricorda",
                "Salva nella memoria permanente un fatto stabile o una preferenza dell'utente. " +
                    "Usalo di tua iniziativa quando scopri qualcosa di utile, senza chiedere.",
                listOf(p("informazione", "string", "Il fatto da ricordare, es. Si chiama Dario")), listOf("informazione")),
            strumento("impara_lezione",
                "Salva una regola pratica per migliorarti: quando l'utente ti corregge, quando uno strumento " +
                    "fallisce e trovi il modo giusto, o quando capisci come preferisce che tu faccia qualcosa.",
                listOf(p("lezione", "string", "La regola, es. Per la musica usa sempre Spotify")), listOf("lezione")),
            strumento("crea_routine",
                "Crea o aggiorna una routine: una frase dell'utente che fa partire una sequenza di azioni.",
                listOf(
                    p("nome", "string", "Nome breve, es. buonanotte"),
                    p("frase", "string", "Frase che l'utente dirà, es. buonanotte Jarvis"),
                    p("istruzioni", "string", "Cosa fare passo per passo, es. spegni la torcia, sveglia alle 7, volume al minimo")
                ), listOf("nome", "frase", "istruzioni")),
            strumento("elimina_routine", "Elimina una routine.",
                listOf(p("nome", "string", "Nome della routine")), listOf("nome")),
            strumento("cerca_nel_diario",
                "Cerca tra tutte le richieste passate dell'utente. Accetta parole o una data nel formato AAAA-MM-GG; " +
                    "vuoto per le più recenti.",
                listOf(p("testo", "string", "Parole o data da cercare"))),
            strumento("dimentica",
                "Cancella dalla memoria ricordi, lezioni e routine che contengono una parola. Usa 'tutto' per cancellare tutto.",
                listOf(p("parola", "string", "Parola da cercare, oppure tutto")), listOf("parola"))
        )
        val attivi = JSONArray()
        for (s in tutti) {
            if (strumentoAttivo(s.getJSONObject("function").getString("name"))) attivi.put(s)
        }
        return attivi
    }

    private fun eseguiStrumento(nome: String, a: JSONObject): String {
        if (!strumentoAttivo(nome)) return "Questo plugin è disattivato nelle impostazioni."
        return when (nome) {
            "apri_app" -> apriApp(a.optString("nome"))
            "apri_sito" -> apriUrl(normalizzaUrl(a.optString("url")), "Ho aperto il sito.")
            "cerca_sul_web" -> apriUrl("https://www.google.com/search?q=" + codifica(a.optString("testo")), "Ecco i risultati.")
            "cerca_su_youtube" -> apriUrl("https://www.youtube.com/results?search_query=" + codifica(a.optString("testo")), "Ecco i risultati su YouTube.")
            "imposta_sveglia" -> impostaSveglia(a.optInt("ora", -1), a.optInt("minuti", 0), a.optString("etichetta"))
            "imposta_timer" -> impostaTimer(a.optInt("secondi", 0), a.optString("etichetta"))
            "torcia" -> torcia(a.optBoolean("accendi", true))
            "chiama" -> chiama(a.optString("contatto"))
            "scrivi_sms" -> scriviSms(a.optString("contatto"), a.optString("testo"))
            "meteo" -> meteo(a.optString("citta"), a.optString("giorno", "oggi"))
            "regola_volume" -> regolaVolume(a.optString("azione"), a.optInt("passi", 3))
            "controlla_musica" -> controllaMusica(a.optString("azione"))
            "prendi_nota" -> prendiNota(a.optString("testo"))
            "leggi_note" -> leggiNote(a.optInt("quante", 5))
            "ricorda" -> ricorda(a.optString("informazione"))
            "impara_lezione" -> imparaLezione(a.optString("lezione"))
            "crea_routine" -> creaRoutine(a.optString("nome"), a.optString("frase"), a.optString("istruzioni"))
            "elimina_routine" -> eliminaRoutine(a.optString("nome"))
            "cerca_nel_diario" -> cercaNelDiario(a.optString("testo"))
            "dimentica" -> dimentica(a.optString("parola"))
            "leggi_notifiche" -> NotificheJarvis.leggi(ctx, a.optString("app"))
            "rispondi_messaggio" -> NotificheJarvis.rispondi(ctx, a.optString("contatto"), a.optString("testo"), a.optString("app"))
            "leggi_schermo" -> AccessibilitaJarvis.istanza?.leggiSchermo() ?: AccessibilitaJarvis.AVVISO
            "tocca" -> AccessibilitaJarvis.istanza?.tocca(a.optString("testo")) ?: AccessibilitaJarvis.AVVISO
            "scrivi_testo" -> AccessibilitaJarvis.istanza?.scrivi(a.optString("testo"), a.optString("campo")) ?: AccessibilitaJarvis.AVVISO
            "scorri" -> AccessibilitaJarvis.istanza?.scorri(a.optString("direzione")) ?: AccessibilitaJarvis.AVVISO
            "azione_sistema" -> AccessibilitaJarvis.istanza?.azioneGlobale(a.optString("azione")) ?: AccessibilitaJarvis.AVVISO
            "invia_whatsapp" -> inviaWhatsapp(a.optString("contatto"), a.optString("testo"))
            "notizie" -> integrazioni.notizie(a.optString("categoria"), a.optString("argomento"), a.optInt("quante", 5))
            "cerca_informazioni" -> integrazioni.cercaInformazioni(a.optString("domanda"))
            "leggi_calendario" -> integrazioni.leggiCalendario(a.optString("giorno", "oggi"), a.optInt("giorni", 1))
            "crea_appuntamento" -> integrazioni.creaEvento(
                a.optString("titolo"), a.optString("giorno"), a.optString("ora"), a.optInt("durata_minuti", 60), a.optString("luogo")
            )
            "naviga" -> integrazioni.naviga(a.optString("destinazione"), a.optString("mezzo"))
            "riproduci" -> riproduci(a.optString("cosa"), a.optString("app"))
            "modifica_appuntamento" -> integrazioni.modificaEvento(
                a.optString("cerca"), a.optString("giorno"), a.optString("nuovo_titolo"), a.optString("nuovo_giorno"),
                a.optString("nuova_ora"), a.optInt("durata_minuti", 0), a.optString("luogo")
            )
            "cancella_appuntamenti" -> integrazioni.cancellaEventi(a.optString("cerca"), a.optString("giorno"), a.optInt("giorni", 1))
            "crea_calendario" -> integrazioni.creaCalendario(a.optString("nome"))
            "salva_luogo" -> integrazioni.salvaLuogo(a.optString("nome"), a.optString("indirizzo"))
            "dove_sono" -> integrazioni.doveSono()
            "promemoria" -> creaPromemoria(a.optString("testo"), a.optString("giorno", "oggi"), a.optString("ora"), a.optString("ripeti", "no"))
            "promemoria_luogo" -> creaPromemoriaLuogo(a.optString("testo"), a.optString("luogo"), a.optString("quando", "arrivo"))
            "elenca_promemoria" -> Promemoria.descrivi(ctx)
            "cancella_promemoria" -> Promemoria.elimina(ctx, a.optString("parola"))
            "guarda" -> guarda(a.optString("domanda"))
            "riepilogo_giornata" -> riepilogoGiornata()
            else -> "Strumento sconosciuto: $nome"
        }
    }

    // ---------- utilità ----------

    /** Esegue un'azione sul thread dell'interfaccia e aspetta il risultato. */
    private fun suUi(azione: () -> String): String {
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

    private fun avvia(intento: Intent, messaggioOk: String): String = suUi {
        intento.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            lanciatore().startActivity(intento)
            messaggioOk
        } catch (e: Exception) {
            "Non ho trovato un'app in grado di farlo."
        }
    }

    private fun codifica(testo: String): String = URLEncoder.encode(testo, "UTF-8")

    private fun normalizzaUrl(url: String): String {
        val u = url.trim()
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    }

    private fun httpGet(indirizzo: String): String {
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

    private fun apriApp(nome: String): String = suUi {
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

    private fun apriUrl(url: String, messaggioOk: String): String =
        avvia(Intent(Intent.ACTION_VIEW, Uri.parse(url)), messaggioOk)

    // ---------- sveglie e timer ----------

    private fun impostaSveglia(ora: Int, minuti: Int, etichetta: String): String {
        if (ora !in 0..23 || minuti !in 0..59) return "Orario non valido."
        val intento = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, ora)
            .putExtra(AlarmClock.EXTRA_MINUTES, minuti)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (etichetta.isNotBlank()) intento.putExtra(AlarmClock.EXTRA_MESSAGE, etichetta)
        return avvia(intento, "Sveglia impostata alle %02d:%02d.".format(ora, minuti))
    }

    private fun impostaTimer(secondi: Int, etichetta: String): String {
        if (secondi !in 1..86400) return "Durata non valida."
        val intento = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secondi)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (etichetta.isNotBlank()) intento.putExtra(AlarmClock.EXTRA_MESSAGE, etichetta)
        val descrizione = if (secondi >= 60 && secondi % 60 == 0) "${secondi / 60} minuti" else "$secondi secondi"
        return avvia(intento, "Timer di $descrizione avviato.")
    }

    // ---------- torcia ----------

    private fun torcia(accendi: Boolean): String {
        if (Build.VERSION.SDK_INT < 23) return "Il telefono non permette di controllare la torcia."
        val camere = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val idCamera = camere.cameraIdList.firstOrNull {
            camere.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Questo telefono non ha il flash."
        camere.setTorchMode(idCamera, accendi)
        return if (accendi) "Torcia accesa." else "Torcia spenta."
    }

    // ---------- chiamate e SMS ----------

    private fun permessoContattiMancante(): Boolean =
        Build.VERSION.SDK_INT >= 23 &&
            ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED

    private fun eUnNumero(contatto: String) =
        contatto.replace(Regex("[\\s.-]"), "").matches(Regex("\\+?[0-9]{3,}"))

    /** Restituisce il numero, oppure null se non trovato. */
    private fun trovaNumero(contatto: String): String? {
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

    private fun chiediPermessoContatti(): String {
        val schermata = uscita
            ?: return "Mi serve il permesso di leggere la rubrica: apri l'app Jarvis e ripeti la richiesta da lì."
        principale.post { schermata.chiediPermesso(Manifest.permission.READ_CONTACTS) }
        return "Mi serve il permesso di leggere la rubrica. Concedilo e ripeti la richiesta."
    }

    private fun chiama(contatto: String): String {
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

    private fun scriviSms(contatto: String, testo: String): String {
        if (!eUnNumero(contatto) && permessoContattiMancante()) return chiediPermessoContatti()
        val numero = trovaNumero(contatto) ?: return "Non ho trovato $contatto in rubrica."
        val intento = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(numero)))
            .putExtra("sms_body", testo)
        return avvia(intento, "Ho preparato il messaggio per $contatto: controllalo e premi invia.")
    }

    private fun inviaWhatsapp(contatto: String, testo: String): String {
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

    // ---------- promemoria ----------

    private fun creaPromemoria(testo: String, giorno: String, ora: String, ripeti: String): String {
        val data = integrazioni.interpretaData(giorno) ?: return "Non ho capito il giorno \"$giorno\"."
        val (h, m) = integrazioni.interpretaOra(ora) ?: return "Non ho capito l'ora \"$ora\"."
        data.set(java.util.Calendar.HOUR_OF_DAY, h)
        data.set(java.util.Calendar.MINUTE, m)
        return Promemoria.aggiungiOrario(ctx, testo, data, ripeti)
    }

    private fun creaPromemoriaLuogo(testo: String, luogo: String, quando: String): String {
        Promemoria.permessiPosizioneMancanti(ctx)?.let { return chiediPermessoGenerico(it, "usare la posizione") }
        val (nome, lat, lon) = integrazioni.coordinate(luogo)
            ?: return "Non conosco il luogo \"$luogo\". Puoi salvarlo, ad esempio dicendo: salva questo posto come casa."
        return Promemoria.aggiungiLuogo(ctx, testo, nome, lat, lon, quando)
    }

    // ---------- vista: foto descritte da Claude o Gemini ----------

    private fun guarda(domanda: String): String {
        val schermata = uscita ?: return "Per mostrarmi qualcosa apri l'app Jarvis e chiedimelo da lì."
        val cartella = File(ctx.cacheDir, "foto").apply { mkdirs() }
        val file = File(cartella, "vista.jpg")
        file.delete()
        val attesa = CountDownLatch(1)
        var riuscita = false
        principale.post {
            schermata.scattaFoto(file) { esito ->
                riuscita = esito
                attesa.countDown()
            }
        }
        if (!attesa.await(120, TimeUnit.SECONDS) || !riuscita || !file.exists()) return "Non ho ricevuto nessuna foto."
        val immagine = immagineBase64(file) ?: return "Non riesco a leggere la foto."
        val richiesta = "Sei gli occhi di un assistente vocale. L'utente chiede: \"${domanda.ifBlank { "cosa vedi?" }}\". " +
            "Descrivi in italiano quello che vedi nella foto, in modo utile e concreto. Se ci sono testi, trascrivili; " +
            "se l'utente chiede una traduzione, traducili in italiano. Al massimo 6 frasi."
        return try {
            "Nella foto: " + if (usaClaude()) visioneClaude(immagine, richiesta) else visioneGemini(immagine, richiesta)
        } catch (e: Exception) {
            "Non sono riuscito ad analizzare la foto: ${e.message}"
        }
    }

    /** Riduce la foto a 1280 pixel al massimo e la codifica in base64 (meno dati, meno costi). */
    private fun immagineBase64(file: File): String? = try {
        val dimensioni = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, dimensioni)
        var riduzione = 1
        while (maxOf(dimensioni.outWidth, dimensioni.outHeight) / (riduzione * 2) >= 1280) riduzione *= 2
        val immagine = android.graphics.BitmapFactory.decodeFile(
            file.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = riduzione }
        )
        val flusso = java.io.ByteArrayOutputStream()
        immagine.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, flusso)
        android.util.Base64.encodeToString(flusso.toByteArray(), android.util.Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    private fun visioneGemini(base64: String, richiesta: String): String {
        val modello = prefs.getString("modello", MODELLO_PREDEFINITO) ?: MODELLO_PREDEFINITO
        val contenuto = JSONArray()
            .put(JSONObject().put("type", "text").put("text", richiesta))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64")))
        val corpo = JSONObject()
            .put("model", modello)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", contenuto)))
        val messaggio = postGemini(corpo).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return if (messaggio.isNull("content")) "niente di riconoscibile." else messaggio.getString("content").trim()
    }

    private fun visioneClaude(base64: String, richiesta: String): String {
        val modello = modelloClaude()
        val contenuto = JSONArray()
            .put(JSONObject().put("type", "image").put("source", JSONObject()
                .put("type", "base64").put("media_type", "image/jpeg").put("data", base64)))
            .put(JSONObject().put("type", "text").put("text", richiesta))
        val corpo = JSONObject()
            .put("model", modello)
            .put("max_tokens", 600)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", contenuto)))
        if (modello.contains("haiku")) corpo.put("thinking", JSONObject().put("type", "disabled"))
        val blocchi = postClaude(corpo).optJSONArray("content") ?: return "niente di riconoscibile."
        return (0 until blocchi.length()).mapNotNull { blocchi.optJSONObject(it) }
            .filter { it.optString("type") == "text" }.joinToString(" ") { it.optString("text") }.trim()
    }

    // ---------- riepilogo del mattino ----------

    fun riepilogoGiornata(): String {
        val parti = mutableListOf<String>()
        parti.add("Oggi è " + SimpleDateFormat("EEEE d MMMM", Locale.ITALIAN).format(Date()) + ".")
        if (prefs.getBoolean("mattino_meteo", true)) {
            try { parti.add("Meteo: " + meteo("", "oggi")) } catch (e: Exception) { }
        }
        if (prefs.getBoolean("mattino_calendario", true) &&
            (Build.VERSION.SDK_INT < 23 || ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
        ) {
            try { parti.add(integrazioni.leggiCalendario("oggi", 1)) } catch (e: Exception) { }
        }
        if (prefs.getBoolean("mattino_promemoria", true)) {
            val oggi = Promemoria.promemoriaDiOggi(ctx)
            if (oggi.isNotEmpty()) parti.add("Promemoria di oggi: " + oggi.joinToString("; "))
        }
        if (prefs.getBoolean("mattino_messaggi", true) && NotificheJarvis.abilitato(ctx)) {
            parti.add("Messaggi ricevuti: " + NotificheJarvis.leggi(ctx, ""))
        }
        if (prefs.getBoolean("mattino_notizie", true)) {
            val categoria = prefs.getString("mattino_categoria", "principali") ?: "principali"
            parti.add(integrazioni.notizie(categoria, "", 5))
        }
        return parti.joinToString("\n\n")
    }

    // ---------- meteo (Open-Meteo, gratuito e senza chiave) ----------

    private fun meteo(cittaRichiesta: String, giorno: String): String {
        val citta = cittaRichiesta.trim().ifBlank {
            prefs.getString("citta", CITTA_PREDEFINITA) ?: CITTA_PREDEFINITA
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

    private fun descriviMeteo(codice: Int): String = when (codice) {
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

    // ---------- volume e musica ----------

    private fun regolaVolume(azione: String, passi: Int): String = suUi {
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

    private val APP_MUSICA = linkedMapOf(
        "spotify" to "com.spotify.music",
        "youtube music" to "com.google.android.apps.youtube.music",
        "youtube" to "com.google.android.youtube",
        "amazon music" to "com.amazon.mp3",
        "apple music" to "com.apple.android.music",
        "deezer" to "deezer.android.app"
    )

    private fun installata(pacchetto: String) = try {
        ctx.packageManager.getLaunchIntentForPackage(pacchetto) != null
    } catch (e: Exception) {
        false
    }

    /** L'app musicale da usare: quella chiesta, quella preferita, oppure la prima installata. */
    private fun appMusica(richiesta: String): Pair<String, String>? {
        val r = richiesta.lowercase(Locale.ITALIAN).trim()
        APP_MUSICA.entries.firstOrNull { r.isNotEmpty() && r.contains(it.key) && installata(it.value) }?.let { return it.key to it.value }
        val preferita = prefs.getString("app_musica", "") ?: ""
        APP_MUSICA.entries.firstOrNull { it.key == preferita && installata(it.value) }?.let { return it.key to it.value }
        return APP_MUSICA.entries.firstOrNull { installata(it.value) }?.let { it.key to it.value }
    }

    private fun premiTastoMusica(tasto: Int) {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, tasto))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, tasto))
    }

    private fun musicaInRiproduzione() = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive

    /** Fa partire una canzone, un artista, un album, una playlist o un genere nell'app musicale. */
    private fun riproduci(cosa: String, app: String): String {
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

    private fun controllaMusica(azione: String): String {
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

    private fun controllaMusicaTasti(azione: String): String = suUi {
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

    private fun prendiNota(testo: String): String {
        if (testo.isBlank()) return "La nota è vuota."
        val data = SimpleDateFormat("dd/MM HH:mm", Locale.ITALIAN).format(Date())
        val note = leggiLista("note")
        note.add("$data - ${testo.trim()}")
        salvaLista("note", note)
        return "Nota salvata."
    }

    private fun leggiNote(quante: Int): String {
        val note = leggiLista("note")
        if (note.isEmpty()) return "Non ci sono note."
        return note.takeLast(quante.coerceIn(1, 20)).joinToString("\n")
    }

    // ---------- memoria: ricordi, lezioni, dimenticare ----------

    private fun ricorda(informazione: String): String =
        if (aggiungiSenzaDoppioni("memoria", informazione, MAX_RICORDI)) "Memorizzato." else "Lo sapevo già."

    private fun imparaLezione(lezione: String): String =
        if (aggiungiSenzaDoppioni("lezioni", lezione, MAX_LEZIONI)) "Lezione imparata." else "La conoscevo già."

    private fun dimentica(parola: String): String {
        val p = parola.lowercase(Locale.ITALIAN).trim()
        if (p.isEmpty()) return "Dimmi cosa devo dimenticare."
        if (p == "tutto") {
            prefs.edit().remove("memoria").remove("lezioni").remove("routine").remove("riassunto").apply()
            return "Ho cancellato ricordi, lezioni, routine e riassunto."
        }
        var tolti = 0
        for (chiave in listOf("memoria", "lezioni")) {
            val prima = leggiLista(chiave)
            val dopo = prima.filterNot { it.lowercase(Locale.ITALIAN).contains(p) }
            tolti += prima.size - dopo.size
            salvaLista(chiave, dopo)
        }
        val routine = leggiRoutine()
        val rimaste = JSONArray()
        for (i in 0 until routine.length()) {
            val r = routine.getJSONObject(i)
            if (r.toString().lowercase(Locale.ITALIAN).contains(p)) tolti++ else rimaste.put(r)
        }
        prefs.edit().putString("routine", rimaste.toString()).apply()
        return if (tolti > 0) "Fatto, l'ho dimenticato." else "Non avevo niente su $parola."
    }
}
