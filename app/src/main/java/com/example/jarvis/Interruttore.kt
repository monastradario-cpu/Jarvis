package com.example.jarvis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import org.json.JSONObject
import org.vosk.Recognizer
import java.util.Locale
import kotlin.math.sqrt

/**
 * Mentre Jarvis parla, ascolta (offline) se l'utente dice "basta", "stop" o "Jarvis".
 * Usa la cancellazione dell'eco del telefono e, se la voce è registrata, risponde solo all'utente:
 * così non si interrompe da solo sentendo la propria voce.
 */
object Interruttore {

    private val PAROLE_STOP = setOf("basta", "stop", "zitto", "fermati", "silenzio")

    @Volatile private var attivo = false
    private var filo: Thread? = null

    /**
     * @param detto il testo che Jarvis sta pronunciando (per ignorare l'eco delle sue stesse parole)
     * @param quando chiamato una volta sola: true se l'utente ha detto "Jarvis" e vuole parlare
     */
    fun avvia(ctx: Context, detto: CharSequence, quando: (Boolean) -> Unit) {
        if (attivo) return
        if (Build.VERSION.SDK_INT >= 23 &&
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) return
        val modello = ImprontaVocale.modello
        if (modello == null) {
            ImprontaVocale.carica(ctx) { }   // pronto per la prossima volta
            return
        }
        attivo = true
        val app = ctx.applicationContext
        val nome = ImprontaVocale.parola(app)
        filo = Thread {
            val riconoscitore = try {
                Recognizer(modello, 16000f, ImprontaVocale.grammaticaInterruzione(app)).apply {
                    setWords(true)
                    ImprontaVocale.modelloVoce?.let { setSpeakerModel(it) }
                }
            } catch (e: Exception) {
                attivo = false
                return@Thread
            }
            val minimo = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // VOICE_COMMUNICATION attiva la cancellazione dell'eco: il microfono "sente meno" l'altoparlante
            val registratore = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo, 6400) * 2
                )
            } catch (e: Exception) {
                null
            }
            if (registratore == null || registratore.state != AudioRecord.STATE_INITIALIZED) {
                registratore?.release()
                riconoscitore.close()
                attivo = false
                return@Thread
            }
            val eco = try {
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(registratore.audioSessionId)?.apply { enabled = true } else null
            } catch (e: Exception) { null }
            val rumore = try {
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(registratore.audioSessionId)?.apply { enabled = true } else null
            } catch (e: Exception) { null }

            try {
                registratore.startRecording()
            } catch (e: Exception) {
                attivo = false
            }
            val blocco = ShortArray(1600)
            var fondo = 400.0
            var ultimoForte = 0L
            var inAnalisi = false
            val inizio = System.currentTimeMillis()

            fun valuta(json: String?): Boolean {
                val r = try { JSONObject(json ?: return false) } catch (e: Exception) { return false }
                val parole = r.optJSONArray("result") ?: return false
                // una parola detta da sola: la voce continua di Jarvis produce frasi lunghe, che vengono scartate
                if (parole.length() > 3) return false
                val voce = ImprontaVocale.verificaSePossibile(app, r)
                if (voce == false) return false                       // non è la voce dell'utente
                val minima = if (voce == true) 0.8 else 0.92           // senza voce registrata serve più sicurezza
                var trovata: String? = null
                for (i in 0 until parole.length()) {
                    val p = parole.optJSONObject(i) ?: continue
                    val w = p.optString("word")
                    if ((w in PAROLE_STOP || w == nome) && p.optDouble("conf", 0.0) >= minima) trovata = w
                }
                val parola = trovata ?: return false
                if (voce == null) {
                    // voce non registrata: ignoro la parola se Jarvis la sta pronunciando lui stesso
                    val sta = try { detto.toString().lowercase(Locale.ITALIAN) } catch (e: Exception) { "" }
                    if (sta.contains(parola)) return false
                }
                attivo = false
                quando(parola == nome)
                return true
            }

            while (attivo) {
                val letti = registratore.read(blocco, 0, blocco.size)
                if (letti <= 0) continue
                // i primi 400 ms servono solo a misurare il rumore (inizio della voce di Jarvis)
                var somma = 0.0
                for (i in 0 until letti) somma += blocco[i].toDouble() * blocco[i]
                val volume = sqrt(somma / letti)
                val ora = System.currentTimeMillis()
                if (ora - inizio < 400) {
                    fondo = maxOf(fondo, volume)
                    continue
                }
                if (volume < fondo * 1.3) fondo = (fondo * 0.98 + volume * 0.02).coerceAtLeast(150.0)
                if (volume > maxOf(500.0, fondo * 2.0)) ultimoForte = ora
                if (ora - ultimoForte < 800) {
                    inAnalisi = true
                    if (riconoscitore.acceptWaveForm(blocco, letti) && valuta(riconoscitore.result)) break
                } else if (inAnalisi) {
                    inAnalisi = false
                    if (valuta(riconoscitore.finalResult)) break
                    riconoscitore.reset()
                }
            }
            try { registratore.stop() } catch (e: Exception) { }
            try { eco?.release() } catch (e: Exception) { }
            try { rumore?.release() } catch (e: Exception) { }
            registratore.release()
            riconoscitore.close()
            attivo = false
        }.apply {
            name = "jarvis-interruzione"
            start()
        }
    }

    fun ferma() {
        attivo = false
        val f = filo
        filo = null
        try {
            if (f != null && f != Thread.currentThread()) f.join(400)
        } catch (e: Exception) { }
    }
}
