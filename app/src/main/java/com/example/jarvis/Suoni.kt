package com.example.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Suoni e vibrazioni di Jarvis, tutti generati sul telefono (nessun file audio).
 * Si regolano dalla pagina PERSONALIZZA › SUONI E VIBRAZIONI.
 */
object Suoni {

    val SUONI = listOf("bip" to "Bip", "campana" to "Campana", "ronzio" to "Ronzio", "nessuno" to "Nessuno")
    val VIBRAZIONI = listOf("breve" to "Breve", "doppia" to "Doppia", "battito" to "Battito", "no" to "No")

    /** Interruttori: chiave, nome, descrizione, valore predefinito. */
    val INTERRUTTORI = listOf(
        Triple("suoni_fine", "Suono a fine risposta", "Un tocco leggero quando ha finito") to true,
        Triple("suoni_conferma", "Vibra quando esegue", "Sveglie, messaggi, luci, comandi") to true,
        Triple("suoni_pensa", "Ticchettio mentre pensa", "Mentre aspetta il cervello") to false,
        Triple("suoni_trofeo", "Fanfara per i trofei", "Quando sblocchi un traguardo") to true,
        Triple("suoni_notte", "Silenzioso di notte", "Dalle 23 alle 7 solo vibrazione") to true
    )

    private const val FREQ = 22050

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun attivo(ctx: Context, chiave: String): Boolean {
        val predefinito = INTERRUTTORI.firstOrNull { it.first.first == chiave }?.second ?: true
        return prefs(ctx).getBoolean(chiave, predefinito)
    }

    private fun notte(): Boolean {
        val ora = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return ora >= 23 || ora < 7
    }

    /** Di notte (se l'opzione è accesa) i suoni tacciono e restano solo le vibrazioni. */
    fun silenzioso(ctx: Context) = attivo(ctx, "suoni_notte") && notte()

    fun volume(ctx: Context) = prefs(ctx).getInt("suoni_volume", 45).coerceIn(0, 100) / 100f

    // ================================================================
    //  MOMENTI IN CUI JARVIS FA UN SUONO
    // ================================================================

    /** Jarvis si sveglia e inizia ad ascoltare. */
    fun sveglia(ctx: Context) {
        val p = prefs(ctx)
        suona(ctx, p.getString("suono_sveglia", "bip") ?: "bip")
        vibra(ctx, p.getString("vibrazione_sveglia", "breve") ?: "breve")
    }

    fun fineRisposta(ctx: Context) {
        if (attivo(ctx, "suoni_fine")) suona(ctx, "fine")
    }

    /** Un comando è stato eseguito davvero (sveglia, messaggio, luce...). */
    fun esegue(ctx: Context) {
        if (attivo(ctx, "suoni_conferma")) vibra(ctx, "conferma")
    }

    fun ticchettio(ctx: Context) {
        if (attivo(ctx, "suoni_pensa")) suona(ctx, "tic")
    }

    fun fanfara(ctx: Context) {
        if (attivo(ctx, "suoni_trofeo")) suona(ctx, "fanfara")
        vibra(ctx, "doppia")
    }

    // ================================================================
    //  VIBRAZIONI
    // ================================================================

    private fun vibratore(ctx: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    } catch (e: Exception) {
        null
    }

    fun vibra(ctx: Context, nome: String) {
        val schema = when (nome) {
            "breve" -> longArrayOf(0, 35)
            "doppia" -> longArrayOf(0, 35, 90, 35)
            "battito" -> longArrayOf(0, 25, 110, 60)
            "conferma" -> longArrayOf(0, 18)
            "scossa" -> longArrayOf(0, 60, 40, 60, 40, 60)
            else -> return
        }
        try {
            val v = vibratore(ctx) ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(schema, -1))
            else vibraVecchio(v, schema)
        } catch (e: Throwable) { }
    }

    @Suppress("DEPRECATION")
    private fun vibraVecchio(v: Vibrator, schema: LongArray) = v.vibrate(schema, -1)

    // ================================================================
    //  SUONI SINTETIZZATI
    // ================================================================

    /** Una nota semplice (per i minigiochi). */
    fun nota(ctx: Context, frequenza: Double, millisecondi: Int) {
        if (silenzioso(ctx)) return
        val n = FREQ * millisecondi / 1000
        val dati = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / FREQ
            val inviluppo = min(1.0, i / 200.0) * min(1.0, (n - i) / 600.0)
            dati[i] = (sin(2 * PI * frequenza * t) * inviluppo * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
        riproduci(ctx, dati)
    }

    fun suona(ctx: Context, nome: String) {
        if (nome == "nessuno" || silenzioso(ctx)) return
        Thread {
            try {
                val dati = genera(nome) ?: return@Thread
                riproduci(ctx, dati)
            } catch (e: Throwable) { }
        }.start()
    }

    /** I campioni di ogni suono (pubblico per il collaudo). */
    fun genera(nome: String): ShortArray? {
        val pezzi = ArrayList<DoubleArray>()
        fun tono(f: Double, ms: Int, decadimento: Double = 0.0, armoniche: Boolean = false, finoA: Double = f) {
            val n = FREQ * ms / 1000
            val a = DoubleArray(n)
            var fase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / FREQ
                val freq = f + (finoA - f) * i / n
                fase += 2 * PI * freq / FREQ
                var s = sin(fase)
                if (armoniche) s = 0.6 * s + 0.25 * sin(fase * 2.76) + 0.15 * sin(fase * 5.4)
                val attacco = min(1.0, i / 120.0)
                val coda = min(1.0, (n - i) / 300.0)
                val dec = if (decadimento > 0) exp(-t * decadimento) else 1.0
                a[i] = s * attacco * coda * dec
            }
            pezzi.add(a)
        }
        fun pausa(ms: Int) = pezzi.add(DoubleArray(FREQ * ms / 1000))
        when (nome) {
            "bip" -> { tono(880.0, 70); pausa(25); tono(1320.0, 90) }
            "campana" -> tono(1046.5, 700, decadimento = 5.0, armoniche = true)
            "ronzio" -> tono(180.0, 260, finoA = 420.0)
            "fine" -> { tono(990.0, 60); pausa(15); tono(660.0, 90) }
            "tic" -> tono(2000.0, 12)
            "fanfara" -> { tono(523.3, 110); tono(659.3, 110); tono(784.0, 110); tono(1046.5, 320, decadimento = 3.0) }
            "errore" -> { tono(330.0, 120); pausa(30); tono(247.0, 220) }
            "vittoria" -> { tono(784.0, 90); tono(988.0, 90); tono(1175.0, 200, decadimento = 3.0) }
            else -> return null
        }
        val totale = pezzi.sumOf { it.size }
        val uscita = ShortArray(totale)
        var k = 0
        for (p in pezzi) for (v in p) uscita[k++] = (v * 0.55 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        return uscita
    }

    private fun riproduci(ctx: Context, dati: ShortArray) {
        if (dati.isEmpty()) return
        val volume = volume(ctx)
        if (volume <= 0f) return
        try {
            val traccia = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(FREQ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(dati.size * 2)
                .build()
            traccia.write(dati, 0, dati.size)
            traccia.setVolume(volume)
            traccia.play()
            val durata = dati.size * 1000L / FREQ + 150
            Thread {
                try { Thread.sleep(durata) } catch (e: InterruptedException) { }
                try { traccia.stop() } catch (e: Throwable) { }
                try { traccia.release() } catch (e: Throwable) { }
            }.start()
        } catch (e: Throwable) { }
    }
}
