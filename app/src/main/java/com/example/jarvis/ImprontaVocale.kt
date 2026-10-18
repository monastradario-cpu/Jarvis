package com.example.jarvis

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.StorageService
import java.io.File
import kotlin.math.sqrt

/**
 * Riconoscimento della voce dell'utente, tutto sul telefono e offline.
 *
 * Registrazione: l'utente dice il comando di attivazione alcune volte; da ogni frase
 * si ricava un'"impronta" numerica della voce (x-vector) e se ne fa la media.
 * Verifica: ogni volta che sente "Ehi Jarvis", Jarvis confronta la voce con l'impronta
 * e risponde solo se assomiglia abbastanza.
 */
object ImprontaVocale {

    // Modello italiano: riconosce "ehi jarvis" e "ciao jarvis" pronunciati all'italiana
    private const val CARTELLA_MODELLO = "model-it"
    private const val CARTELLA_VOCE = "model-spk"

    /** Frasi di attivazione: le altre parole diventano [unk] e non attivano niente. */
    const val GRAMMATICA =
        "[\"ehi jarvis\", \"hey jarvis\", \"ei jarvis\", \"ciao jarvis\", \"ok jarvis\", \"okay jarvis\", \"jarvis\", \"[unk]\"]"

    /** Parole per interrompere Jarvis mentre parla. */
    const val GRAMMATICA_INTERRUZIONE =
        "[\"basta\", \"stop\", \"zitto\", \"fermati\", \"silenzio\", \"jarvis\", \"ehi jarvis\", \"ciao jarvis\", \"[unk]\"]"

    /** Saluti accettati davanti a "jarvis". */
    val SALUTI_EHI = setOf("ehi", "hey", "ei", "ok", "okay", "hi")
    val SALUTI_CIAO = setOf("ciao")

    @Volatile var modello: Model? = null
        private set
    @Volatile var modelloVoce: SpeakerModel? = null
        private set

    private val principale = Handler(Looper.getMainLooper())

    // ================================================================
    //  CARICAMENTO DEI MODELLI (una volta sola, condivisi da app e servizio)
    // ================================================================

    fun carica(ctx: Context, pronto: (Model?) -> Unit) {
        modello?.let { pronto(it); return }
        StorageService.unpack(
            ctx.applicationContext, CARTELLA_MODELLO, "model",
            { m: Model ->
                modello = m
                caricaModelloVoce(ctx.applicationContext)
                pronto(m)
            },
            { _: java.io.IOException -> pronto(null) }
        )
    }

    /** Il modello della voce è piccolo: lo copio dagli asset e lo apro. */
    private fun caricaModelloVoce(ctx: Context) {
        if (modelloVoce != null) return
        try {
            val destinazione = File(ctx.filesDir, CARTELLA_VOCE)
            val versione = ctx.assets.open("$CARTELLA_VOCE/uuid").bufferedReader().use { it.readText().trim() }
            val segno = File(destinazione, "uuid")
            if (!segno.exists() || segno.readText().trim() != versione) {
                destinazione.deleteRecursively()
                copiaAsset(ctx, CARTELLA_VOCE, destinazione)
            }
            modelloVoce = SpeakerModel(destinazione.absolutePath)
        } catch (e: Exception) {
            modelloVoce = null
        }
    }

    private fun copiaAsset(ctx: Context, percorso: String, destinazione: File) {
        val contenuto = ctx.assets.list(percorso) ?: emptyArray()
        if (contenuto.isEmpty()) {
            destinazione.parentFile?.mkdirs()
            ctx.assets.open(percorso).use { ingresso -> destinazione.outputStream().use { ingresso.copyTo(it) } }
        } else {
            destinazione.mkdirs()
            for (nome in contenuto) copiaAsset(ctx, "$percorso/$nome", File(destinazione, nome))
        }
    }

    /** Crea il riconoscitore per le frasi di attivazione, con l'impronta della voce se disponibile. */
    fun nuovoRiconoscitore(m: Model): Recognizer {
        val r = Recognizer(m, 16000f, GRAMMATICA)
        r.setWords(true)
        modelloVoce?.let { r.setSpeakerModel(it) }
        return r
    }

    // ================================================================
    //  CONFRONTO TRA VOCI
    // ================================================================

    private fun vettore(array: JSONArray): DoubleArray = DoubleArray(array.length()) { array.optDouble(it, 0.0) }

    private fun normalizza(v: DoubleArray): DoubleArray {
        val n = sqrt(v.sumOf { it * it })
        return if (n == 0.0) v else DoubleArray(v.size) { v[it] / n }
    }

    private fun somiglianza(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        val na = normalizza(a)
        val nb = normalizza(b)
        return na.indices.sumOf { na[it] * nb[it] }
    }

    fun registrata(ctx: Context): Boolean =
        prefs(ctx).getString("impronta_voce", null) != null

