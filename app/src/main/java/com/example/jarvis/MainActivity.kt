package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
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
import android.provider.Settings
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
import android.widget.SeekBar
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
class MainActivity : Activity(), Cervello.Uscita {

    companion object {
        /** true mentre la schermata è aperta: il servizio lascia libero il microfono. */
        @Volatile var visibile = false
    }

    private val CODICE_MIC = 1
    private val CODICE_CONTATTI = 2
    private val CODICE_MIC_SERVIZIO = 3

    private val COLORE_SFONDO = Color.parseColor("#0B1220")
    private val COLORE_ACCENTO = Color.parseColor("#3FC1FF")
    private val COLORE_TESTO = Color.parseColor("#E6F1FF")
    private val COLORE_TENUE = Color.parseColor("#7A8BA6")
    private val COLORE_MEMORIA = Color.parseColor("#B794F6")

    // ---------------- STATO ----------------
    private lateinit var cervello: Cervello
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

    private fun chiaveAttiva() = cervello.chiaveAttiva()
    private fun usaClaude() = cervello.usaClaude()
    private fun pluginAttivo(id: String) = cervello.pluginAttivo(id)

    // ================================================================
    //  AVVIO
    // ================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cervello = Cervello.prendi(this)
        prefs = cervello.prefs
        costruisciInterfaccia()

