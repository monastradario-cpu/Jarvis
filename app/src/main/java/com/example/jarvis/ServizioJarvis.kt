package com.example.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale

/**
 * Jarvis sempre attivo: resta in background con una notifica fissa,
 * ascolta offline la parola "Jarvis" e poi il comando vero e proprio.
 */
class ServizioJarvis : Service() {

    companion object {
        @Volatile var attivo = false

        const val AZIONE_PARLA = "com.example.jarvis.PARLA"
        const val AZIONE_PAUSA = "com.example.jarvis.PAUSA"
        const val AZIONE_RIPRENDI = "com.example.jarvis.RIPRENDI"
        const val AZIONE_FERMA = "com.example.jarvis.FERMA"

        private const val CANALE = "jarvis_sempre_attivo"
        private const val ID_NOTIFICA = 7
        private const val CARTELLA_MODELLO = "model-en-us"

        fun avvia(ctx: Context) {
            val intento = Intent(ctx, ServizioJarvis::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intento) else ctx.startService(intento)
            } catch (e: Exception) {
                // Android non permette di avviarlo in questo momento
            }
        }

        fun invia(ctx: Context, azione: String) {
            if (!attivo) return
            try {
                ctx.startService(Intent(ctx, ServizioJarvis::class.java).setAction(azione))
            } catch (e: Exception) {
                // il servizio non è raggiungibile
            }
        }
    }

    private lateinit var cervello: Cervello
    private val principale = Handler(Looper.getMainLooper())

    private var modello: Model? = null
    private var ascoltoParola: SpeechService? = null
    private var riconoscitore: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private var inPausa = false            // la schermata di Jarvis è aperta
    private var occupato = false           // sta ascoltando un comando, pensando o parlando
    private var continuaDopo = false       // dopo aver parlato, ascolta di nuovo (ha fatto una domanda)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cervello = Cervello.prendi(this)
        creaCanale()
        tts = TextToSpeech(this) { esito ->
            if (esito == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("it", "IT"))
                tts?.setOnUtteranceProgressListener(ascoltatoreVoce)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!attivo) {
            try {
                val notifica = creaNotifica("Mi preparo...")
                if (Build.VERSION.SDK_INT >= 30) {
                    startForeground(ID_NOTIFICA, notifica, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(ID_NOTIFICA, notifica)
                }
                attivo = true
            } catch (e: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }
            inPausa = MainActivity.visibile
            caricaModello()
        }

        when (intent?.action) {
            AZIONE_PARLA -> avviaComando()
            AZIONE_PAUSA -> {
                inPausa = true
                fermaParola()
                if (!occupato) aggiornaNotifica("In pausa mentre l'app è aperta")
            }
            AZIONE_RIPRENDI -> {
                inPausa = false
                if (!occupato) riprendiAttesa()
            }
            AZIONE_FERMA -> {
                cervello.prefs.edit().putBoolean("sempre_attivo", false).apply()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        attivo = false
        principale.removeCallbacksAndMessages(null)
        try { ascoltoParola?.shutdown() } catch (e: Exception) { }
        try { riconoscitore?.destroy() } catch (e: Exception) { }
        try { tts?.shutdown() } catch (e: Exception) { }
        try { modello?.close() } catch (e: Exception) { }
        super.onDestroy()
    }

    // ================================================================
    //  PAROLA DI ATTIVAZIONE (Vosk, offline)
    // ================================================================

    private fun parolaAttiva() = cervello.prefs.getBoolean("parola_attivazione", true)

    private fun caricaModello() {
        if (!parolaAttiva()) {
            aggiornaNotifica("Tocca Parla per darmi un comando")
            return
        }
        StorageService.unpack(
            this, CARTELLA_MODELLO, "model",
            { m: Model ->
                modello = m
                if (!occupato) riprendiAttesa()
            },
            { e: java.io.IOException ->
                aggiornaNotifica("Parola di attivazione non disponibile: tocca Parla")
            }
        )
    }

    private fun riprendiAttesa() {
        occupato = false
        if (!attivo) return
        if (inPausa) {
            aggiornaNotifica("In pausa mentre l'app è aperta")
            return
        }
        val m = modello
        if (m == null || !parolaAttiva()) {
            aggiornaNotifica("Tocca Parla per darmi un comando")
            return
        }
        try {
            if (ascoltoParola == null) {
                val riconoscitoreParola = Recognizer(m, 16000f, "[\"jarvis\", \"hey jarvis\", \"[unk]\"]")
                ascoltoParola = SpeechService(riconoscitoreParola, 16000f)
            }
            ascoltoParola?.startListening(ascoltatoreParola)
            aggiornaNotifica("Di' \"Jarvis\" per chiamarmi")
        } catch (e: Exception) {
            aggiornaNotifica("Microfono occupato: tocca Parla")
        }
    }

    private fun fermaParola() {
        try { ascoltoParola?.stop() } catch (e: Exception) { }
    }

    private val ascoltatoreParola = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = controlla(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = controlla(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {
            aggiornaNotifica("Errore del microfono: tocca Parla")
        }
        override fun onTimeout() {}
    }

    private fun controlla(json: String?, campo: String) {
        if (occupato || json == null) return
        val testo = try { JSONObject(json).optString(campo) } catch (e: Exception) { "" }
        if (testo.contains("jarvis")) principale.post { avviaComando() }
    }

    // ================================================================
    //  COMANDO VOCALE (riconoscimento di Android, italiano)
    // ================================================================

    private fun avviaComando() {
        if (!attivo) return
        occupato = true
        fermaParola()
        tts?.stop()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            riprendiAttesa()
            return
        }
        if (riconoscitore == null) {
            riconoscitore = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(ascoltatoreComando)
            }
        }
        val intento = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
        }
        aggiornaNotifica("Ti ascolto...")
        // un attimo di pausa per liberare il microfono dalla parola di attivazione
        principale.postDelayed({
            try {
                riconoscitore?.startListening(intento)
            } catch (e: Exception) {
                riprendiAttesa()
            }
        }, 300)
    }

    private val ascoltatoreComando = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val testo = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (testo.isNullOrBlank()) riprendiAttesa() else pensa(testo)
        }

        override fun onError(error: Int) {
            riprendiAttesa()
        }
    }

    private fun pensa(testo: String) {
        aggiornaNotifica("Sto pensando: $testo")
        Thread {
            val risposta = if (cervello.chiaveAttiva().isEmpty()) {
                Cervello.Risposta("Manca la chiave API: apri Jarvis e inseriscila nelle impostazioni.", emptyList())
            } else {
                cervello.rispondi(testo)
            }
            principale.post {
                cervello.uscita?.nuovoScambio(testo, risposta.testo, risposta.fonti)
                parla(risposta.testo)
            }
            cervello.manutenzione()
        }.start()
    }

    // ================================================================
    //  VOCE
    // ================================================================

    private fun parla(testo: String) {
        if (!attivo) return
        val pulito = testo.replace(Regex("[*#_`]"), "")
        continuaDopo = pulito.trim().endsWith("?")
        aggiornaNotifica(pulito.take(120))
        val voce = tts
        if (voce == null || voce.speak(pulito, TextToSpeech.QUEUE_FLUSH, null, "servizio") != TextToSpeech.SUCCESS) {
            riprendiAttesa()
        }
    }

    private val ascoltatoreVoce = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            // Se Jarvis ha fatto una domanda, ascolta subito la risposta senza dover dire "Jarvis"
            principale.post { if (continuaDopo) avviaComando() else riprendiAttesa() }
        }

        @Deprecated("Richiesto dalle versioni vecchie di Android")
        override fun onError(utteranceId: String?) {
            principale.post { riprendiAttesa() }
        }
    }

    // ================================================================
    //  NOTIFICA FISSA
    // ================================================================

    private fun creaCanale() {
        if (Build.VERSION.SDK_INT >= 26) {
            val canale = NotificationChannel(CANALE, "Jarvis sempre attivo", NotificationManager.IMPORTANCE_LOW)
            canale.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canale)
        }
    }

    private fun creaNotifica(testo: String): Notification {
        val flag = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val apri = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flag)
        val parla = PendingIntent.getService(this, 1, Intent(this, ServizioJarvis::class.java).setAction(AZIONE_PARLA), flag)
        val ferma = PendingIntent.getService(this, 2, Intent(this, ServizioJarvis::class.java).setAction(AZIONE_FERMA), flag)
        val costruttore = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CANALE) else Notification.Builder(this)
        return costruttore
            .setContentTitle("Jarvis")
            .setContentText(testo)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentIntent(apri)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null as Icon?, "Parla", parla).build())
            .addAction(Notification.Action.Builder(null as Icon?, "Spegni", ferma).build())
            .build()
    }

    private fun aggiornaNotifica(testo: String) {
        if (!attivo) return
        try {
            getSystemService(NotificationManager::class.java).notify(ID_NOTIFICA, creaNotifica(testo))
        } catch (e: Exception) {
            // notifiche disattivate: Jarvis funziona lo stesso
        }
    }
}
