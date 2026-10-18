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

        private const val MAX_RICORDI = 300
        private const val MAX_LEZIONI = 80
        private const val MAX_PASSAGGI = 25
        private const val MAX_DIARIO = 300
        private const val RIFLESSIONE_OGNI = 6

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
    class Risposta(val testo: String, val fonti: List<String>, val mancaChiave: Boolean = false)

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
        Plugin("vista", "Vista: foto e fotocamera dal vivo, testi letti e tradotti",
            listOf("guarda", "vista_dal_vivo")),
        Plugin("mattino", "Riepilogo del mattino",
            listOf("riepilogo_giornata")),
        Plugin("note", "Note",
            listOf("prendi_nota", "leggi_note")),
        Plugin("casa", "Casa intelligente: Home Assistant e scorciatoie web",
            listOf("casa_dispositivi", "casa_comando", "esegui_scorciatoia")),
        Plugin("email", "Email: leggere, scrivere e rispondere",
            listOf("leggi_email", "invia_email", "rispondi_email")),
        Plugin("pc", "Collegamento al PC Windows",
            listOf("pc", "pc_accendi"))
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
    private val casa by lazy { Casa(prefs) }
    private val posta by lazy { Posta(prefs) }
    private val pc by lazy { PontePc(prefs) }

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

    // Risposta in corso: dove mandare i pezzi di testo man mano che arrivano (per parlare subito)
    @Volatile private var pezzoAttuale: ((String) -> Unit)? = null
    @Volatile private var pezziInviati = false
    @Volatile private var richiestaCorrente = ""

    /**
     * Risponde a una richiesta. Va chiamata da un thread in background.
     * @param alPezzo se presente, riceve il testo della risposta man mano che arriva
     */
    fun rispondi(testo: String, alPezzo: ((String) -> Unit)? = null): Risposta = synchronized(blocco) {
        turno++
        strumentiUsati.clear()
        fontiUltimaRisposta.clear()
        richiestaCorrente = testo
        val inizio = System.currentTimeMillis()

        // 1. Comandi semplici: subito, anche offline e senza chiave
        val rapida = if (prefs.getBoolean("comandi_rapidi", true)) {
            try { ComandiRapidi.prova(this, testo) } catch (e: Exception) { null }
        } else null
        if (rapida != null) {
            scriviNelDiario(testo, rapida, "rapida")
            registraScambioLocale(testo, rapida)
            salvaConversazioni()
            Statistiche.richiesta(ctx, "rapida", System.currentTimeMillis() - inizio, strumentiUsati)
            return Risposta(rapida, emptyList())
        }
        if (chiaveAttiva().isEmpty()) {
            return Risposta("Manca la chiave API: apri Jarvis e inseriscila in Cervello. Intanto posso fare i comandi base, " +
                "come torcia, volume, sveglie, timer, musica e chiamate.", emptyList(), mancaChiave = true)
        }
        // 2. Senza internet il cervello non è raggiungibile
        if (!Orecchie.online(ctx)) {
            Statistiche.richiesta(ctx, "offline", 0, emptyList())
            return Risposta("Sono offline. Senza internet posso fare solo i comandi base: torcia, volume, sveglie, timer, " +
                "musica, chiamate, note, l'ora e la batteria.", emptyList())
        }
        // 3. Il cervello vero e proprio
        pezzoAttuale = alPezzo
        pezziInviati = false
        var errore = false
        val r = try {
            if (usaClaude()) conversaClaudeConRecupero(testo) else conversaGeminiConRecupero(testo)
        } catch (e: Exception) {
            errore = true
            "Scusa, c'è stato un problema: ${e.message}"
        } finally {
            pezzoAttuale = null
        }
        val fonti = fontiUltimaRisposta.toList()
        scriviNelDiario(testo, r)
        salvaConversazioni()
        Statistiche.richiesta(ctx, if (errore) "errore" else "ia", System.currentTimeMillis() - inizio, strumentiUsati)
        Risposta(r, fonti)
    }

    /** Un comando eseguito senza IA entra comunque nella conversazione, così il cervello sa cosa è successo. */
    private fun registraScambioLocale(domanda: String, risposta: String) {
        if (messaggi.length() == 0) messaggi.put(JSONObject().put("role", "system").put("content", ""))
        messaggi.put(JSONObject().put("role", "user").put("content", domanda))
        messaggi.put(JSONObject().put("role", "assistant").put("content", risposta))
        val ultimo = if (messaggiClaude.length() > 0) messaggiClaude.optJSONObject(messaggiClaude.length() - 1) else null
        if (ultimo == null || ultimo.optString("role") == "assistant") {
            messaggiClaude.put(JSONObject().put("role", "user").put("content", domanda))
            messaggiClaude.put(JSONObject().put("role", "assistant").put("content", risposta))
        }
    }

    /** Ricarica la conversazione dai file (dopo il ripristino di un backup). */
    fun ricarica() = synchronized(blocco) {
        svuota(messaggi)
        svuota(messaggiClaude)
        caricaConversazioni()
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

    /** Il diario intero (per le statistiche). */
    fun diario(): JSONArray = leggiDiario()

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
        val tuttiRicordi = leggiLista("memoria")
        val ricordi = ricordiPertinenti(tuttiRicordi)
        val lezioni = ricordiPertinenti(leggiLista("lezioni"), 30)
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
        if (prefs.getBoolean("in_auto", false)) {
            p.append("SITUAZIONE: l'utente è IN AUTO e sta guidando. Rispondi con al massimo due frasi brevi, niente domande ")
            p.append("di approfondimento, non chiedergli di guardare o toccare lo schermo. Per la strada usa naviga.\n\n")
        }
        p.append("STILE DI CONVERSAZIONE\n")
        p.append("- Sii partecipe e curioso. Quando ha senso, fai UNA breve domanda di approfondimento o proponi un passo ")
        p.append("successivo utile; non farlo quando l'utente vuole solo un comando veloce (sveglia, torcia, volume).\n")
        p.append("- Collega le risposte a ciò che sai di lui e alle conversazioni passate.\n")
        p.append("- Quando nella conversazione emergono gusti, abitudini, progetti, persone importanti o opinioni, salvali con ricorda; ")
        p.append("quando ti corregge o preferisce un modo diverso, salva una lezione con impara_lezione.\n")
        p.append("- Alcune azioni rispondono \"NON ANCORA ESEGUITO\": in quel caso spiega all'utente cosa farai e chiedi conferma. ")
        p.append("Chiama conferma_azione solo DOPO la sua risposta, mai nello stesso turno in cui hai chiesto.\n\n")

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
            p.append("- Per cancellare appuntamenti usa cancella_appuntamenti: NON elencarli, di' solo quanti sono e segui le due conferme richieste. ")
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
            p.append("- Se l'utente ti chiede di guardare, leggere o tradurre qualcosa che ha davanti, usa guarda (una foto). ")
            p.append("Se vuole mostrarti qualcosa dal vivo o farti più domande su ciò che inquadra, usa vista_dal_vivo.\n")
        }
        if (pluginAttivo("mattino")) {
            p.append("- Per il riepilogo del mattino usa riepilogo_giornata e raccontalo come un buongiorno, in poche frasi.\n")
        }
        p.append("- Apri app, chiama e invia messaggi direttamente, senza chiedere conferma.\n")
        if (pluginAttivo("casa")) {
            p.append("- Per luci, prese, termostato, tapparelle e altri dispositivi di casa usa casa_comando (prima casa_dispositivi se non sai il nome esatto); ")
            p.append("per le azioni personalizzate dell'utente usa esegui_scorciatoia.\n")
        }
        if (pluginAttivo("email")) {
            p.append("- Per le email usa leggi_email, invia_email e rispondi_email. Riassumi le email a voce, non leggerle parola per parola.\n")
        }
        if (pluginAttivo("pc")) {
            p.append("- Per il computer dell'utente usa pc (aprire programmi o siti, volume, musica, bloccare, spegnere, scrivere testo) e pc_accendi per accenderlo.\n")
        }
        p.append("\nRICHIESTE IN PIÙ PASSAGGI\n")
        p.append("- Se la richiesta ha più parti (per esempio \"prenota un tavolo e scrivilo a Marco\"), dividila in passi ed eseguili tutti, uno alla volta.\n")
        p.append("- Dopo ogni azione su un'altra app guarda lo SCHERMO DOPO L'AZIONE che ricevi: controlla che il passo sia riuscito prima di andare avanti.\n")
        p.append("- Se un passo non riesce, prova un'altra strada (scorri, cerca il pulsante con un altro nome, torna indietro, usa attendi se l'app sta caricando).\n")
        p.append("- Non fermarti a metà e non dire \"fatto\" finché tutte le parti sono completate; alla fine riassumi in una frase cosa hai fatto.\n")
        p.append("- ACQUISTI: prima di comprare, pagare, ordinare o abbonarti a qualcosa chiedi SEMPRE due conferme ")
        p.append("all'utente (cosa, prezzo se lo vedi, e poi \"è sicuro?\"). Il tocco su pulsanti di pagamento richiede comunque conferma.\n")
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
        val simili = richiestePassateSimili()
        if (simili.isNotBlank()) p.append("Richieste passate simili a quella di adesso: $simili\n")
        if (tuttiRicordi.size > ricordi.size) {
            p.append("Qui vedi solo i ricordi pertinenti (${ricordi.size} su ${tuttiRicordi.size}): se ti serve altro, usa cerca_ricordi.\n")
        }
        val luoghi = integrazioni.descriviLuoghi()
        if (luoghi.isNotBlank()) p.append("Luoghi salvati: $luoghi.\n")
        if (ricordi.isEmpty() && lezioni.isEmpty() && routine.length() == 0 && riassunto.isBlank()) {
            p.append("Ancora vuota: è una delle prime conversazioni.\n")
        }

        p.append("\nPlugin attivi: ").append(pluginAttivi.joinToString(", ") { it.nome }).append(".")
        return p.toString()
    }

    // ================================================================
    //  MEMORIA PERTINENTE: solo i ricordi che servono per la richiesta di adesso
    // ================================================================

    private val PAROLE_VUOTE = setOf(
        "della", "delle", "degli", "dello", "nella", "nelle", "sono", "come", "cosa", "anche", "questo", "questa",
        "quello", "quella", "perché", "quando", "dove", "allora", "molto", "tutto", "tutti", "fammi", "dimmi",
        "puoi", "vorrei", "voglio", "jarvis", "utente", "sempre", "ogni", "essere", "fare", "avere", "dopo", "prima"
    )

    private fun radici(testo: String): Set<String> = testo.lowercase(Locale.ITALIAN)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 3 && it !in PAROLE_VUOTE }
        .map { it.take(5) }
        .toSet()

    /** Fatti di base da avere sempre presenti (nome, famiglia, casa, lavoro). */
    private val PAROLE_BASE = listOf("chiama", "nome", "vive", "abita", "lavor", "moglie", "marito", "figli", "compagn", "fidanzat", "preferisc", "non vuole", "odia")

    private fun ricordiPertinenti(tutti: List<String>, massimo: Int = 25): List<String> {
        if (tutti.size <= massimo) return tutti
        val chiave = radici(richiestaCorrente + " " + ultimaDomandaPrecedente())
        val pertinenti = tutti.map { it to radici(it).count { r -> r in chiave } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(massimo / 2)
            .map { it.first }
        val base = tutti.filter { r -> PAROLE_BASE.any { r.lowercase(Locale.ITALIAN).contains(it) } }.take(8)
        val recenti = tutti.takeLast(6)
        return (base + pertinenti + recenti).distinct()
    }

    private fun ultimaDomandaPrecedente(): String {
        val diario = leggiDiario()
        return diario.optJSONObject(diario.length() - 1)?.optString("d") ?: ""
    }

    /** Le 3 richieste del diario più simili a quella di adesso (escluse le ultimissime, già nella conversazione). */
    private fun richiestePassateSimili(): String {
        val chiave = radici(richiestaCorrente)
        if (chiave.size < 2) return ""
        val diario = leggiDiario()
        val fine = (diario.length() - 6).coerceAtLeast(0)
        val trovate = (0 until fine).mapNotNull { diario.optJSONObject(it) }
            .map { it to radici(it.optString("d")).count { r -> r in chiave } }
            .filter { it.second >= 2 }
            .sortedByDescending { it.second }
            .take(3)
        return trovate.joinToString("; ") { (v, _) -> "${v.optString("t")} \"${v.optString("d")}\" → ${v.optString("r").take(100)}" }
    }

    private fun cercaRicordi(testo: String): String {
        val chiave = radici(testo)
        val tutti = leggiLista("memoria") + leggiLista("lezioni")
        val trovati = tutti.filter { r -> chiave.isEmpty() || radici(r).any { it in chiave } || r.contains(testo, ignoreCase = true) }
        return if (trovati.isEmpty()) "Non ho ricordi su \"$testo\"." else trovati.take(20).joinToString("; ")
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

            repeat(MAX_PASSAGGI) {
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
        val alPezzo = pezzoAttuale
        if (alPezzo != null && prefs.getBoolean("streaming", true)) {
            try {
                return postClaudeFlusso(corpo, alPezzo)
            } catch (e: ErroreApi) {
                throw e
            } catch (e: Exception) {
                if (pezziInviati) throw e       // aveva già iniziato a parlare: niente secondo tentativo
            }
        }
        return postClaude(corpo)
    }

    /** Risposta di Claude in streaming: il testo arriva a pezzi e Jarvis inizia a parlare subito. */
    private fun postClaudeFlusso(corpo: JSONObject, alPezzo: (String) -> Unit): JSONObject {
        val chiave = (prefs.getString("chiave_claude", "") ?: "").trim()
        val connessione = URL(URL_CLAUDE).openConnection() as HttpURLConnection
        connessione.requestMethod = "POST"
        connessione.connectTimeout = 15000
        connessione.readTimeout = 90000
        connessione.doOutput = true
        connessione.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connessione.setRequestProperty("Accept", "text/event-stream")
        connessione.setRequestProperty("x-api-key", chiave)
        connessione.setRequestProperty("anthropic-version", "2023-06-01")
        val richiesta = JSONObject(corpo.toString()).put("stream", true)
        connessione.outputStream.use { it.write(richiesta.toString().toByteArray(Charsets.UTF_8)) }

        val codice = connessione.responseCode
        if (codice !in 200..299) {
            val testo = connessione.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            connessione.disconnect()
            throw erroreClaude(codice, testo)
        }
        val blocchi = java.util.TreeMap<Int, JSONObject>()
        val jsonParziali = HashMap<Int, StringBuilder>()
        var motivo = ""
        try {
            connessione.inputStream.bufferedReader(Charsets.UTF_8).use { lettore ->
                while (true) {
                    val riga = lettore.readLine() ?: break
                    if (!riga.startsWith("data:")) continue
                    val evento = try { JSONObject(riga.substring(5).trim()) } catch (e: Exception) { continue }
                    val indice = evento.optInt("index", 0)
                    when (evento.optString("type")) {
                        "content_block_start" -> {
                            val b = JSONObject(evento.optJSONObject("content_block")?.toString() ?: "{}")
                            blocchi[indice] = b
                            if (b.optString("type") == "tool_use" || b.optString("type") == "server_tool_use") {
                                jsonParziali[indice] = StringBuilder()
                            }
                        }
                        "content_block_delta" -> {
                            val b = blocchi[indice] ?: continue
                            val d = evento.optJSONObject("delta") ?: continue
                            when (d.optString("type")) {
                                "text_delta" -> {
                                    val t = d.optString("text")
                                    b.put("text", b.optString("text") + t)
                                    if (t.isNotEmpty()) {
                                        pezziInviati = true
                                        alPezzo(t)
                                    }
                                }
                                "input_json_delta" -> jsonParziali[indice]?.append(d.optString("partial_json"))
                                "citations_delta" -> {
                                    val elenco = b.optJSONArray("citations") ?: JSONArray().also { b.put("citations", it) }
                                    d.optJSONObject("citation")?.let { elenco.put(it) }
                                }
                                "thinking_delta" -> b.put("thinking", b.optString("thinking") + d.optString("thinking"))
                                "signature_delta" -> b.put("signature", d.optString("signature"))
                            }
                        }
                        "content_block_stop" -> {
                            val parziale = jsonParziali.remove(indice)
                            val b = blocchi[indice]
                            if (parziale != null && b != null) {
                                b.put("input", if (parziale.isBlank()) JSONObject() else try {
                                    JSONObject(parziale.toString())
                                } catch (e: Exception) {
                                    JSONObject()
                                })
                            }
                        }
                        "message_delta" -> {
                            val m = evento.optJSONObject("delta")?.optString("stop_reason") ?: ""
                            if (m.isNotEmpty() && m != "null") motivo = m
                        }
                        "error" -> throw ErroreApi(529, "i server di Claude sono occupati, riprova tra poco.")
                    }
                }
            }
        } finally {
            connessione.disconnect()
        }
        val contenuto = JSONArray()
        for (b in blocchi.values) {
            if (b.has("citations") && b.isNull("citations")) b.remove("citations")
            contenuto.put(b)
        }
        return JSONObject().put("content", contenuto).put("stop_reason", motivo)
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

        if (codice !in 200..299) throw erroreClaude(codice, risposta)
        return JSONObject(risposta)
    }

    private fun erroreClaude(codice: Int, risposta: String): ErroreApi {
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
        return ErroreApi(codice, messaggio, ripetibile)
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

            repeat(MAX_PASSAGGI) {
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

        val alPezzo = pezzoAttuale
        var inFlusso: JSONObject? = null
        if (alPezzo != null && prefs.getBoolean("streaming", true)) {
            try {
                inFlusso = postGeminiFlusso(corpo, alPezzo)
            } catch (e: ErroreApi) {
                throw e
            } catch (e: Exception) {
                if (pezziInviati) throw e       // aveva già iniziato a parlare: niente secondo tentativo
            }
        }
        val messaggio: JSONObject = inFlusso
            ?: postGemini(corpo).getJSONArray("choices").getJSONObject(0).getJSONObject("message")

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

    /** Risposta di Gemini in streaming: il testo arriva a pezzi e Jarvis inizia a parlare subito. */
    private fun postGeminiFlusso(corpo: JSONObject, alPezzo: (String) -> Unit): JSONObject {
        val base = (prefs.getString("base_url", URL_PREDEFINITO) ?: URL_PREDEFINITO).trim().trimEnd('/')
        val chiave = (prefs.getString("api_key", "") ?: "").trim()
        val connessione = URL("$base/chat/completions").openConnection() as HttpURLConnection
        connessione.requestMethod = "POST"
        connessione.connectTimeout = 15000
        connessione.readTimeout = 60000
        connessione.doOutput = true
        connessione.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connessione.setRequestProperty("Accept", "text/event-stream")
        connessione.setRequestProperty("Authorization", "Bearer $chiave")
        val richiesta = JSONObject(corpo.toString()).put("stream", true)
        connessione.outputStream.use { it.write(richiesta.toString().toByteArray(Charsets.UTF_8)) }

        val codice = connessione.responseCode
        if (codice !in 200..299) {
            val testo = connessione.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            connessione.disconnect()
            throw erroreGemini(codice, testo)
        }
        val testo = StringBuilder()
        val chiamate = mutableListOf<JSONObject>()
        val perIndice = HashMap<Int, JSONObject>()
        var extra: Any? = null
        try {
            connessione.inputStream.bufferedReader(Charsets.UTF_8).use { lettore ->
                while (true) {
                    val riga = lettore.readLine() ?: break
                    if (!riga.startsWith("data:")) continue
                    val dati = riga.substring(5).trim()
                    if (dati == "[DONE]") break
                    if (dati.isEmpty()) continue
                    val pezzo = try { JSONObject(dati) } catch (e: Exception) { continue }
                    pezzo.optJSONObject("error")?.let { throw ErroreApi(500, "errore di Gemini: ${it.optString("message")}") }
                    val delta = pezzo.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    if (delta.has("extra_content")) extra = delta.get("extra_content")
                    val contenuto = if (delta.isNull("content")) "" else delta.optString("content")
                    if (contenuto.isNotEmpty()) {
                        testo.append(contenuto)
                        pezziInviati = true
                        alPezzo(contenuto)
                    }
                    val nuove = delta.optJSONArray("tool_calls") ?: continue
                    for (i in 0 until nuove.length()) {
                        val d = nuove.optJSONObject(i) ?: continue
                        val id = d.optString("id")
                        val indice = if (d.has("index")) d.optInt("index") else -1
                        var voce: JSONObject? = if (indice >= 0) perIndice[indice] else null
                        // stesso indice ma id diverso: è una chiamata nuova
                        if (voce != null && id.isNotEmpty() && voce.optString("id").isNotEmpty() && voce.optString("id") != id) voce = null
                        if (voce == null && indice < 0 && id.isEmpty()) voce = chiamate.lastOrNull()
                        if (voce == null) {
                            voce = JSONObject().put("id", id).put("type", "function")
                                .put("function", JSONObject().put("name", "").put("arguments", ""))
                            chiamate.add(voce)
                            if (indice >= 0) perIndice[indice] = voce
                        }
                        if (id.isNotEmpty()) voce.put("id", id)
                        d.optJSONObject("function")?.let { f ->
                            val fn = voce.getJSONObject("function")
                            if (f.has("name") && !f.isNull("name")) fn.put("name", fn.optString("name") + f.optString("name"))
                            if (f.has("arguments") && !f.isNull("arguments")) fn.put("arguments", fn.optString("arguments") + f.optString("arguments"))
                        }
                        if (d.has("extra_content")) voce.put("extra_content", d.get("extra_content"))
                    }
                }
            }
        } finally {
            connessione.disconnect()
        }
        val messaggio = JSONObject().put("role", "assistant")
        messaggio.put("content", if (testo.isEmpty()) JSONObject.NULL else testo.toString())
        if (chiamate.isNotEmpty()) messaggio.put("tool_calls", JSONArray(chiamate))
        if (extra != null) messaggio.put("extra_content", extra)
        return messaggio
    }

    private fun erroreGemini(codice: Int, risposta: String) = ErroreApi(
        codice,
        when (codice) {
            400 -> "richiesta non valida. ${estraiErrore(risposta)}"
            401, 403 -> "la chiave API non è valida, controllala nelle impostazioni."
            404 -> "modello o indirizzo non trovato, controlla le impostazioni."
            429 -> "ho raggiunto il limite di richieste gratuite, riprova tra un po'."
            else -> "errore $codice. ${estraiErrore(risposta)}"
        }
    )

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

        if (codice !in 200..299) throw erroreGemini(codice, risposta)
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

    private fun scriviNelDiario(domanda: String, risposta: String, tipo: String = "ia") {
        val voce = JSONObject()
            .put("t", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ITALIAN).format(Date()))
            .put("d", domanda)
            .put("r", risposta.take(300))
            .put("s", JSONArray(strumentiUsati.distinct()))
            .put("k", tipo)
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

    private fun conversazioneRecente(): String = synchronized(blocco) {
        val array = if (usaClaude()) messaggiClaude else messaggi
        val da = (array.length() - 20).coerceAtLeast(0)
        testoDellaConversazione((da until array.length()).mapNotNull { array.optJSONObject(it) }).takeLast(3000)
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
            "Conversazione recente:\n${conversazioneRecente()}\n\n" +
            "Impara soprattutto dalle discussioni: interessi, progetti, persone, opinioni e preferenze emerse parlando.\n" +
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
                listOf(p("parola", "string", "Parola da cercare, oppure tutto")), listOf("parola")),
            strumento("cerca_ricordi", "Cerca tra tutti i ricordi e le lezioni salvati, anche quelli che non vedi nel contesto.",
                listOf(p("testo", "string", "Parole da cercare")), listOf("testo")),
            strumento("attendi", "Aspetta qualche secondo, per esempio mentre un'app si carica, poi rileggi lo schermo.",
                listOf(p("secondi", "integer", "Da 1 a 10")), listOf("secondi")),
            // --- Vista dal vivo ---
            strumento("vista_dal_vivo", "Apre la fotocamera dal vivo: l'utente inquadra e fa domande su quello che vede in tempo reale.",
                listOf(p("domanda", "string", "Prima domanda, facoltativa"))),
            // --- Casa ---
            strumento("casa_dispositivi", "Elenca i dispositivi di casa (luci, prese, termostati, tapparelle...) e il loro stato.",
                listOf(p("filtro", "string", "Stanza o tipo, es. luci, cucina; vuoto per tutti"))),
            strumento("casa_comando", "Comanda un dispositivo di casa intelligente.",
                listOf(
                    p("dispositivo", "string", "Nome del dispositivo o della stanza, es. luce cucina"),
                    p("azione", "string", "accendi, spegni, alterna, apri, chiudi, imposta, attiva"),
                    p("valore", "string", "Facoltativo: luminosità o posizione in percentuale, temperatura in gradi")
                ), listOf("dispositivo", "azione")),
            strumento("esegui_scorciatoia", "Esegue una scorciatoia web creata dall'utente (Home Assistant, IFTTT, Shelly, Alexa...).",
                listOf(p("nome", "string", "Nome della scorciatoia")), listOf("nome")),
            // --- Email ---
            strumento("leggi_email", "Legge le ultime email ricevute.",
                listOf(
                    p("quante", "integer", "Quante email, di solito 5"),
                    p("solo_non_lette", "boolean", "true per leggere solo quelle non lette"),
                    p("cerca", "string", "Mittente o parola da cercare, facoltativo")
                )),
            strumento("invia_email", "Invia una email.",
                listOf(
                    p("destinatario", "string", "Indirizzo email o nome di un contatto in rubrica"),
                    p("oggetto", "string", "Oggetto"),
                    p("testo", "string", "Testo dell'email")
                ), listOf("destinatario", "testo")),
            strumento("rispondi_email", "Risponde a una email ricevuta (usa il numero restituito da leggi_email).",
                listOf(
                    p("numero", "integer", "Numero dell'email tra parentesi quadre in leggi_email"),
                    p("testo", "string", "Testo della risposta")
                ), listOf("numero", "testo")),
            // --- PC ---
            strumento("pc", "Comanda il computer Windows dell'utente.",
                listOf(
                    p("azione", "string", "apri, sito, cerca, volume_su, volume_giu, muto, play_pausa, avanti, indietro, " +
                        "blocca, spegni, riavvia, annulla_spegnimento, scrivi, copia, messaggio oppure stato"),
                    p("valore", "string", "Programma, sito, testo o messaggio, se serve")
                ), listOf("azione")),
            strumento("pc_accendi", "Accende il computer da remoto (Wake-on-LAN).",
                listOf(p("motivo", "string", "Facoltativo"))),
            strumento("conferma_azione",
                "Comunica la risposta dell'utente a un'azione in attesa di conferma (apertura di un'app, cancellazioni).",
                listOf(p("risposta", "string", "sì se l'utente ha confermato, no se ha rifiutato")), listOf("risposta"))
        )
        val attivi = JSONArray()
        for (s in tutti) {
            if (strumentoAttivo(s.getJSONObject("function").getString("name"))) attivi.put(s)
        }
        return attivi
    }

    // ================================================================
    //  CONFERME: prima di aprire app e (due volte) prima di cancellare
    // ================================================================

    private class AzioneInSospeso(
        val nome: String,
        val argomenti: JSONObject,
        var fase: Int,
        val fasiTotali: Int,
        val turno: Int,
        val creata: Long = System.currentTimeMillis()
    )

    @Volatile private var turno = 0
    @Volatile private var inSospeso: AzioneInSospeso? = null

    /** Pulsanti che spendono soldi: per toccarli servono due conferme dell'utente. */
    private val PAROLE_ACQUISTO = listOf(
        "acquist", "compra", "paga", "pagamento", "ordina", "ordine", "checkout", "cassa", "abbonati", "abbonamento",
        "noleggia", "prenota e paga", "conferma e paga", "buy", "pay", "purchase", "place order", "subscribe", "rent"
    )

    private fun eUnAcquisto(testo: String): Boolean {
        val t = testo.lowercase(Locale.ITALIAN)
        return PAROLE_ACQUISTO.any { t.contains(it) }
    }

    /** Strumenti che aprono un'altra app: solo se l'utente attiva la conferma in CARATTERE. */
    private val STRUMENTI_CHE_APRONO_APP = setOf(
        "apri_app", "apri_sito", "cerca_sul_web", "cerca_su_youtube", "naviga",
        "riproduci", "chiama", "scrivi_sms", "invia_whatsapp"
    )

    private fun eseguiStrumento(nome: String, a: JSONObject): String {
        if (!strumentoAttivo(nome)) return "Questo plugin è disattivato nelle impostazioni."
        return when {
            nome == "conferma_azione" -> confermaAzione(a.optString("risposta"))
            nome == "cancella_appuntamenti" -> preparaCancellazione(a)
            nome == "tocca" && eUnAcquisto(a.optString("testo")) -> {
                inSospeso = AzioneInSospeso(nome, a, 1, 2, turno)
                "NON ANCORA ESEGUITO: \"${a.optString("testo")}\" è un pulsante di acquisto o pagamento. " +
                    "Di' all'utente cosa sta per comprare (e il prezzo, se lo hai letto sullo schermo) e chiedi la prima conferma."
            }
            nome in STRUMENTI_CHE_APRONO_APP && prefs.getBoolean("conferma_app", false) -> {
                inSospeso = AzioneInSospeso(nome, a, 1, 1, turno)
                "NON ANCORA ESEGUITO. Prima di aprire l'app spiega all'utente in una frase cosa farai " +
                    "(quale app aprirai e cosa farai lì) e chiedi se può procedere. Aspetta la sua risposta."
            }
            else -> conSchermo(nome, eseguiStrumentoDiretto(nome, a))
        }
    }

    /** Ciclo di controllo: dopo un'azione su un'altra app, il cervello vede subito com'è lo schermo. */
    private fun conSchermo(nome: String, risultato: String): String {
        val acc = AccessibilitaJarvis.istanza ?: return risultato
        if (nome !in setOf("tocca", "scrivi_testo", "scorri", "azione_sistema", "apri_app", "invia_whatsapp")) return risultato
        if (!pluginAttivo("controllo")) return risultato
        return try {
            risultato + "\n\nSCHERMO DOPO L'AZIONE:\n" + acc.leggiSchermo().take(2500)
        } catch (e: Exception) {
            risultato
        }
    }

    private fun preparaCancellazione(a: JSONObject): String {
        val quanti = integrazioni.contaEventi(a.optString("cerca"), a.optString("giorno"), a.optInt("giorni", 1))
        if (quanti < 0) return chiediPermessoGenerico(Manifest.permission.WRITE_CALENDAR, "modificare il calendario")
        if (quanti == 0) return "Non trovo appuntamenti da cancellare con questi criteri."
        inSospeso = AzioneInSospeso("cancella_appuntamenti", a, 1, 2, turno)
        return "NON ANCORA ESEGUITO. Ho trovato $quanti appuntamenti: NON elencarli. " +
            "Chiedi all'utente la prima conferma, per esempio: \"Vuole che cancelli $quanti appuntamenti?\""
    }

    private fun confermaAzione(risposta: String): String {
        val azione = inSospeso ?: return "Non c'è nessuna azione in attesa di conferma."
        if (azione.turno == turno) {
            return "Devi prima aspettare la risposta dell'utente: chiedigli conferma e fermati."
        }
        if (System.currentTimeMillis() - azione.creata > 5 * 60_000L) {
            inSospeso = null
            return "La richiesta è scaduta: chiedi all'utente di ripeterla."
        }
        val r = risposta.lowercase(Locale.ITALIAN).trim()
        val si = listOf("sì", "si", "ok", "va bene", "procedi", "confermo", "certo", "yes", "vai", "esatto", "d'accordo")
            .any { r == it || r.startsWith("$it ") || r.startsWith("$it,") }
        if (!si) {
            inSospeso = null
            return "Annullato: non ho fatto nulla."
        }
        if (azione.fase < azione.fasiTotali) {
            azione.fase++
            val esempio = if (azione.nome == "cancella_appuntamenti") "\"È sicuro? La cancellazione è definitiva e non si può annullare.\""
            else "\"È sicuro di voler procedere con l'acquisto?\""
            return "Prima conferma ricevuta, ancora NON ESEGUITO. Ora chiedi la SECONDA conferma, per esempio: " +
                "$esempio Poi aspetta la risposta."
        }
        inSospeso = null
        return if (azione.nome == "cancella_appuntamenti") {
            integrazioni.cancellaEventi(
                azione.argomenti.optString("cerca"), azione.argomenti.optString("giorno"), azione.argomenti.optInt("giorni", 1)
            )
        } else {
            eseguiStrumentoDiretto(azione.nome, azione.argomenti)
        }
    }

    private fun eseguiStrumentoDiretto(nome: String, a: JSONObject): String {
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
            "cerca_ricordi" -> cercaRicordi(a.optString("testo"))
            "attendi" -> {
                Thread.sleep(a.optInt("secondi", 2).coerceIn(1, 10) * 1000L)
                "Ho aspettato."
            }
            "vista_dal_vivo" -> avvia(
                Intent(ctx, VistaDalVivo::class.java).putExtra("domanda", a.optString("domanda")),
                "Ho aperto la fotocamera dal vivo: l'utente ora inquadra e ti fa domande da lì."
            )
            "casa_dispositivi" -> casa.elenca(a.optString("filtro"))
            "casa_comando" -> casa.comanda(a.optString("dispositivo"), a.optString("azione"), a.optString("valore"))
            "esegui_scorciatoia" -> casa.scorciatoia(a.optString("nome"))
            "leggi_email" -> posta.leggi(a.optInt("quante", 5), a.optBoolean("solo_non_lette", false), a.optString("cerca"))
            "invia_email" -> {
                val indirizzo = trovaEmail(a.optString("destinatario"))
                    ?: return "Non trovo l'indirizzo email di ${a.optString("destinatario")} in rubrica."
                posta.invia(indirizzo, a.optString("oggetto"), a.optString("testo"))
            }
            "rispondi_email" -> posta.rispondi(a.optLong("numero", -1L), a.optString("testo"))
            "pc" -> pc.comando(a.optString("azione"), a.optString("valore"))
            "pc_accendi" -> pc.accendi()
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

    private fun apriUrl(url: String, messaggioOk: String): String =
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

    /** Indirizzo email: scritto direttamente oppure cercato in rubrica per nome. */
    private fun trovaEmail(chi: String): String? {
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

    /** Vista dal vivo: descrive un fotogramma della fotocamera (usata da VistaDalVivo). */
    fun descriviFotogramma(base64: String, domanda: String, contesto: String): String {
        val richiesta = "Sei gli occhi di Jarvis, un assistente vocale. L'utente sta inquadrando con la fotocamera " +
            "e chiede: \"${domanda.ifBlank { "cosa vedi?" }}\". " +
            (if (contesto.isNotBlank()) "Domande e risposte precedenti: $contesto. " else "") +
            "Rispondi in italiano, in modo concreto e breve (al massimo 3 frasi, da leggere ad alta voce). " +
            "Se ci sono testi, leggili; se chiede una traduzione, traducili in italiano."
        val inizio = System.currentTimeMillis()
        val r = if (usaClaude()) visioneClaude(base64, richiesta) else visioneGemini(base64, richiesta)
        Statistiche.richiesta(ctx, "vista", System.currentTimeMillis() - inizio, listOf("vista_dal_vivo"))
        return r
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

    internal fun prendiNota(testo: String): String {
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