        tts = TextToSpeech(this) { esito ->
            if (esito == TextToSpeech.SUCCESS) {
                tts?.let { Voce.applica(it, prefs) }
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
        } else if (cervello.haConversazione()) {
            aggiungiAlRegistro("Jarvis", "Bentornato. Ricordo la nostra ultima conversazione. Tocca il microfono e parla.")
        } else {
            aggiungiAlRegistro("Jarvis", "Pronto. Tocca il microfono e parla.")
        }

        // Se era attiva la modalità sempre attiva, la riaccende (ora l'app è in primo piano e può farlo)
        if (prefs.getBoolean("sempre_attivo", false) && !ServizioJarvis.attivo && permessoMicrofono()) {
            ServizioJarvis.avvia(this)
        }
        controllaRichiestaAscolto(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        controllaRichiestaAscolto(intent)
    }

    /** Aperta dalla tessera rapida o dalla notifica: inizia subito ad ascoltare. */
    private fun controllaRichiestaAscolto(intento: Intent?) {
        if (intento?.getBooleanExtra("ascolta", false) == true) {
            intento.removeExtra("ascolta")
            reattore.postDelayed({ iniziaAscolto() }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        cervello.uscita = this
        visibile = true
        // Mentre la schermata è aperta, la parola di attivazione va in pausa per liberare il microfono
        ServizioJarvis.invia(this, ServizioJarvis.AZIONE_PAUSA)
    }

    override fun onPause() {
        cervello.uscita = null
        visibile = false
        if (inAscolto) fermaAscolto()
        ServizioJarvis.invia(this, ServizioJarvis.AZIONE_RIPRENDI)
        super.onPause()
    }

    // ---------- Cervello.Uscita: messaggi dal cervello e dal servizio ----------

    override fun notaMemoria(testo: String) = aggiungiAlRegistro("Memoria", testo)

    override fun chiediPermesso(permesso: String) {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(permesso), CODICE_CONTATTI)
    }

    override fun nuovoScambio(domanda: String, risposta: String, fonti: List<String>) {
        aggiungiAlRegistro("Tu", domanda)
        aggiungiAlRegistro("Jarvis", risposta)
        if (fonti.isNotEmpty()) aggiungiAlRegistro("Fonti", fonti.joinToString(" · "))
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
        val modelloGemini = campo("Modello Gemini", prefs.getString("modello", Cervello.MODELLO_PREDEFINITO) ?: Cervello.MODELLO_PREDEFINITO)

        titoloSezione("Claude (facoltativo)")
        nota("Le API di Claude si pagano a parte su platform.claude.com: l'abbonamento Pro o Max non le include.")
        val chiaveClaude = campo("Chiave API Claude", prefs.getString("chiave_claude", "") ?: "", true)
        val modelloClaude = campo(
            "Modello Claude",
            prefs.getString("modello_claude", Cervello.MODELLO_CLAUDE_PREDEFINITO) ?: Cervello.MODELLO_CLAUDE_PREDEFINITO
        )

        titoloSezione("Plugin")
        nota("Jarvis sceglie da solo quale plugin usare in base alla richiesta.")
        val caselle = cervello.PLUGIN.map { plugin ->
            val casella = CheckBox(this).apply {
                text = plugin.nome
                isChecked = pluginAttivo(plugin.id)
            }
            contenitore.addView(casella)
            plugin.id to casella
        }

        titoloSezione("Voce")
        nota("Scegli tra le voci italiane del telefono e regola tono e velocità.")
        var tonoScelto = prefs.getFloat("voce_tono", Voce.TONO_PREDEFINITO)
        var velocitaScelta = prefs.getFloat("voce_velocita", Voce.VELOCITA_PREDEFINITA)

        fun prova() {
            val voce = tts ?: return
            Voce.applica(voce, prefs)
            voce.setPitch(tonoScelto)
            voce.setSpeechRate(velocitaScelta)
            voce.speak("Buongiorno. Sono Jarvis, a sua disposizione.", TextToSpeech.QUEUE_FLUSH, null, "prova")
        }

        fun cursore(etichetta: String, valore: Float, cambia: (Float) -> Unit) {
            val testo = TextView(this).apply { setPadding(0, dp(10), 0, 0) }
            fun scrivi(v: Float) { testo.text = "$etichetta: ${(v * 100).toInt()}%" }
            scrivi(valore)
            contenitore.addView(testo)
            contenitore.addView(SeekBar(this).apply {
                max = 100                         // da 50% a 150%
                progress = ((valore - 0.5f) * 100).toInt().coerceIn(0, 100)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(barra: SeekBar?, p: Int, utente: Boolean) {
                        val v = 0.5f + p / 100f
                        scrivi(v)
                        cambia(v)
                    }
                    override fun onStartTrackingTouch(barra: SeekBar?) {}
                    override fun onStopTrackingTouch(barra: SeekBar?) { prova() }
                })
            })
        }

        val nomeVoce = TextView(this).apply { setPadding(0, dp(6), 0, 0) }
        fun aggiornaNomeVoce() {
            val voce = tts
            val scelta = prefs.getString("voce_nome", "") ?: ""
            val elenco = if (voce != null) Voce.vociItaliane(voce) else emptyList()
            val indice = elenco.indexOfFirst { it.name == scelta }
            nomeVoce.text = "Voce attuale: " + if (indice >= 0) Voce.etichetta(elenco[indice], indice + 1) else "predefinita del telefono"
        }
        aggiornaNomeVoce()
        contenitore.addView(nomeVoce)
        contenitore.addView(Button(this).apply {
            text = "Scegli la voce"
            isAllCaps = false
            setOnClickListener {
                val voce = tts
                val elenco = if (voce != null) Voce.vociItaliane(voce) else emptyList()
                if (elenco.isEmpty()) {
                    aggiungiAlRegistro("Jarvis", "Non trovo voci italiane. Installale da Impostazioni > Sintesi vocale > Dati vocali.")
                    return@setOnClickListener
                }
                val nomi = elenco.mapIndexed { i, v -> Voce.etichetta(v, i + 1) }.toTypedArray()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Tocca una voce per sentirla")
                    .setItems(nomi) { _, i ->
                        prefs.edit().putString("voce_nome", elenco[i].name).apply()
                        aggiornaNomeVoce()
                        prova()
                    }
                    .setNegativeButton("Chiudi", null)
                    .show()
            }
        })
        cursore("Tono", tonoScelto) { tonoScelto = it }
        cursore("Velocità", velocitaScelta) { velocitaScelta = it }
        contenitore.addView(Button(this).apply {
            text = "Prova la voce"
            isAllCaps = false
            setOnClickListener { prova() }
        })
        contenitore.addView(Button(this).apply {
            text = "Preset Jarvis: maschile, grave, calmo"
            isAllCaps = false
            setOnClickListener {
                val voce = tts
                val maschile = if (voce != null) Voce.vociItaliane(voce).withIndex()
                    .firstOrNull { Voce.etichetta(it.value, it.index + 1).contains("maschile") }?.value else null
                if (maschile != null) prefs.edit().putString("voce_nome", maschile.name).apply()
                tonoScelto = 0.85f
                velocitaScelta = 0.95f
                prefs.edit().putFloat("voce_tono", tonoScelto).putFloat("voce_velocita", velocitaScelta).apply()
                aggiornaNomeVoce()
                prova()
                aggiungiAlRegistro("Jarvis", "Preset Jarvis applicato. Riapri le impostazioni per vedere i nuovi valori.")
            }
        })
        nota("Altre voci: Impostazioni del telefono > Sintesi vocale > Dati vocali di Google, oppure installa un altro motore vocale.")

        titoloSezione("Sempre attivo")
        nota("Jarvis resta acceso in background con una notifica fissa: di' \"Jarvis\" oppure tocca Parla nella notifica " +
            "o la tessera Jarvis nel menu a tendina.")
        val parolaAttivazione = CheckBox(this).apply {
            text = "Parola di attivazione \"Jarvis\" (offline)"
            isChecked = prefs.getBoolean("parola_attivazione", true)
        }
        contenitore.addView(parolaAttivazione)

        fun bottone(testo: String, azione: () -> Unit) {
            contenitore.addView(Button(this).apply {
                text = testo
                isAllCaps = false
                setOnClickListener { azione() }
            })
        }
        bottone(if (ServizioJarvis.attivo) "Spegni la modalità sempre attiva" else "Accendi la modalità sempre attiva") {
            if (ServizioJarvis.attivo) spegniSempreAttivo() else accendiSempreAttivo()
        }
        nota("Permessi per le altre app:")
        bottone("Accesso alle notifiche" + if (NotificheJarvis.abilitato(this)) " ✓" else "") {
            apriImpostazione(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        bottone("Controllo delle app (Accessibilità)" + if (AccessibilitaJarvis.istanza != null) " ✓" else "") {
            apriImpostazione(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        bottone("Batteria senza limiti") {
            apriImpostazione(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        bottone("Avvio automatico (Xiaomi)") {
            val xiaomi = Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            )
            if (!apriImpostazione(xiaomi, silenzioso = true)) {
                apriImpostazione(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        nota("Se Android non ti lascia attivare notifiche o accessibilità: Impostazioni > App > Jarvis > menu ⋮ > " +
            "Consenti impostazioni con limitazioni, poi riprova.")

        titoloSezione("Altro")
        val citta = campo("Città per il meteo", prefs.getString("citta", Cervello.CITTA_PREDEFINITA) ?: Cervello.CITTA_PREDEFINITA)

        AlertDialog.Builder(this)
            .setTitle("Impostazioni")
            .setView(ScrollView(this).apply { addView(contenitore) })
            .setPositiveButton("Salva") { _, _ ->
                val nuovoFornitore = if (gruppo.checkedRadioButtonId == sceltaGemini.id) "gemini" else "claude"
                val modifica = prefs.edit()
                    .putString("fornitore", nuovoFornitore)
                    .putString("chiave_claude", chiaveClaude.text.toString().trim())
                    .putString("modello_claude", modelloClaude.text.toString().trim().ifBlank { Cervello.MODELLO_CLAUDE_PREDEFINITO })
                    .putString("api_key", chiaveGemini.text.toString().trim())
                    .putString("modello", modelloGemini.text.toString().trim().ifBlank { Cervello.MODELLO_PREDEFINITO })
                    .putString("citta", citta.text.toString().trim().ifBlank { Cervello.CITTA_PREDEFINITA })
                for ((id, casella) in caselle) modifica.putBoolean("plugin_$id", casella.isChecked)
                modifica.putFloat("voce_tono", tonoScelto).putFloat("voce_velocita", velocitaScelta)
                val parolaCambiata = parolaAttivazione.isChecked != prefs.getBoolean("parola_attivazione", true)
                modifica.putBoolean("parola_attivazione", parolaAttivazione.isChecked)
                modifica.apply()
                if (parolaCambiata && ServizioJarvis.attivo) {
                    stopService(Intent(this, ServizioJarvis::class.java))
                    reattore.postDelayed({ ServizioJarvis.avvia(this) }, 600)
                }

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
        val ricordi = cervello.leggiLista("memoria")
        val lezioni = cervello.leggiLista("lezioni")
        val routine = cervello.leggiRoutine()
        val riassunto = prefs.getString("riassunto", "") ?: ""
        val richieste = cervello.numeroRichieste()

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
                Thread { cervello.nuovaConversazione() }.start()
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
                Thread { cervello.cancellaTutto() }.start()
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
            CODICE_MIC_SERVIZIO -> {
                if (concesso) accendiSempreAttivo() else stato.text = "Senza microfono la modalità sempre attiva non può partire."
            }
        }
    }

    // ================================================================
    //  BOCCA: sintesi vocale
    // ================================================================

    private fun parla(testo: String) {
        val pulito = testo.replace(Regex("[*#_`]"), "")
        tts?.let { Voce.applica(it, prefs) }
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
            val risposta = cervello.rispondi(testo)
            runOnUiThread {
                occupato = false
                stato.text = ""
                aggiornaMic()
                aggiungiAlRegistro("Jarvis", risposta.testo)
                if (risposta.fonti.isNotEmpty()) aggiungiAlRegistro("Fonti", risposta.fonti.joinToString(" · "))
                parla(risposta.testo)
            }
            cervello.manutenzione()
        }.start()
    }

    // ================================================================
    //  MODALITÀ SEMPRE ATTIVA
    // ================================================================

    private fun permessoMicrofono() = Build.VERSION.SDK_INT < 23 ||
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun accendiSempreAttivo() {
        if (!permessoMicrofono()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), CODICE_MIC_SERVIZIO)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
        }
        prefs.edit().putBoolean("sempre_attivo", true).apply()
        ServizioJarvis.avvia(this)
        ServizioJarvis.invia(this, ServizioJarvis.AZIONE_PAUSA)
        aggiungiAlRegistro(
            "Jarvis",
            "Modalità sempre attiva accesa. Quando chiudi questa schermata, di' \"Jarvis\" per chiamarmi. " +
                "Per non farmi chiudere dal telefono, attiva anche \"Batteria senza limiti\" e \"Avvio automatico\" nelle impostazioni."
        )
    }

    private fun spegniSempreAttivo() {
        prefs.edit().putBoolean("sempre_attivo", false).apply()
        stopService(Intent(this, ServizioJarvis::class.java))
        aggiungiAlRegistro("Jarvis", "Modalità sempre attiva spenta.")
    }

    private fun apriImpostazione(intento: Intent, silenzioso: Boolean = false): Boolean = try {
        startActivity(intento)
        true
    } catch (e: Exception) {
        if (!silenzioso) aggiungiAlRegistro("Jarvis", "Non riesco ad aprire questa impostazione su questo telefono.")
        false
    }
}
