package com.example.jarvis

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Vista dal vivo: la fotocamera resta aperta, inquadri e chiedi a voce.
 * Ogni domanda prende il fotogramma di quel momento; in modalità CONTINUO Jarvis
 * ti dice da solo quando cambia qualcosa di importante.
 */
class VistaDalVivo : ComponentActivity() {

    private lateinit var tema: Tema.Palette
    private lateinit var anteprima: PreviewView
    private lateinit var sottotitolo: TextView
    private lateinit var stato: TextView
    private lateinit var pulsanteContinuo: TextView
    private var cattura: ImageCapture? = null
    private var tts: TextToSpeech? = null
    private var riconoscitore: SpeechRecognizer? = null
    private val principale = Handler(Looper.getMainLooper())
    private val lavoro = Executors.newSingleThreadExecutor()
    private val storia = mutableListOf<String>()
    @Volatile private var occupato = false
    private var continuo = false
    private var parlando = false
    private var ascoltaDopo = false
    private var primaDomanda: String? = null

    private val permessoCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concesso ->
        if (concesso) avviaCamera() else {
            sottotitolo.text = "Senza il permesso della fotocamera non posso guardare."
        }
    }
    private val permessoMicrofono = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concesso ->
        if (concesso) ascolta()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tema = Tema.attuale(getSharedPreferences("jarvis", MODE_PRIVATE))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        primaDomanda = intent?.getStringExtra("domanda")?.takeIf { it.isNotBlank() }

        val radice = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        anteprima = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        radice.addView(anteprima, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Mirino dorato al centro
        radice.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Tema.conAlfa(tema.accento, 200))
            }
        }, FrameLayout.LayoutParams(dp(90), dp(90), Gravity.CENTER))

        val alto = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(8))
        }
        alto.addView(testoHud("${Nome.attuale.uppercase()} · VISTA", 13f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        alto.addView(testoHud("CHIUDI", 12f).apply {
            setPadding(dp(10), dp(8), dp(4), dp(8))
            setOnClickListener { finish() }
        })
        radice.addView(alto, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val basso = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xE6000000.toInt(), 0x99000000.toInt(), 0x00000000)
            )
        }
        stato = TextView(this).apply {
            setTextColor(tema.tenue)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        sottotitolo = TextView(this).apply {
            setTextColor(tema.testo)
            textSize = 17f
            typeface = Typeface.create("serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            text = "Inquadra qualcosa e tocca CHIEDI, oppure DESCRIVI."
            setPadding(0, dp(6), 0, dp(12))
        }
        basso.addView(stato)
        basso.addView(sottotitolo)
        val pulsanti = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        pulsanti.addView(pulsante("CHIEDI") { chiediAVoce() })
        pulsanti.addView(pulsante("DESCRIVI") { chiedi("Descrivi cosa vedi.", false) })
        pulsanteContinuo = pulsante("CONTINUO") { cambiaContinuo() }
        pulsanti.addView(pulsanteContinuo)
        basso.addView(pulsanti)
        radice.addView(basso, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(radice)

        tts = TextToSpeech(this) { esito ->
            if (esito == TextToSpeech.SUCCESS) {
                tts?.let { Voce.applica(it, getSharedPreferences("jarvis", MODE_PRIVATE)) }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { principale.post { parlando = true } }
                    override fun onDone(id: String?) {
                        principale.post {
                            parlando = false
                            if (ascoltaDopo && !continuo) {
                                ascoltaDopo = false
                                principale.postDelayed({ ascolta() }, 300)
                            }
                        }
                    }
                    @Deprecated("Richiesto dalle versioni vecchie di Android")
                    override fun onError(id: String?) { principale.post { parlando = false } }
                })
            }
        }

        if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            avviaCamera()
        } else {
            permessoCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun testoHud(t: String, dimensione: Float) = TextView(this).apply {
        text = t
        setTextColor(tema.accento)
        textSize = dimensione
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.25f
    }

    private fun pulsante(t: String, azione: () -> Unit) = testoHud(t, 12f).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Tema.conAlfa(tema.accento, 180))
            setColor(0x99000000.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4); marginEnd = dp(4)
        }
        setOnClickListener { azione() }
    }

    override fun onResume() {
        super.onResume()
        ServizioJarvis.invia(this, ServizioJarvis.AZIONE_PAUSA)
    }

    override fun onPause() {
        continuo = false
        aggiornaContinuo()
        try { tts?.stop() } catch (e: Exception) { }
        try { riconoscitore?.cancel() } catch (e: Exception) { }
        if (!MainActivity.visibile) ServizioJarvis.invia(this, ServizioJarvis.AZIONE_RIPRENDI)
        super.onPause()
    }

    override fun onDestroy() {
        principale.removeCallbacksAndMessages(null)
        try { tts?.shutdown() } catch (e: Exception) { }
        try { riconoscitore?.destroy() } catch (e: Exception) { }
        lavoro.shutdown()
        super.onDestroy()
    }

    // ================================================================
    //  FOTOCAMERA
    // ================================================================

    private fun avviaCamera() {
        val futuro = ProcessCameraProvider.getInstance(this)
        futuro.addListener({
            try {
                val fornitore = futuro.get()
                val vista = Preview.Builder().build().also { it.setSurfaceProvider(anteprima.surfaceProvider) }
                val foto = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                fornitore.unbindAll()
                fornitore.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, vista, foto)
                cattura = foto
                primaDomanda?.let { d ->
                    primaDomanda = null
                    principale.postDelayed({ chiedi(d, false) }, 900)
                }
            } catch (e: Exception) {
                sottotitolo.text = "Non riesco ad aprire la fotocamera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Scatta un fotogramma e lo restituisce in base64, ridotto e ruotato correttamente. */
    private fun scatta(alTermine: (String?) -> Unit) {
        val foto = cattura ?: return alTermine(null)
        foto.takePicture(lavoro, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(immagine: ImageProxy) {
                val risultato = try { converti(immagine) } catch (e: Exception) { null }
                immagine.close()
                principale.post { alTermine(risultato) }
            }

            override fun onError(e: ImageCaptureException) {
                principale.post { alTermine(null) }
            }
        })
    }

    private fun converti(immagine: ImageProxy): String? {
        val buffer = immagine.planes[0].buffer
        val byte = ByteArray(buffer.remaining())
        buffer.get(byte)
        val misure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(byte, 0, byte.size, misure)
        var riduzione = 1
        while (maxOf(misure.outWidth, misure.outHeight) / (riduzione * 2) >= 1024) riduzione *= 2
        var bitmap = BitmapFactory.decodeByteArray(byte, 0, byte.size, BitmapFactory.Options().apply { inSampleSize = riduzione })
            ?: return null
        val rotazione = immagine.imageInfo.rotationDegrees
        if (rotazione != 0) {
            val m = Matrix().apply { postRotate(rotazione.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
        val uscita = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, uscita)
        return Base64.encodeToString(uscita.toByteArray(), Base64.NO_WRAP)
    }

    // ================================================================
    //  DOMANDE
    // ================================================================

    private fun chiedi(domanda: String, daVoce: Boolean, silenziosoSeInvariato: Boolean = false) {
        if (occupato) return
        occupato = true
        stato.text = "Guardo..."
        scatta { immagine ->
            if (immagine == null) {
                occupato = false
                stato.text = "Non sono riuscito a scattare, riprova."
                return@scatta
            }
            val contesto = storia.takeLast(4).joinToString(" | ")
            val cervello = Cervello.prendi(this)
            Thread {
                val risposta = try {
                    if (cervello.chiaveAttiva().isEmpty()) "Manca la chiave API: inseriscila in Cervello."
                    else cervello.descriviFotogramma(immagine, domanda, contesto)
                } catch (e: Exception) {
                    "Non riesco a guardare in questo momento: ${e.message}"
                }
                principale.post {
                    occupato = false
                    stato.text = ""
                    val invariato = risposta.trim().lowercase().startsWith("invariato")
                    if (silenziosoSeInvariato && invariato) return@post
                    storia.add("D: $domanda R: $risposta")
                    while (storia.size > 8) storia.removeAt(0)
                    sottotitolo.text = risposta
                    ascoltaDopo = daVoce
                    parla(risposta)
                }
            }.start()
        }
    }

    private fun parla(testo: String) {
        val voce = tts ?: return
        Voce.applica(voce, getSharedPreferences("jarvis", MODE_PRIVATE))
        voce.speak(Parlatore.pulisci(testo), TextToSpeech.QUEUE_FLUSH, null, "vista")
    }

    private fun chiediAVoce() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permessoMicrofono.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        ascolta()
    }

    private fun ascolta() {
        if (occupato) return
        try { tts?.stop() } catch (e: Exception) { }
        if (riconoscitore == null) {
            riconoscitore = Orecchie.crea(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { stato.text = "Ti ascolto..." }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { stato.text = "Un attimo..." }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            ?.let { if (it.isNotBlank()) stato.text = it }
                    }
                    override fun onResults(results: Bundle?) {
                        val testo = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (testo.isNullOrBlank()) {
                            stato.text = ""
                            return
                        }
                        val t = testo.lowercase()
                        if (t == "basta" || t == "chiudi" || t == "esci" || t.startsWith("grazie")) {
                            finish()
                            return
                        }
                        chiedi(testo, true)
                    }
                    override fun onError(error: Int) { stato.text = "" }
                })
            }
        }
        riconoscitore?.startListening(Orecchie.richiesta(this, true))
    }

    // ---------- modalità continua ----------

    private val giro = object : Runnable {
        override fun run() {
            if (!continuo) return
            if (!occupato && !parlando) {
                chiedi(
                    "Descrivi in una frase breve cosa vedi adesso. Se rispetto alle risposte precedenti non è cambiato " +
                        "niente di importante, rispondi solo con la parola: invariato.",
                    daVoce = false, silenziosoSeInvariato = storia.isNotEmpty()
                )
            }
            principale.postDelayed(this, 7000)
        }
    }

    private fun cambiaContinuo() {
        continuo = !continuo
        aggiornaContinuo()
        if (continuo) {
            sottotitolo.text = "Modalità continua: ti dico quando cambia qualcosa."
            principale.post(giro)
        }
    }

    private fun aggiornaContinuo() {
        if (!::pulsanteContinuo.isInitialized) return
        pulsanteContinuo.text = if (continuo) "■ CONTINUO" else "CONTINUO"
        if (!continuo) principale.removeCallbacks(giro)
    }
}
