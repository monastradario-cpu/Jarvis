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
import android.speech.tts.UtteranceProgressListener
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
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
 * JARVIS per Android
 * - Orecchie:  riconoscimento vocale di Android (italiano)
 * - Bocca:     sintesi vocale di Android (italiano)
 * - Cervello:  Claude di Anthropic (oppure Gemini di Google, a scelta)
 * - Plugin:    telefono, audio, chiamate e SMS, web, ricerca web, meteo, note
 * - Memoria:   fatti sull'utente, lezioni, routine, riassunto delle conversazioni,
 *              diario di tutte le richieste e conversazione salvata
 * - Si migliora da solo: impara dalle correzioni e ogni tanto rilegge il diario
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

    // Limiti della memoria
    private val MAX_RICORDI = 80
    private val MAX_LEZIONI = 40
    private val MAX_DIARIO = 300
    private val RIFLESSIONE_OGNI = 12   // ogni quante richieste Jarvis rilegge il diario

    private val CODICE_MIC = 1
    private val CODICE_CONTATTI = 2

    private val COLORE_SFONDO = Color.parseColor("#0B1220")
    private val COLORE_ACCENTO = Color.parseColor("#3FC1FF")
    private val COLORE_ASCOLTO = Color.parseColor("#FF5A5F")
    private val COLORE_TESTO = Color.parseColor("#E6F1FF")
    private val COLORE_TENUE = Color.parseColor("#7A8BA6")
    private val COLORE_MEMORIA = Color.parseColor("#B794F6")

    // ---------------- PLUGIN ----------------
    private class Plugin(val id: String, val nome: String, val strumenti: List<String>)

    private val PLUGIN = listOf(
        Plugin("telefono", "Telefono: app, sveglie, timer, torcia",
            listOf("apri_app", "imposta_sveglia", "imposta_timer", "torcia")),
        Plugin("audio", "Volume e musica",
            listOf("regola_volume", "controlla_musica")),
        Plugin("comunicazione", "Chiamate e SMS",
            listOf("chiama", "scrivi_sms")),
        Plugin("web", "Web: siti, Google, YouTube",
            listOf("apri_sito", "cerca_sul_web", "cerca_su_youtube")),
        Plugin("ricerca", "Ricerca web integrata (solo con Claude, a pagamento)",
            listOf("web_search")),
        Plugin("meteo", "Meteo",
            listOf("meteo")),
        Plugin("note", "Note",
            listOf("prendi_nota", "leggi_note"))
    )
    // Gli strumenti della memoria (ricorda, lezioni, routine, diario) sono sempre attivi.

    private fun pluginAttivo(id: String) = prefs.getBoolean("plugin_$id", true)

    private fun strumentoAttivo(nome: String): Boolean {
        val plugin = PLUGIN.firstOrNull { nome in it.strumenti } ?: return true
        return pluginAttivo(plugin.id)
    }

    // ---------------- STATO ----------------
    private lateinit var prefs: SharedPreferences
    private lateinit var radice: LinearLayout
    private lateinit var registro: TextView
    private lateinit var scorrimento: ScrollView
    private lateinit var stato: TextView
    private lateinit var campoTesto: EditText
    private lateinit var reattore: ReattoreView
    @Volatile private var parlando = false

    private var tts: TextToSpeech? = null
    private var riconoscitore: SpeechRecognizer? = null
    private var inAscolto = false
    @Volatile private var occupato = false

    private val blocco = Any()                // una cosa alla volta: conversazione e memoria
    private val messaggi = JSONArray()        // conversazione con Gemini (formato OpenAI)
    private val messaggiClaude = JSONArray()  // conversazione con Claude (formato Anthropic)
    private val strumentiUsati = mutableListOf<String>()
    private val fontiUltimaRisposta = mutableListOf<String>()

    private fun usaClaude() = prefs.getString("fornitore", "gemini") == "claude"

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
        caricaConversazioni()

        tts = TextToSpeech(this) { esito ->
            if (esito == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("it", "IT"))
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        runOnUiThread { parlando = true; aggiornaMic() }
                    }

                    override fun onDone(utteranceId: String?) {
                        runOnUiThread { parlando = false; aggiornaMic() }
                    }

                    @Deprecated("Richiesto dalle versioni vecchie di Android")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { parlando = false; aggiornaMic() }
                    }
                })
            }
        }

        if (chiaveAttiva().isEmpty()) {
            aggiungiAlRegistro("Jarvis", "Benvenuto! Crea una chiave gratuita su aistudio.google.com/apikey e incollala nelle impostazioni.")
            mostraImpostazioni()
        } else if (messaggiClaude.length() > 0 || messaggi.length() > 1) {
            aggiungiAlRegistro("Jarvis", "Bentornato. Ricordo la nostra ultima conversazione. Tocca il microfono e parla.")
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

        // Barra superiore: titolo + memoria + impostazioni
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titolo = TextView(this).apply {
            text = "JARVIS"
            setTextColor(COLORE_ACCENTO)
            textSize = 24f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.15f
        }
        barra.addView(titolo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val bottoneMemoria = Button(this).apply {
            text = "🧠"
            textSize = 20f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { mostraMemoria() }
        }
        barra.addView(bottoneMemoria)
        val bottoneImpostazioni = Button(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(COLORE_TENUE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { mostraImpostazioni() }
        }
        barra.addView(bottoneImpostazioni)
        radice.addView(barra)

        // Il reattore animato: toccalo per parlare
        reattore = ReattoreView(this).apply {
            isClickable = true
            contentDescription = "Tocca per parlare"
            setOnClickListener { if (inAscolto) fermaAscolto() else iniziaAscolto() }
        }
        radice.addView(
            reattore,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.25f).apply { topMargin = dp(4) }
        )

        // Conversazione
        registro = TextView(this).apply {
            setTextColor(COLORE_TESTO)
            textSize = 15f
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
        }
        scorrimento = ScrollView(this).apply { addView(registro) }

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

        radice.addView(
            scorrimento,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) }
        )
        aggiornaMic()

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

    /** Aggiorna il reattore in base a cosa sta facendo Jarvis. */
    private fun aggiornaMic() {
        when {
            inAscolto -> {
                reattore.stato = ReattoreView.Stato.ASCOLTO
                reattore.testoCentrale = "IN ASCOLTO"
            }
            occupato -> {
                reattore.stato = ReattoreView.Stato.PENSIERO
                reattore.testoCentrale = "ELABORO"
            }
            parlando -> {
                reattore.stato = ReattoreView.Stato.PARLA
                reattore.testoCentrale = "JARVIS"
            }
            else -> {
                reattore.stato = ReattoreView.Stato.RIPOSO
                reattore.testoCentrale = "TOCCA"
                reattore.livelloVoce = 0f
            }
        }
    }

    private fun aggiungiAlRegistro(chi: String, testo: String) {
        val intestazione = "$chi: "
        val riga = SpannableString("$intestazione$testo\n\n")
        val colore = when (chi) {
            "Tu", "Fonti" -> COLORE_TENUE
            "Memoria" -> COLORE_MEMORIA
            else -> COLORE_ACCENTO
        }
        riga.setSpan(ForegroundColorSpan(colore), 0, intestazione.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        riga.setSpan(StyleSpan(Typeface.BOLD), 0, intestazione.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (chi == "Fonti" || chi == "Memoria") {
            riga.setSpan(ForegroundColorSpan(colore), 0, riga.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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

        fun nota(testo: String) {
            contenitore.addView(TextView(this).apply {
                text = testo
                setPadding(0, dp(4), 0, 0)
            })
        }

        // Scelta del cervello
        titoloSezione("Cervello di Jarvis")
        val sceltaGemini = RadioButton(this).apply { text = "Gemini (Google): gratuito, consigliato"; id = View.generateViewId() }
        val sceltaClaude = RadioButton(this).apply { text = "Claude (Anthropic): a consumo, facoltativo"; id = View.generateViewId() }
        val gruppo = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(sceltaGemini)
            addView(sceltaClaude)
            check(if (usaClaude()) sceltaClaude.id else sceltaGemini.id)
        }
        contenitore.addView(gruppo)

        titoloSezione("Gemini (gratuito)")
        nota("Chiave gratuita su aistudio.google.com/apikey")
        val chiaveGemini = campo("Chiave API Gemini", prefs.getString("api_key", "") ?: "", true)
        val modelloGemini = campo("Modello Gemini", prefs.getString("modello", MODELLO_PREDEFINITO) ?: MODELLO_PREDEFINITO)

        titoloSezione("Claude (facoltativo)")
        nota("Le API di Claude si pagano a parte su platform.claude.com: l'abbonamento Pro o Max non le include.")
        val chiaveClaude = campo("Chiave API Claude", prefs.getString("chiave_claude", "") ?: "", true)
        val modelloClaude = campo(
            "Modello Claude",
            prefs.getString("modello_claude", MODELLO_CLAUDE_PREDEFINITO) ?: MODELLO_CLAUDE_PREDEFINITO
        )

        titoloSezione("Plugin")
        nota("Jarvis sceglie da solo quale plugin usare in base alla richiesta.")
        val caselle = PLUGIN.map { plugin ->
            val casella = CheckBox(this).apply {
                text = plugin.nome
                isChecked = pluginAttivo(plugin.id)
            }
            contenitore.addView(casella)
            plugin.id to casella
        }

        titoloSezione("Altro")
        val citta = campo("Città per il meteo", prefs.getString("citta", CITTA_PREDEFINITA) ?: CITTA_PREDEFINITA)

        AlertDialog.Builder(this)
            .setTitle("Impostazioni")
            .setView(ScrollView(this).apply { addView(contenitore) })
            .setPositiveButton("Salva") { _, _ ->
                val nuovoFornitore = if (gruppo.checkedRadioButtonId == sceltaGemini.id) "gemini" else "claude"
                val modifica = prefs.edit()
                    .putString("fornitore", nuovoFornitore)
                    .putString("chiave_claude", chiaveClaude.text.toString().trim())
                    .putString("modello_claude", modelloClaude.text.toString().trim().ifBlank { MODELLO_CLAUDE_PREDEFINITO })
                    .putString("api_key", chiaveGemini.text.toString().trim())
                    .putString("modello", modelloGemini.text.toString().trim().ifBlank { MODELLO_PREDEFINITO })
                    .putString("citta", citta.text.toString().trim().ifBlank { CITTA_PREDEFINITA })
                for ((id, casella) in caselle) modifica.putBoolean("plugin_$id", casella.isChecked)
                modifica.apply()

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
    //  SCHERMATA DELLA MEMORIA (🧠)
    // ================================================================

    private fun mostraMemoria() {
        val ricordi = leggiLista("memoria")
        val lezioni = leggiLista("lezioni")
        val routine = leggiRoutine()
        val riassunto = prefs.getString("riassunto", "") ?: ""
        val richieste = leggiDiario().length()

        val testo = StringBuilder()
        testo.append("COSA SO DI TE\n")
        testo.append(if (ricordi.isEmpty()) "Ancora niente.\n" else ricordi.joinToString("\n") { "• $it" } + "\n")
        testo.append("\nLEZIONI IMPARATE\n")
        testo.append(if (lezioni.isEmpty()) "Ancora nessuna.\n" else lezioni.joinToString("\n") { "• $it" } + "\n")
        testo.append("\nROUTINE\n")
        if (routine.length() == 0) {
            testo.append("Nessuna. Puoi chiedermi di crearne una, ad esempio \"crea una routine buonanotte\".\n")
        } else {
            for (i in 0 until routine.length()) {
                val r = routine.getJSONObject(i)
                testo.append("• ${r.optString("nome")} (\"${r.optString("frase")}\"): ${r.optString("istruzioni")}\n")
            }
        }
        testo.append("\nRIASSUNTO DELLE CONVERSAZIONI PASSATE\n")
        testo.append(riassunto.ifBlank { "Ancora vuoto: si crea quando la conversazione diventa lunga." }).append("\n")
        testo.append("\nDIARIO\n$richieste richieste salvate sul telefono.")

        val vista = TextView(this).apply {
            text = testo
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setTextIsSelectable(true)
        }

        AlertDialog.Builder(this)
            .setTitle("Memoria di Jarvis")
            .setView(ScrollView(this).apply { addView(vista) })
            .setPositiveButton("Chiudi", null)
            .setNeutralButton("Nuova conversazione") { _, _ ->
                Thread {
                    synchronized(blocco) {
                        svuota(messaggiClaude)
                        svuota(messaggi)
                        salvaConversazioni()
                    }
                }.start()
                aggiungiAlRegistro("Jarvis", "Ok, ricominciamo da capo. I ricordi restano.")
            }
            .setNegativeButton("Cancella tutto") { _, _ -> confermaCancellaTutto() }
            .show()
    }

    private fun confermaCancellaTutto() {
        AlertDialog.Builder(this)
            .setTitle("Cancellare tutta la memoria?")
            .setMessage("Jarvis dimenticherà ricordi, lezioni, routine, riassunto, diario e conversazione. Le note restano.")
            .setPositiveButton("Cancella") { _, _ ->
                Thread {
                    synchronized(blocco) {
                        prefs.edit()
                            .remove("memoria").remove("lezioni").remove("routine")
                            .remove("riassunto").remove("contatore")
                            .apply()
                        scriviFile("diario.json", "[]")
                        svuota(messaggiClaude)
                        svuota(messaggi)
                        salvaConversazioni()
                    }
                }.start()
                aggiungiAlRegistro("Memoria", "Memoria cancellata.")
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
        stato.text = ""
    }

    private fun fermaAscolto() {
        riconoscitore?.stopListening()
        inAscolto = false
        aggiornaMic()
    }

    private val ascoltatore = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            reattore.livelloVoce = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }
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
    //  GESTIONE DELLE RICHIESTE
    // ================================================================

    private fun gestisciRichiesta(testo: String) {
        if (occupato) return
        if (chiaveAttiva().isEmpty()) {
            mostraImpostazioni()
            return
        }
        aggiungiAlRegistro("Tu", testo)
        occupato = true
        stato.text = ""
        aggiornaMic()

        Thread {
            var fonti: List<String> = emptyList()
            val risposta = synchronized(blocco) {
                strumentiUsati.clear()
                fontiUltimaRisposta.clear()
                val r = try {
                    if (usaClaude()) conversaClaudeConRecupero(testo) else conversaGeminiConRecupero(testo)
                } catch (e: Exception) {
                    "Scusa, c'è stato un problema: ${e.message}"
                }
                fonti = fontiUltimaRisposta.toList()
                scriviNelDiario(testo, r)
                salvaConversazioni()
                r
            }
            runOnUiThread {
                occupato = false
                stato.text = ""
                aggiornaMic()
                aggiungiAlRegistro("Jarvis", risposta)
                if (fonti.isNotEmpty()) aggiungiAlRegistro("Fonti", fonti.joinToString(" · "))
                parla(risposta)
            }
            // Dopo la risposta: riordina la memoria e, ogni tanto, impara dal diario
            try {
                manutenzioneMemoria()
            } catch (e: Exception) {
                // la manutenzione non deve mai bloccare Jarvis
            }
        }.start()
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
        p.append("- Non salvare mai password, codici o dati bancari.\n\n")

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

            repeat(8) {
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

            repeat(6) {
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
        val file = File(filesDir, nome)
        if (file.exists()) file.readText() else null
    } catch (e: Exception) {
        null
    }

    private fun scriviFile(nome: String, testo: String) {
        try {
            File(filesDir, nome).writeText(testo)
        } catch (e: Exception) {
            // spazio pieno o file non scrivibile: si continua senza salvare
        }
    }

    private fun leggiLista(chiave: String): MutableList<String> {
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

    private fun leggiRoutine(): JSONArray = try {
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
            runOnUiThread {
                aggiungiAlRegistro("Memoria", "Ho imparato: " + imparato.joinToString("; ") + ". Puoi vedere tutto con 🧠.")
            }
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
            strumento("controlla_musica", "Controlla la musica o il video in riproduzione.",
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
            else -> "Strumento sconosciuto: $nome"
        }
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

    private fun eUnNumero(contatto: String) =
        contatto.replace(Regex("[\\s.-]"), "").matches(Regex("\\+?[0-9]{3,}"))

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
