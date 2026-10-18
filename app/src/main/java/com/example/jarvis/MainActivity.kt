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
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
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

    // Colori del tema scelto in ASPETTO (predefinito: nero, oro e bianco)
    private lateinit var tema: Tema.Palette
    private val COLORE_SFONDO: Int get() = tema.sfondo
    private val COLORE_ORO: Int get() = tema.accento
    private val COLORE_BIANCO: Int get() = tema.testo
    private val COLORE_TENUE: Int get() = tema.tenue
    private val MONO = Typeface.MONOSPACE
    private val SERIF = Typeface.create("serif", Typeface.NORMAL)

    private lateinit var infoHud: TextView
    private lateinit var contenitoreSfera: FrameLayout

    // Sottotitoli: ultima domanda, ultima risposta e note
    private var ultimaDomanda = ""
    private var ultimaRisposta = ""
    private val noteRecenti = mutableListOf<Pair<String, String>>()
    private val cronologiaSessione = mutableListOf<Pair<String, String>>()
    private val aggiornaHud = object : Runnable {
        override fun run() {
            scriviInfoHud()
            reattore.postDelayed(this, 20_000)
        }
    }

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
    private lateinit var parlatore: Parlatore
    private var risposteParziale = StringBuilder()
    private var riconoscitore: SpeechRecognizer? = null
    private var inAscolto = false
    @Volatile private var occupato = false

    /** Ascolta la voce (di Android o neurale): quando un pezzo finisce, il Parlatore decide cosa fare. */
    private val ascoltoVoce = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            runOnUiThread { if (utteranceId?.startsWith("j") == true) { parlando = true; aggiornaMic() } }
        }

        override fun onDone(utteranceId: String?) {
            parlatore.pezzoFinito(utteranceId)
        }

        @Deprecated("Richiesto dalle versioni vecchie di Android")
        override fun onError(utteranceId: String?) {
            parlatore.pezzoFinito(utteranceId)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            parlatore.pezzoFinito(utteranceId)
        }
    }

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
        tema = Tema.attuale(prefs)
        window.statusBarColor = tema.sfondo
        window.navigationBarColor = tema.sfondo
        costruisciInterfaccia()

        parlatore = Parlatore(this, prefs,
            alTermine = { domanda ->
                parlando = false
                aggiornaMic()
                // Conversazione continua: Jarvis ha fatto una domanda, ascolto la risposta
                if (domanda && visibile && !inAscolto && !occupato && prefs.getBoolean("dialogo_continuo", true)) {
                    reattore.postDelayed({ if (!inAscolto && !occupato) iniziaAscolto() }, 350)
                }
            },
            alInterrotto = { vuoleParlare ->
                parlando = false
                aggiornaMic()
                stato.text = if (vuoleParlare) "" else "Interrotto."
                if (vuoleParlare && visibile) reattore.postDelayed({ iniziaAscolto() }, 250)
            }
        )
        tts = TextToSpeech(this) { esito ->
            if (esito == TextToSpeech.SUCCESS) {
                tts?.let { Voce.applica(it, prefs) }
                parlatore.tts = tts
                tts?.setOnUtteranceProgressListener(ascoltoVoce)
            }
        }
        parlatore.ascoltatore = ascoltoVoce
        // Modelli vocali pronti in anticipo (per l'interruzione con "basta") e controlli proattivi
        if (permessoMicrofono()) ImprontaVocale.carica(this) { }
        Proattivo.programma(this)
        WidgetJarvis.aggiorna(this)

        if (chiaveAttiva().isEmpty()) {
            aggiungiAlRegistro("Jarvis", "Benvenuto! Crea una chiave gratuita su aistudio.google.com/apikey e incollala in Cervello.")
            mostraCervello()
        } else if (cervello.haConversazione()) {
            aggiungiAlRegistro("Jarvis", "Bentornato. Ricordo la nostra ultima conversazione. Tocchi la sfera per parlare, la tenga premuta per il menu.")
        } else {
            aggiungiAlRegistro("Jarvis", "Pronto. Tocchi la sfera per parlare, la tenga premuta per il menu.")
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

    /** Aperta dalla tessera rapida, dal widget, dall'assistente o dalla notifica: ascolta subito, o legge il riepilogo. */
    private fun controllaRichiestaAscolto(intento: Intent?) {
        if (intento?.getBooleanExtra("aggiorna", false) == true) {
            intento.removeExtra("aggiorna")
            reattore.postDelayed({ aggiornaOra() }, 500)
            return
        }
        if (intento?.getBooleanExtra("attiva_auto", false) == true) {
            intento.removeExtra("attiva_auto")
            if (!ServizioJarvis.attivo) accendiSempreAttivo()
            reattore.postDelayed({ parla("Buon viaggio. Chiuda pure l'app: quando vuole, dica Ehi Jarvis.") }, 700)
            return
        }
        if (intento?.action == Intent.ACTION_ASSIST || intento?.action == Intent.ACTION_VOICE_COMMAND ||
            intento?.action == "android.intent.action.SEARCH_LONG_PRESS"
        ) {
            intento.action = Intent.ACTION_MAIN
            reattore.postDelayed({ iniziaAscolto() }, 400)
            return
        }
        if (intento?.getBooleanExtra("riepilogo", false) == true) {
            intento.removeExtra("riepilogo")
            reattore.postDelayed({ gestisciRichiesta("Buongiorno. Fammi il riepilogo del mattino.") }, 600)
            return
        }
        if (intento?.getBooleanExtra("ascolta", false) == true) {
            intento.removeExtra("ascolta")
            reattore.postDelayed({ iniziaAscolto() }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        cervello.uscita = this
        visibile = true
        reattore.post(aggiornaHud)
        // Mentre la schermata è aperta, la parola di attivazione va in pausa per liberare il microfono
        ServizioJarvis.invia(this, ServizioJarvis.AZIONE_PAUSA)
    }

    override fun onPause() {
        cervello.uscita = null
        visibile = false
        reattore.removeCallbacks(aggiornaHud)
        if (inAscolto) fermaAscolto()
        ServizioJarvis.invia(this, ServizioJarvis.AZIONE_RIPRENDI)
        super.onPause()
    }

    // ---------- Cervello.Uscita: messaggi dal cervello e dal servizio ----------

    override fun notaMemoria(testo: String) = aggiungiAlRegistro("Memoria", testo)

    override fun chiediPermesso(permesso: String) {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(permesso), CODICE_CONTATTI)
    }

    private val CODICE_FOTO = 10
    private var alTermineFoto: ((Boolean) -> Unit)? = null
    private var fileFoto: File? = null

    override fun scattaFoto(file: File, alTermine: (Boolean) -> Unit) {
        alTermineFoto = alTermine
        fileFoto = file
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.file", file)
            val intento = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            @Suppress("DEPRECATION")
            startActivityForResult(intento, CODICE_FOTO)
            stato.text = "Inquadra e scatta la foto"
        } catch (e: Exception) {
            alTermineFoto = null
            alTermine(false)
        }
    }

    @Deprecated("Risultato della fotocamera")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode in listOf(CODICE_ESPORTA, CODICE_IMPORTA, CODICE_CARTELLA)) {
            risultatoDati(requestCode, data)
            return
        }
        if (requestCode == CODICE_FOTO) {
            val ok = resultCode == RESULT_OK && (fileFoto?.length() ?: 0L) > 0L
            stato.text = if (ok) "Guardo la foto..." else ""
            alTermineFoto?.invoke(ok)
            alTermineFoto = null
        }
    }

    override fun nuovoScambio(domanda: String, risposta: String, fonti: List<String>) {
        aggiungiAlRegistro("Tu", domanda)
        aggiungiAlRegistro("Jarvis", risposta)
        if (fonti.isNotEmpty()) aggiungiAlRegistro("Fonti", fonti.joinToString(" · "))
    }

    override fun onDestroy() {
        parlatore.ferma()
        tts?.stop()
        tts?.shutdown()
        riconoscitore?.destroy()
        super.onDestroy()
    }

    // ================================================================
    //  INTERFACCIA
    // ================================================================

    private fun dp(valore: Int) = (valore * resources.displayMetrics.density).toInt()

    /** Testo in stile HUD: carattere a spaziatura fissa, maiuscolo e distanziato. */
    private fun testoHud(testo: String, colore: Int, dimensione: Float, spaziatura: Float = 0.25f) =
        TextView(this).apply {
            text = testo
            setTextColor(colore)
            textSize = dimensione
            typeface = MONO
            letterSpacing = spaziatura
        }

    @Suppress("DEPRECATION")
    private fun costruisciInterfaccia() {
        radice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLORE_SFONDO)
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }
        // Evita che la barra di stato e la tastiera coprano l'app
        radice.setOnApplyWindowInsetsListener { vista, insets ->
            vista.setPadding(
                dp(18),
                dp(14) + insets.systemWindowInsetTop,
                dp(18),
                dp(14) + insets.systemWindowInsetBottom
            )
            insets
        }

        // ---- Barra HUD: memoria a sinistra, JARVIS al centro, sistema a destra ----
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        barra.addView(testoHud("MEMORIA", COLORE_ORO, 11f, 0.2f).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), dp(8), dp(8))
            setOnClickListener { mostraMemoria() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        barra.addView(testoHud(cervello.nomeAssistente().uppercase(Locale.ITALIAN), COLORE_ORO, 18f, 0.5f).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        barra.addView(testoHud("SISTEMA", COLORE_ORO, 11f, 0.2f).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), 0, dp(8))
            setOnClickListener { mostraImpostazioni() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        radice.addView(barra)

        // Linea dorata sottile
        radice.addView(View(this).apply { setBackgroundColor(COLORE_ORO); alpha = 0.45f },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(2) })

        // Riga di dati: data, ora, batteria, plugin
        infoHud = testoHud("", COLORE_TENUE, 9.5f, 0.12f).apply {
            gravity = Gravity.CENTER
            visibility = if (prefs.getString("interfaccia", "hud") == "minimal" || !prefs.getBoolean("mostra_dati", true)) View.GONE else View.VISIBLE
        }
        radice.addView(infoHud, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // ---- Il reattore: sfera dorata con anelli HUD. Toccalo per parlare ----
        reattore = ReattoreView(this).apply {
            isClickable = true
            contentDescription = "Tocca per parlare, tieni premuto per il menu"
            palette = tema
            applicaRegolazioni(this)
            alCambioZoom = { z -> prefs.edit().putFloat("r_scala", z).apply() }
            setOnClickListener {
                when {
                    inAscolto -> fermaAscolto()
                    parlando || parlatore.attivo -> { parlatore.ferma(); parlando = false; aggiornaMic() }
                    else -> iniziaAscolto()
                }
            }
            setOnLongClickListener { mostraImpostazioni(); true }
        }
        contenitoreSfera = FrameLayout(this)
        contenitoreSfera.addView(reattore, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        radice.addView(
            contenitoreSfera,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.3f).apply { topMargin = dp(4) }
        )

        // Testo che stai dicendo, in tempo reale
        stato = TextView(this).apply {
            setTextColor(COLORE_BIANCO)
            alpha = 0.75f
            textSize = 14f * scalaTesto()
            typeface = carattere()
            gravity = Gravity.CENTER
        }
        radice.addView(stato, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ---- Conversazione ----
        registro = TextView(this).apply {
            setTextColor(COLORE_BIANCO)
            textSize = 15f * scalaTesto()
            setLineSpacing(dp(2).toFloat(), 1.15f)
            setTextIsSelectable(true)
        }
        scorrimento = ScrollView(this).apply {
            addView(registro)
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(28))
        }
        radice.addView(
            scorrimento,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) }
        )
        aggiornaMic()

        // ---- Campo per scrivere: una pillola con bordo dorato ----
        val rigaTesto = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(6), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Tema.conAlfa(tema.accento, 170))
                setColor(tema.superficie)
            }
        }
        campoTesto = EditText(this).apply {
            hint = "Scrivi o tocca la sfera"
            setHintTextColor(COLORE_TENUE)
            setTextColor(COLORE_BIANCO)
            textSize = 15f
            background = null
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, azione, _ ->
                if (azione == EditorInfo.IME_ACTION_SEND) {
                    inviaTestoScritto(); true
                } else false
            }
        }
        rigaTesto.addView(campoTesto, LinearLayout.LayoutParams(0, dp(46), 1f))
        rigaTesto.addView(testoHud("INVIA", COLORE_ORO, 12f, 0.2f).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { inviaTestoScritto() }
        })
        radice.addView(rigaTesto, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        setContentView(radice)
        scriviInfoHud()
    }

    private fun scalaTesto() = when (prefs.getString("testo_dimensione", "normale")) {
        "piccolo" -> 0.85f
        "grande" -> 1.2f
        "enorme" -> 1.45f
        else -> 1f
    }

    private fun carattere(): Typeface = when (prefs.getString("testo_carattere", "serif")) {
        "sans" -> Typeface.SANS_SERIF
        "mono" -> Typeface.MONOSPACE
        else -> SERIF
    }

    private fun famigliaCarattere() = when (prefs.getString("testo_carattere", "serif")) {
        "sans" -> "sans-serif"
        "mono" -> "monospace"
        else -> "serif"
    }

    /** Applica al cuore animato forma, stile, sfondo e regolazioni dell'editor dal vivo. */
    private fun applicaRegolazioni(r: ReattoreView) {
        r.ridotto = prefs.getString("animazioni", "piene") == "ridotte"
        r.forma = prefs.getString("forma", "sfera") ?: "sfera"
        r.interfaccia = prefs.getString("interfaccia", "hud") ?: "hud"
        r.sfondo = prefs.getString("sfondo", "nessuno") ?: "nessuno"
        r.scala = prefs.getFloat("r_scala", 1f)
        r.velocitaBase = prefs.getFloat("r_velocita", 1f)
        r.numeroParticelle = prefs.getInt("r_particelle", 40)
        r.luminosita = prefs.getFloat("r_luminosita", 1f)
        r.spessore = prefs.getFloat("r_spessore", 1f)
        r.tacche = prefs.getBoolean("r_tacche", true)
    }

    /** Riga di dati in alto: data, ora, batteria, plugin attivi e ricordi. */
    private fun scriviInfoHud() {
        val data = SimpleDateFormat("EEE d MMM · HH:mm", Locale.ITALIAN).format(Date()).uppercase(Locale.ITALIAN)
        val batteria = try {
            (getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
                .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }
        val plugin = cervello.PLUGIN.count { cervello.pluginAttivo(it.id) && (it.id != "ricerca" || cervello.usaClaude()) }
        val ricordi = cervello.leggiLista("memoria").size
        val sempre = if (ServizioJarvis.attivo) " · SEMPRE ATTIVO" else ""
        infoHud.text = buildString {
            append(data)
            if (batteria >= 0) append(" · BATT $batteria%")
            append(" · $plugin PLUGIN · $ricordi RICORDI")
            append(sempre)
            if (cervello.privata) append(" · PRIVATA")
        }
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
                reattore.testoCentrale = "RISPONDO"
            }
            else -> {
                reattore.stato = ReattoreView.Stato.RIPOSO
                reattore.testoCentrale = "TOCCA PER PARLARE"
                reattore.livelloVoce = 0f
            }
        }
    }

    private fun modoSottotitoli() = prefs.getString("conversazione", "sottotitoli") != "cronologia"

    /** Una riga di conversazione formattata: domanda, risposta oppure nota. */
    private fun formatta(chi: String, testo: String, finale: String): SpannableString {
        val riga: SpannableString
        when (chi) {
            "Tu" -> {
                riga = SpannableString("› $testo$finale")
                riga.setSpan(TypefaceSpan("monospace"), 0, riga.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                riga.setSpan(ForegroundColorSpan(COLORE_TENUE), 0, riga.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                riga.setSpan(RelativeSizeSpan(0.9f), 0, riga.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            "Memoria", "Fonti" -> {
                val simbolo = if (chi == "Memoria") "◆ " else "◇ Fonti: "
                riga = SpannableString("$simbolo$testo$finale")
                riga.setSpan(TypefaceSpan("monospace"), 0, riga.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                riga.setSpan(ForegroundColorSpan(COLORE_ORO), 0, riga.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                riga.setSpan(RelativeSizeSpan(0.8f), 0, riga.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            else -> {
                riga = SpannableString("$testo$finale")
                riga.setSpan(TypefaceSpan(famigliaCarattere()), 0, riga.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                riga.setSpan(ForegroundColorSpan(COLORE_BIANCO), 0, riga.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (modoSottotitoli()) riga.setSpan(RelativeSizeSpan(1.12f), 0, riga.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return riga
    }

    /**
     * Conversazione. In modalità sottotitoli mostra solo l'ultima domanda e risposta, centrate;
     * in modalità cronologia tutta la conversazione che scorre.
     */
    private fun aggiungiAlRegistro(chi: String, testo: String) {
        cronologiaSessione.add(chi to testo)
        while (cronologiaSessione.size > 200) cronologiaSessione.removeAt(0)
        when (chi) {
            "Tu" -> {
                ultimaDomanda = testo
                ultimaRisposta = ""
                noteRecenti.clear()
            }
            "Memoria", "Fonti" -> noteRecenti.add(chi to testo)
            else -> ultimaRisposta = testo
        }
        if (modoSottotitoli()) {
            ridisegnaConversazione()
        } else {
            registro.gravity = Gravity.START
            registro.append(formatta(chi, testo, if (chi == "Tu") "\n" else "\n\n"))
            scorrimento.post { scorrimento.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun ridisegnaConversazione() {
        val testo = SpannableStringBuilder()
        if (modoSottotitoli()) {
            registro.gravity = Gravity.CENTER_HORIZONTAL
            if (ultimaDomanda.isNotEmpty()) testo.append(formatta("Tu", ultimaDomanda, "\n\n"))
            if (ultimaRisposta.isNotEmpty()) testo.append(formatta("Jarvis", ultimaRisposta, "\n\n"))
            for ((chi, nota) in noteRecenti) testo.append(formatta(chi, nota, "\n"))
            registro.text = testo
            scorrimento.post { scorrimento.scrollTo(0, 0) }
        } else {
            registro.gravity = Gravity.START
            for ((chi, riga) in cronologiaSessione) testo.append(formatta(chi, riga, if (chi == "Tu") "\n" else "\n\n"))
            registro.text = testo
            scorrimento.post { scorrimento.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun inviaTestoScritto() {
        val testo = campoTesto.text.toString().trim()
        if (testo.isEmpty()) return
        campoTesto.setText("")
        gestisciRichiesta(testo)
    }

    // ================================================================
    //  MENU RADIALE E SOTTOMENÙ
    // ================================================================

    private val VOCI_MENU = listOf("CERVELLO", "CARATTERE", "VOCE", "PLUGIN", "ATTIVO", "MATTINO", "ASPETTO", "COLLEGA", "DATI")
    private var menuRadiale: MenuRadialeView? = null

    /** Apre il menu radiale attorno alla sfera. */
    private fun mostraImpostazioni() {
        if (menuRadiale != null) return
        val menu = MenuRadialeView(
            this, VOCI_MENU, tema,
            alScegliere = { i ->
                chiudiMenuRadiale()
                when (i) {
                    0 -> mostraCervello()
                    1 -> mostraPersonalita()
                    2 -> mostraVoce()
                    3 -> mostraPlugin()
                    4 -> mostraSempreAttivo()
                    5 -> mostraMattino()
                    6 -> mostraAspetto()
                    7 -> mostraCollegamenti()
                    else -> mostraDati()
                }
            },
            alChiudere = { chiudiMenuRadiale() }
        )
        menuRadiale = menu
        contenitoreSfera.addView(menu, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun chiudiMenuRadiale() {
        menuRadiale?.let { contenitoreSfera.removeView(it) }
        menuRadiale = null
    }

    @Deprecated("Gestione del tasto indietro")
    override fun onBackPressed() {
        if (menuRadiale != null) {
            chiudiMenuRadiale()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /** Una pagina di sottomenù, con titolo, contenuti e tasto Salva. */
    private inner class Pagina(private val titolo: String) {
        val contenitore = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }

        fun titoloSezione(testo: String) {
            contenitore.addView(TextView(this@MainActivity).apply {
                text = testo.uppercase(Locale.ITALIAN)
                textSize = 12f
                typeface = MONO
                letterSpacing = 0.2f
                setTextColor(COLORE_ORO)
                setPadding(0, dp(16), 0, dp(2))
            })
        }

        fun nota(testo: String) {
            contenitore.addView(TextView(this@MainActivity).apply {
                text = testo
                textSize = 13f
                setTextColor(COLORE_TENUE)
                setPadding(0, dp(4), 0, 0)
            })
        }

        fun campo(etichetta: String, valore: String, nascosto: Boolean = false): EditText {
            contenitore.addView(TextView(this@MainActivity).apply {
                text = etichetta
                setPadding(0, dp(10), 0, 0)
            })
            val casella = EditText(this@MainActivity).apply {
                setText(valore)
                setSingleLine(true)
                if (nascosto) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            contenitore.addView(casella)
            return casella
        }

        fun casella(testo: String, attiva: Boolean): CheckBox {
            val c = CheckBox(this@MainActivity).apply {
                text = testo
                isChecked = attiva
            }
            contenitore.addView(c)
            return c
        }

        fun bottone(testo: String, azione: () -> Unit): Button {
            val b = Button(this@MainActivity).apply {
                text = testo
                isAllCaps = false
                setOnClickListener { azione() }
            }
            contenitore.addView(b)
            return b
        }

        /** Scelta tra opzioni (valore salvato, testo mostrato). Restituisce chi legge la scelta. */
        fun scelta(opzioni: List<Pair<String, String>>, attuale: String): () -> String {
            val bottoni = opzioni.map { (_, testo) ->
                RadioButton(this@MainActivity).apply {
                    text = testo
                    id = View.generateViewId()
                }
            }
            val gruppo = RadioGroup(this@MainActivity).apply {
                orientation = RadioGroup.VERTICAL
                bottoni.forEach { addView(it) }
                val indice = opzioni.indexOfFirst { it.first == attuale }.coerceAtLeast(0)
                check(bottoni[indice].id)
            }
            contenitore.addView(gruppo)
            return {
                val indice = bottoni.indexOfFirst { it.id == gruppo.checkedRadioButtonId }.coerceAtLeast(0)
                opzioni[indice].first
            }
        }

        fun mostra(alSalvare: (() -> Unit)? = null) {
            val finestra = AlertDialog.Builder(this@MainActivity)
                .setTitle(titolo)
                .setView(ScrollView(this@MainActivity).apply { addView(contenitore) })
            if (alSalvare != null) {
                finestra.setPositiveButton("Salva") { _, _ -> alSalvare() }
                finestra.setNegativeButton("Annulla", null)
            } else {
                finestra.setPositiveButton("Chiudi", null)
            }
            finestra.setNeutralButton("Menu") { _, _ -> mostraImpostazioni() }
            finestra.show()
        }
    }

    // ---------- CERVELLO ----------
    private fun mostraCervello() {
        val pagina = Pagina("Cervello")
        pagina.titoloSezione("Chi ragiona per Jarvis")
        val fornitore = pagina.scelta(
            listOf(
                "gemini" to "Gemini (Google): gratuito, consigliato",
                "claude" to "Claude (Anthropic): a consumo, facoltativo"
            ),
            if (usaClaude()) "claude" else "gemini"
        )
        pagina.titoloSezione("Gemini")
        pagina.nota("Chiave gratuita su aistudio.google.com/apikey")
        val chiaveGemini = pagina.campo("Chiave API Gemini", Cassaforte.leggi(prefs, "api_key"), true)
        val modelloGemini = pagina.campo("Modello Gemini", prefs.getString("modello", Cervello.MODELLO_PREDEFINITO) ?: Cervello.MODELLO_PREDEFINITO)
        pagina.titoloSezione("Claude")
        pagina.nota("Le API di Claude si pagano a parte su platform.claude.com: l'abbonamento Pro o Max non le include.")
        val chiaveClaude = pagina.campo("Chiave API Claude", Cassaforte.leggi(prefs, "chiave_claude"), true)
        val modelloClaude = pagina.campo(
            "Modello Claude",
            prefs.getString("modello_claude", Cervello.MODELLO_CLAUDE_PREDEFINITO) ?: Cervello.MODELLO_CLAUDE_PREDEFINITO
        )
        pagina.titoloSezione("Meteo")
        val citta = pagina.campo("Città predefinita", prefs.getString("citta", Cervello.CITTA_PREDEFINITA) ?: Cervello.CITTA_PREDEFINITA)
        pagina.mostra {
            val scelto = fornitore()
            prefs.edit()
                .putString("fornitore", scelto)
                .let { Cassaforte.scrivi(it, "api_key", chiaveGemini.text.toString().trim()) }
                .putString("modello", modelloGemini.text.toString().trim().ifBlank { Cervello.MODELLO_PREDEFINITO })
                .let { Cassaforte.scrivi(it, "chiave_claude", chiaveClaude.text.toString().trim()) }
                .putString("modello_claude", modelloClaude.text.toString().trim().ifBlank { Cervello.MODELLO_CLAUDE_PREDEFINITO })
                .putString("citta", citta.text.toString().trim().ifBlank { Cervello.CITTA_PREDEFINITA })
                .apply()
            val nome = if (scelto == "claude") "Claude" else "Gemini"
            if (chiaveAttiva().isEmpty()) {
                aggiungiAlRegistro("Jarvis", "Manca la chiave API di $nome: aggiungila in Cervello.")
            } else {
                aggiungiAlRegistro("Jarvis", "Impostazioni salvate, uso $nome. Tocca la sfera e parla.")
            }
            scriviInfoHud()
        }
    }

    // ---------- CARATTERE ----------
    private fun mostraPersonalita() {
        val pagina = Pagina("Carattere")
        pagina.titoloSezione("Personalità")
        val stile = pagina.scelta(
            listOf(
                "maggiordomo" to "Maggiordomo ironico: elegante, cortese, battute asciutte",
                "formale" to "Formale serio: preciso ed essenziale",
                "amichevole" to "Amichevole: dà del tu, tono caldo"
            ),
            prefs.getString("personalita", "maggiordomo") ?: "maggiordomo"
        )
        pagina.titoloSezione("Come ti chiama")
        val appellativo = pagina.campo("Signore, signora, il tuo nome, oppure vuoto", prefs.getString("appellativo", "signore") ?: "")
        pagina.titoloSezione("Interazione")
        val dialogo = pagina.casella(
            "Conversazione continua: quando Jarvis ti fa una domanda, ascolta subito la risposta",
            prefs.getBoolean("dialogo_continuo", true)
        )
        val interrompibile = pagina.casella(
            "Interrompibile: mentre parla, di' \"basta\" o \"stop\" per zittirlo, o \"Jarvis\" per parlargli sopra",
            prefs.getBoolean("interrompibile", true)
        )
        val confermaApp = pagina.casella(
            "Prima di aprire un'app, spiegami cosa farai e chiedimi conferma",
            prefs.getBoolean("conferma_app", false)
        )
        pagina.nota("Per gli acquisti e per cancellare appuntamenti Jarvis chiede sempre due conferme.")
        pagina.titoloSezione("Velocità")
        val rapidi = pagina.casella(
            "Comandi istantanei: torcia, volume, sveglie, timer, musica, chiamate e ora senza IA (anche offline)",
            prefs.getBoolean("comandi_rapidi", true)
        )
        val flusso = pagina.casella(
            "Risposte in streaming: inizia a parlare mentre la risposta sta ancora arrivando",
            prefs.getBoolean("streaming", true)
        )
        pagina.titoloSezione("Jarvis proattivo")
        pagina.nota("Ti avvisa da solo: appuntamenti in arrivo (e quando partire), messaggi insistenti, pioggia. Mai a voce di notte.")
        val proattivo = pagina.casella("Avvisi proattivi", prefs.getBoolean("proattivo", true))
        val proCalendario = pagina.casella("Appuntamenti e quando partire", prefs.getBoolean("proattivo_calendario", true))
        val proMessaggi = pagina.casella("Qualcuno ti scrive più volte di fila", prefs.getBoolean("proattivo_messaggi", true))
        val proMeteo = pagina.casella("Pioggia in arrivo", prefs.getBoolean("proattivo_meteo", true))
        val proVoce = pagina.casella("Dimmelo anche a voce (se è sempre attivo)", prefs.getBoolean("proattivo_voce", true))
        pagina.mostra {
            prefs.edit()
                .putString("personalita", stile())
                .putString("appellativo", appellativo.text.toString().trim())
                .putBoolean("dialogo_continuo", dialogo.isChecked)
                .putBoolean("interrompibile", interrompibile.isChecked)
                .putBoolean("conferma_app", confermaApp.isChecked)
                .putBoolean("comandi_rapidi", rapidi.isChecked)
                .putBoolean("streaming", flusso.isChecked)
                .putBoolean("proattivo", proattivo.isChecked)
                .putBoolean("proattivo_calendario", proCalendario.isChecked)
                .putBoolean("proattivo_messaggi", proMessaggi.isChecked)
                .putBoolean("proattivo_meteo", proMeteo.isChecked)
                .putBoolean("proattivo_voce", proVoce.isChecked)
                .apply()
            Proattivo.programma(this)
            aggiungiAlRegistro("Jarvis", "Carattere aggiornato.")
        }
    }

    // ---------- VOCE ----------
    private fun mostraVoce() {
        val pagina = Pagina("Voce")
        var tonoScelto = prefs.getFloat("voce_tono", Voce.TONO_PREDEFINITO)
        var velocitaScelta = prefs.getFloat("voce_velocita", Voce.VELOCITA_PREDEFINITA)

        fun prova() {
            if (VoceNeurale.attiva(this)) {
                prefs.edit().putFloat("voce_velocita", velocitaScelta).apply()
                parla("Buongiorno. Sono ${cervello.nomeAssistente()}, a sua disposizione.")
                return
            }
            val voce = tts ?: return
            Voce.applica(voce, prefs)
            voce.setPitch(tonoScelto)
            voce.setSpeechRate(velocitaScelta)
            voce.speak("Buongiorno. Sono Jarvis, a sua disposizione.", TextToSpeech.QUEUE_FLUSH, null, "prova")
        }

        val nomeVoce = TextView(this).apply { setPadding(0, dp(6), 0, 0) }
        fun aggiornaNomeVoce() {
            val voce = tts
            val scelta = prefs.getString("voce_nome", "") ?: ""
            val elenco = if (voce != null) Voce.vociItaliane(voce) else emptyList()
            val indice = elenco.indexOfFirst { it.name == scelta }
            nomeVoce.text = "Voce attuale: " + if (indice >= 0) Voce.etichetta(elenco[indice], indice + 1) else "predefinita del telefono"
        }

        fun cursore(etichetta: String, valore: Float, cambia: (Float) -> Unit): SeekBar {
            val testo = TextView(this).apply { setPadding(0, dp(10), 0, 0) }
            fun scrivi(v: Float) { testo.text = "$etichetta: ${(v * 100).toInt()}%" }
            scrivi(valore)
            pagina.contenitore.addView(testo)
            val barra = SeekBar(this).apply {
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
            }
            pagina.contenitore.addView(barra)
            return barra
        }

        pagina.titoloSezione("Motore della voce")
        val motore = pagina.scelta(
            listOf(
                "android" to "Voce del telefono (Google)",
                "neurale" to "Voce neurale offline: molto più naturale, funziona senza internet"
            ),
            prefs.getString("motore_voce", "android") ?: "android"
        )
        val statoNeurale = TextView(this).apply { setPadding(0, dp(4), 0, 0); setTextColor(COLORE_TENUE); textSize = 13f }
        fun scriviNeurale() {
            val scelta = VoceNeurale.scelta(this)
            val nome = VoceNeurale.VOCI.firstOrNull { it.id == scelta }?.nome ?: scelta
            statoNeurale.text = "Voce neurale: $nome " + if (VoceNeurale.scaricata(this, scelta)) "✓ sul telefono" else "(da scaricare)"
        }
        scriviNeurale()
        pagina.contenitore.addView(statoNeurale)
        pagina.bottone("Scegli e scarica la voce neurale") {
            val voci = VoceNeurale.VOCI
            val nomi = voci.map { v -> v.nome + (if (VoceNeurale.scaricata(this, v.id)) " ✓" else " · ${v.dimensioneMb} MB") }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Voce neurale")
                .setItems(nomi) { _, i ->
                    val v = voci[i]
                    prefs.edit().putString("voce_neurale", v.id).putString("motore_voce", "neurale").apply()
                    scriviNeurale()
                    if (VoceNeurale.scaricata(this, v.id)) {
                        parla("Buongiorno. Sono ${cervello.nomeAssistente()}, a sua disposizione.")
                    } else {
                        statoNeurale.text = "Scarico ${v.nome}..."
                        Thread {
                            val esito = VoceNeurale.scarica(this, v.id) { p -> runOnUiThread { statoNeurale.text = "Scarico ${v.nome}: $p%" } }
                            runOnUiThread {
                                scriviNeurale()
                                aggiungiAlRegistro("Jarvis", esito)
                                if (VoceNeurale.scaricata(this, v.id)) parla("Buongiorno. Questa è la mia nuova voce.")
                            }
                        }.start()
                    }
                }
                .setNegativeButton("Chiudi", null)
                .show()
        }
        pagina.nota("Le voci neurali (Piper) sono libere e originali. Velocità e prova valgono anche per loro; il tono si regola solo con la voce del telefono.")
        pagina.titoloSezione("Voce del telefono")
        aggiornaNomeVoce()
        pagina.contenitore.addView(nomeVoce)
        pagina.bottone("Scegli la voce") {
            val voce = tts
            val elenco = if (voce != null) Voce.vociItaliane(voce) else emptyList()
            if (elenco.isEmpty()) {
                aggiungiAlRegistro("Jarvis", "Non trovo voci italiane. Installale da Impostazioni > Sintesi vocale > Dati vocali.")
                return@bottone
            }
            val nomi = elenco.mapIndexed { i, v -> Voce.etichetta(v, i + 1) }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Tocca una voce per sentirla")
                .setItems(nomi) { _, i ->
                    prefs.edit().putString("voce_nome", elenco[i].name).apply()
                    aggiornaNomeVoce()
                    prova()
                }
                .setNegativeButton("Chiudi", null)
                .show()
        }
        pagina.titoloSezione("Tono e velocità")
        val barraTono = cursore("Tono", tonoScelto) { tonoScelto = it }
        val barraVelocita = cursore("Velocità", velocitaScelta) { velocitaScelta = it }
        pagina.bottone("Prova la voce") { prova() }
        pagina.bottone("Preset Jarvis: maschile, grave, calmo") {
            val voce = tts
            val maschile = if (voce != null) Voce.vociItaliane(voce).withIndex()
                .firstOrNull { Voce.etichetta(it.value, it.index + 1).contains("maschile") }?.value else null
            if (maschile != null) prefs.edit().putString("voce_nome", maschile.name).apply()
            barraTono.progress = 35        // 85%
            barraVelocita.progress = 45    // 95%
            aggiornaNomeVoce()
            prova()
        }
        pagina.nota("Altre voci: Impostazioni del telefono > Sintesi vocale > Dati vocali di Google, oppure installa un altro motore vocale.")
        pagina.mostra {
            prefs.edit().putFloat("voce_tono", tonoScelto).putFloat("voce_velocita", velocitaScelta)
                .putString("motore_voce", motore()).apply()
            if (motore() == "neurale" && !VoceNeurale.scaricata(this, VoceNeurale.scelta(this))) {
                aggiungiAlRegistro("Jarvis", "Scarica prima una voce neurale: fino ad allora uso la voce del telefono.")
            } else {
                aggiungiAlRegistro("Jarvis", "Voce aggiornata.")
            }
        }
    }

    // ---------- PLUGIN ----------
    private fun mostraPlugin() {
        val pagina = Pagina("Plugin")
        pagina.nota("Jarvis sceglie da solo quale plugin usare in base alla richiesta.")
        val caselle = cervello.PLUGIN.map { plugin -> plugin.id to pagina.casella(plugin.nome, pluginAttivo(plugin.id)) }
        pagina.mostra {
            val modifica = prefs.edit()
            for ((id, casella) in caselle) modifica.putBoolean("plugin_$id", casella.isChecked)
            modifica.apply()
            scriviInfoHud()
            aggiungiAlRegistro("Jarvis", "Plugin aggiornati.")
        }
    }

    // ---------- SEMPRE ATTIVO ----------
    private fun mostraSempreAttivo() {
        val pagina = Pagina("Sempre attivo")
        pagina.nota("Jarvis resta acceso in background con una notifica fissa: di' \"Ehi Jarvis\" oppure tocca Parla " +
            "nella notifica o la tessera Jarvis nel menu a tendina.")
        val parolaAttivazione = pagina.casella("Parola di attivazione \"Jarvis\" (offline)", prefs.getBoolean("parola_attivazione", true))
        pagina.titoloSezione("Nome dell'assistente")
        pagina.nota("Come vuoi chiamarlo: Jarvis, Venerdì, Max... Una sola parola. Dopo averlo cambiato provalo: se non lo riconosco scegline un altro.")
        val nomeAssistente = pagina.campo("Nome", cervello.nomeAssistente())
        pagina.bottone("Prova il nome (parla per 5 secondi)") {
            prefs.edit().putString("nome_assistente", nomeAssistente.text.toString().trim().split(" ").first().ifEmpty { "Jarvis" }).apply()
            if (!permessoMicrofono()) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), CODICE_MIC)
                return@bottone
            }
            ServizioJarvis.invia(this, ServizioJarvis.AZIONE_PAUSA)
            aggiungiAlRegistro("Jarvis", "Di' \"Ehi ${nomeAssistente.text.toString().trim()}\" adesso...")
            ImprontaVocale.provaNome(this) { esito -> aggiungiAlRegistro("Jarvis", esito) }
        }
        pagina.titoloSezione("Comando di attivazione")
        val frase = pagina.scelta(
            listOf(
                "entrambi" to "Ehi Jarvis oppure Ciao Jarvis",
                "ehi" to "Solo Ehi Jarvis",
                "ciao" to "Solo Ciao Jarvis",
                "jarvis" to "Solo Jarvis (si attiva più facilmente)"
            ),
            prefs.getString("frase_attivazione", "entrambi") ?: "entrambi"
        )
        pagina.nota("Di' il comando, fai una piccola pausa e poi la richiesta.")
        pagina.titoloSezione("Sensibilità")
        val sensibilita = pagina.scelta(
            listOf(
                "bassa" to "Bassa: solo se detto chiaro (consigliata con TV o gente intorno)",
                "media" to "Media: equilibrata",
                "alta" to "Alta: lo sente anche da lontano, ma sbaglia più spesso"
            ),
            prefs.getString("sensibilita", "media") ?: "media"
        )
        pagina.titoloSezione("Riconoscimento della voce")
        pagina.nota(
            if (ImprontaVocale.registrata(this)) "Voce registrata ✓ Jarvis può rispondere solo a te."
            else "Registra la tua voce: Jarvis risponderà al comando solo se a parlare sei tu, non la TV o altre persone."
        )
        val soloMiaVoce = pagina.casella("Rispondi solo alla mia voce", prefs.getBoolean("verifica_voce", true))
        val rigore = pagina.scelta(
            listOf(
                "rigido" to "Rigido: più sicuro, a volte non ti riconosce",
                "normale" to "Normale",
                "permissivo" to "Permissivo: ti riconosce sempre, ma è meno selettivo"
            ),
            prefs.getString("rigore_voce", "normale") ?: "normale"
        )
        pagina.bottone(if (ImprontaVocale.registrata(this)) "Registra di nuovo la mia voce" else "Registra la mia voce") {
            registraVoce()
        }
        pagina.titoloSezione("Voci della famiglia")
        val famiglia = ImprontaVocale.famiglia(this)
        pagina.nota(
            if (famiglia.isEmpty()) "Registra anche le voci dei tuoi familiari: Jarvis risponderà anche a loro, li chiamerà per nome e terrà i loro ricordi separati."
            else "Voci registrate: " + famiglia.joinToString(", ") { it.nome }
        )
        pagina.bottone("Aggiungi la voce di un familiare") {
            val campoNome = EditText(this).apply { hint = "Nome, es. Anna" }
            AlertDialog.Builder(this)
                .setTitle("Chi registriamo?")
                .setView(campoNome)
                .setPositiveButton("Registra") { _, _ ->
                    val n = campoNome.text.toString().trim()
                    if (n.isNotEmpty()) registraVoce(n)
                }
                .setNegativeButton("Annulla", null)
                .show()
        }
        if (famiglia.isNotEmpty()) {
            pagina.bottone("Rimuovi la voce di un familiare") {
                AlertDialog.Builder(this)
                    .setTitle("Rimuovi")
                    .setItems(famiglia.map { it.nome }.toTypedArray()) { _, i ->
                        ImprontaVocale.rimuoviPersona(this, famiglia[i].nome)
                        aggiungiAlRegistro("Jarvis", "Voce di ${famiglia[i].nome} rimossa.")
                    }
                    .show()
            }
        }
        if (ImprontaVocale.registrata(this)) {
            pagina.bottone("Cancella la voce registrata") {
                ImprontaVocale.cancella(this)
                aggiungiAlRegistro("Jarvis", "Voce registrata cancellata: ora rispondo a chiunque dica il comando.")
            }
        }

        pagina.titoloSezione("Risparmio batteria")
        val risparmio = pagina.scelta(
            listOf(
                "massimo" to "Massimo: ascolta solo a schermo acceso (o in carica)",
                "equilibrato" to "Equilibrato: sempre, ma si ferma sotto il 20% di batteria",
                "prestazioni" to "Prestazioni: ascolta sempre"
            ),
            prefs.getString("risparmio", "equilibrato") ?: "equilibrato"
        )
        pagina.nota("In ogni caso Jarvis analizza l'audio solo quando qualcuno parla: in silenzio consuma pochissimo.")
        pagina.bottone(if (ServizioJarvis.attivo) "Spegni la modalità sempre attiva" else "Accendi la modalità sempre attiva") {
            if (ServizioJarvis.attivo) spegniSempreAttivo() else accendiSempreAttivo()
        }
        pagina.titoloSezione("Assistente del telefono")
        pagina.nota("Rendi Jarvis l'assistente predefinito: tenendo premuto il tasto di accensione (o con il gesto " +
            "dall'angolo) si apre Jarvis già in ascolto. Scegli Jarvis in \"App assistente digitale\".")
        pagina.bottone("Imposta Jarvis come assistente") {
            if (!apriImpostazione(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), silenzioso = true)) {
                apriImpostazione(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        }
        pagina.titoloSezione("Auto e cuffie")
        pagina.nota("Quando il telefono si collega al Bluetooth dell'auto Jarvis si mette in ascolto e risponde in breve. " +
            "Con le cuffie ascolta anche a schermo spento." +
            (prefs.getString("ultimo_bluetooth", "")?.takeIf { it.isNotBlank() }?.let { "\nUltimo dispositivo collegato: $it" } ?: ""))
        val autoAttiva = pagina.casella("Modalità auto e cuffie automatica", prefs.getBoolean("auto_attiva", true))
        val nomiAuto = pagina.campo("Nome Bluetooth della tua auto (facoltativo, separati da virgola)", prefs.getString("auto_nomi", "") ?: "")
        pagina.bottone("L'ultimo dispositivo collegato è la mia auto") {
            val ultimo = prefs.getString("ultimo_bluetooth", "") ?: ""
            if (ultimo.isNotBlank()) nomiAuto.setText(ultimo) else aggiungiAlRegistro("Jarvis", "Collega prima il telefono all'auto.")
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pagina.bottone("Permetti a Jarvis di vedere i dispositivi Bluetooth") {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 5)
            }
        }
        pagina.titoloSezione("Permessi per le altre app")
        pagina.bottone("Accesso alle notifiche" + if (NotificheJarvis.abilitato(this)) " ✓" else "") {
            apriImpostazione(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        pagina.bottone("Controllo delle app (Accessibilità)" + if (AccessibilitaJarvis.istanza != null) " ✓" else "") {
            apriImpostazione(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        pagina.bottone("Batteria senza limiti") {
            apriImpostazione(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        pagina.bottone("Avvio automatico (Xiaomi)") {
            val xiaomi = Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            )
            if (!apriImpostazione(xiaomi, silenzioso = true)) {
                apriImpostazione(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        pagina.nota("Se Android non ti lascia attivare notifiche o accessibilità: Impostazioni > App > Jarvis > menu ⋮ > " +
            "Consenti impostazioni con limitazioni, poi riprova.")
        pagina.mostra {
            val nuovoNome = nomeAssistente.text.toString().trim().split(" ").first().ifEmpty { "Jarvis" }
            val cambiata = parolaAttivazione.isChecked != prefs.getBoolean("parola_attivazione", true) ||
                nuovoNome != cervello.nomeAssistente()
            prefs.edit()
                .putString("nome_assistente", nuovoNome)
                .putBoolean("parola_attivazione", parolaAttivazione.isChecked)
                .putString("sensibilita", sensibilita())
                .putString("frase_attivazione", frase())
                .putBoolean("verifica_voce", soloMiaVoce.isChecked)
                .putString("rigore_voce", rigore())
                .putString("risparmio", risparmio())
                .putBoolean("auto_attiva", autoAttiva.isChecked)
                .putString("auto_nomi", nomiAuto.text.toString().trim())
                .apply()
            if (cambiata && ServizioJarvis.attivo) {
                stopService(Intent(this, ServizioJarvis::class.java))
                reattore.postDelayed({ ServizioJarvis.avvia(this) }, 600)
            }
        }
    }

    // ---------- registrazione della voce ----------
    private fun registraVoce(persona: String = "") {
        if (!permessoMicrofono()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), CODICE_MIC)
            return
        }
        val nome = cervello.nomeAssistente()
        val frase = when (prefs.getString("frase_attivazione", "entrambi")) {
            "ciao" -> "Ciao $nome"
            "jarvis" -> nome
            else -> "Ehi $nome"
        }
        val totale = 5
        val testo = TextView(this).apply {
            text = "Preparo il microfono..."
            textSize = 16f
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val finestra = AlertDialog.Builder(this)
            .setTitle(if (persona.isEmpty()) "Registra la tua voce" else "Registra la voce di $persona")
            .setView(testo)
            .setNegativeButton("Annulla") { _, _ -> ImprontaVocale.ferma() }
            .setCancelable(false)
            .show()
        fun mostra(n: Int) {
            testo.text = (if (persona.isEmpty()) "" else "$persona, ") + "di' \"$frase\" con la tua voce normale, poi fai una pausa.\n\n" +
                "Frasi registrate: $n di $totale\n" + "●".repeat(n) + "○".repeat(totale - n)
        }
        ImprontaVocale.carica(this) { m ->
            if (m == null) {
                finestra.dismiss()
                aggiungiAlRegistro("Jarvis", "Non riesco a preparare il riconoscimento della voce.")
                return@carica
            }
            mostra(0)
            ImprontaVocale.registra(this, totale,
                avanzamento = { n -> mostra(n) },
                fine = { riuscito, messaggio ->
                    finestra.dismiss()
                    aggiungiAlRegistro("Jarvis", messaggio)
                    if (riuscito) parla(messaggio)
                },
                persona = persona
            )
        }
    }

    // ---------- MATTINO ----------
    private fun mostraMattino() {
        val pagina = Pagina("Mattino")
        pagina.nota("Ogni mattina, all'ora scelta, Jarvis ti fa il riepilogo della giornata. " +
            "Se è sempre attivo lo legge a voce, altrimenti ti manda una notifica.")
        val attivo = pagina.casella("Riepilogo automatico ogni mattina", prefs.getBoolean("mattino_attivo", false))
        val ora = pagina.campo("Ora (HH:MM)", prefs.getString("mattino_ora", "07:30") ?: "07:30")
        pagina.titoloSezione("Cosa includere")
        val meteo = pagina.casella("Meteo", prefs.getBoolean("mattino_meteo", true))
        val calendario = pagina.casella("Impegni del calendario", prefs.getBoolean("mattino_calendario", true))
        val promemoria = pagina.casella("Promemoria di oggi", prefs.getBoolean("mattino_promemoria", true))
        val messaggi = pagina.casella("Messaggi ricevuti", prefs.getBoolean("mattino_messaggi", true))
        val notizie = pagina.casella("Notizie", prefs.getBoolean("mattino_notizie", true))
        pagina.titoloSezione("Notizie da leggere")
        val categoria = pagina.scelta(
            listOf(
                "principali" to "Principali",
                "cronaca" to "Cronaca",
                "politica" to "Politica",
                "economia" to "Economia",
                "mondo" to "Mondo",
                "sport" to "Sport",
                "tecnologia" to "Tecnologia"
            ),
            prefs.getString("mattino_categoria", "principali") ?: "principali"
        )
        pagina.bottone("Fammi sentire il riepilogo adesso") {
            gestisciRichiesta("Fammi il riepilogo del mattino.")
        }
        pagina.titoloSezione("Ricerca Google (facoltativa)")
        pagina.nota("Gratis Jarvis usa ANSA, Google News e Wikipedia. Con il piano a pagamento di Gemini puoi attivare " +
            "la ricerca Google vera: include 5.000 ricerche gratuite al mese. Col piano gratuito lascia spento.")
        val ricercaGoogle = pagina.casella("Usa la ricerca Google di Gemini", prefs.getBoolean("ricerca_google", false))
        pagina.mostra {
            val orario = ora.text.toString().trim().replace(".", ":")
            val valido = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$").matches(orario)
            prefs.edit()
                .putBoolean("mattino_attivo", attivo.isChecked)
                .putString("mattino_ora", if (valido) orario else "07:30")
                .putBoolean("mattino_meteo", meteo.isChecked)
                .putBoolean("mattino_calendario", calendario.isChecked)
                .putBoolean("mattino_promemoria", promemoria.isChecked)
                .putBoolean("mattino_messaggi", messaggi.isChecked)
                .putBoolean("mattino_notizie", notizie.isChecked)
                .putString("mattino_categoria", categoria())
                .putBoolean("ricerca_google", ricercaGoogle.isChecked)
                .apply()
            Promemoria.programmaRiepilogo(this)
            if (attivo.isChecked) {
                aggiungiAlRegistro("Jarvis", "Riepilogo del mattino programmato alle ${if (valido) orario else "07:30"}." +
                    if (!valido) " L'ora che hai scritto non era valida." else "")
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
            }
        }
    }

    // ---------- ASPETTO ----------
    private fun mostraAspetto() {
        val pagina = Pagina("Aspetto")
        pagina.titoloSezione("Colori")
        val colori = pagina.scelta(Tema.PALETTE.map { it.id to it.nome }, tema.id)
        pagina.titoloSezione("Interfaccia")
        val interfaccia = pagina.scelta(Tema.INTERFACCE, prefs.getString("interfaccia", "hud") ?: "hud")
        pagina.titoloSezione("Cuore animato")
        val forma = pagina.scelta(Tema.FORME, prefs.getString("forma", "sfera") ?: "sfera")
        pagina.titoloSezione("Conversazione")
        val modo = pagina.scelta(
            listOf(
                "sottotitoli" to "Sottotitoli: solo l'ultima domanda e risposta",
                "cronologia" to "Cronologia: tutta la conversazione che scorre"
            ),
            prefs.getString("conversazione", "sottotitoli") ?: "sottotitoli"
        )
        pagina.titoloSezione("Sfondo")
        val sfondo = pagina.scelta(Tema.SFONDI, prefs.getString("sfondo", "nessuno") ?: "nessuno")
        pagina.titoloSezione("Testo")
        val dimensione = pagina.scelta(
            listOf("piccolo" to "Piccolo", "normale" to "Normale", "grande" to "Grande", "enorme" to "Molto grande"),
            prefs.getString("testo_dimensione", "normale") ?: "normale"
        )
        val carattereScelto = pagina.scelta(
            listOf("serif" to "Elegante (con grazie)", "sans" to "Moderno", "mono" to "Tecnico (macchina da scrivere)"),
            prefs.getString("testo_carattere", "serif") ?: "serif"
        )
        val dati = pagina.casella("Riga di dati in alto (ora, batteria, plugin)", prefs.getBoolean("mostra_dati", true))
        pagina.titoloSezione("Animazioni")
        val animazioni = pagina.scelta(
            listOf(
                "piene" to "Piene: sfera fluida e particelle",
                "ridotte" to "Ridotte: più lente, consumano meno batteria"
            ),
            prefs.getString("animazioni", "piene") ?: "piene"
        )
        pagina.titoloSezione("Gioca con la sfera")
        pagina.nota("Due dita per lo zoom · trascina in cerchio per far girare gli anelli · lancia con il dito per farli ruotare · " +
            "tocca per le onde di luce · doppio tocco per tornare normale · cinque tocchi veloci per una sorpresa.")
        pagina.bottone("✎ Modifica dal vivo: dimensione, velocità, particelle, colore...") { mostraEditor() }
        pagina.mostra {
            val cambiaTutto = colori() != tema.id || interfaccia() != prefs.getString("interfaccia", "hud") ||
                dimensione() != prefs.getString("testo_dimensione", "normale") ||
                carattereScelto() != prefs.getString("testo_carattere", "serif") || dati.isChecked != prefs.getBoolean("mostra_dati", true)
            prefs.edit()
                .putString("conversazione", modo())
                .putString("animazioni", animazioni())
                .putString("tema", colori())
                .putString("interfaccia", interfaccia())
                .putString("forma", forma())
                .putString("sfondo", sfondo())
                .putString("testo_dimensione", dimensione())
                .putString("testo_carattere", carattereScelto())
                .putBoolean("mostra_dati", dati.isChecked)
                .apply()
            if (cambiaTutto) {
                recreate()      // ricostruisce la schermata con i nuovi colori
            } else {
                applicaRegolazioni(reattore)
                ridisegnaConversazione()
            }
        }
    }

    // ================================================================
    //  EDITOR DAL VIVO: si regola il cuore animato e si vede subito il risultato
    // ================================================================

    private var editor: View? = null

    private fun mostraEditor() {
        if (editor != null) return
        chiudiMenuRadiale()
        val r = reattore
        val pannello = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val contenitore = ScrollView(this).apply {
            addView(pannello)
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Tema.conAlfa(tema.accento, 150))
                setColor(Tema.conAlfa(tema.superficie, 235))
            }
        }
        val intestazione = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        intestazione.addView(testoHud("MODIFICA DAL VIVO", COLORE_ORO, 12f, 0.2f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        intestazione.addView(testoHud("✕", COLORE_ORO, 16f, 0f).apply {
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { chiudiEditor(false) }
        })
        pannello.addView(intestazione)

        fun cursore(etichetta: String, minimo: Float, massimo: Float, valore: Float, formato: (Float) -> String, cambia: (Float) -> Unit) {
            val testo = TextView(this).apply {
                setTextColor(COLORE_BIANCO)
                textSize = 12f
                setPadding(0, dp(8), 0, 0)
            }
            fun scrivi(v: Float) { testo.text = "$etichetta: ${formato(v)}" }
            scrivi(valore)
            pannello.addView(testo)
            pannello.addView(SeekBar(this).apply {
                max = 1000
                progress = (((valore - minimo) / (massimo - minimo)) * 1000).toInt().coerceIn(0, 1000)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar?, p: Int, utente: Boolean) {
                        val v = minimo + (massimo - minimo) * p / 1000f
                        scrivi(v)
                        cambia(v)
                    }
                    override fun onStartTrackingTouch(b: SeekBar?) {}
                    override fun onStopTrackingTouch(b: SeekBar?) {}
                })
            })
        }
        val percento: (Float) -> String = { "${(it * 100).roundToInt()}%" }
        cursore("Dimensione", 0.5f, 1.8f, r.scala, percento) { r.scala = it }
        cursore("Velocità", 0f, 3f, r.velocitaBase, percento) { r.velocitaBase = it }
        cursore("Particelle", 0f, 160f, r.numeroParticelle.toFloat(), { "${it.roundToInt()}" }) { r.numeroParticelle = it.roundToInt() }
        cursore("Luminosità", 0.3f, 1.6f, r.luminosita, percento) { r.luminosita = it }
        cursore("Spessore delle linee", 0.5f, 2.5f, r.spessore, percento) { r.spessore = it }
        val base = Tema.PALETTE.firstOrNull { it.id == prefs.getString("tema", "oro") } ?: Tema.PALETTE[0]
        var tonalita = prefs.getInt("r_tonalita", -1)
        cursore("Colore", -1f, 360f, tonalita.toFloat(), { if (it < 0f) "del tema" else "tonalità ${it.roundToInt()}°" }) {
            tonalita = it.roundToInt()
            r.palette = if (tonalita >= 0) Tema.conTonalita(base, tonalita.toFloat()) else base
        }

        // scelte che si cambiano con un tocco
        fun ciclo(etichetta: String, opzioni: List<Pair<String, String>>, attuale: () -> String, imposta: (String) -> Unit) {
            val b = testoHud("", COLORE_ORO, 12f, 0.1f).apply {
                setPadding(0, dp(12), 0, dp(12))
            }
            fun scrivi() {
                val nome = opzioni.firstOrNull { it.first == attuale() }?.second?.substringBefore(":") ?: attuale()
                b.text = "$etichetta: ${nome.uppercase(Locale.ITALIAN)}  ▸"
            }
            scrivi()
            b.setOnClickListener {
                val i = opzioni.indexOfFirst { it.first == attuale() }
                imposta(opzioni[(i + 1) % opzioni.size].first)
                scrivi()
            }
            pannello.addView(b)
        }
        ciclo("FORMA", Tema.FORME, { r.forma }) { r.forma = it }
        ciclo("STILE", Tema.INTERFACCE, { r.interfaccia }) { r.interfaccia = it }
        ciclo("SFONDO", Tema.SFONDI, { r.sfondo }) { r.sfondo = it }
        ciclo("TACCHE", listOf("si" to "Sì", "no" to "No"), { if (r.tacche) "si" else "no" }) { r.tacche = it == "si" }

        val riga = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun azione(t: String, f: () -> Unit) = riga.addView(Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { f() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        azione("Salva") {
            prefs.edit()
                .putFloat("r_scala", r.scala)
                .putFloat("r_velocita", r.velocitaBase)
                .putInt("r_particelle", r.numeroParticelle)
                .putFloat("r_luminosita", r.luminosita)
                .putFloat("r_spessore", r.spessore)
                .putBoolean("r_tacche", r.tacche)
                .putInt("r_tonalita", tonalita)
                .putString("forma", r.forma)
                .putString("interfaccia", r.interfaccia)
                .putString("sfondo", r.sfondo)
                .apply()
            chiudiEditor(true)
        }
        azione("Sorpresa") {
            val c = kotlin.random.Random(System.nanoTime())
            r.forma = Tema.FORME[c.nextInt(Tema.FORME.size)].first
            r.interfaccia = Tema.INTERFACCE[c.nextInt(Tema.INTERFACCE.size)].first
            r.sfondo = Tema.SFONDI[c.nextInt(Tema.SFONDI.size)].first
            r.velocitaBase = 0.5f + c.nextFloat() * 2f
            r.numeroParticelle = c.nextInt(20, 160)
            r.spessore = 0.7f + c.nextFloat() * 1.3f
            tonalita = c.nextInt(0, 360)
            r.palette = Tema.conTonalita(base, tonalita.toFloat())
            chiudiEditor(false, ricarica = false)
            mostraEditor()
        }
        azione("Predefinito") {
            prefs.edit().remove("r_scala").remove("r_velocita").remove("r_particelle").remove("r_luminosita")
                .remove("r_spessore").remove("r_tacche").remove("r_tonalita").apply()
            r.palette = base
            applicaRegolazioni(r)
            chiudiEditor(false, ricarica = false)
            mostraEditor()
        }
        pannello.addView(riga)

        editor = contenitore
        contenitoreSfera.addView(contenitore, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, (contenitoreSfera.height * 0.62f).toInt().coerceAtLeast(dp(260)), Gravity.BOTTOM
        ))
    }

    private fun chiudiEditor(salvato: Boolean, ricarica: Boolean = true) {
        editor?.let { contenitoreSfera.removeView(it) }
        editor = null
        if (salvato) {
            val cambiaColore = prefs.getInt("r_tonalita", -1) >= 0 || tema != Tema.attuale(prefs)
            aggiungiAlRegistro("Jarvis", "Aspetto salvato.")
            if (cambiaColore) recreate()
        } else if (ricarica) {
            // annullato: torna come prima
            reattore.palette = tema
            applicaRegolazioni(reattore)
        }
    }

    // ---------- COLLEGAMENTI: casa, email, PC ----------
    private fun mostraCollegamenti() {
        val pagina = Pagina("Collegamenti")
        pagina.titoloSezione("Casa intelligente · Home Assistant")
        pagina.nota("Home Assistant è gratuito e collega quasi tutto: luci, prese, termostati, Google Home, Alexa, Tuya/Smart Life, " +
            "Hue, Shelly, Xiaomi. Token: nel tuo profilo di Home Assistant > Sicurezza > Token di accesso a lunga durata.")
        val casaUrl = pagina.campo("Indirizzo (es. http://192.168.1.10:8123)", prefs.getString("casa_url", "") ?: "")
        val casaToken = pagina.campo("Token di accesso", Cassaforte.leggi(prefs, "casa_token"), true)
        pagina.titoloSezione("Scorciatoie web")
        pagina.nota("Una per riga: nome = indirizzo. Per esempio:\ncancello = https://maker.ifttt.com/trigger/cancello/with/key/...\n" +
            "presa = http://192.168.1.50/relay/0?turn=toggle\nMetti POST prima dell'indirizzo se il servizio lo richiede.")
        val scorciatoie = EditText(this).apply {
            setText(prefs.getString("scorciatoie", "") ?: "")
            minLines = 3
            textSize = 13f
        }
        pagina.contenitore.addView(scorciatoie)

        pagina.titoloSezione("Email")
        pagina.nota("Per Gmail serve una \"password per le app\": myaccount.google.com/apppasswords (richiede la verifica in due passaggi). " +
            "Funziona anche con Libero, Yahoo, iCloud e altre caselle IMAP. Outlook/Hotmail non accetta più queste password.")
        val email = pagina.campo("Il tuo indirizzo email", prefs.getString("posta_indirizzo", "") ?: "")
        val emailPassword = pagina.campo("Password per le app", Cassaforte.leggi(prefs, "posta_password"), true)
        val imap = pagina.campo("Server IMAP (facoltativo)", prefs.getString("posta_imap", "") ?: "")
        val smtp = pagina.campo("Server SMTP (facoltativo)", prefs.getString("posta_smtp", "") ?: "")

        pagina.titoloSezione("PC Windows")
        pagina.nota("Sul PC avvia jarvis_pc.py (lo trovi nel progetto su GitHub): ti mostra indirizzo, codice e MAC da scrivere qui. " +
            "PC e telefono devono essere sulla stessa rete Wi-Fi. Per accenderlo da spento attiva il Wake-on-LAN nel BIOS. " +
            "Per mandare un file al PC: Condividi > Jarvis: invia al PC.")
        val pcIp = pagina.campo("Indirizzo del PC (es. 192.168.1.20)", prefs.getString("pc_ip", "") ?: "")
        val pcToken = pagina.campo("Codice di collegamento", Cassaforte.leggi(prefs, "pc_token"), true)
        val pcMac = pagina.campo("MAC del PC, per accenderlo (facoltativo)", prefs.getString("pc_mac", "") ?: "")
        pagina.bottone("Prova il collegamento") {
            prefs.edit().putString("pc_ip", pcIp.text.toString().trim()).let { Cassaforte.scrivi(it, "pc_token", pcToken.text.toString().trim()) }
                .putString("casa_url", casaUrl.text.toString().trim()).let { Cassaforte.scrivi(it, "casa_token", casaToken.text.toString().trim()) }
                .putString("posta_indirizzo", email.text.toString().trim()).let { Cassaforte.scrivi(it, "posta_password", emailPassword.text.toString().trim()) }
                .putString("posta_imap", imap.text.toString().trim()).putString("posta_smtp", smtp.text.toString().trim())
                .apply()
            aggiungiAlRegistro("Jarvis", "Provo i collegamenti...")
            Thread {
                val esiti = mutableListOf<String>()
                if (pcIp.text.isNotBlank()) esiti.add("PC: " + PontePc(prefs).comando("stato", ""))
                if (casaUrl.text.isNotBlank()) esiti.add("Casa: " + Casa(prefs).elenca("").lines().size.let { "$it dispositivi trovati" })
                if (email.text.isNotBlank()) esiti.add("Email: " + Posta(prefs).nonLette().let { if (it >= 0) "collegata, $it non lette" else "non riesco ad accedere" })
                runOnUiThread { aggiungiAlRegistro("Jarvis", if (esiti.isEmpty()) "Non c'è niente da provare." else esiti.joinToString("\n")) }
            }.start()
        }
        pagina.mostra {
            prefs.edit()
                .putString("casa_url", casaUrl.text.toString().trim())
                .let { Cassaforte.scrivi(it, "casa_token", casaToken.text.toString().trim()) }
                .putString("scorciatoie", scorciatoie.text.toString().trim())
                .putString("posta_indirizzo", email.text.toString().trim())
                .let { Cassaforte.scrivi(it, "posta_password", emailPassword.text.toString().trim()) }
                .putString("posta_imap", imap.text.toString().trim())
                .putString("posta_smtp", smtp.text.toString().trim())
                .putString("pc_ip", pcIp.text.toString().trim())
                .let { Cassaforte.scrivi(it, "pc_token", pcToken.text.toString().trim()) }
                .putString("pc_mac", pcMac.text.toString().trim())
                .apply()
            aggiungiAlRegistro("Jarvis", "Collegamenti salvati.")
        }
    }

    // ---------- DATI: statistiche e backup ----------
    private val CODICE_ESPORTA = 20
    private val CODICE_IMPORTA = 21
    private val CODICE_CARTELLA = 22

    private fun mostraDati() {
        val pagina = Pagina("Dati")
        pagina.titoloSezione("Statistiche")
        pagina.contenitore.addView(TextView(this).apply {
            text = Statistiche.descrivi(this@MainActivity)
            typeface = MONO
            textSize = 11.5f
            setTextColor(COLORE_BIANCO)
            setPadding(0, dp(6), 0, 0)
            setTextIsSelectable(true)
        })
        pagina.titoloSezione("Aggiornamenti")
        pagina.nota("Versione installata: ${Aggiornamenti.versioneInstallata(this)}. Jarvis controlla da solo ogni qualche ora se ce n'è una nuova.")
        pagina.bottone("Cerca aggiornamenti adesso") { aggiornaOra() }
        val autoAggiorna = pagina.casella("Avvisami quando c'è una versione nuova", prefs.getBoolean("aggiornamenti_auto", true))
        autoAggiorna.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("aggiornamenti_auto", v).apply() }
        pagina.titoloSezione("Resoconto della settimana")
        val resoconto = pagina.casella("Ogni domenica sera, il resoconto della settimana", prefs.getBoolean("resoconto", true))
        resoconto.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("resoconto", v).apply() }
        pagina.bottone("Fammi il resoconto adesso") { gestisciRichiesta("Fammi il resoconto della settimana.") }
        pagina.titoloSezione("Backup della memoria")
        pagina.nota("Salva ricordi, lezioni, routine, diario, note, promemoria, luoghi, voce registrata e impostazioni in un file. " +
            "Puoi metterlo su Google Drive e ripristinarlo su un altro telefono. Le chiavi API e le password non vengono copiate.")
        pagina.bottone("Salva un backup adesso") {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, Backup.nomeFile()),
                CODICE_ESPORTA
            )
        }
        pagina.bottone("Ripristina da un backup") {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                CODICE_IMPORTA
            )
        }
        val cartella = prefs.getString("backup_cartella", "") ?: ""
        pagina.bottone(if (cartella.isEmpty()) "Backup automatico ogni settimana: scegli la cartella" else "Backup automatico attivo ✓ (cambia cartella)") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), CODICE_CARTELLA)
        }
        if (cartella.isNotEmpty()) {
            pagina.bottone("Disattiva il backup automatico") {
                prefs.edit().remove("backup_cartella").apply()
                aggiungiAlRegistro("Jarvis", "Backup automatico disattivato.")
            }
        }
        pagina.bottone("Azzera le statistiche") {
            Statistiche.azzera(this)
            aggiungiAlRegistro("Jarvis", "Statistiche azzerate.")
        }
        pagina.mostra()
    }

    /** Cerca una versione nuova su GitHub, la scarica e apre l'installazione. */
    private fun aggiornaOra() {
        aggiungiAlRegistro("Jarvis", "Cerco aggiornamenti...")
        Thread {
            val nuova = try { Aggiornamenti.controlla(this) } catch (e: Exception) { null }
            if (nuova == null) {
                runOnUiThread { aggiungiAlRegistro("Jarvis", "Hai già l'ultima versione (${Aggiornamenti.versioneInstallata(this)}).") }
                return@Thread
            }
            runOnUiThread { stato.text = "Scarico la versione ${nuova.numero}..." }
            val file = try {
                Aggiornamenti.scarica(this, nuova) { p -> runOnUiThread { stato.text = "Scarico la versione ${nuova.numero}: $p%" } }
            } catch (e: Exception) { null }
            runOnUiThread {
                stato.text = ""
                if (file == null) {
                    aggiungiAlRegistro("Jarvis", "Non sono riuscito a scaricare l'aggiornamento. Riprova più tardi.")
                } else if (!Aggiornamenti.installa(this, file)) {
                    aggiungiAlRegistro("Jarvis", "Permetti a Jarvis di installare app (si è aperta la pagina), poi torna qui e tocca di nuovo Cerca aggiornamenti.")
                } else {
                    aggiungiAlRegistro("Jarvis", "Conferma l'installazione della versione ${nuova.numero}: ricordi e impostazioni restano.")
                }
            }
        }.start()
    }

    private fun risultatoDati(codice: Int, dati: Intent?) {
        val uri = dati?.data ?: return
        when (codice) {
            CODICE_ESPORTA -> Thread {
                val esito = try {
                    contentResolver.openOutputStream(uri)?.use { it.write(Backup.esporta(this).toByteArray(Charsets.UTF_8)) }
                    "Backup salvato."
                } catch (e: Exception) {
                    "Non sono riuscito a salvare il backup: ${e.message}"
                }
                runOnUiThread { aggiungiAlRegistro("Memoria", esito) }
            }.start()
            CODICE_IMPORTA -> Thread {
                val esito = try {
                    val testo = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    Backup.importa(this, testo)
                } catch (e: Exception) {
                    "Non sono riuscito a leggere il backup: ${e.message}"
                }
                runOnUiThread {
                    aggiungiAlRegistro("Memoria", esito)
                    scriviInfoHud()
                }
            }.start()
            CODICE_CARTELLA -> {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) { }
                prefs.edit().putString("backup_cartella", uri.toString()).apply()
                Thread {
                    val esito = Backup.salvaInCartella(this, uri)
                    runOnUiThread { aggiungiAlRegistro("Memoria", "Backup automatico attivo. $esito") }
                }.start()
            }
        }
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
        testo.append("\nPROMEMORIA\n").append(Promemoria.descrivi(this)).append("\n")
        testo.append("\nLISTE\n").append(Liste.descriviTutte(this)).append("\n")
        testo.append("\nAUTOMAZIONI\n").append(Automazioni.descriviTutte(this)).append("\n")
        val luoghi = prefs.getString("luoghi", "[]") ?: "[]"
        testo.append("\nLUOGHI SALVATI\n").append(
            try {
                val a = org.json.JSONArray(luoghi)
                if (a.length() == 0) "Nessuno. Prova: \"salva questo posto come casa\".\n"
                else (0 until a.length()).joinToString("\n") { "• ${a.getJSONObject(it).optString("nome")}: ${a.getJSONObject(it).optString("indirizzo")}" } + "\n"
            } catch (e: Exception) { "\n" }
        )
        testo.append("\nRIASSUNTO DELLE CONVERSAZIONI PASSATE\n")
        testo.append(riassunto.ifBlank { "Ancora vuoto: si crea quando la conversazione diventa lunga." }).append("\n")
        testo.append("\nDIARIO\n$richieste richieste salvate sul telefono.")

        val vista = TextView(this).apply {
            text = testo
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setTextIsSelectable(true)
        }
        val contenuto = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(this@MainActivity).apply {
                text = "Cronologia delle richieste"
                isAllCaps = false
                setOnClickListener { mostraCronologia() }
            })
            addView(vista)
        }

        AlertDialog.Builder(this)
            .setTitle("Memoria di Jarvis")
            .setView(ScrollView(this).apply { addView(contenuto) })
            .setPositiveButton("Chiudi", null)
            .setNeutralButton("Nuova conversazione") { _, _ ->
                Thread { cervello.nuovaConversazione() }.start()
                aggiungiAlRegistro("Jarvis", "Ok, ricominciamo da capo. I ricordi restano.")
            }
            .setNegativeButton("Cancella tutto") { _, _ -> confermaCancellaTutto() }
            .show()
    }

    private fun mostraCronologia() {
        val vista = TextView(this).apply {
            text = cervello.ultimeRichieste(40)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Cronologia")
            .setView(ScrollView(this).apply { addView(vista) })
            .setPositiveButton("Chiudi", null)
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
        if (!Orecchie.disponibile(this)) {
            aggiungiAlRegistro("Jarvis", "Il riconoscimento vocale non è disponibile. Installa o aggiorna l'app Google.")
            return
        }
        parlatore.ferma()
        parlando = false
        if (riconoscitore == null) {
            riconoscitore = Orecchie.crea(this).apply {
                setRecognitionListener(ascoltatore)
            }
        }
        riconoscitore?.startListening(Orecchie.richiesta(this, true))
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
        parlatore.di(testo)
    }

    // ================================================================
    //  GESTIONE DELLE RICHIESTE
    // ================================================================

    private fun gestisciRichiesta(testo: String) {
        if (occupato) return
        aggiungiAlRegistro("Tu", testo)
        occupato = true
        stato.text = ""
        aggiornaMic()
        cervello.chiParla = ""
        parlatore.inizia()
        risposteParziale = StringBuilder()

        Thread {
            // la risposta arriva a pezzi: Jarvis parla subito e i sottotitoli si scrivono man mano
            val risposta = cervello.rispondi(testo) { pezzo ->
                parlatore.aggiungi(pezzo)
                runOnUiThread {
                    risposteParziale.append(pezzo)
                    if (modoSottotitoli()) {
                        ultimaRisposta = risposteParziale.toString()
                        ridisegnaConversazione()
                    } else {
                        stato.text = risposteParziale.toString().takeLast(160)
                    }
                }
            }
            runOnUiThread {
                occupato = false
                stato.text = ""
                aggiornaMic()
                aggiungiAlRegistro("Jarvis", risposta.testo)
                if (risposta.fonti.isNotEmpty()) aggiungiAlRegistro("Fonti", risposta.fonti.joinToString(" · "))
                parlatore.fine(risposta.testo)
                if (risposta.mancaChiave) mostraCervello()
                scriviInfoHud()
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
            "Modalità sempre attiva accesa. Quando chiudi questa schermata, di' \"Ehi Jarvis\" o \"Ciao Jarvis\" per chiamarmi. " +
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
