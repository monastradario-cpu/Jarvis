package com.example.jarvis

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech

/**
 * La bocca di Jarvis, condivisa da schermata e servizio.
 *
 * - Parla a pezzi: legge ogni frase appena arriva dal cervello, senza aspettare la risposta intera.
 * - Si può interrompere: mentre parla ascolta "basta", "stop" o "Jarvis" (vedi [Interruttore]).
 *
 * Tutto avviene sul thread principale; i metodi pubblici si possono chiamare da qualsiasi thread.
 */
class Parlatore(
    private val ctx: Context,
    private val prefs: SharedPreferences,
    /** Chiamato quando ha finito di parlare; true se l'ultima frase era una domanda. */
    private val alTermine: (domanda: Boolean) -> Unit,
    /** Chiamato quando l'utente lo interrompe: true se ha detto "Jarvis" (vuole dire qualcosa). */
    private val alInterrotto: (vuoleParlare: Boolean) -> Unit
) {
    var tts: TextToSpeech? = null
    /** Lo stesso ascoltatore usato per la voce di Android, per la voce neurale. */
    var ascoltatore: android.speech.tts.UtteranceProgressListener? = null

    private val principale = Handler(Looper.getMainLooper())
    private val buffer = StringBuilder()
    private val detto = StringBuilder()
    private var inCoda = 0
    private var chiuso = true
    private var primo = true
    private var generazione = 0
    private var numero = 0

    /** true mentre ha frasi in coda o sta parlando. */
    @Volatile var attivo = false
        private set

    /** Inizia una nuova risposta (le frasi arriveranno con [aggiungi]). */
    fun inizia() = principale.post {
        generazione++
        buffer.setLength(0)
        detto.setLength(0)
        inCoda = 0
        numero = 0
        chiuso = false
        primo = true
        attivo = true
    }

    /** Un pezzo di testo appena arrivato dal cervello. */
    fun aggiungi(pezzo: String) = principale.post {
        if (chiuso) return@post
        buffer.append(pezzo)
        estraiFrasi(false)
    }

    /** La risposta è completa: dice quello che manca (o tutto, se non ha ancora detto niente). */
    fun fine(completo: String) = principale.post {
        if (chiuso) return@post
        if (detto.isEmpty() && buffer.isBlank()) {
            buffer.setLength(0)
            buffer.append(completo)
        }
        estraiFrasi(true)
        chiuso = true
        if (inCoda == 0) termina()
    }

    /** Dice subito una frase intera. */
    fun di(testo: String) {
        inizia()
        fine(testo)
    }

    /** Smette di parlare subito, senza chiamare [alTermine]. */
    fun ferma() = principale.post {
        generazione++
        chiuso = true
        inCoda = 0
        attivo = false
        buffer.setLength(0)
        Interruttore.ferma()
        try { tts?.stop() } catch (e: Exception) { }
        VoceNeurale.ferma()
    }

    /** Da chiamare nell'UtteranceProgressListener (onDone, onError, onStop). */
    fun pezzoFinito(id: String?) = principale.post {
        if (id == null || !id.startsWith("j${generazione}_")) return@post
        inCoda = (inCoda - 1).coerceAtLeast(0)
        if (chiuso && inCoda == 0) termina()
    }

    private fun termina() {
        if (!attivo) return
        attivo = false
        Interruttore.ferma()
        alTermine(detto.toString().trim().endsWith("?"))
    }

    // ---------- frasi ----------

    private val FINE_FRASE = Regex("[.!?…;:](\\s|$)|\\n")

    private fun estraiFrasi(tutto: Boolean) {
        while (true) {
            // la prima frase parte prima possibile; le successive a blocchi più lunghi, per una voce più fluida
            val minimo = if (detto.isEmpty()) 12 else 40
            var taglio = -1
            var inizioRicerca = 0
            while (true) {
                val m = FINE_FRASE.find(buffer, inizioRicerca) ?: break
                val fine = m.range.last + 1
                if (fine >= minimo) { taglio = fine; break }
                inizioRicerca = fine
            }
            if (taglio < 0) break
            val frase = buffer.substring(0, taglio)
            buffer.delete(0, taglio)
            pronuncia(frase)
        }
        if (tutto && buffer.isNotBlank()) {
            pronuncia(buffer.toString())
            buffer.setLength(0)
        }
    }

    private fun pronuncia(frase: String) {
        val pulita = pulisci(frase)
        if (pulita.isBlank()) return
        detto.append(pulita).append(' ')
        val neurale = VoceNeurale.attiva(ctx)
        val voce = tts
        if (voce == null && !neurale) return
        if (primo) {
            if (voce != null) Voce.applica(voce, prefs)
            if (prefs.getBoolean("interrompibile", true)) {
                Interruttore.avvia(ctx, detto) { vuoleParlare ->
                    principale.post {
                        if (!attivo) return@post
                        ferma()
                        alInterrotto(vuoleParlare)
                    }
                }
            }
        }
        val id = "j${generazione}_${numero++}"
        if (neurale) {
            if (primo) try { voce?.stop() } catch (e: Exception) { }
            VoceNeurale.parla(ctx, pulita, primo, id, ascoltatore)
            primo = false
            inCoda++
            return
        }
        val esito = voce!!.speak(pulita, if (primo) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        primo = false
        if (esito == TextToSpeech.SUCCESS) inCoda++
    }

    companion object {
        /** Toglie simboli di formattazione e indirizzi web, che letti ad alta voce suonano male. */
        fun pulisci(testo: String): String = testo
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*#_`>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
