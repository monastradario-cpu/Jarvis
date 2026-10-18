package com.example.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
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
        const val AZIONE_RIEPILOGO = "com.example.jarvis.RIEPILOGO"
        const val AZIONE_DI = "com.example.jarvis.DI"
        const val AZIONE_AUTO = "com.example.jarvis.AUTO"
        const val AZIONE_STATO = "com.example.jarvis.STATO"

        private const val CANALE = "jarvis_sempre_attivo"
        private const val ID_NOTIFICA = 7

        fun avvia(ctx: Context) {
            val intento = Intent(ctx, ServizioJarvis::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intento) else ctx.startService(intento)
            } catch (e: Exception) {
                // Android non permette di avviarlo in questo momento
            }
        }

        /** Fa dire una frase a Jarvis (per esempio un promemoria o un avviso proattivo). */
        fun dici(ctx: Context, testo: String, proattivo: Boolean = false) {
            if (!attivo) return
            try {
                ctx.startService(
                    Intent(ctx, ServizioJarvis::class.java).setAction(AZIONE_DI)
                        .putExtra("testo", testo).putExtra("proattivo", proattivo)
                )
            } catch (e: Exception) {
                // il servizio non è raggiungibile
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
    @Volatile private var ascoltando = false
    private var filoAscolto: Thread? = null
    private var riconoscitore: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var parlatore: Parlatore

    private var inPausa = false            // la schermata di Jarvis è aperta
    private var occupato = false           // sta ascoltando un comando, pensando o parlando

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cervello = Cervello.prendi(this)
        creaCanale()
        parlatore = Parlatore(this, cervello.prefs,
            alTermine = { domanda ->
                // Se Jarvis ha fatto una domanda, ascolta subito la risposta senza dover dire "Jarvis"
                if (domanda && cervello.prefs.getBoolean("dialogo_continuo", true)) avviaComando() else riprendiAttesa()
            },
            alInterrotto = { vuoleParlare -> if (vuoleParlare) avviaComando() else riprendiAttesa() }
        )
        tts = TextToSpeech(this) { esito ->
            if (esito == TextToSpeech.SUCCESS) {
                tts?.let { Voce.applica(it, cervello.prefs) }
                tts?.setOnUtteranceProgressListener(ascoltatoreVoce)
                parlatore.tts = tts
            }
        }
        parlatore.ascoltatore = ascoltatoreVoce
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
            registraStatoTelefono()
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
            AZIONE_RIEPILOGO -> {
                if (!occupato) {
                    occupato = true
                    fermaParola()
                    pensa("Buongiorno. Fammi il riepilogo del mattino.")
                }
            }
            AZIONE_DI -> {
                val testo = intent.getStringExtra("testo").orEmpty()
                // gli avvisi proattivi si dicono a voce solo se l'utente può sentirli
                val proattivo = intent.getBooleanExtra("proattivo", false)
                val puoSentire = schermoAcceso || cuffie() || inAuto()
                if (testo.isNotBlank() && !occupato && (!proattivo || puoSentire)) {
                    occupato = true
                    fermaParola()
                    parla(testo)
                }
            }
            AZIONE_AUTO -> {
                if (!occupato) {
                    occupato = true
                    fermaParola()
                    parla("Buon viaggio. Sono in ascolto: mi chiami quando vuole.")
                }
            }
            AZIONE_STATO -> if (!occupato) riprendiAttesa()
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
        fermaParola()
        Interruttore.ferma()
        try { unregisterReceiver(ricevitoreStato) } catch (e: Exception) { }
        try { unregisterReceiver(ricevitoreCuffie) } catch (e: Exception) { }
        try { riconoscitore?.destroy() } catch (e: Exception) { }
        try { tts?.shutdown() } catch (e: Exception) { }
        try { modello?.close() } catch (e: Exception) { }
        super.onDestroy()
    }

    // ================================================================
    //  PAROLA DI ATTIVAZIONE (Vosk, offline, a basso consumo)
    // ================================================================

    private fun parolaAttiva() = cervello.prefs.getBoolean("parola_attivazione", true)

    /** Il comando scelto nel menu: "ehi" (Ehi Jarvis), "ciao" (Ciao Jarvis), "entrambi" oppure "jarvis". */
    private fun frase() = cervello.prefs.getString("frase_attivazione", "entrambi") ?: "entrambi"

    private fun nomeFrase(): String {
        val n = cervello.nomeAssistente()
        return when (frase()) {
            "ehi" -> "Ehi $n"
            "ciao" -> "Ciao $n"
            "jarvis" -> n
            else -> "Ehi $n o Ciao $n"
        }
    }

    private fun caricaModello() {
        if (!parolaAttiva()) {
            aggiornaNotifica("Tocca Parla per darmi un comando")
            return
        }
        // Modelli condivisi con l'app (anche quello per riconoscere la tua voce)
        ImprontaVocale.carica(this) { m ->
            if (m == null) {
                aggiornaNotifica("Parola di attivazione non disponibile: tocca Parla")
            } else {
                modello = m
                if (!occupato) riprendiAttesa()
            }
        }
    }

    // ---------- risparmio batteria: schermo e livello della batteria ----------

    private var schermoAcceso = true
    private var livelloBatteria = 100
    private var inCarica = false

    private val ricevitoreStato = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> schermoAcceso = true
                Intent.ACTION_SCREEN_OFF -> schermoAcceso = false
                Intent.ACTION_BATTERY_CHANGED -> {
                    val livello = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scala = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val prima = livelloBatteria
                    val eraInCarica = inCarica
                    if (livello >= 0 && scala > 0) livelloBatteria = livello * 100 / scala
                    inCarica = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    // automazioni: batteria bassa, inizio e fine della ricarica
                    if (statoNoto && eraInCarica != inCarica) Automazioni.evento(c, if (inCarica) "carica" else "fine_carica")
                    statoNoto = true
                    Automazioni.batteria(c, livelloBatteria, inCarica)
                    // statistiche: batteria persa mentre Jarvis ascoltava
                    if (ascoltando && !inCarica && livelloBatteria < prima) batteriaPersa += prima - livelloBatteria
                }
            }
            if (!occupato) {
                if (deveAscoltare()) riprendiAttesa() else {
                    fermaParola()
                    aggiornaNotifica(motivoPausa())
                }
            }
        }
    }

    private var batteriaPersa = 0
    private var statoNoto = false

    private fun cuffie() = cervello.prefs.getBoolean("cuffie", false)
    private fun inAuto() = cervello.prefs.getBoolean("in_auto", false)

    /** Cuffie con il filo e Bluetooth (anche le cuffie Bluetooth vengono segnalate da Auto). */
    private val ricevitoreCuffie = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                android.media.AudioManager.ACTION_HEADSET_PLUG -> {
                    cervello.prefs.edit().putBoolean("cuffie", i.getIntExtra("state", 0) == 1).apply()
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED,
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val d: android.bluetooth.BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        i.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (d != null) {
                        if (i.action == android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED) Auto.collegato(c, d) else Auto.scollegato(c, d)
                    }
                }
            }
            if (!occupato) riprendiAttesa()
        }
    }

    private fun registraStatoTelefono() {
        try {
            val filtroCuffie = IntentFilter(android.media.AudioManager.ACTION_HEADSET_PLUG)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(ricevitoreCuffie, filtroCuffie, Context.RECEIVER_EXPORTED)
            else registerReceiver(ricevitoreCuffie, filtroCuffie)
        } catch (e: Exception) { }
        val filtro = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(ricevitoreStato, filtro, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(ricevitoreStato, filtro)
        } catch (e: Exception) {
            // si ascolta sempre
        }
    }

    /**
     * Modalità di risparmio:
     * - massimo: ascolta solo con lo schermo acceso
     * - equilibrato: ascolta sempre, ma si ferma sotto il 20% di batteria (se non è in carica)
     * - prestazioni: ascolta sempre
     */
    private fun deveAscoltare(): Boolean = inAuto() || cuffie() || when (cervello.prefs.getString("risparmio", "equilibrato")) {
        "massimo" -> schermoAcceso || inCarica
        "prestazioni" -> true
        else -> inCarica || livelloBatteria > 20
    }

    private fun motivoPausa() = if (!schermoAcceso) "In pausa a schermo spento: tocca Parla"
    else "In pausa per batteria scarica: tocca Parla"

    private var ultimaRipresa = 0L     // per ignorare l'eco subito dopo che Jarvis ha parlato

    private fun riprendiAttesa() {
        occupato = false
        ultimaRipresa = System.currentTimeMillis()
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
        if (!deveAscoltare()) {
            fermaParola()
            aggiornaNotifica(motivoPausa())
            return
        }
        avviaAscoltoParola(m)
        aggiornaNotifica("Di' \"${nomeFrase()}\" per chiamarmi")
    }

    /**
     * Ascolto a basso consumo: legge il microfono a blocchi di 100 ms e misura solo il volume.
     * Il riconoscitore lavora soltanto quando qualcuno parla (più un attimo prima e dopo):
     * in silenzio il telefono quasi non consuma.
     */
    private fun avviaAscoltoParola(m: Model) {
        if (ascoltando) return
        ascoltando = true
        filoAscolto = Thread {
            // Solo le frasi di attivazione: le altre parole diventano [unk] e non attivano niente.
            val riconoscitoreParola = try {
                ImprontaVocale.nuovoRiconoscitore(this, m)
            } catch (e: Exception) {
                ascoltando = false
                principale.post { aggiornaNotifica("Parola di attivazione non disponibile: tocca Parla") }
                return@Thread
            }
            val minimo = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val registratore = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo, 6400) * 2
                )
            } catch (e: SecurityException) {
                null
            }
            if (registratore == null || registratore.state != AudioRecord.STATE_INITIALIZED) {
                ascoltando = false
                registratore?.release()
                riconoscitoreParola.close()
                principale.post { aggiornaNotifica("Microfono occupato: tocca Parla") }
                return@Thread
            }
            registratore.startRecording()

            val blocco = ShortArray(1600)                 // 100 ms
            val prima = ArrayDeque<ShortArray>()          // gli ultimi 300 ms, per non perdere l'inizio della parola
            var rumoreDiFondo = 300.0
            var ultimoParlato = 0L
            var inAnalisi = false
            var blocchiAscolto = 0L
            var blocchiAnalisi = 0L
            var ultimoSalvataggio = System.currentTimeMillis()

            while (ascoltando) {
                // statistiche: ogni 5 minuti salva il tempo di ascolto
                if (System.currentTimeMillis() - ultimoSalvataggio > 300_000L) {
                    Statistiche.ascolto(this, blocchiAscolto / 10, blocchiAnalisi / 10, batteriaPersa)
                    batteriaPersa = 0
                    blocchiAscolto = 0
                    blocchiAnalisi = 0
                    ultimoSalvataggio = System.currentTimeMillis()
                }
                blocchiAscolto++
                if (inAnalisi) blocchiAnalisi++
                val letti = registratore.read(blocco, 0, blocco.size)
                if (letti <= 0) continue
                var somma = 0.0
                for (i in 0 until letti) somma += blocco[i].toDouble() * blocco[i]
                val volume = Math.sqrt(somma / letti)
                // il rumore di fondo si adatta lentamente all'ambiente
                if (volume < rumoreDiFondo * 1.5) rumoreDiFondo = (rumoreDiFondo * 0.97 + volume * 0.03).coerceAtLeast(120.0)
                val ora = System.currentTimeMillis()
                if (volume > maxOf(350.0, rumoreDiFondo * 2.4)) ultimoParlato = ora

                if (ora - ultimoParlato < 1200) {
                    if (!inAnalisi) {
                        inAnalisi = true
                        for (b in prima) riconoscitoreParola.acceptWaveForm(b, b.size)
                        prima.clear()
                    }
                    if (riconoscitoreParola.acceptWaveForm(blocco, letti)) controlla(riconoscitoreParola.result)
                } else {
                    if (inAnalisi) {
                        inAnalisi = false
                        controlla(riconoscitoreParola.finalResult)
                        riconoscitoreParola.reset()
                    }
                    prima.addLast(blocco.copyOf(letti))
                    if (prima.size > 3) prima.removeFirst()
                }
            }
            try { registratore.stop() } catch (e: Exception) { }
            registratore.release()
            riconoscitoreParola.close()
            Statistiche.ascolto(this, blocchiAscolto / 10, blocchiAnalisi / 10, batteriaPersa)
            batteriaPersa = 0
        }.apply {
            name = "jarvis-ascolto"
            start()
        }
    }

    private fun fermaParola() {
        ascoltando = false
        val filo = filoAscolto
        filoAscolto = null
        try {
            if (filo != null && filo != Thread.currentThread()) filo.join(600)
        } catch (e: Exception) { }
    }

    /**
     * Decide se la frase appena finita era davvero il comando di attivazione.
     * - "Ehi Jarvis" / "Ciao Jarvis": il nome deve essere preceduto dal saluto giusto
     * - sensibilità bassa, media o alta: quanto deve essere sicuro il riconoscimento
     */
    private fun controlla(json: String?) {
        if (occupato || json == null) return
        if (System.currentTimeMillis() - ultimaRipresa < 2500) return
        val risultato = try { JSONObject(json) } catch (e: Exception) { return }
        val parole = risultato.optString("text").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val nome = ImprontaVocale.parola(this)
        val posizione = parole.indexOf(nome)
        if (posizione < 0) return
        val saluto = parole.getOrNull(posizione - 1)
        val salutiEhi = ImprontaVocale.SALUTI_EHI
        val salutiCiao = ImprontaVocale.SALUTI_CIAO
        val salutoGiusto = when (frase()) {
            "ehi" -> saluto in salutiEhi
            "ciao" -> saluto in salutiCiao
            "jarvis" -> true
            else -> saluto in salutiEhi || saluto in salutiCiao
        }
        if (!salutoGiusto) return

        var sicurezza = 0.0
        val elenco = risultato.optJSONArray("result")
        if (elenco != null) {
            for (i in 0 until elenco.length()) {
                val p = elenco.optJSONObject(i) ?: continue
                if (p.optString("word") == nome) sicurezza = maxOf(sicurezza, p.optDouble("conf", 0.0))
            }
        }
        val soglia = when (cervello.prefs.getString("sensibilita", "media")) {
            "bassa" -> 0.92
            "alta" -> 0.6
            else -> 0.8
        }
        if (sicurezza < soglia) return

        // Riconoscimento della voce: se attivo, risponde solo all'utente registrato
        val chi = if (ImprontaVocale.attiva(this)) ImprontaVocale.chiParla(this, risultato) else ""
        if (chi == null) {
            principale.post {
                if (!occupato) {
                    aggiornaNotifica("Voce non riconosciuta: ignoro")
                    principale.postDelayed({ if (!occupato && attivo) aggiornaNotifica("Di' \"${nomeFrase()}\" per chiamarmi") }, 4000)
                }
            }
            return
        }
        Statistiche.attivazione(this)
        cervello.chiParla = chi
        principale.post { if (!occupato) avviaComando() }
    }

    // ================================================================
    //  COMANDO VOCALE (riconoscimento di Android, italiano)
    // ================================================================

    private fun avviaComando() {
        if (!attivo) return
        occupato = true
        fermaParola()
        parlatore.ferma()
        if (!Orecchie.disponibile(this)) {
            riprendiAttesa()
            return
        }
        if (riconoscitore == null) {
            riconoscitore = Orecchie.crea(this).apply {
                setRecognitionListener(ascoltatoreComando)
            }
        }
        val intento = Orecchie.richiesta(this, false)
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
        // "basta", "niente", "lascia stare": nessuna risposta, torna in attesa
        if (Regex("^(basta|niente|lascia stare|annulla|stop|nulla|no grazie)[.!]?$").matches(testo.trim().lowercase())) {
            riprendiAttesa()
            return
        }
        parlatore.inizia()
        Thread {
            val risposta = cervello.rispondi(testo) { pezzo -> parlatore.aggiungi(pezzo) }
            principale.post {
                cervello.uscita?.nuovoScambio(testo, risposta.testo, risposta.fonti)
                aggiornaNotifica(Parlatore.pulisci(risposta.testo).take(120))
                parlatore.fine(risposta.testo)
            }
            cervello.manutenzione()
        }.start()
    }

    // ================================================================
    //  VOCE
    // ================================================================

    private fun parla(testo: String) {
        if (!attivo) return
        aggiornaNotifica(Parlatore.pulisci(testo).take(120))
        if (tts == null) {
            riprendiAttesa()
            return
        }
        parlatore.di(testo)
    }

    private val ascoltatoreVoce = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

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
