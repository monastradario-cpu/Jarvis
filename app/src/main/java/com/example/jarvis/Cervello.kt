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

        internal const val MAX_RICORDI = 300
        internal const val MAX_LEZIONI = 80
        internal const val MAX_PASSAGGI = 25
        internal const val MAX_DIARIO = 300
        internal const val RIFLESSIONE_OGNI = 6

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

    // ---------- i moduli del cervello ----------
    internal val ia by lazy { ClientIA(this) }
    internal val memoria by lazy { Memoria(this) }
    internal val telefono by lazy { AzioniTelefono(this) }
    internal val contenuti by lazy { Contenuti(this) }
    internal val prompt by lazy { PromptJarvis(this) }

    internal val principale = Handler(Looper.getMainLooper())
    private val blocco = Any()
    internal val messaggi = JSONArray()        // conversazione con Gemini (formato OpenAI)
    private val messaggiClaude = JSONArray()  // conversazione con Claude (formato Anthropic)
    internal val strumentiUsati = mutableListOf<String>()
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
            listOf("pc", "pc_accendi")),
        Plugin("sistema", "Non disturbare, suoneria e luminosità",
            listOf("non_disturbare", "suoneria", "luminosita")),
        Plugin("automazioni", "Automazioni: se succede questo, fai quello",
            listOf("crea_automazione", "elenca_automazioni", "elimina_automazione")),
        Plugin("liste", "Liste: spesa, cose da fare...",
            listOf("lista_aggiungi", "lista_leggi", "lista_togli", "lista_invia")),
        Plugin("traduttore", "Traduttore simultaneo",
            listOf("traduttore")),
        Plugin("resoconto", "Resoconto della settimana",
            listOf("resoconto_settimana")),
        Plugin("archivio", "Archivio dei documenti caricati",
            listOf("archivio_cerca", "archivio_leggi", "archivio_elimina"))
    )
    // Gli strumenti della memoria (ricorda, lezioni, routine, diario) sono sempre attivi.

    init {
        caricaConversazioni()
    }

    fun pluginAttivo(id: String) = prefs.getBoolean("plugin_$id", true)

    internal fun strumentoAttivo(nome: String): Boolean {
        val plugin = PLUGIN.firstOrNull { nome in it.strumenti } ?: return true
        return pluginAttivo(plugin.id)
    }

    internal val integrazioni by lazy { Integrazioni(ctx, prefs, ::avvia, ::chiediPermessoGenerico) }
    internal val casa by lazy { Casa(prefs) }
    private val posta by lazy { Posta(prefs) }
    internal val pc by lazy { PontePc(prefs) }

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
    internal fun lanciatore(): Context = AccessibilitaJarvis.istanza ?: ctx

    // ================================================================
    //  FUNZIONI PER LA SCHERMATA E IL SERVIZIO
    // ================================================================

    // Risposta in corso: dove mandare i pezzi di testo man mano che arrivano (per parlare subito)
    @Volatile private var pezzoAttuale: ((String) -> Unit)? = null
    @Volatile internal var richiestaCorrente = ""
    /** Chi sta parlando (riconosciuto dalla voce): vuoto per l'utente principale, altrimenti il nome del familiare. */
    @Volatile var chiParla = ""

    fun nomeAssistente() = (prefs.getString("nome_assistente", "Jarvis") ?: "Jarvis").trim().ifEmpty { "Jarvis" }

    internal fun contesto(): Context = ctx

    // ---------- modalità privata: niente diario, niente ricordi, niente salvataggi ----------
    @Volatile var privata = false
        private set
    private var inizioPrivataGemini = 0
    private var inizioPrivataClaude = 0
    private var ultimaPrivata = 0L
    private var daChiuderePrivata = false

    internal fun modalitaPrivata(attiva: Boolean): String {
        if (attiva) {
            if (!privata) {
                inizioPrivataGemini = (messaggi.length() - 1).coerceAtLeast(1)
                inizioPrivataClaude = (messaggiClaude.length() - 1).coerceAtLeast(0)
                // la conversazione privata parte da una domanda dell'utente: torno all'ultima
                while (inizioPrivataClaude > 0 && !eDomandaUtente(messaggiClaude.getJSONObject(inizioPrivataClaude))) inizioPrivataClaude--
                while (inizioPrivataGemini > 1 && messaggi.getJSONObject(inizioPrivataGemini).optString("role") != "user") inizioPrivataGemini--
            }
            privata = true
            ultimaPrivata = System.currentTimeMillis()
            return "Modalità privata attiva: da ora non ricordo e non salvo niente di quello che diciamo, finché non mi dice di uscire."
        }
        if (!privata) return "La modalità privata non era attiva."
        daChiuderePrivata = true
        return "Modalità privata finita: ho dimenticato tutto quello che ci siamo detti."
    }

    /** Alla fine della risposta: chiude la modalità privata togliendo dalla memoria quella parte di conversazione. */
    private fun chiudiPrivataSeServe() {
        val scaduta = privata && System.currentTimeMillis() - ultimaPrivata > 30 * 60_000L
        if (!daChiuderePrivata && !scaduta) return
        while (messaggi.length() > inizioPrivataGemini && messaggi.length() > 1) messaggi.remove(messaggi.length() - 1)
        while (messaggiClaude.length() > inizioPrivataClaude) messaggiClaude.remove(messaggiClaude.length() - 1)
        privata = false
        daChiuderePrivata = false
    }

    // Se lo streaming dà problemi con un fornitore, per questa sessione si torna alle risposte normali
    @Volatile private var flussoGuasto = false
    private fun usaFlusso() = !flussoGuasto && prefs.getBoolean("streaming", true)

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
        if (privata && inizio - ultimaPrivata > 30 * 60_000L) chiudiPrivataSeServe()
        if (privata) ultimaPrivata = inizio

        // 1. Comandi semplici: subito, anche offline e senza chiave
        val rapida = if (prefs.getBoolean("comandi_rapidi", true)) {
            try { ComandiRapidi.prova(this, testo) } catch (e: Exception) { null }
        } else null
        if (rapida != null) {
            scriviNelDiario(testo, rapida, "rapida")
            registraScambioLocale(testo, rapida)
            chiudiPrivataSeServe()
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
        chiudiPrivataSeServe()
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
        if (privata) return
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

    /** Se la conversazione salvata non è più valida, la azzera e riprova una volta. */
    private fun conversaClaudeConRecupero(testo: String): String = try {
        conversaClaude(testo)
    } catch (e: ErroreApi) {
        if (e.codice == 400 && usaFlusso() && !pezziInviati) {
            flussoGuasto = true
            conversaClaudeConRecupero(testo)
        } else if (e.ripetibile && messaggiClaude.length() > 0) {
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
        if (alPezzo != null && usaFlusso()) {
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

    // ================================================================
    //  CERVELLO GEMINI (formato compatibile OpenAI)
    // ================================================================

    /** Se la conversazione salvata non è più valida, la azzera e riprova una volta. */
    private fun conversaGeminiConRecupero(testo: String): String = try {
        conversa(testo)
    } catch (e: ErroreApi) {
        if (e.codice == 400 && usaFlusso() && !pezziInviati) {
            // forse la conversazione in streaming non piace al modello: riprovo in modo normale
            flussoGuasto = true
            conversaGeminiConRecupero(testo)
        } else if (e.codice == 400 && messaggi.length() > 1) {
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
        if (alPezzo != null && usaFlusso()) {
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

    // ================================================================
    //  MEMORIA: archivio sul telefono
    // ================================================================

    private fun svuota(array: JSONArray) {
        while (array.length() > 0) array.remove(array.length() - 1)
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
        if (privata) return
        scriviFile("conversazione_claude.json", messaggiClaude.toString())
        scriviFile("conversazione_gemini.json", messaggi.toString())
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

    internal fun conversazioneRecente(): String = synchronized(blocco) {
        val array = if (usaClaude()) messaggiClaude else messaggi
        val da = (array.length() - 20).coerceAtLeast(0)
        testoDellaConversazione((da until array.length()).mapNotNull { array.optJSONObject(it) }).takeLast(3000)
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

    @Volatile internal var turno = 0
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
            "non_disturbare" -> suUi { Sistema.nonDisturbare(lanciatore(), a.optBoolean("attiva", true)) }
            "suoneria" -> suUi { Sistema.suoneria(lanciatore(), a.optString("modalita")) }
            "luminosita" -> suUi { Sistema.luminosita(lanciatore(), a.optString("valore")) }
            "crea_automazione" -> {
                if (privata) return "In modalità privata non creo automazioni."
                val tipo = Automazioni.normalizzaTipo(a.optString("quando"))
                    ?: return "Non ho capito quando deve scattare. Posso: a un orario, arrivo o uscita da un luogo, batteria bassa, auto, cuffie, ricarica."
                var luogo: Triple<String, Double, Double>? = null
                if (tipo == "arrivo" || tipo == "uscita") {
                    Promemoria.permessiPosizioneMancanti(ctx)?.let { return chiediPermessoGenerico(it, "usare la posizione") }
                    luogo = integrazioni.coordinate(a.optString("luogo"))
                }
                Automazioni.crea(ctx, a.optString("nome"), tipo, a.optString("valore"), a.optString("giorni"), a.optString("istruzioni"), luogo)
            }
            "elenca_automazioni" -> Automazioni.descriviTutte(ctx)
            "elimina_automazione" -> Automazioni.elimina(ctx, a.optString("parola"))
            "lista_aggiungi" -> Liste.aggiungi(ctx, a.optString("lista"), a.optString("cosa"))
            "lista_leggi" -> Liste.leggi(ctx, a.optString("lista"))
            "lista_togli" -> Liste.togli(ctx, a.optString("lista"), a.optString("cosa"))
            "lista_invia" -> {
                val testoLista = Liste.testo(ctx, a.optString("lista")) ?: return "La lista ${Liste.nome(a.optString("lista"))} è vuota."
                inviaWhatsapp(a.optString("contatto"), testoLista)
            }
            "traduttore" -> avvia(
                Intent(ctx, Traduttore::class.java).putExtra("lingua", a.optString("lingua")),
                "Ho aperto il traduttore simultaneo: l'utente parla in italiano, l'altra persona nella sua lingua."
            )
            "resoconto_settimana" -> resocontoSettimana()
            "archivio_cerca" -> Archivio.cerca(ctx, a.optString("testo"))
            "archivio_leggi" -> {
                val voce = Archivio.trova(ctx, a.optString("documento")) ?: return "Non trovo \"${a.optString("documento")}\" nell'archivio."
                val doc = Archivio.carica(ctx, voce) ?: return "Il file di ${voce.optString("nome")} non c'è più."
                "Da ${voce.optString("nome")}: " + analizzaContenuti(listOf(doc), a.optString("domanda"), "")
            }
            "archivio_elimina" -> Archivio.elimina(ctx, a.optString("documento"))
            "modalita_privata" -> modalitaPrivata(a.optBoolean("attiva", true))
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

    // ================================================================
    //  CONTENUTI CONDIVISI, TRADUZIONI E RESOCONTO
    // ================================================================

    /** Qualcosa condiviso con Jarvis da un'altra app. tipo: testo, link, immagine, pdf, audio, video, file */
    class Contenuto(val tipo: String, val titolo: String, val testo: String, val base64: String?, val mime: String)

    /** Uno scambio avvenuto fuori dalla conversazione normale (per esempio sui file) entra in diario e conversazione. */
    fun registraScambioEsterno(domanda: String, risposta: String) = synchronized(blocco) {
        scriviNelDiario(domanda, risposta, "file")
        registraScambioLocale(domanda, risposta)
        salvaConversazioni()
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

    internal fun guarda(domanda: String): String {
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


    // ---------- collegamenti ai moduli (le funzioni ora vivono nei file dedicati) ----------
    private fun modelloClaude() = ia.modelloClaude()
    private fun postClaudeFlusso(corpo: JSONObject, alPezzo: (String) -> Unit): JSONObject = ia.postClaudeFlusso(corpo, alPezzo)
    private fun postClaude(corpo: JSONObject): JSONObject = ia.postClaude(corpo)
    private fun postGeminiFlusso(corpo: JSONObject, alPezzo: (String) -> Unit): JSONObject = ia.postGeminiFlusso(corpo, alPezzo)
    private fun postGemini(corpo: JSONObject): JSONObject = ia.postGemini(corpo)
    internal fun chiamaSemplice(richiesta: String, maxToken: Int): String = ia.chiamaSemplice(richiesta, maxToken)
    internal fun geminiFile(file: List<Contenuto>, richiesta: String): String = ia.geminiFile(file, richiesta)
    internal fun claudeFile(file: List<Contenuto>, richiesta: String): String = ia.claudeFile(file, richiesta)
    internal fun visioneGemini(base64: String, richiesta: String): String = ia.visioneGemini(base64, richiesta)
    internal fun visioneClaude(base64: String, richiesta: String): String = ia.visioneClaude(base64, richiesta)
    fun usaClaude() = ia.usaClaude()
    fun chiaveAttiva(): String = ia.chiaveAttiva()
    private var pezziInviati: Boolean
        get() = ia.pezziInviati
        set(v) { ia.pezziInviati = v }
    private fun leggiFile(nome: String): String? = memoria.leggiFile(nome)
    private fun scriviFile(nome: String, testo: String) = memoria.scriviFile(nome, testo)
    fun leggiLista(chiave: String): MutableList<String> = memoria.leggiLista(chiave)
    internal fun salvaLista(chiave: String, lista: List<String>) = memoria.salvaLista(chiave, lista)
    internal fun leggiDiario(): JSONArray = memoria.leggiDiario()
    private fun scriviNelDiario(domanda: String, risposta: String, tipo: String = "ia") = memoria.scriviNelDiario(domanda, risposta, tipo)
    private fun cercaNelDiario(testo: String): String = memoria.cercaNelDiario(testo)
    fun leggiRoutine(): JSONArray = memoria.leggiRoutine()
    private fun creaRoutine(nome: String, frase: String, istruzioni: String): String = memoria.creaRoutine(nome, frase, istruzioni)
    private fun eliminaRoutine(nome: String): String = memoria.eliminaRoutine(nome)
    internal fun ricordiPertinenti(tutti: List<String>, massimo: Int = 25): List<String> = memoria.ricordiPertinenti(tutti, massimo)
    internal fun richiestePassateSimili(): String = memoria.richiestePassateSimili()
    private fun cercaRicordi(testo: String): String = memoria.cercaRicordi(testo)
    private fun ricorda(informazione: String): String = memoria.ricorda(informazione)
    private fun imparaLezione(lezione: String): String = memoria.imparaLezione(lezione)
    private fun dimentica(parola: String): String = memoria.dimentica(parola)
    fun ultimeRichieste(quante: Int): String = memoria.ultimeRichieste(quante)
    fun numeroRichieste() = memoria.numeroRichieste()
    private fun aggiornaRiassunto(testoNuovo: String) = memoria.aggiornaRiassunto(testoNuovo)
    private fun rifletti() = memoria.rifletti()
    private fun avvia(intento: Intent, messaggioOk: String): String = telefono.avvia(intento, messaggioOk)
    private fun codifica(testo: String): String = telefono.codifica(testo)
    private fun normalizzaUrl(url: String): String = telefono.normalizzaUrl(url)
    internal fun apriApp(nome: String): String = telefono.apriApp(nome)
    private fun apriUrl(url: String, messaggioOk: String): String = telefono.apriUrl(url, messaggioOk)
    internal fun impostaSveglia(ora: Int, minuti: Int, etichetta: String): String = telefono.impostaSveglia(ora, minuti, etichetta)
    internal fun impostaTimer(secondi: Int, etichetta: String): String = telefono.impostaTimer(secondi, etichetta)
    internal fun torcia(accendi: Boolean): String = telefono.torcia(accendi)
    internal fun chiama(contatto: String): String = telefono.chiama(contatto)
    private fun scriviSms(contatto: String, testo: String): String = telefono.scriviSms(contatto, testo)
    private fun inviaWhatsapp(contatto: String, testo: String): String = telefono.inviaWhatsapp(contatto, testo)
    private fun trovaEmail(chi: String): String? = telefono.trovaEmail(chi)
    internal fun volumePercento(percento: Int): String = telefono.volumePercento(percento)
    internal fun batteria(): String = telefono.batteria()
    internal fun regolaVolume(azione: String, passi: Int): String = telefono.regolaVolume(azione, passi)
    internal fun riproduci(cosa: String, app: String): String = telefono.riproduci(cosa, app)
    internal fun controllaMusica(azione: String): String = telefono.controllaMusica(azione)
    internal fun prendiNota(testo: String): String = telefono.prendiNota(testo)
    private fun leggiNote(quante: Int): String = telefono.leggiNote(quante)
    private fun meteo(cittaRichiesta: String, giorno: String): String = telefono.meteo(cittaRichiesta, giorno)
    fun testoDellaPagina(url: String): String = contenuti.testoDellaPagina(url)
    fun analizzaContenuto(c: Contenuto, istruzione: String): String = contenuti.analizzaContenuto(c, istruzione)
    fun analizzaContenuti(lista: List<Contenuto>, istruzione: String, contesto: String): String = contenuti.analizzaContenuti(lista, istruzione, contesto)
    fun riassuntoPerArchivio(c: Contenuto): String = contenuti.riassuntoPerArchivio(c)
    fun traduci(testo: String, da: String, a: String): String = contenuti.traduci(testo, da, a)
    fun resocontoSettimana(): String = contenuti.resocontoSettimana()
    fun descriviFotogramma(base64: String, domanda: String, contesto: String): String = contenuti.descriviFotogramma(base64, domanda, contesto)
    internal fun personalita(): String = prompt.personalita()
    private fun promptDiSistema(): String = prompt.promptDiSistema()
    private fun definizioniStrumenti(): JSONArray = DefinizioniStrumenti.definizioniStrumenti(::strumentoAttivo)

}
