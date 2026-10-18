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

    // Palette: nero, oro e bianco
    private val COLORE_SFONDO = Color.parseColor("#050505")
    private val COLORE_ORO = Color.parseColor("#D4A84B")
    private val COLORE_BIANCO = Color.parseColor("#F5F5F5")
    private val COLORE_TENUE = Color.parseColor("#8A8A8A")
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

    /** Aperta dalla tessera rapida o dalla notifica: inizia subito ad ascoltare, o legge il riepilogo. */
    private fun controllaRichiestaAscolto(intento: Intent?) {
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
        barra.addView(testoHud("JARVIS", COLORE_ORO, 18f, 0.5f).apply {
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
        infoHud = testoHud("", COLORE_TENUE, 9.5f, 0.12f).apply { gravity = Gravity.CENTER }
        radice.addView(infoHud, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // ---- Il reattore: sfera dorata con anelli HUD. Toccalo per parlare ----
        reattore = ReattoreView(this).apply {
            isClickable = true
            contentDescription = "Tocca per parlare, tieni premuto per il menu"
            ridotto = prefs.getString("animazioni", "piene") == "ridotte"
            setOnClickListener { if (inAscolto) fermaAscolto() else iniziaAscolto() }
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
            textSize = 14f
            typeface = SERIF
            gravity = Gravity.CENTER
        }
        radice.addView(stato, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ---- Conversazione ----
        registro = TextView(this).apply {
            setTextColor(COLORE_BIANCO)
            textSize = 15f
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
                setStroke(dp(1), Color.argb(170, 212, 168, 75))
                setColor(Color.parseColor("#0D0D0D"))
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
                riga.setSpan(TypefaceSpan("serif"), 0, riga.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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

    private val VOCI_MENU = listOf("CERVELLO", "CARATTERE", "VOCE", "PLUGIN", "ATTIVO", "MATTINO", "ASPETTO")
    private var menuRadiale: MenuRadialeView? = null

    /** Apre il menu radiale attorno alla sfera. */
    private fun mostraImpostazioni() {
        if (menuRadiale != null) return
        val menu = MenuRadialeView(
            this, VOCI_MENU,
            alScegliere = { i ->
                chiudiMenuRadiale()
                when (i) {
                    0 -> mostraCervello()
                    1 -> mostraPersonalita()
                    2 -> mostraVoce()
                    3 -> mostraPlugin()
                    4 -> mostraSempreAttivo()
                    5 -> mostraMattino()
                    else -> mostraAspetto()
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
        val chiaveGemini = pagina.campo("Chiave API Gemini", prefs.getString("api_key", "") ?: "", true)
        val modelloGemini = pagina.campo("Modello Gemini", prefs.getString("modello", Cervello.MODELLO_PREDEFINITO) ?: Cervello.MODELLO_PREDEFINITO)
        pagina.titoloSezione("Claude")
        pagina.nota("Le API di Claude si pagano a parte su platform.claude.com: l'abbonamento Pro o Max non le include.")
        val chiaveClaude = pagina.campo("Chiave API Claude", prefs.getString("chiave_claude", "") ?: "", true)
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
                .putString("api_key", chiaveGemini.text.toString().trim())
                .putString("modello", modelloGemini.text.toString().trim().ifBlank { Cervello.MODELLO_PREDEFINITO })
                .putString("chiave_claude", chiaveClaude.text.toString().trim())
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
        pagina.mostra {
            prefs.edit()
                .putString("personalita", stile())
                .putString("appellativo", appellativo.text.toString().trim())
                .apply()
            aggiungiAlRegistro("Jarvis", "Carattere aggiornato.")
        }
    }

    // ---------- VOCE ----------
    private fun mostraVoce() {
        val pagina = Pagina("Voce")
        var tonoScelto = prefs.getFloat("voce_tono", Voce.TONO_PREDEFINITO)
        var velocitaScelta = prefs.getFloat("voce_velocita", Voce.VELOCITA_PREDEFINITA)

        fun prova() {
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

        pagina.titoloSezione("Voce")
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
            prefs.edit().putFloat("voce_tono", tonoScelto).putFloat("voce_velocita", velocitaScelta).apply()
            aggiungiAlRegistro("Jarvis", "Voce aggiornata.")
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
        pagina.nota("Jarvis resta acceso in background con una notifica fissa: di' \"Jarvis\" oppure tocca Parla " +
            "nella notifica o la tessera Jarvis nel menu a tendina.")
        val parolaAttivazione = pagina.casella("Parola di attivazione \"Jarvis\" (offline)", prefs.getBoolean("parola_attivazione", true))
        pagina.titoloSezione("Sensibilità della parola \"Jarvis\"")
        pagina.nota("Di' \"Jarvis\" da solo, fai una piccola pausa e poi il comando.")
        val sensibilita = pagina.scelta(
            listOf(
                "bassa" to "Bassa: solo \"Jarvis\" detto chiaro e da solo (consigliata con TV o gente intorno)",
                "media" to "Media: equilibrata",
                "alta" to "Alta: lo sente anche dentro una frase, ma sbaglia più spesso"
            ),
            prefs.getString("sensibilita", "media") ?: "media"
        )
        pagina.bottone(if (ServizioJarvis.attivo) "Spegni la modalità sempre attiva" else "Accendi la modalità sempre attiva") {
            if (ServizioJarvis.attivo) spegniSempreAttivo() else accendiSempreAttivo()
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
            val cambiata = parolaAttivazione.isChecked != prefs.getBoolean("parola_attivazione", true)
            prefs.edit()
                .putBoolean("parola_attivazione", parolaAttivazione.isChecked)
                .putString("sensibilita", sensibilita())
                .apply()
            if (cambiata && ServizioJarvis.attivo) {
                stopService(Intent(this, ServizioJarvis::class.java))
                reattore.postDelayed({ ServizioJarvis.avvia(this) }, 600)
            }
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
        pagina.titoloSezione("Conversazione")
        val modo = pagina.scelta(
            listOf(
                "sottotitoli" to "Sottotitoli: solo l'ultima domanda e risposta",
                "cronologia" to "Cronologia: tutta la conversazione che scorre"
            ),
            prefs.getString("conversazione", "sottotitoli") ?: "sottotitoli"
        )
        pagina.titoloSezione("Animazioni")
        val animazioni = pagina.scelta(
            listOf(
                "piene" to "Piene: sfera fluida e particelle",
                "ridotte" to "Ridotte: più lente, consumano meno batteria"
            ),
            prefs.getString("animazioni", "piene") ?: "piene"
        )
        pagina.mostra {
            prefs.edit().putString("conversazione", modo()).putString("animazioni", animazioni()).apply()
            reattore.ridotto = animazioni() == "ridotte"
            ridisegnaConversazione()
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
            mostraCervello()
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
