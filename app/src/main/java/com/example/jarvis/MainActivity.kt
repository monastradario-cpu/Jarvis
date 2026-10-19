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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.Toast
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
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

    // Personalizzazione: sfondo con foto, blocchi, pulsanti rapidi, dock, gioco in corso
    private lateinit var fondo: FrameLayout
    private lateinit var fotoSfondo: ImageView
    private lateinit var veloSfondo: View
    private lateinit var scorriSopra: HorizontalScrollView
    private lateinit var scorriSotto: HorizontalScrollView
    private lateinit var blocchiSopra: LinearLayout
    private lateinit var blocchiSotto: LinearLayout
    private lateinit var scorriPulsanti: HorizontalScrollView
    private lateinit var rigaPulsanti: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var rigaGioco: TextView
    private var pannello: PannelloPersonalizza? = null
    private var barraAnteprima: View? = null
    private var sensori: SensoriJarvis? = null
    private var torciaAccesa = false
    private var fotoCaricata = ""
    @Volatile private var meteoBreve = ""
    @Volatile private var meteoQuando = 0L
    @Volatile private var agendaBreve = ""
    @Volatile private var agendaQuando = 0L

    // Sottotitoli: ultima domanda, ultima risposta e note
    private var ultimaDomanda = ""
    private var ultimaRisposta = ""
    private val noteRecenti = mutableListOf<Pair<String, String>>()
    private val cronologiaSessione = mutableListOf<Pair<String, String>>()
    private val aggiornaHud = object : Runnable {
        override fun run() {
            scriviInfoHud()
            if (!reattore.dorme) reattore.umore = Umore.attuale(this@MainActivity)
            if (Personalizza.blocchi(prefs).isNotEmpty()) riempiBlocchi()
            reattore.postDelayed(this, 20_000)
        }
    }

    /** Un ticchettio leggero mentre Jarvis pensa (se acceso in SUONI). */
    private val ticchettio = object : Runnable {
        override fun run() {
            if (occupato && risposteParziale.isEmpty()) {
                Suoni.ticchettio(this@MainActivity)
                reattore.postDelayed(this, 650)
            }
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
                Suoni.fineRisposta(this)
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
                Voce.ttsAttivo = tts
                tts?.setOnUtteranceProgressListener(ascoltoVoce)
            }
        }
        parlatore.ascoltatore = ascoltoVoce
        mostraUltimoErrore()
        // Modelli vocali pronti in anticipo (per l'interruzione con "basta") e controlli proattivi
        if (permessoMicrofono()) ImprontaVocale.carica(this) { }
        Proattivo.programma(this)
        WidgetJarvis.aggiorna(this)

        // Jarvis vivo: scossa, inclinazione, telefono a faccia in giù
        sensori = SensoriJarvis(this,
            alScossa = { runOnUiThread { scossa() } },
            alInclinare = { x, y -> reattore.inclinaX = x; reattore.inclinaY = y },
            alCapovolgere = { giu -> runOnUiThread { capovolto(giu) } }
        )

        val riapri = prefs.getString("riapri", null)
        if (riapri != null) {
            // la schermata è stata ricostruita dopo un salvataggio: si torna alla pagina, non alla home
            prefs.edit().remove("riapri").apply()
            reattore.post {
                when (riapri) {
                    "aspetto" -> mostraAspetto()
                    "editor" -> mostraEditor()
                }
            }
        } else if (chiaveAttiva().isEmpty()) {
            aggiungiAlRegistro("Jarvis", "Benvenuto! Crea una chiave gratuita su aistudio.google.com/apikey e incollala in Cervello.")
            mostraCervello()
        } else if (cervello.haConversazione()) {
            aggiungiAlRegistro("Jarvis", "Bentornato. Ricordo la nostra ultima conversazione. Tocchi la sfera per parlare, la tenga premuta per il menu.")
        } else {
            aggiungiAlRegistro("Jarvis", "Pronto. Tocchi la sfera per parlare, la tenga premuta per il menu.")
        }
        if (riapri == null) Umore.sorpresaDelGiorno(this)?.let { aggiungiAlRegistro("Jarvis", it) }

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
        Trofei.alSblocco = { messaggio -> runOnUiThread { trofeoSbloccato(messaggio) } }
        sensori?.avvia()
        reattore.umore = Umore.attuale(this)
        aggiornaPersonalizzazione()
        reattore.post(aggiornaHud)
        // Mentre la schermata è aperta, la parola di attivazione va in pausa per liberare il microfono
        ServizioJarvis.invia(this, ServizioJarvis.AZIONE_PAUSA)
    }

    override fun onPause() {
        cervello.uscita = null
        visibile = false
        Trofei.alSblocco = null
        sensori?.ferma()
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
        if (resultCode == RESULT_OK && requestCode == CODICE_SFONDO) {
            data?.data?.let { salvaFotoSfondo(it) }
            return
        }
        if (resultCode == RESULT_OK && (requestCode == CODICE_GALLERIA || requestCode == CODICE_DOCUMENTO)) {
            risultatoAllegati(data)
            return
        }
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

        // Partita in corso (giochi a voce)
        rigaGioco = testoHud("", COLORE_ORO, 10.5f, 0.1f).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(6), 0, dp(2))
            setOnClickListener { mostraGiochi() }
        }
        radice.addView(rigaGioco, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Blocchi della home (sopra la sfera)
        blocchiSopra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scorriSopra = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(blocchiSopra)
        }
        radice.addView(scorriSopra, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

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
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, Personalizza.pesoSfera(prefs)).apply { topMargin = dp(4) }
        )

        // Blocchi della home (sotto la sfera)
        blocchiSotto = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scorriSotto = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(blocchiSotto)
        }
        radice.addView(scorriSotto, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

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
            setPadding(dp(4), 0, dp(6), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Tema.conAlfa(tema.accento, 170))
                setColor(tema.superficie)
            }
        }
        // ＋ per caricare foto, documenti e altri file
        rigaTesto.addView(testoHud("＋", COLORE_ORO, 20f, 0f).apply {
            setPadding(dp(12), dp(8), dp(8), dp(8))
            contentDescription = "Carica foto o documenti"
            setOnClickListener { scegliAllegato() }
        })
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
        // allegati pronti per la prossima domanda
        vistaAllegati = testoHud("", COLORE_ORO, 11f, 0.05f).apply {
            visibility = View.GONE
            setPadding(dp(14), dp(8), dp(14), dp(2))
            setOnClickListener { scegliAllegato() }
        }
        // Pulsanti rapidi
        rigaPulsanti = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scorriPulsanti = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(rigaPulsanti)
        }
        radice.addView(scorriPulsanti, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        radice.addView(vistaAllegati)
        radice.addView(rigaTesto, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        // Dock in basso (stile di menù "dock")
        dock = costruisciDock()
        radice.addView(dock, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(66)).apply { topMargin = dp(10) })

        // Strati: colore di fondo, foto, velo scuro, schermata
        fondo = FrameLayout(this).apply { setBackgroundColor(COLORE_SFONDO) }
        fotoSfondo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        veloSfondo = View(this).apply { visibility = View.GONE }
        val pieno = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        fondo.addView(fotoSfondo, pieno)
        fondo.addView(veloSfondo, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        fondo.addView(radice, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(fondo)
        scriviInfoHud()
        aggiornaPersonalizzazione()
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
        r.sfondo = Personalizza.sfondoAttuale(this, prefs)
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
            "Memoria", "Fonti", "Trofeo" -> {
                val simbolo = when (chi) { "Memoria" -> "◆ "; "Trofeo" -> "★ "; else -> "◇ Fonti: " }
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
            "Memoria", "Fonti", "Trofeo" -> noteRecenti.add(chi to testo)
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
        val testo = campoTesto.text.toString().trim().ifEmpty {
            if (allegati.isNotEmpty()) "Cosa contiene? Descrivilo e dimmi le cose importanti." else ""
        }
        if (testo.isEmpty()) return
        campoTesto.setText("")
        gestisciRichiesta(testo)
    }

    // ================================================================
    //  MENU RADIALE E SOTTOMENÙ
    // ================================================================

    /** Le voci del menù: id, nome breve per il menù radiale, spiegazione. */
    private val VOCI_MENU = listOf(
        Triple("CERVELLO", "CERVELLO", "Gemini o Claude, chiave e modello"),
        Triple("CARATTERE", "CARATTERE", "Tono, battute e lunghezza delle risposte"),
        Triple("VOCE", "VOCE", "Voce neurale, velocità e tono"),
        Triple("ASPETTO", "ASPETTO", "Colori, forma della sfera e testo"),
        Triple("PERSONALIZZA", "STILE", "Menù, home a blocchi, pulsanti, sfondo e suoni"),
        Triple("GIOCHI", "GIOCHI", "Giochi a voce, minigiochi, trofei e Jarvis vivo"),
        Triple("PLUGIN", "PLUGIN", "Cosa può usare Jarvis"),
        Triple("ATTIVO", "ATTIVO", "Ehi Jarvis sempre in ascolto"),
        Triple("MATTINO", "MATTINO", "Riepilogo del mattino"),
        Triple("COLLEGA", "COLLEGA", "Casa, email e PC"),
        Triple("DATI", "DATI", "Memoria, archivio e backup"),
        Triple("AGGIORNA", "AGGIORNA", "Cerca una nuova versione")
    )
    private var menuRadiale: MenuRadialeView? = null

    /** Apre il menù nello stile scelto: radiale attorno alla sfera, oppure a tessere. */
    private fun mostraImpostazioni() {
        if (Personalizza.stileMenu(prefs) != "radiale") {
            mostraTessere()
            return
        }
        if (menuRadiale != null) return
        val menu = MenuRadialeView(
            this, VOCI_MENU.map { it.second }, tema,
            alScegliere = { i ->
                chiudiMenuRadiale()
                apriVoce(VOCI_MENU[i].first)
            },
            alChiudere = { chiudiMenuRadiale() },
            descrizioni = VOCI_MENU.map { it.third }
        )
        menuRadiale = menu
        fondo.addView(menu, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun chiudiMenuRadiale() {
        menuRadiale?.let { fondo.removeView(it) }
        menuRadiale = null
    }

    private fun apriVoce(id: String) {
        when (id) {
            "CERVELLO" -> mostraCervello()
            "CARATTERE" -> mostraPersonalita()
            "VOCE" -> mostraVoce()
            "ASPETTO" -> mostraAspetto()
            "PERSONALIZZA" -> mostraPersonalizza()
            "GIOCHI" -> mostraGiochi()
            "PLUGIN" -> mostraPlugin()
            "ATTIVO" -> mostraSempreAttivo()
            "MATTINO" -> mostraMattino()
            "COLLEGA" -> mostraCollegamenti()
            "DATI" -> mostraDati()
            else -> aggiornaOra()
        }
    }

    /** Una riga sotto il nome di ogni tessera: lo stato attuale. */
    private fun sottotitoloVoce(id: String): String = try {
        when (id) {
            "CERVELLO" -> if (usaClaude()) "Claude" else "Gemini"
            "VOCE" -> if (prefs.getString("motore_voce", "") == "neurale") "Voce neurale" else "Voce del telefono"
            "PERSONALIZZA" -> Personalizza.STILI_MENU.firstOrNull { it.first == Personalizza.stileMenu(prefs) }?.second?.substringBefore(":") ?: ""
            "GIOCHI" -> Giochi.inCorso(this)?.substringBefore(" ·")?.let { "In corso: $it" } ?: "Livello ${Trofei.livello(Trofei.punti(this)).first}"
            "ATTIVO" -> if (ServizioJarvis.attivo) "Acceso" else "Spento"
            else -> VOCI_MENU.firstOrNull { it.first == id }?.third ?: ""
        }
    } catch (e: Exception) {
        ""
    }

    /** Menù a tessere: tutte le voci in riquadri grandi. */
    private fun mostraTessere() {
        chiudiMenuRadiale()
        val griglia = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        var finestra: AlertDialog? = null
        for (coppia in VOCI_MENU.chunked(2)) {
            val riga = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((id, _, _) in coppia) {
                val evidenzia = id == "GIOCHI"
                val tessera = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.BOTTOM
                    setPadding(dp(14), dp(12), dp(12), dp(12))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        if (evidenzia) setColor(tema.accento) else {
                            setColor(tema.superficie)
                            setStroke(dp(1), Tema.conAlfa(tema.accento, 90))
                        }
                    }
                    isClickable = true
                    contentDescription = id.lowercase(Locale.ITALIAN)
                    setOnClickListener {
                        finestra?.dismiss()
                        apriVoce(id)
                    }
                }
                tessera.addView(TextView(this).apply {
                    text = id.lowercase(Locale.ITALIAN).replaceFirstChar { it.uppercase() }
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (evidenzia) tema.sfondo else COLORE_BIANCO)
                })
                tessera.addView(TextView(this).apply {
                    text = sottotitoloVoce(id)
                    textSize = 11.5f
                    maxLines = 2
                    setTextColor(if (evidenzia) Tema.conAlfa(tema.sfondo, 200) else COLORE_TENUE)
                })
                riga.addView(tessera, LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            if (coppia.size == 1) riga.addView(View(this), LinearLayout.LayoutParams(0, dp(92), 1f))
            griglia.addView(riga)
        }
        finestra = AlertDialog.Builder(this)
            .setTitle(cervello.nomeAssistente())
            .setView(ScrollView(this).apply { addView(griglia) })
            .setPositiveButton("Chiudi", null)
            .show()
    }

    // ================================================================
    //  DOCK, GESTI, BLOCCHI, PULSANTI E SFONDO
    // ================================================================

    private fun costruisciDock(): LinearLayout {
        val d = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(tema.superficie)
                setStroke(dp(1), Tema.conAlfa(tema.accento, 90))
            }
            visibility = View.GONE
        }
        fun voce(simbolo: String, nome: String, azione: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            contentDescription = nome
            setOnClickListener { azione() }
            addView(TextView(this@MainActivity).apply {
                text = simbolo
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(COLORE_ORO)
            })
            addView(TextView(this@MainActivity).apply {
                text = nome
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(COLORE_TENUE)
            })
        }
        val pieno = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f) }
        d.addView(voce("⌂", "Casa") { tornaCasa() }, pieno())
        d.addView(voce("☰", "Chat") { mostraCronologia() }, pieno())
        d.addView(TextView(this).apply {
            text = "◉"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(tema.sfondo)
            contentDescription = "Parla con Jarvis"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(tema.accento)
            }
            setOnClickListener { reattore.performClick() }
        }, LinearLayout.LayoutParams(dp(54), dp(54)).apply { setMargins(dp(6), 0, dp(6), 0) })
        d.addView(voce("✦", "Giochi") { mostraGiochi() }, pieno())
        d.addView(voce("≡", "Opzioni") { mostraTessere() }, pieno())
        return d
    }

    private fun tornaCasa() {
        chiudiMenuRadiale()
        pannello?.chiudi()
        if (editor != null) chiudiEditor(false)
        scorrimento.post { scorrimento.scrollTo(0, 0) }
        stato.text = ""
    }

    /** Azioni dei gesti sulla sfera (e di qualche blocco). */
    private fun eseguiAzione(azione: String) {
        when (azione) {
            "chat" -> mostraCronologia()
            "opzioni" -> mostraTessere()
            "giochi" -> mostraGiochi()
            "agenda" -> gestisciRichiesta("cosa ho oggi")
            "meteo" -> gestisciRichiesta("che tempo fa")
            "trofei" -> mostraTrofei()
            "minigiochi" -> avviaMinigioco(null)
            "personalizza" -> mostraPersonalizza()
        }
    }

    /** Riapplica alla schermata tutte le scelte di PERSONALIZZA (anche durante la prova dal vivo). */
    private fun aggiornaPersonalizzazione() {
        if (!::dock.isInitialized) return
        val stile = Personalizza.stileMenu(prefs)
        dock.visibility = if (stile == "dock") View.VISIBLE else View.GONE
        reattore.alGesto = if (stile == "gesti") { direzione -> eseguiAzione(Personalizza.azioneGesto(prefs, direzione)) } else null
        (contenitoreSfera.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = Personalizza.pesoSfera(prefs)
            contenitoreSfera.layoutParams = it
        }
        reattore.sfondo = Personalizza.sfondoAttuale(this, prefs)
        riempiBlocchi()
        riempiPulsanti()
        aggiornaSfondo()
        aggiornaRigaGioco()
    }

    private fun aggiornaRigaGioco() {
        val r = try { Giochi.inCorso(this) } catch (e: Exception) { null }
        rigaGioco.text = if (r != null) "▶ ${r.uppercase(Locale.ITALIAN)}" else ""
        rigaGioco.visibility = if (r != null) View.VISIBLE else View.GONE
    }

    private fun riempiBlocchi() {
        val elenco = Personalizza.blocchi(prefs)
        val sopra = prefs.getBoolean("blocchi_sopra", true)
        blocchiSopra.removeAllViews()
        blocchiSotto.removeAllViews()
        scorriSopra.visibility = if (sopra && elenco.isNotEmpty()) View.VISIBLE else View.GONE
        scorriSotto.visibility = if (!sopra && elenco.isNotEmpty()) View.VISIBLE else View.GONE
        val dove = if (sopra) blocchiSopra else blocchiSotto
        for (id in elenco) {
            dove.addView(vistaBlocco(id), LinearLayout.LayoutParams(dp(138), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
        }
        val adesso = System.currentTimeMillis()
        if ("meteo" in elenco && adesso - meteoQuando > 30 * 60_000L) {
            meteoQuando = adesso
            Thread {
                val r = try { cervello.telefono.meteo("", "oggi") } catch (e: Exception) { "" }
                meteoBreve = r.substringBefore(". ").take(80)
                runOnUiThread { riempiBlocchi() }
            }.start()
        }
        if ("agenda" in elenco && adesso - agendaQuando > 10 * 60_000L) {
            agendaQuando = adesso
            Thread {
                val r = try { ComandiRapidi.prova(cervello, "cosa ho oggi") ?: "" } catch (e: Exception) { "" }
                agendaBreve = r.take(90)
                runOnUiThread { riempiBlocchi() }
            }.start()
        }
    }

    private fun vistaBlocco(id: String): View {
        val (valore, azione) = try {
            when (id) {
                "meteo" -> meteoBreve.ifEmpty { "Carico il meteo…" } to { gestisciRichiesta("che tempo fa") }
                "batteria" -> {
                    val gestore = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                    val livello = gestore.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val carica = Build.VERSION.SDK_INT >= 23 && gestore.isCharging
                    "$livello%${if (carica) " · in carica" else ""}" to { gestisciRichiesta("quanta batteria ho") }
                }
                "agenda" -> agendaBreve.ifEmpty { "Leggo l'agenda…" } to { gestisciRichiesta("cosa ho oggi") }
                "promemoria" -> (Promemoria.promemoriaDiOggi(this).firstOrNull() ?: "Nessuno per oggi") to {
                    aggiungiAlRegistro("Jarvis", Promemoria.descrivi(this))
                }
                "gioco" -> (Giochi.inCorso(this) ?: "Indovinello del giorno: tocca per giocare") to { avviaGiocoDelGiorno() }
                "trofei" -> {
                    val (l, dentro, serve) = Trofei.livello(Trofei.punti(this))
                    "Livello $l · ${Trofei.nomeLivello(l)} · $dentro/$serve" to { mostraTrofei() }
                }
                "sfida" -> Trofei.sfida(this) to { mostraTrofei() }
                else -> Liste.leggi(this, "spesa").take(90) to { gestisciRichiesta("cosa c'è nella lista della spesa") }
            }
        } catch (e: Exception) {
            "—" to {}
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Tema.conAlfa(tema.superficie, 230))
                setStroke(dp(1), Tema.conAlfa(tema.accento, 80))
            }
            isClickable = true
            contentDescription = Personalizza.nomeBlocco(id)
            setOnClickListener { azione() }
            addView(testoHud(Personalizza.nomeBlocco(id).uppercase(Locale.ITALIAN), COLORE_TENUE, 9f, 0.12f))
            addView(TextView(this@MainActivity).apply {
                text = valore
                textSize = 13f
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLORE_BIANCO)
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun avviaGiocoDelGiorno() {
        if (occupato) return
        Thread {
            Trofei.giocoIniziato(this, "indovinello")
            val r = Giochi.avviaIndovinello(this, Giochi.indiceDelGiorno())
            runOnUiThread {
                aggiungiAlRegistro("Tu", "Gioco del giorno")
                aggiungiAlRegistro("Jarvis", r)
                parla(r)
                aggiornaRigaGioco()
            }
        }.start()
    }

    private fun riempiPulsanti() {
        rigaPulsanti.removeAllViews()
        val elenco = Personalizza.pulsanti(prefs)
        scorriPulsanti.visibility = if (elenco.isEmpty()) View.GONE else View.VISIBLE
        for (pu in elenco) {
            rigaPulsanti.addView(TextView(this).apply {
                text = pu.etichetta
                textSize = 13f
                setTextColor(tema.sfondo)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(tema.accento)
                }
                setOnClickListener { premiPulsante(pu) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            })
        }
    }

    private fun premiPulsante(pu: Personalizza.Pulsante) {
        when (pu.frase) {
            Personalizza.ZITTO -> { parlatore.ferma(); parlando = false; aggiornaMic() }
            Personalizza.GIOCO_DEL_GIORNO -> avviaGiocoDelGiorno()
            Personalizza.MINIGIOCHI -> avviaMinigioco(null)
            Personalizza.TROFEI -> mostraTrofei()
            Personalizza.TORCIA -> {
                torciaAccesa = !torciaAccesa
                gestisciRichiesta(if (torciaAccesa) "accendi la torcia" else "spegni la torcia")
            }
            else -> gestisciRichiesta(pu.frase)
        }
    }

    private val CODICE_SFONDO = 40

    private fun scegliFotoSfondo() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), CODICE_SFONDO)
        } catch (e: Exception) {
            Toast.makeText(this, "Non trovo un'app per scegliere le foto.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Copia la foto scelta (ridotta) nei file di Jarvis e la mette come sfondo, in prova. */
    private fun salvaFotoSfondo(uri: Uri) {
        Thread {
            val ok = try {
                val dati = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                val misure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(dati, 0, dati.size, misure)
                var riduzione = 1
                while (maxOf(misure.outWidth, misure.outHeight) / (riduzione * 2) >= 1600) riduzione *= 2
                val immagine = BitmapFactory.decodeByteArray(dati, 0, dati.size, BitmapFactory.Options().apply { inSampleSize = riduzione })
                Personalizza.fileFoto(this).outputStream().use { immagine.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                true
            } catch (e: Throwable) {
                false
            }
            runOnUiThread {
                if (ok) {
                    prefs.edit().putString("sfondo", "foto").putBoolean("sfondo_giornaliero", false).apply()
                    fotoCaricata = ""
                    aggiornaPersonalizzazione()
                    pannello?.ricarica()
                } else {
                    Toast.makeText(this, "Non riesco a leggere questa foto.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun aggiornaSfondo() {
        val scelto = Personalizza.sfondoAttuale(this, prefs)
        val file = Personalizza.fileFoto(this)
        if (scelto == "foto" && file.exists()) {
            val sfoca = Personalizza.sfocatura(prefs)
            val chiave = "${file.lastModified()}:$sfoca"
            if (chiave != fotoCaricata) {
                fotoCaricata = chiave
                val nuova = Build.VERSION.SDK_INT >= 31
                val opzioni = BitmapFactory.Options().apply { inSampleSize = if (nuova) 1 else 1 + sfoca / 12 }
                val immagine = try { BitmapFactory.decodeFile(file.path, opzioni) } catch (e: Throwable) { null }
                fotoSfondo.setImageBitmap(immagine)
                if (nuova) {
                    val raggio = sfoca * 0.3f * resources.displayMetrics.density
                    fotoSfondo.setRenderEffect(if (raggio > 0f) RenderEffect.createBlurEffect(raggio, raggio, Shader.TileMode.CLAMP) else null)
                }
            }
            fotoSfondo.visibility = View.VISIBLE
            veloSfondo.visibility = View.VISIBLE
            veloSfondo.setBackgroundColor(Tema.conAlfa(tema.sfondo, Personalizza.oscura(prefs) * 255 / 100))
        } else {
            fotoSfondo.visibility = View.GONE
            veloSfondo.visibility = View.GONE
            if (fotoCaricata.isNotEmpty()) {
                fotoSfondo.setImageDrawable(null)
                fotoCaricata = ""
            }
        }
    }

    // ================================================================
    //  PERSONALIZZA: pannello con prova dal vivo
    // ================================================================

    private fun mostraPersonalizza(scheda: String = "menu") {
        chiudiMenuRadiale()
        if (pannello != null) {
            pannello?.mostraScheda(scheda)
            return
        }
        val altezza = (fondo.height * 0.6f).toInt().coerceAtLeast(dp(340))
        val p = PannelloPersonalizza(this, prefs, tema, altezza,
            aggiorna = { aggiornaPersonalizzazione() },
            scegliFoto = { scegliFotoSfondo() },
            alChiudere = {
                pannello?.let { fondo.removeView(it.vista) }
                pannello = null
            })
        pannello = p
        fondo.addView(p.vista, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, altezza, Gravity.BOTTOM))
        p.mostraScheda(scheda)
    }

    // ================================================================
    //  GIOCHI, TROFEI E JARVIS VIVO
    // ================================================================

    private fun avviaMinigioco(id: String?) {
        val intento = Intent(this, MinigiochiActivity::class.java)
        if (id != null) intento.putExtra("gioco", id)
        startActivity(intento)
    }

    private fun mostraGiochi() {
        chiudiMenuRadiale()
        val pagina = Pagina("Giochi")
        Giochi.inCorso(this)?.let { inCorso ->
            pagina.titoloSezione("Partita in corso")
            pagina.nota(inCorso)
            pagina.bottone("Continua: ripetimi l'ultima domanda") { pagina.chiudi(); gestisciRichiesta("ripeti") }
            pagina.bottone("Termina la partita") {
                Giochi.termina(this)
                aggiornaRigaGioco()
                pagina.chiudi()
                aggiungiAlRegistro("Jarvis", "Partita terminata.")
            }
        }
        pagina.titoloSezione("Giochi a voce")
        pagina.nota("Si gioca parlando. Impiccato, indovinelli e quiz funzionano anche senza internet. Per smettere di' «basta giocare».")
        for ((id, nome) in Giochi.ELENCO) {
            val frase = Giochi.FRASI[id] ?: continue
            pagina.bottone("$nome  ·  «$frase»") { pagina.chiudi(); gestisciRichiesta(frase) }
        }
        pagina.titoloSezione("Minigiochi con la sfera")
        for ((id, nome, _) in MinigiochiActivity.GIOCHI) {
            val record = MinigiochiActivity.record(this, id)
            pagina.bottone(if (record > 0) "$nome  ·  record $record" else nome) { avviaMinigioco(id) }
        }
        pagina.titoloSezione("Livelli e trofei")
        val (livello, dentro, serve) = Trofei.livello(Trofei.punti(this))
        pagina.nota("Livello $livello · ${Trofei.nomeLivello(livello)} · $dentro su $serve punti · " +
            "${Trofei.sbloccati(this).size} trofei su ${Trofei.TUTTI.size}")
        pagina.bottone("Apri livelli e trofei") { pagina.chiudi(); mostraTrofei() }

        pagina.titoloSezione("Jarvis vivo")
        val caselle = Umore.OPZIONI.map { (chiave, testo, _) -> chiave to pagina.casella(testo, Umore.opzione(this, chiave)) }
        pagina.nota("Umore")
        val umore = pagina.scelta(listOf("auto" to "Automatico: cambia da solo durante la giornata") + Umore.UMORI,
            prefs.getString("umore_fisso", "auto") ?: "auto")
        pagina.prova("Prova l'umore: guarda la sfera",
            applica = { reattore.umore = umore().let { if (it == "auto") Umore.attuale(this) else it } },
            ripristina = { reattore.umore = Umore.attuale(this) })
        pagina.mostra {
            val e = prefs.edit()
            for ((chiave, casella) in caselle) e.putBoolean(chiave, casella.isChecked)
            e.putString("umore_fisso", umore()).apply()
            reattore.umore = Umore.attuale(this)
            if (!Umore.opzione(this, "vivo_inclina")) { reattore.inclinaX = 0f; reattore.inclinaY = 0f }
            Thread { Trofei.evento(this, "personalizza", 5) }.start()
        }
    }

    private fun mostraTrofei() {
        chiudiMenuRadiale()
        val pagina = Pagina("Livelli e trofei")
        val punti = Trofei.punti(this)
        val (livello, dentro, serve) = Trofei.livello(punti)
        pagina.titoloSezione("Livello $livello · ${Trofei.nomeLivello(livello)}")
        val pieni = (dentro * 16 / serve.coerceAtLeast(1)).coerceIn(0, 16)
        pagina.riga("█".repeat(pieni) + "░".repeat(16 - pieni) + "  $dentro su $serve", COLORE_ORO)
        pagina.nota("Punti totali: $punti · giorni di fila: ${Trofei.serie(this)}")
        pagina.titoloSezione("Sfida di oggi")
        pagina.nota(Trofei.sfida(this))
        val sbloccati = Trofei.sbloccati(this)
        pagina.titoloSezione("Trofei · ${sbloccati.size} su ${Trofei.TUTTI.size}")
        for (t in Trofei.TUTTI) {
            val preso = t.id in sbloccati
            pagina.riga((if (preso) "◆ " else "◇ ") + t.nome + "  ·  " + t.descrizione, if (preso) COLORE_BIANCO else COLORE_TENUE)
        }
        pagina.nota("Si guadagnano punti usando Jarvis, giocando e personalizzandolo. Ogni trofeo vale 25 punti.")
        pagina.mostra()
    }

    /** Un trofeo, un livello o una sfida completata: festa sulla sfera. */
    private fun trofeoSbloccato(messaggio: String) {
        aggiungiAlRegistro("Trofeo", messaggio)
        Umore.imposta("allegro", 120)
        reattore.umore = "allegro"
        reattore.festeggia()
        aggiornaRigaGioco()
        if (Personalizza.blocchi(prefs).isNotEmpty()) riempiBlocchi()
    }

    private fun scossa() {
        reattore.scuoti()
        Suoni.vibra(this, "scossa")
        val frase = Umore.scossa(this)
        reattore.umore = Umore.attuale(this)
        if (!occupato && !inAscolto && !parlando && !reattore.dorme) {
            stato.text = frase
            parla(frase)
        }
    }

    private fun capovolto(giu: Boolean) {
        reattore.dorme = giu
        if (giu) {
            parlatore.ferma()
            parlando = false
            if (inAscolto) fermaAscolto()
            aggiornaMic()
            stato.text = "Dormo. Girami per svegliarmi."
        } else {
            reattore.umore = Umore.attuale(this)
            stato.text = "Eccomi, sono sveglio."
        }
    }

    @Deprecated("Gestione del tasto indietro")
    override fun onBackPressed() {
        when {
            barraAnteprima != null -> barraAnteprima?.performClick()
            menuRadiale != null -> chiudiMenuRadiale()
            pannello != null -> pannello?.chiudi()
            editor != null -> chiudiEditor(false)
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    /** Barra in basso durante la prova di una pagina: toccandola si torna alla pagina. */
    private fun mostraBarraAnteprima(alTornare: () -> Unit) {
        barraAnteprima?.let { fondo.removeView(it) }
        val barra = testoHud("ANTEPRIMA · TOCCA QUI PER TORNARE ALLA PAGINA", tema.sfondo, 11f, 0.12f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(tema.accento)
            }
            setOnClickListener {
                fondo.removeView(this)
                barraAnteprima = null
                alTornare()
            }
        }
        barraAnteprima = barra
        fondo.addView(barra, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(16), 0, dp(16), dp(28))
        })
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

        fun riga(testo: String, colore: Int) {
            contenitore.addView(TextView(this@MainActivity).apply {
                text = testo
                textSize = 13.5f
                setTextColor(colore)
                setPadding(0, dp(5), 0, 0)
            })
        }

        var dialogo: AlertDialog? = null
        private var applicaProva: (() -> Unit)? = null
        private var ripristinaProva: (() -> Unit)? = null
        private var inProva = false

        fun chiudi() {
            dialogo?.dismiss()
        }

        /** Un pulsante in cima alla pagina: nasconde la pagina e mostra la schermata con le scelte, prima di salvarle. */
        fun prova(testo: String = "Prova: guarda come viene sullo schermo", applica: () -> Unit, ripristina: () -> Unit) {
            applicaProva = applica
            ripristinaProva = ripristina
            contenitore.addView(Button(this@MainActivity).apply {
                text = "◉  $testo"
                isAllCaps = false
                setOnClickListener {
                    val d = dialogo ?: return@setOnClickListener
                    applica()
                    inProva = true
                    d.hide()
                    mostraBarraAnteprima { d.show() }
                }
            }, 0)
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
            val leggi: () -> String = {
                val indice = bottoni.indexOfFirst { it.id == gruppo.checkedRadioButtonId }.coerceAtLeast(0)
                opzioni[indice].first
            }
            selettori[leggi] = { valore ->
                val i = opzioni.indexOfFirst { it.first == valore }
                if (i >= 0) gruppo.check(bottoni[i].id)
            }
            return leggi
        }

        private val selettori = HashMap<Any, (String) -> Unit>()

        /** Cambia la scelta mostrata (per esempio quando un'altra azione della pagina la modifica). */
        fun seleziona(scelta: () -> String, valore: String) {
            selettori[scelta]?.invoke(valore)
        }

        /** Mostra la pagina. Salva non chiude: si resta sulla pagina e si può continuare a cambiare. */
        fun mostra(alSalvare: (() -> Unit)? = null) {
            val finestra = AlertDialog.Builder(this@MainActivity)
                .setTitle(titolo)
                .setView(ScrollView(this@MainActivity).apply { addView(contenitore) })
            if (alSalvare != null) {
                finestra.setPositiveButton("Salva", null)
                finestra.setNegativeButton("Chiudi", null)
            } else {
                finestra.setPositiveButton("Chiudi", null)
            }
            finestra.setNeutralButton("Menu") { _, _ -> mostraImpostazioni() }
            val d = finestra.create()
            dialogo = d
            d.setOnDismissListener {
                if (barraAnteprima != null) { barraAnteprima?.let { fondo.removeView(it) }; barraAnteprima = null }
                if (inProva) { inProva = false; ripristinaProva?.invoke() }
            }
            d.show()
            if (alSalvare != null) {
                d.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    try {
                        alSalvare()
                        inProva = false
                        if (!isFinishing && d.isShowing) {
                            d.setTitle("$titolo  ·  salvato ✓")
                            Toast.makeText(this@MainActivity, "Salvato. Resti su questa pagina.", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Non sono riuscito a salvare: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
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
        pagina.titoloSezione("Risparmio richieste")
        pagina.nota("Di giorno Jarvis impara da solo, senza IA, e fa i comandi semplici (meteo, promemoria, agenda, notizie, messaggi...) " +
            "sul telefono. Riassunti, apprendimento e riassunti dei documenti li fa tutti insieme di notte, con pochissime richieste.")
        val risparmio = pagina.casella("Risparmia richieste: elaborazioni automatiche di notte", prefs.getBoolean("risparmio_richieste", true))
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
                .putBoolean("risparmio_richieste", risparmio.isChecked)
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
                    pagina.seleziona(motore, "neurale")
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
                    prefs.edit().putString("voce_nome", elenco[i].name).putString("motore_voce", "android").apply()
                    pagina.seleziona(motore, "android")
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
            if (NotificheJarvis.abilitato(this)) apriImpostazione(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            else guidaLimitazioni("l'accesso alle notifiche", Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        pagina.bottone("Controllo delle app (Accessibilità)" + if (AccessibilitaJarvis.istanza != null) " ✓" else "") {
            if (AccessibilitaJarvis.istanza != null) apriImpostazione(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            else guidaLimitazioni("il controllo delle app", Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        pagina.bottone("✎ Modifica dal vivo: dimensione, velocità, particelle, colore...") { pagina.chiudi(); mostraEditor() }
        pagina.prova(
            applica = {
                val p = Tema.PALETTE.firstOrNull { it.id == colori() } ?: tema
                reattore.palette = p
                reattore.forma = forma()
                reattore.interfaccia = interfaccia()
                reattore.sfondo = sfondo()
                reattore.ridotto = animazioni() == "ridotte"
                fondo.setBackgroundColor(p.sfondo)
                registro.setTextColor(p.testo)
                infoHud.visibility = if (interfaccia() == "minimal" || !dati.isChecked) View.GONE else View.VISIBLE
            },
            ripristina = {
                reattore.palette = tema
                applicaRegolazioni(reattore)
                fondo.setBackgroundColor(tema.sfondo)
                registro.setTextColor(COLORE_BIANCO)
                infoHud.visibility = if (prefs.getString("interfaccia", "hud") == "minimal" || !prefs.getBoolean("mostra_dati", true)) View.GONE else View.VISIBLE
                aggiornaPersonalizzazione()
            }
        )
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
                prefs.edit().putString("riapri", "aspetto").apply()
                recreate()      // ricostruisce la schermata con i nuovi colori e riapre questa pagina
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
        val titoloEditor = testoHud("MODIFICA DAL VIVO", COLORE_ORO, 12f, 0.2f)
        intestazione.addView(titoloEditor, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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
            Thread { Trofei.evento(this, "personalizza", 10) }.start()
            if (tema.accento != Tema.attuale(prefs).accento) {
                // nuovo colore: si ricostruisce la schermata e si torna qui
                prefs.edit().putString("riapri", "editor").apply()
                chiudiEditor(true)
            } else {
                titoloEditor.text = "MODIFICA DAL VIVO · SALVATO ✓"
            }
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
        fondo.addView(contenitore, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, (fondo.height * 0.5f).toInt().coerceAtLeast(dp(300)), Gravity.BOTTOM
        ))
    }

    private fun chiudiEditor(salvato: Boolean, ricarica: Boolean = true) {
        editor?.let { fondo.removeView(it) }
        editor = null
        if (salvato) {
            val cambiaColore = tema.accento != Tema.attuale(prefs).accento
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
        pagina.titoloSezione("Fase notturna")
        pagina.nota("Ogni notte (o dalle 23 se il telefono è in carica) Jarvis riassume le conversazioni, impara dalle richieste " +
            "e completa i riassunti dei documenti, tutto con una o poche richieste.\n" +
            (prefs.getString("notturna_esito", "")?.takeIf { it.isNotBlank() }?.let { "Ultima volta: $it" } ?: "Non è ancora stata eseguita."))
        pagina.bottone("Fallo adesso") {
            aggiungiAlRegistro("Jarvis", "Elaboro la memoria...")
            Thread {
                val esito = FaseNotturna.esegui(this, forzata = true)
                runOnUiThread { aggiungiAlRegistro("Memoria", esito.ifBlank { "Fatto." }) }
            }.start()
        }
        pagina.titoloSezione("Archivio dei documenti")
        pagina.nota("${Archivio.numero(this)} documenti. Quando carichi un file con il tasto +, Jarvis lo conserva con un riassunto per le domande future.")
        val archivioAuto = pagina.casella("Conserva automaticamente i file caricati", prefs.getBoolean("archivio_auto", true))
        archivioAuto.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("archivio_auto", v).apply() }
        pagina.bottone("Apri l'archivio") { mostraArchivio() }
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

    // ================================================================
    //  ALLEGATI: foto, documenti e file caricati
    // ================================================================

    private lateinit var vistaAllegati: TextView
    private val allegati = mutableListOf<Cervello.Contenuto>()
    private val archiviati = mutableSetOf<String>()
    private var contestoAllegati = ""
    private val CODICE_GALLERIA = 30
    private val CODICE_DOCUMENTO = 31

    private fun scegliAllegato() {
        val voci = mutableListOf("Foto dalla galleria", "Scatta una foto", "Documento o file (PDF, Word, Excel, audio...)")
        if (allegati.isNotEmpty()) voci.add("Togli gli allegati")
        voci.add("Archivio dei documenti")
        AlertDialog.Builder(this)
            .setTitle("Carica")
            .setItems(voci.toTypedArray()) { _, i ->
                when (voci[i]) {
                    "Foto dalla galleria" -> apriSelettore("image/*", CODICE_GALLERIA)
                    "Scatta una foto" -> scattaAllegato()
                    "Togli gli allegati" -> togliAllegati()
                    "Archivio dei documenti" -> mostraArchivio()
                    else -> apriSelettore("*/*", CODICE_DOCUMENTO)
                }
            }
            .show()
    }

    private fun apriSelettore(tipo: String, codice: Int) {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(tipo)
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                codice
            )
        } catch (e: Exception) {
            aggiungiAlRegistro("Jarvis", "Non trovo un'app per scegliere i file.")
        }
    }

    private fun scattaAllegato() {
        val cartella = File(cacheDir, "foto").apply { mkdirs() }
        val file = File(cartella, "allegato.jpg")
        file.delete()
        scattaFoto(file) { ok ->
            if (!ok) return@scattaFoto
            Thread {
                val c = Allegati.daByte("foto " + SimpleDateFormat("d MMM HH:mm", Locale.ITALIAN).format(Date()) + ".jpg",
                    "image/jpeg", riduciFoto(file.readBytes()))
                runOnUiThread { if (c != null) aggiungiAllegati(listOf(c)) }
            }.start()
        }
    }

    /** Le foto grandi vengono ridotte a 2048 pixel: arrivano prima e costano meno. */
    private fun riduciFoto(dati: ByteArray): ByteArray = try {
        val misure = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(dati, 0, dati.size, misure)
        if (dati.size < 2_500_000 && maxOf(misure.outWidth, misure.outHeight) <= 2560) dati else {
            var riduzione = 1
            while (maxOf(misure.outWidth, misure.outHeight) / (riduzione * 2) >= 2048) riduzione *= 2
            val immagine = android.graphics.BitmapFactory.decodeByteArray(dati, 0, dati.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = riduzione })
            val uscita = java.io.ByteArrayOutputStream()
            immagine.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, uscita)
            uscita.toByteArray()
        }
    } catch (e: Exception) {
        dati
    }

    private fun risultatoAllegati(dati: Intent?) {
        val uris = mutableListOf<Uri>()
        dati?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        if (uris.isEmpty()) dati?.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        stato.text = "Preparo i file..."
        Thread {
            val letti = mutableListOf<Cervello.Contenuto>()
            val scartati = mutableListOf<String>()
            for (uri in uris.take(8)) {
                var c = Allegati.leggi(this, uri)
                val letto = c
                val base64 = letto?.base64
                if (letto != null && letto.tipo == "immagine" && base64 != null) {
                    // ridimensiono le foto pesanti
                    val byte = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                    if (byte.size > 2_500_000) c = Allegati.daByte(letto.titolo, "image/jpeg", riduciFoto(byte))
                }
                if (c == null) scartati.add(Allegati.nome(this, uri)) else letti.add(c)
            }
            runOnUiThread {
                stato.text = ""
                if (letti.isNotEmpty()) aggiungiAllegati(letti)
                if (scartati.isNotEmpty()) aggiungiAlRegistro("Jarvis", "Non riesco a caricare ${scartati.joinToString(", ")}: troppo grande (massimo 19 MB) o illeggibile.")
            }
        }.start()
    }

    private fun aggiungiAllegati(nuovi: List<Cervello.Contenuto>) {
        if (allegati.isEmpty()) contestoAllegati = ""
        allegati.addAll(nuovi)
        while (allegati.size > 8) allegati.removeAt(0)
        aggiornaVistaAllegati()
        aggiungiAlRegistro("Memoria", "Caricato: ${nuovi.joinToString(", ") { it.titolo }}. Ora chiedimi qualcosa, a voce o per iscritto.")
        campoTesto.hint = "Chiedi qualcosa sui file caricati"
    }

    private fun togliAllegati() {
        allegati.clear()
        contestoAllegati = ""
        aggiornaVistaAllegati()
        campoTesto.hint = "Scrivi o tocca la sfera"
    }

    private fun aggiornaVistaAllegati() {
        if (allegati.isEmpty()) {
            vistaAllegati.visibility = View.GONE
        } else {
            vistaAllegati.visibility = View.VISIBLE
            vistaAllegati.text = "📎 " + allegati.joinToString(" · ") { it.titolo.take(28) } + "   (tocca per cambiare)"
        }
    }

    private fun gestisciConAllegati(testo: String) {
        val file = allegati.toList()
        aggiungiAlRegistro("Tu", "$testo  📎 ${file.size}")
        occupato = true
        stato.text = "Leggo ${if (file.size == 1) file[0].titolo else "${file.size} file"}..."
        aggiornaMic()
        val archiviaAuto = prefs.getBoolean("archivio_auto", true)
        Thread {
            val risposta = try {
                cervello.analizzaContenuti(file, testo, contestoAllegati)
            } catch (e: Exception) {
                "Non sono riuscito a leggere i file: ${e.message}"
            }
            contestoAllegati += "\nD: $testo\nR: ${risposta.take(800)}"
            cervello.registraScambioEsterno("$testo [file: ${file.joinToString(", ") { it.titolo }}]", risposta)
            runOnUiThread {
                occupato = false
                stato.text = ""
                aggiornaMic()
                aggiungiAlRegistro("Jarvis", risposta)
                parla(risposta)
            }
            // conservo i documenti nell'archivio, con un riassunto, per le domande future
            if (archiviaAuto && !cervello.privata) {
                for (c in file) {
                    if (c.titolo in archiviati) continue
                    archiviati.add(c.titolo)
                    try {
                        val esito = Archivio.archiviaCaricato(this, c, testo, risposta)
                        runOnUiThread { aggiungiAlRegistro("Memoria", esito) }
                    } catch (e: Exception) { }
                }
            }
            cervello.manutenzione()
        }.start()
    }

    private fun mostraArchivio() {
        val elenco = Archivio.elenco(this)
        val voci = (0 until elenco.length()).map { elenco.getJSONObject(it) }
        if (voci.isEmpty()) {
            aggiungiAlRegistro("Jarvis", "L'archivio è vuoto: carica un documento con il tasto + e lo conservo io.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Archivio · ${voci.size} documenti")
            .setItems(voci.map { "${it.optString("nome")}\n${it.optString("riassunto").take(90)}" }.toTypedArray()) { _, i ->
                val v = voci[i]
                AlertDialog.Builder(this)
                    .setTitle(v.optString("nome"))
                    .setMessage(v.optString("data") + "\n\n" + v.optString("riassunto"))
                    .setPositiveButton("Fai domande") { _, _ ->
                        Archivio.carica(this, v)?.let { togliAllegati(); archiviati.add(it.titolo); aggiungiAllegati(listOf(it)) }
                    }
                    .setNeutralButton("Elimina") { _, _ ->
                        aggiungiAlRegistro("Memoria", Archivio.elimina(this, v.optString("nome")))
                    }
                    .setNegativeButton("Chiudi", null)
                    .show()
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    /** Guida per le "impostazioni con limitazioni" di Android (app installate fuori dal Play Store). */
    private fun guidaLimitazioni(cosa: String, impostazione: Intent) {
        AlertDialog.Builder(this)
            .setTitle("Attivare $cosa")
            .setMessage(
                "Android protegge questa impostazione per le app installate fuori dal Play Store. Se vedi \"Impostazioni con limitazioni\":\n\n" +
                    "1. Tocca APRI INFO APP qui sotto\n" +
                    "2. In alto a destra tocca i tre puntini ⋮\n" +
                    "3. Scegli \"Consenti impostazioni con limitazioni\" e conferma con impronta o PIN\n" +
                    "4. Torna qui e tocca VAI ALL'IMPOSTAZIONE, poi attiva Jarvis\n\n" +
                    "La voce nel menu ⋮ compare solo dopo aver provato almeno una volta ad attivare l'impostazione."
            )
            .setPositiveButton("Vai all'impostazione") { _, _ -> apriImpostazione(impostazione) }
            .setNeutralButton("Apri info app") { _, _ ->
                apriImpostazione(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    /** Se l'ultima volta Jarvis si è chiuso per un errore, lo mostra e permette di copiarlo o mandarlo. */
    private fun mostraUltimoErrore() {
        val file = JarvisApp.errore(this)
        if (prefs.getBoolean("neurale_guasta", false) && !file.exists()) {
            prefs.edit().putBoolean("neurale_guasta", false).apply()
            aggiungiAlRegistro("Jarvis", "La voce neurale non è riuscita a partire (${VoceNeurale.ultimoErrore.ifBlank { "errore del motore" }}): " +
                "per ora uso la voce del telefono.")
            return
        }
        if (!file.exists()) return
        val testo = try { file.readText() } catch (e: Exception) { "" }
        file.delete()
        prefs.edit().putBoolean("neurale_guasta", false).apply()
        if (testo.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle("Jarvis si era chiuso per un errore")
            .setMessage("Copia questo testo e mandamelo: mi serve per correggere il problema.\n\n" + testo.take(3000))
            .setPositiveButton("Copia") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Errore Jarvis", testo))
                aggiungiAlRegistro("Jarvis", "Errore copiato: incollalo nella chat con Claude.")
            }
            .setNeutralButton("Condividi") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, testo), "Manda l'errore"))
            }
            .setNegativeButton("Chiudi", null)
            .show()
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
        testo.append("\nARCHIVIO DEI DOCUMENTI\n").append(Archivio.descrivi(this)).append("\n")
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
        Suoni.sveglia(this)
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
        if (allegati.isNotEmpty()) {
            gestisciConAllegati(testo)
            return
        }
        aggiungiAlRegistro("Tu", testo)
        occupato = true
        stato.text = ""
        aggiornaMic()
        cervello.chiParla = ""
        parlatore.inizia()
        risposteParziale = StringBuilder()
        if (Suoni.attivo(this, "suoni_pensa")) reattore.postDelayed(ticchettio, 450)

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
                aggiornaRigaGioco()
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
