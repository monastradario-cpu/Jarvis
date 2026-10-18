package com.example.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.UtteranceProgressListener
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue

/**
 * Voce neurale offline (Piper, tramite sherpa-onnx): molto più naturale della sintesi di Android,
 * funziona senza internet. Le voci sono libere e si scaricano una volta sola (circa 20 MB).
 */
object VoceNeurale {

    class Modello(val id: String, val nome: String, val dimensioneMb: Int)

    val VOCI = listOf(
        Modello("vits-piper-it_IT-miro-high-int8", "Miro: maschile, alta qualità", 21),
        Modello("vits-piper-it_IT-paola-medium-int8", "Paola: femminile", 21),
        Modello("vits-piper-it_IT-riccardo-x_low-int8", "Riccardo: maschile, leggera e veloce", 13),
        Modello("vits-piper-it_IT-dii-high-int8", "Dii: alta qualità", 21)
    )

    private const val INDIRIZZO = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"

    private class Frase(val testo: String, val id: String, val ascoltatore: UtteranceProgressListener?)

    private val coda = LinkedBlockingQueue<Frase>()
    @Volatile private var motore: OfflineTts? = null
    @Volatile private var caricata = ""
    @Volatile private var interrompi = false
    @Volatile private var inCorso: Frase? = null
    private var filo: Thread? = null
    private var traccia: AudioTrack? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    /** Segna quando il motore nativo lavora: se l'app si chiude in quel momento, al riavvio si torna alla voce del telefono. */
    private fun rischio(ctx: Context, attivo: Boolean) {
        try { prefs(ctx).edit().putBoolean("neurale_rischio", attivo).commit() } catch (e: Exception) { }
    }

    @Volatile var ultimoErrore = ""

    private fun cartella(ctx: Context, id: String) = File(ctx.filesDir, "voci/$id")

    fun scaricata(ctx: Context, id: String) = File(cartella(ctx, id), "tokens.txt").exists()

    fun scelta(ctx: Context) = prefs(ctx).getString("voce_neurale", VOCI[0].id) ?: VOCI[0].id

    /** true se l'utente ha scelto la voce neurale e il modello è sul telefono. */
    fun attiva(ctx: Context) = prefs(ctx).getString("motore_voce", "android") == "neurale" && scaricata(ctx, scelta(ctx))

    // ================================================================
    //  DOWNLOAD DELLA VOCE
    // ================================================================

