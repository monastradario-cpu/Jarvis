package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * JARVIS per Android
 * - Orecchie: riconoscimento vocale di Android (italiano)
 * - Bocca:    sintesi vocale di Android (italiano)
 * - Cervello: Claude di Anthropic (oppure Gemini di Google, a scelta)
 * - Poteri:   app, siti, ricerche, sveglie, timer, torcia, chiamate, SMS,
 *             meteo, volume, musica, note e memoria
 */
class MainActivity : Activity() {

    // ---------------- IMPOSTAZIONI PREDEFINITE ----------------
    // Claude (Anthropic) - cervello principale
    private val URL_CLAUDE = "https://api.anthropic.com/v1/messages"
    private val MODELLO_CLAUDE_PREDEFINITO = "claude-haiku-5-5"

    // Gemini (Google) - alternativa gratuita
    private val URL_PREDEFINITO = "https://generativelanguage.googleapis.com/v1beta/openai"
    private val MODELLO_PREDEFINITO = "gemini-3.5-flash-lite"

    private val CITTA_PREDEFINITA = "Roma"

    private val CODICE_MIC = 1
    private val CODICE_CONTATTI = 2

    private val COLORE_SFONDO = Color.parseColor("#0B1220")
    private val COLORE_ACCENTO = Color.parseColor("#3FC1FF")
    private val COLORE_ASCOLTO = Color.parseColor("#FF5A5F")
    private val COLORE_TESTO = Color.parseColor("#E6F1FF")
    private val COLORE_TENUE = Color.parseColor("#7A8BA6")

    // ---------------- STATO ----------------
    private lateinit var prefs: SharedPreferences
    private lateinit var radice: LinearLayout
    private lateinit var registro: TextView
    private lateinit var scorrimento: ScrollView
    private lateinit var stato: TextView
    private lateinit var campoTesto: EditText
    private lateinit var bottoneMic: Button

    private var tts: TextToSpeech? = null
    private var riconoscitore: SpeechRecognizer? = null
    private var inAscolto = false
    @Volatile private var occupato = false

    private val messaggi = JSONArray()        // conversazione con Gemini (formato OpenAI)
    private val messaggiClaude = JSONArray()  // conversazione con Claude (formato Anthropic)

    private fun usaClaude() = prefs.getString("fornitore", "claude") != "gemini"

    private fun chiaveAttiva(): String =
        (if (usaClaude()) prefs.getString("chiave_claude", "") else prefs.getString("api_key", ""))
            ?.trim() ?: ""

    // ================================================================
    //  AVVIO
    // ================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        costruisciInterfaccia()