    fun attiva(ctx: Context): Boolean =
        registrata(ctx) && prefs(ctx).getBoolean("verifica_voce", true)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    /**
     * Restituisce true se la voce del risultato di Vosk è quella dell'utente.
     * Se la verifica non è attiva, accetta sempre.
     */
    fun eLaMiaVoce(ctx: Context, risultato: JSONObject): Boolean {
        if (!attiva(ctx)) return true
        val spk = risultato.optJSONArray("spk") ?: return false
        if (risultato.optInt("spk_frames", 100) < 20) return false     // troppo breve per giudicare
        val impronta = try {
            vettore(JSONArray(prefs(ctx).getString("impronta_voce", "[]")))
        } catch (e: Exception) {
            return true
        }
        val base = prefs(ctx).getFloat("soglia_voce", 0.5f).toDouble()
        val margine = when (prefs(ctx).getString("rigore_voce", "normale")) {
            "rigido" -> 0.05
            "permissivo" -> -0.08
            else -> 0.0
        }
        return somiglianza(vettore(spk), impronta) >= base + margine
    }

    /** Come [eLaMiaVoce], ma null se la voce non è registrata (non si può verificare). */
    fun verificaSePossibile(ctx: Context, risultato: JSONObject): Boolean? {
        if (!registrata(ctx)) return null
        val spk = risultato.optJSONArray("spk") ?: return null
        if (risultato.optInt("spk_frames", 100) < 15) return null
        val impronta = try {
            vettore(JSONArray(prefs(ctx).getString("impronta_voce", "[]")))
        } catch (e: Exception) {
            return null
        }
        val base = prefs(ctx).getFloat("soglia_voce", 0.5f).toDouble()
        return somiglianza(vettore(spk), impronta) >= base - 0.05
    }

    fun cancella(ctx: Context) {
        prefs(ctx).edit().remove("impronta_voce").remove("soglia_voce").apply()
    }

    // ================================================================
    //  REGISTRAZIONE DELLA VOCE
    // ================================================================

    @Volatile private var registrando = false

    fun ferma() {
        registrando = false
    }

    /**
     * Ascolta [quante] frasi e ne ricava l'impronta.
     * @param avanzamento chiamato con il numero di frasi già registrate
     * @param fine chiamato con (riuscito, messaggio)
     */
    fun registra(ctx: Context, quante: Int, avanzamento: (Int) -> Unit, fine: (Boolean, String) -> Unit) {
        if (registrando) return
        registrando = true
        carica(ctx) { m ->
            val spkModel = modelloVoce
            if (m == null || spkModel == null) {
                registrando = false
                fine(false, "Il modello della voce non è disponibile su questa versione.")
                return@carica
            }
            Thread {
                val campioni = mutableListOf<DoubleArray>()
                val riconoscitore = Recognizer(m, 16000f, GRAMMATICA).apply { setSpeakerModel(spkModel) }
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
                    registrando = false
                    registratore?.release()
                    riconoscitore.close()
                    principale.post { fine(false, "Non riesco a usare il microfono.") }
                    return@Thread
                }
                registratore.startRecording()
                val blocco = ShortArray(1600)
                var rumore = 300.0
                var ultimoParlato = 0L
                var inFrase = false
                val inizio = System.currentTimeMillis()

                while (registrando && campioni.size < quante && System.currentTimeMillis() - inizio < 90_000) {
                    val letti = registratore.read(blocco, 0, blocco.size)
                    if (letti <= 0) continue
                    var somma = 0.0
                    for (i in 0 until letti) somma += blocco[i].toDouble() * blocco[i]
                    val volume = sqrt(somma / letti)
                    if (volume < rumore * 1.5) rumore = (rumore * 0.97 + volume * 0.03).coerceAtLeast(120.0)
                    val ora = System.currentTimeMillis()
                    if (volume > maxOf(350.0, rumore * 2.4)) ultimoParlato = ora
                    if (ora - ultimoParlato < 700) {
                        inFrase = true
                        riconoscitore.acceptWaveForm(blocco, letti)
                    } else if (inFrase) {
                        inFrase = false
                        val risultato = try { JSONObject(riconoscitore.finalResult) } catch (e: Exception) { null }
                        riconoscitore.reset()
                        val spk = risultato?.optJSONArray("spk")
                        if (spk != null && risultato.optInt("spk_frames", 0) >= 20) {
                            campioni.add(vettore(spk))
                            val n = campioni.size
                            principale.post { avanzamento(n) }
                        }
                    }
                }
                try { registratore.stop() } catch (e: Exception) { }
                registratore.release()
                riconoscitore.close()
                registrando = false

                if (campioni.size < quante) {
                    principale.post { fine(false, "Registrazione interrotta: ho sentito solo ${campioni.size} frasi su $quante.") }
                    return@Thread
                }
                // media delle impronte normalizzate
                val normalizzati = campioni.map { normalizza(it) }
                val media = DoubleArray(normalizzati[0].size) { i -> normalizzati.sumOf { it[i] } / normalizzati.size }
                // quanto si assomigliano tra loro le tue frasi: da qui la soglia su misura
                val somiglianze = normalizzati.map { somiglianza(it, media) }
                val tipica = somiglianze.average()
                val minima = somiglianze.minOrNull() ?: tipica
                val soglia = (minOf(tipica - 0.12, minima - 0.04)).coerceIn(0.3, 0.85)
                prefs(ctx).edit()
                    .putString("impronta_voce", JSONArray(media.toList()).toString())
                    .putFloat("soglia_voce", soglia.toFloat())
                    .putBoolean("verifica_voce", true)
                    .apply()
                principale.post { fine(true, "Voce registrata. Da ora risponderò solo a te.") }
            }.apply {
                name = "jarvis-registra-voce"
                start()
            }
        }
    }
}