    /** Scarica ed estrae una voce. Da chiamare in un thread in background. Restituisce un messaggio. */
    fun scarica(ctx: Context, id: String, avanzamento: (Int) -> Unit): String {
        val destinazione = cartella(ctx, id)
        val temporanea = File(ctx.filesDir, "voci/.$id")
        temporanea.deleteRecursively()
        temporanea.mkdirs()
        return try {
            val c = URL("$INDIRIZZO$id.tar.bz2").openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            if (c.responseCode !in 200..299) return "Non riesco a scaricare la voce (errore ${c.responseCode})."
            val totale = c.contentLengthLong
            var letti = 0L
            var ultimo = -1
            val conteggio = object : java.io.FilterInputStream(c.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        letti += n
                        val p = if (totale > 0) (letti * 100 / totale).toInt() else 0
                        if (p != ultimo) { ultimo = p; avanzamento(p) }
                    }
                    return n
                }
            }
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(conteggio, 64 * 1024))).use { tar ->
                while (true) {
                    val voce = tar.nextEntry ?: break
                    // tolgo la prima cartella del nome (es. vits-piper-it_IT-miro-high-int8/)
                    val relativo = voce.name.substringAfter('/', "")
                    if (relativo.isEmpty() || relativo.contains("..")) continue
                    val file = File(temporanea, relativo)
                    if (voce.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { tar.copyTo(it) }
                    }
                }
            }
            c.disconnect()
            if (!File(temporanea, "tokens.txt").exists()) return "Il file della voce non è valido."
            destinazione.deleteRecursively()
            temporanea.renameTo(destinazione)
            "Voce scaricata."
        } catch (e: Throwable) {
            temporanea.deleteRecursively()
            "Non riesco a scaricare la voce: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun elimina(ctx: Context, id: String) {
        if (caricata == id) {
            ferma()
            motore?.release()
            motore = null
            caricata = ""
        }
        cartella(ctx, id).deleteRecursively()
    }

    // ================================================================
    //  SINTESI
    // ================================================================

    @Synchronized
    private fun prepara(ctx: Context): OfflineTts? {
        val id = scelta(ctx)
        motore?.let { if (caricata == id) return it }
        motore?.release()
        motore = null
        val dir = cartella(ctx, id)
        val onnx = dir.listFiles()?.firstOrNull { it.name.endsWith(".onnx") } ?: return null
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = onnx.absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1
        )
        rischio(ctx, true)
        return try {
            OfflineTts(null, config).also {
                motore = it
                caricata = id
            }
        } catch (e: Throwable) {
            ultimoErrore = e.message ?: e.javaClass.simpleName
            null
        } finally {
            rischio(ctx, false)
        }
    }

    /** Come TextToSpeech.speak: mette in coda (o svuota la coda) e avvisa con l'ascoltatore. */
    fun parla(ctx: Context, testo: String, svuota: Boolean, id: String, ascoltatore: UtteranceProgressListener?) {
        if (svuota) ferma()
        coda.put(Frase(testo, id, ascoltatore))
        avviaFilo(ctx.applicationContext)
    }

    fun ferma() {
        val rimaste = mutableListOf<Frase>()
        coda.drainTo(rimaste)
        interrompi = true
        try { traccia?.pause(); traccia?.flush() } catch (e: Exception) { }
        for (f in rimaste) f.ascoltatore?.onStop(f.id, false)
    }

    fun staParlando() = inCorso != null || coda.isNotEmpty()

    @Synchronized
    private fun avviaFilo(ctx: Context) {
        if (filo?.isAlive == true) return
        filo = Thread {
            while (true) {
                val frase = try { coda.poll(30, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Throwable) { null } ?: break
                try {
                    pronuncia(ctx, frase)
                } catch (e: Throwable) {
                    // qualsiasi problema: la frase viene saltata, l'app non si chiude
                    ultimoErrore = e.message ?: e.javaClass.simpleName
                    inCorso = null
                    rischio(ctx, false)
                    try { frase.ascoltatore?.onError(frase.id) } catch (x: Throwable) { }
                }
            }
        }.apply {
            name = "jarvis-voce-neurale"
            start()
        }
    }

    private fun pronuncia(ctx: Context, frase: Frase) {
        val tts = prepara(ctx)
        if (tts == null) {
            // la voce non si carica: torno alla voce del telefono e lo segnalo
            prefs(ctx).edit().putString("motore_voce", "android").putBoolean("neurale_guasta", true).apply()
            frase.ascoltatore?.onError(frase.id)
            return
        }
        interrompi = false
        inCorso = frase
        val frequenza = tts.sampleRate()
        if (frequenza <= 0) {
            inCorso = null
            frase.ascoltatore?.onError(frase.id)
            return
        }
        val t = traccia?.takeIf { it.sampleRate == frequenza && it.state == AudioTrack.STATE_INITIALIZED } ?: run {
            try { traccia?.release() } catch (e: Exception) { }
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(frequenza)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(frequenza, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { traccia = it }
        }
        frase.ascoltatore?.onStart(frase.id)
        var campioni = 0L
        try {
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            val partenza = t.playbackHeadPosition.toLong()
            val velocita = prefs(ctx).getFloat("voce_velocita", 1f).coerceIn(0.5f, 2f)
            rischio(ctx, true)
            try {
                // genero tutta la frase e poi la suono: più sicuro della generazione a pezzi
                val audio = tts.generate(frase.testo, 0, velocita)
                rischio(ctx, false)
                val campioniFrase = audio.samples
                var scritti = 0
                while (!interrompi && scritti < campioniFrase.size) {
                    val n = t.write(campioniFrase, scritti, minOf(4096, campioniFrase.size - scritti), AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) break
                    scritti += n
                }
                campioni += scritti
            } finally {
                rischio(ctx, false)
            }
            // aspetta che l'altoparlante finisca di suonare quanto scritto
            val limite = System.currentTimeMillis() + 2000 + campioni * 1000 / frequenza
            while (!interrompi && t.playbackHeadPosition - partenza < campioni && System.currentTimeMillis() < limite) Thread.sleep(30)
        } catch (e: Throwable) {
            inCorso = null
            frase.ascoltatore?.onError(frase.id)
            return
        }
        inCorso = null
        if (interrompi) frase.ascoltatore?.onStop(frase.id, true) else frase.ascoltatore?.onDone(frase.id)
    }
}