        tts = TextToSpeech(this) { esito ->
            if (esito == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("it", "IT"))
            }
        }

        if (chiaveAttiva().isEmpty()) {
            aggiungiAlRegistro("Jarvis", "Benvenuto! Prima inserisci la tua chiave API nelle impostazioni.")
            mostraImpostazioni()
        } else {
            aggiungiAlRegistro("Jarvis", "Pronto. Tocca il microfono e parla.")
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        riconoscitore?.destroy()
        super.onDestroy()
    }

    // ================================================================
    //  INTERFACCIA
    // ================================================================

    private fun dp(valore: Int) = (valore * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun costruisciInterfaccia() {
        radice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLORE_SFONDO)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        // Evita che la barra di stato e la tastiera coprano l'app
        radice.setOnApplyWindowInsetsListener { vista, insets ->
            vista.setPadding(
                dp(16),
                dp(16) + insets.systemWindowInsetTop,
                dp(16),
                dp(16) + insets.systemWindowInsetBottom
            )
            insets
        }

        // Barra superiore: titolo + impostazioni
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titolo = TextView(this).apply {
            text = "J.A.R.V.I.S."
            setTextColor(COLORE_ACCENTO)
            textSize = 24f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.15f
        }
        barra.addView(titolo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val bottoneImpostazioni = Button(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(COLORE_TENUE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { mostraImpostazioni() }
        }
        barra.addView(bottoneImpostazioni)
        radice.addView(barra)

        // Conversazione
        registro = TextView(this).apply {
            setTextColor(COLORE_TESTO)
            textSize = 16f
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
        }
        scorrimento = ScrollView(this).apply { addView(registro) }
        radice.addView(
            scorrimento,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) }
        )

        // Stato ("Ti ascolto...", "Sto pensando...")
        stato = TextView(this).apply {
            setTextColor(COLORE_TENUE)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        radice.addView(
            stato,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        )

        // Grande pulsante del microfono
        bottoneMic = Button(this).apply {
            text = "🎤"
            textSize = 30f
            setTextColor(Color.WHITE)
            setOnClickListener { if (inAscolto) fermaAscolto() else iniziaAscolto() }
        }
        aggiornaMic()
        radice.addView(
            bottoneMic,
            LinearLayout.LayoutParams(dp(88), dp(88)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
                bottomMargin = dp(12)
            }
        )

        // Riga per scrivere invece di parlare
        val rigaTesto = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        campoTesto = EditText(this).apply {
            hint = "Oppure scrivi qui..."
            setHintTextColor(COLORE_TENUE)
            setTextColor(COLORE_TESTO)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, azione, _ ->
                if (azione == EditorInfo.IME_ACTION_SEND) {
                    inviaTestoScritto(); true
                } else false
            }
        }
        rigaTesto.addView(campoTesto, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val bottoneInvia = Button(this).apply {
            text = "Invia"
            setOnClickListener { inviaTestoScritto() }
        }
        rigaTesto.addView(bottoneInvia)
        radice.addView(rigaTesto)

        setContentView(radice)
    }

    private fun aggiornaMic() {
        bottoneMic.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (inAscolto) COLORE_ASCOLTO else COLORE_ACCENTO)
        }
    }

    private fun aggiungiAlRegistro(chi: String, testo: String) {
        val intestazione = "$chi: "
        val riga = SpannableString("$intestazione$testo\n\n")
        val colore = if (chi == "Tu") COLORE_TENUE else COLORE_ACCENTO
        riga.setSpan(ForegroundColorSpan(colore), 0, intestazione.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        riga.setSpan(StyleSpan(Typeface.BOLD), 0, intestazione.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        registro.append(riga)
        scorrimento.post { scorrimento.fullScroll(View.FOCUS_DOWN) }
    }

    private fun inviaTestoScritto() {
        val testo = campoTesto.text.toString().trim()
        if (testo.isEmpty()) return
        campoTesto.setText("")
        gestisciRichiesta(testo)
    }

    // ================================================================
    //  IMPOSTAZIONI
    // ================================================================

    private fun mostraImpostazioni() {
        val contenitore = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        fun campo(etichetta: String, valore: String, nascosto: Boolean = false): EditText {
            contenitore.addView(TextView(this).apply {
                text = etichetta
                setPadding(0, dp(12), 0, 0)
            })
            val casella = EditText(this).apply {
                setText(valore)
                setSingleLine(true)
                if (nascosto) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            contenitore.addView(casella)
            return casella
        }

        fun titoloSezione(testo: String) {
            contenitore.addView(TextView(this).apply {
                text = testo
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(18), 0, 0)
            })
        }

        // Scelta del cervello
        titoloSezione("Cervello di Jarvis")
        val sceltaClaude = RadioButton(this).apply { text = "Claude (Anthropic)"; id = View.generateViewId() }
        val sceltaGemini = RadioButton(this).apply { text = "Gemini (Google, gratuito)"; id = View.generateViewId() }
        val gruppo = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(sceltaClaude)
            addView(sceltaGemini)
            check(if (usaClaude()) sceltaClaude.id else sceltaGemini.id)
        }
        contenitore.addView(gruppo)

        titoloSezione("Claude")
        contenitore.addView(TextView(this).apply {
            text = "Chiave su platform.claude.com (a pagamento, pochi centesimi al mese)"
            setPadding(0, dp(4), 0, 0)
        })
        val chiaveClaude = campo("Chiave API Claude", prefs.getString("chiave_claude", "") ?: "", true)
        val modelloClaude = campo(
            "Modello Claude",
            prefs.getString("modello_claude", MODELLO_CLAUDE_PREDEFINITO) ?: MODELLO_CLAUDE_PREDEFINITO
        )

        titoloSezione("Gemini")
        contenitore.addView(TextView(this).apply {
            text = "Chiave gratuita su aistudio.google.com/apikey"
            setPadding(0, dp(4), 0, 0)
        })
        val chiaveGemini = campo("Chiave API Gemini", prefs.getString("api_key", "") ?: "", true)
        val modelloGemini = campo("Modello Gemini", prefs.getString("modello", MODELLO_PREDEFINITO) ?: MODELLO_PREDEFINITO)

        titoloSezione("Altro")
        val citta = campo("Città per il meteo", prefs.getString("citta", CITTA_PREDEFINITA) ?: CITTA_PREDEFINITA)

        AlertDialog.Builder(this)
            .setTitle("Impostazioni")
            .setView(ScrollView(this).apply { addView(contenitore) })
            .setPositiveButton("Salva") { _, _ ->
                val nuovoFornitore = if (gruppo.checkedRadioButtonId == sceltaGemini.id) "gemini" else "claude"
                prefs.edit()
                    .putString("fornitore", nuovoFornitore)
                    .putString("chiave_claude", chiaveClaude.text.toString().trim())
                    .putString("modello_claude", modelloClaude.text.toString().trim().ifBlank { MODELLO_CLAUDE_PREDEFINITO })
                    .putString("api_key", chiaveGemini.text.toString().trim())
                    .putString("modello", modelloGemini.text.toString().trim().ifBlank { MODELLO_PREDEFINITO })
                    .putString("citta", citta.text.toString().trim().ifBlank { CITTA_PREDEFINITA })
                    .apply()
                val nome = if (nuovoFornitore == "claude") "Claude" else "Gemini"
                if (chiaveAttiva().isEmpty()) {
                    aggiungiAlRegistro("Jarvis", "Manca la chiave API di $nome: aggiungila nelle impostazioni.")
                } else {
                    aggiungiAlRegistro("Jarvis", "Impostazioni salvate, uso $nome. Tocca il microfono e parla.")
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ================================================================
    //  ORECCHIE: riconoscimento vocale
    // ================================================================

    private fun iniziaAscolto() {
        if (occupato) return
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), CODICE_MIC)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            aggiungiAlRegistro("Jarvis", "Il riconoscimento vocale non è disponibile. Installa o aggiorna l'app Google.")
            return
        }
        tts?.stop()
        if (riconoscitore == null) {
            riconoscitore = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(ascoltatore)
            }
        }
        val intento = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        riconoscitore?.startListening(intento)
        inAscolto = true
        aggiornaMic()
        stato.text = "Ti ascolto..."
    }

    private fun fermaAscolto() {
        riconoscitore?.stopListening()
        inAscolto = false
        aggiornaMic()
    }

    private val ascoltatore = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onEndOfSpeech() {
            stato.text = "Un attimo..."
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val parziale = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!parziale.isNullOrBlank()) stato.text = parziale
        }

        override fun onResults(results: Bundle?) {
            inAscolto = false
            aggiornaMic()
            val testo = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (testo.isNullOrBlank()) {
                stato.text = "Non ho capito, riprova."
                return
            }
            gestisciRichiesta(testo)
        }

        override fun onError(error: Int) {
            inAscolto = false
            aggiornaMic()
            stato.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Non ho capito, riprova."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Problema di connessione."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Manca il permesso del microfono."
                else -> "Errore del microfono ($error), riprova."
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val concesso = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            CODICE_MIC -> {
                if (concesso) iniziaAscolto() else stato.text = "Senza microfono puoi solo scrivere."
            }
            CODICE_CONTATTI -> {
                if (concesso) stato.text = "Permesso concesso: ripeti la richiesta."
            }
        }
    }

    // ================================================================
    //  BOCCA: sintesi vocale
    // ================================================================

    private fun parla(testo: String) {
        val pulito = testo.replace(Regex("[*#_`]"), "")
        tts?.speak(pulito, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    // ================================================================
    //  CERVELLO: conversazione con l'API
    // ================================================================

    private fun gestisciRichiesta(testo: String) {
        if (occupato) return
        if (chiaveAttiva().isEmpty()) {
            mostraImpostazioni()
            return
        }
        aggiungiAlRegistro("Tu", testo)
        occupato = true
        stato.text = "Sto pensando..."

        Thread {
            val risposta = try {
                if (usaClaude()) conversaClaude(testo) else conversa(testo)
            } catch (e: Exception) {
                "Scusa, c'è stato un problema: ${e.message}"
            }
            runOnUiThread {
                occupato = false
                stato.text = ""
                aggiungiAlRegistro("Jarvis", risposta)
                parla(risposta)
            }
        }.start()
    }

    private fun conversa(testo: String): String {
        aggiornaPromptDiSistema()
        val lunghezzaIniziale = messaggi.length()
        try {
            messaggi.put(JSONObject().put("role", "user").put("content", testo))

            repeat(6) {
                val messaggio = chiamaApi()
                messaggi.put(messaggio)

                val chiamate = messaggio.optJSONArray("tool_calls")
                if (chiamate == null || chiamate.length() == 0) {
                    accorciaStorico()
                    val contenuto = if (messaggio.isNull("content")) "" else messaggio.getString("content")
                    return contenuto.trim().ifBlank { "Fatto." }
                }

                for (i in 0 until chiamate.length()) {
                    val chiamata = chiamate.getJSONObject(i)
                    val funzione = chiamata.getJSONObject("function")
                    val nome = funzione.getString("name")
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
            // Annulla la parte di conversazione non riuscita
            while (messaggi.length() > lunghezzaIniziale) messaggi.remove(messaggi.length() - 1)
            throw e
        }
    }

    private fun chiamaApi(): JSONObject {
        val base = (prefs.getString("base_url", URL_PREDEFINITO) ?: URL_PREDEFINITO).trim().trimEnd('/')
        val modello = prefs.getString("modello", MODELLO_PREDEFINITO) ?: MODELLO_PREDEFINITO
        val chiave = (prefs.getString("api_key", "") ?: "").trim()

        val corpo = JSONObject()
            .put("model", modello)
            .put("messages", messaggi)
            .put("tools", definizioniStrumenti())
            .put("tool_choice", "auto")

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
            throw Exception(
                when (codice) {
                    400 -> "richiesta non valida. ${estraiErrore(risposta)}"
                    401, 403 -> "la chiave API non è valida, controllala nelle impostazioni."
                    404 -> "modello o indirizzo non trovato, controlla le impostazioni."
                    429 -> "ho raggiunto il limite di richieste gratuite, riprova tra un po'."
                    else -> "errore $codice. ${estraiErrore(risposta)}"
                }
            )
        }

        val messaggio = JSONObject(risposta)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message")

        // Teniamo solo i campi utili (inclusi quelli speciali di Gemini)
        val pulito = JSONObject().put("role", "assistant")
        pulito.put("content", if (messaggio.isNull("content")) JSONObject.NULL else messaggio.get("content"))
        if (messaggio.has("tool_calls")) pulito.put("tool_calls", messaggio.get("tool_calls"))
        if (messaggio.has("extra_content")) pulito.put("extra_content", messaggio.get("extra_content"))
        return pulito
    }

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

    private fun aggiornaPromptDiSistema() {
        val sistema = JSONObject().put("role", "system").put("content", promptDiSistema())
        if (messaggi.length() == 0) messaggi.put(sistema) else messaggi.put(0, sistema)
    }

    private fun promptDiSistema(): String {
        val adesso = SimpleDateFormat("EEEE d MMMM yyyy, 'ore' HH:mm", Locale.ITALIAN).format(Date())
        val citta = prefs.getString("citta", CITTA_PREDEFINITA) ?: CITTA_PREDEFINITA
        val memoria = leggiMemoria()

        var testo = "Sei Jarvis, l'assistente vocale personale dell'utente, sul suo telefono Android. " +
            "Rispondi sempre in italiano, con frasi brevi e naturali da leggere ad alta voce: " +
            "niente elenchi, niente asterischi, niente emoji, niente link. " +
            "Usa gli strumenti quando servono per agire sul telefono e non inventare risultati. " +
            "Adesso è $adesso. La città predefinita per il meteo è $citta."
        if (memoria.isNotEmpty()) {
            testo += " Cose che sai sull'utente: " + memoria.joinToString("; ") + "."
        }
        return testo
    }

    private fun accorciaStorico(massimo: Int = 24) {
        if (messaggi.length() <= massimo + 1) return
        var inizio = messaggi.length() - massimo
        while (inizio < messaggi.length() && messaggi.getJSONObject(inizio).optString("role") != "user") inizio++
        val nuovi = JSONArray().put(messaggi.getJSONObject(0))
        for (i in inizio until messaggi.length()) nuovi.put(messaggi.get(i))
        while (messaggi.length() > 0) messaggi.remove(messaggi.length() - 1)
        for (i in 0 until nuovi.length()) messaggi.put(nuovi.get(i))
    }

    // ================================================================
    //  CERVELLO CLAUDE (API nativa di Anthropic)
    // ================================================================

    private fun conversaClaude(testo: String): String {
        val lunghezzaIniziale = messaggiClaude.length()
        try {
            messaggiClaude.put(JSONObject().put("role", "user").put("content", testo))

            repeat(6) {
                val risposta = chiamaClaude()
                val contenuto = risposta.optJSONArray("content") ?: JSONArray()
                if (contenuto.length() == 0) {
                    contenuto.put(JSONObject().put("type", "text").put("text", "Fatto."))
                }
                // Il messaggio di Claude va rimandato così com'è (anche i blocchi di ragionamento)
                messaggiClaude.put(JSONObject().put("role", "assistant").put("content", contenuto))

                val testi = StringBuilder()
                val risultati = JSONArray()
                for (i in 0 until contenuto.length()) {
                    val blocco = contenuto.getJSONObject(i)
                    when (blocco.optString("type")) {
                        "text" -> testi.append(blocco.optString("text")).append(' ')
                        "tool_use" -> {
                            val nome = blocco.optString("name")
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

                if (risultati.length() == 0) {
                    accorciaStoricoClaude()
                    return testi.toString().trim().ifBlank { "Fatto." }
                }
                messaggiClaude.put(JSONObject().put("role", "user").put("content", risultati))
            }
            return "Ci ho messo troppi passaggi, prova a chiedermelo in modo più semplice."
        } catch (e: Exception) {
            while (messaggiClaude.length() > lunghezzaIniziale) messaggiClaude.remove(messaggiClaude.length() - 1)
            throw e
        }
    }

    private fun chiamaClaude(): JSONObject {
        val modello = (prefs.getString("modello_claude", MODELLO_CLAUDE_PREDEFINITO) ?: MODELLO_CLAUDE_PREDEFINITO).trim()
        val chiave = (prefs.getString("chiave_claude", "") ?: "").trim()

        // Strumenti nel formato di Anthropic: name, description, input_schema
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

        val corpo = JSONObject()
            .put("model", modello)
            .put("max_tokens", 1024)
            .put("system", promptDiSistema())
            .put("messages", messaggiClaude)
            .put("tools", strumenti)
        // Su Haiku spegniamo il ragionamento lungo: risposte più rapide, ideali per la voce
        if (modello.contains("haiku")) {
            corpo.put("thinking", JSONObject().put("type", "disabled"))
        }

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
            throw Exception(
                when {
                    dettaglio.contains("credit balance", ignoreCase = true) ->
                        "il credito API di Claude è esaurito, ricaricalo su platform.claude.com."
                    codice == 401 || codice == 403 -> "la chiave API di Claude non è valida, controllala nelle impostazioni."
                    codice == 404 -> "modello Claude non trovato, controlla il nome nelle impostazioni."
                    codice == 429 -> "troppe richieste in poco tempo, riprova tra qualche secondo."
                    codice == 529 || codice >= 500 -> "i server di Claude sono occupati, riprova tra poco."
                    else -> "errore $codice. $dettaglio"
                }
            )
        }
        return JSONObject(risposta)
    }

    private fun accorciaStoricoClaude(massimo: Int = 24) {
        if (messaggiClaude.length() <= massimo) return
        var inizio = messaggiClaude.length() - massimo
        // Ripartiamo da una domanda dell'utente (non da un risultato di uno strumento)
        while (inizio < messaggiClaude.length()) {
            val m = messaggiClaude.getJSONObject(inizio)
            if (m.optString("role") == "user" && m.opt("content") is String) break
            inizio++
        }
        val nuovi = JSONArray()
        for (i in inizio until messaggiClaude.length()) nuovi.put(messaggiClaude.get(i))
        while (messaggiClaude.length() > 0) messaggiClaude.remove(messaggiClaude.length() - 1)
        for (i in 0 until nuovi.length()) messaggiClaude.put(nuovi.get(i))
    }

    // ================================================================
    //  POTERI (strumenti)
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

    private fun definizioniStrumenti(): JSONArray = JSONArray()
        .put(strumento("apri_app", "Apre un'app installata sul telefono.",
            listOf(p("nome", "string", "Nome dell'app, es. WhatsApp, Spotify, Fotocamera")), listOf("nome")))
        .put(strumento("apri_sito", "Apre un sito web nel browser.",
            listOf(p("url", "string", "Indirizzo del sito, es. https://www.wikipedia.org")), listOf("url")))
        .put(strumento("cerca_sul_web", "Cerca qualcosa su Google e mostra i risultati.",
            listOf(p("testo", "string", "Cosa cercare")), listOf("testo")))
        .put(strumento("cerca_su_youtube", "Cerca un video o una canzone su YouTube.",
            listOf(p("testo", "string", "Cosa cercare su YouTube")), listOf("testo")))
        .put(strumento("imposta_sveglia", "Imposta una sveglia a un orario preciso.",
            listOf(
                p("ora", "integer", "Ora da 0 a 23"),
                p("minuti", "integer", "Minuti da 0 a 59"),
                p("etichetta", "string", "Nome della sveglia, facoltativo")
            ), listOf("ora", "minuti")))
        .put(strumento("imposta_timer", "Avvia un conto alla rovescia.",
            listOf(
                p("secondi", "integer", "Durata in secondi, es. 600 per 10 minuti"),
                p("etichetta", "string", "Nome del timer, facoltativo")
            ), listOf("secondi")))
        .put(strumento("torcia", "Accende o spegne la torcia del telefono.",
            listOf(p("accendi", "boolean", "true per accendere, false per spegnere")), listOf("accendi")))
        .put(strumento("chiama", "Prepara una telefonata a un contatto o a un numero. L'utente preme poi il tasto verde.",
            listOf(p("contatto", "string", "Nome del contatto in rubrica oppure numero di telefono")), listOf("contatto")))
        .put(strumento("scrivi_sms", "Prepara un SMS a un contatto o a un numero. L'utente preme poi invio.",
            listOf(
                p("contatto", "string", "Nome del contatto in rubrica oppure numero di telefono"),
                p("testo", "string", "Testo del messaggio")
            ), listOf("contatto", "testo")))
        .put(strumento("meteo", "Previsioni del tempo per oggi, domani o dopodomani.",
            listOf(
                p("citta", "string", "Città; se vuoto si usa quella predefinita"),
                p("giorno", "string", "oggi, domani oppure dopodomani")
            )))
        .put(strumento("regola_volume", "Alza, abbassa o silenzia il volume multimediale.",
            listOf(
                p("azione", "string", "alza, abbassa, muto oppure massimo"),
                p("passi", "integer", "Di quanti scatti cambiare il volume, di solito 3")
            ), listOf("azione")))
        .put(strumento("controlla_musica", "Controlla la musica o il video in riproduzione.",
            listOf(p("azione", "string", "play, pausa, avanti oppure indietro")), listOf("azione")))
        .put(strumento("prendi_nota", "Salva una nota.",
            listOf(p("testo", "string", "Testo della nota")), listOf("testo")))
        .put(strumento("leggi_note", "Legge le ultime note salvate.",
            listOf(p("quante", "integer", "Quante note leggere, di solito 5"))))
        .put(strumento("ricorda", "Memorizza in modo permanente un'informazione sull'utente, solo se l'utente lo chiede o la dice chiaramente.",
            listOf(p("informazione", "string", "L'informazione da ricordare, es. Si chiama Dario")), listOf("informazione")))
        .put(strumento("dimentica", "Cancella dalla memoria le informazioni che contengono una parola. Usa 'tutto' per cancellare tutto.",
            listOf(p("parola", "string", "Parola da cercare nei ricordi, oppure tutto")), listOf("parola")))

    private fun eseguiStrumento(nome: String, a: JSONObject): String = when (nome) {
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
        "dimentica" -> dimentica(a.optString("parola"))
        else -> "Strumento sconosciuto: $nome"
    }

    // ---------- utilità ----------

    /** Esegue un'azione sul thread dell'interfaccia e aspetta il risultato. */
    private fun suUi(azione: () -> String): String {
        var risultato = "Fatto."
        val attesa = CountDownLatch(1)
        runOnUiThread {
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
            startActivity(intento)
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
        val pm = packageManager
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
                startActivity(lancio)
                "Ho aperto ${trovata.loadLabel(pm)}."
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
        val camere = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val idCamera = camere.cameraIdList.firstOrNull {
            camere.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Questo telefono non ha il flash."
        camere.setTorchMode(idCamera, accendi)
        return if (accendi) "Torcia accesa." else "Torcia spenta."
    }

    // ---------- chiamate e SMS ----------

    private fun permessoContattiMancante(): Boolean =
        Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED

    /** Restituisce il numero, oppure null se non trovato. */
    private fun trovaNumero(contatto: String): String? {
        val compatto = contatto.replace(Regex("[\\s.-]"), "")
        if (compatto.matches(Regex("\\+?[0-9]{3,}"))) return compatto
        val cursore = contentResolver.query(
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

    private fun eUnNumero(contatto: String) =
        contatto.replace(Regex("[\\s.-]"), "").matches(Regex("\\+?[0-9]{3,}"))

    private fun chiediPermessoContatti(): String {
        runOnUiThread { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), CODICE_CONTATTI) }
        return "Mi serve il permesso di leggere la rubrica. Concedilo e ripeti la richiesta."
    }

    private fun chiama(contatto: String): String {
        if (!eUnNumero(contatto) && permessoContattiMancante()) return chiediPermessoContatti()
        val numero = trovaNumero(contatto) ?: return "Non ho trovato $contatto in rubrica."
        return avvia(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(numero))),
            "Ho preparato la chiamata a $contatto: premi il tasto verde."
        )
    }

    private fun scriviSms(contatto: String, testo: String): String {
        if (!eUnNumero(contatto) && permessoContattiMancante()) return chiediPermessoContatti()
        val numero = trovaNumero(contatto) ?: return "Non ho trovato $contatto in rubrica."
        val intento = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(numero)))
            .putExtra("sms_body", testo)
        return avvia(intento, "Ho preparato il messaggio per $contatto: controllalo e premi invia.")
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
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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

    private fun controllaMusica(azione: String): String = suUi {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
        val note = JSONArray(prefs.getString("note", "[]") ?: "[]")
        note.put("$data - ${testo.trim()}")
        prefs.edit().putString("note", note.toString()).apply()
        return "Nota salvata."
    }

    private fun leggiNote(quante: Int): String {
        val note = JSONArray(prefs.getString("note", "[]") ?: "[]")
        if (note.length() == 0) return "Non ci sono note."
        val n = quante.coerceIn(1, 20)
        val inizio = (note.length() - n).coerceAtLeast(0)
        return (inizio until note.length()).joinToString("\n") { note.getString(it) }
    }

    // ---------- memoria permanente ----------

    private fun leggiMemoria(): List<String> {
        val ricordi = JSONArray(prefs.getString("memoria", "[]") ?: "[]")
        return (0 until ricordi.length()).map { ricordi.getString(it) }
    }

    private fun salvaMemoria(ricordi: List<String>) {
        prefs.edit().putString("memoria", JSONArray(ricordi).toString()).apply()
    }

    private fun ricorda(informazione: String): String {
        if (informazione.isBlank()) return "Non c'è niente da ricordare."
        salvaMemoria(leggiMemoria() + informazione.trim())
        return "Lo ricorderò."
    }

    private fun dimentica(parola: String): String {
        val p = parola.lowercase(Locale.ITALIAN).trim()
        if (p.isEmpty()) return "Dimmi cosa devo dimenticare."
        if (p == "tutto") {
            salvaMemoria(emptyList())
            return "Ho cancellato tutti i ricordi."
        }
        val prima = leggiMemoria()
        val dopo = prima.filterNot { it.lowercase(Locale.ITALIAN).contains(p) }
        salvaMemoria(dopo)
        return if (dopo.size < prima.size) "Fatto, l'ho dimenticato." else "Non avevo ricordi su $parola."
    }
}
