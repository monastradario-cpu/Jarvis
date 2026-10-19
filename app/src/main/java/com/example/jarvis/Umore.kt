package com.example.jarvis

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Jarvis vivo: ha un umore, reagisce quando scuoti, inclini o capovolgi il telefono,
 * e ogni tanto ti fa una sorpresa (curiosità del giorno, auguri nelle feste).
 */
object Umore {

    val UMORI = listOf("calmo" to "Calmo", "allegro" to "Allegro", "curioso" to "Curioso", "assonnato" to "Assonnato", "offeso" to "Offeso")

    /** Opzioni della pagina GIOCHI › JARVIS VIVO: chiave, testo, predefinito. */
    val OPZIONI = listOf(
        Triple("vivo_umori", "Umori: cambia ritmo e colori durante la giornata", true),
        Triple("vivo_scuoti", "Scuoti il telefono: la sfera si agita e Jarvis protesta", true),
        Triple("vivo_inclina", "Inclina il telefono: la sfera rotola", true),
        Triple("vivo_giu", "A faccia in giù: Jarvis si zittisce e dorme", true),
        Triple("vivo_sorprese", "Sorprese: curiosità del giorno e auguri nelle feste", true)
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun opzione(ctx: Context, chiave: String) = prefs(ctx).getBoolean(chiave, OPZIONI.firstOrNull { it.first == chiave }?.third ?: true)

    @Volatile private var temporaneo = "calmo"
    @Volatile private var finoA = 0L
    private val scosse = ArrayList<Long>()

    /** L'umore di adesso. */
    fun attuale(ctx: Context): String {
        if (!opzione(ctx, "vivo_umori")) return "calmo"
        val fisso = prefs(ctx).getString("umore_fisso", "auto") ?: "auto"
        if (System.currentTimeMillis() < finoA) return temporaneo
        if (fisso != "auto") return fisso
        val ora = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (ora >= 23 || ora < 6) "assonnato" else "calmo"
    }

    /** Un umore per qualche secondo (dopo una vittoria, una scossa, una curiosità). */
    fun imposta(umore: String, secondi: Int) {
        temporaneo = umore
        finoA = System.currentTimeMillis() + secondi * 1000L
    }

    private val PROTESTE = listOf(
        "Ehi! Mi gira la testa.",
        "Piano, piano! Non sono un frullatore.",
        "Va bene, va bene, sono sveglio!",
        "Se mi scuoti ancora, mi offendo.",
        "Terremoto? Ah no, sei tu.",
        "Calma! I miei anelli si stanno annodando."
    )
    private val OFFESO = listOf(
        "Adesso basta: sono offeso. Per un minuto faccio il muso.",
        "Tre scosse di fila? Mi metto il broncio.",
        "Non ti parlo più. Va bene, ti parlo, ma sono offeso."
    )

    /** Una scossa: restituisce cosa dice Jarvis. */
    fun scossa(ctx: Context): String {
        val adesso = System.currentTimeMillis()
        scosse.add(adesso)
        scosse.removeAll { adesso - it > 20_000 }
        Trofei.evento(ctx, "scossa", 1)
        return if (scosse.size >= 3) {
            scosse.clear()
            imposta("offeso", 60)
            OFFESO[Random.nextInt(OFFESO.size)]
        } else PROTESTE[Random.nextInt(PROTESTE.size)]
    }

    // ================================================================
    //  SORPRESE
    // ================================================================

    private val CURIOSITA = listOf(
        "i polpi hanno tre cuori",
        "su Venere un giorno dura più di un anno: ruota lentissima",
        "le giraffe hanno sette vertebre nel collo, proprio come noi",
        "le api si indicano i fiori danzando",
        "la luce del Sole impiega circa otto minuti per arrivare fino a noi",
        "i koala dormono anche venti ore al giorno",
        "un fulmine è più caldo della superficie del Sole",
        "le farfalle sentono i sapori con le zampe",
        "il pomodoro arrivò in Europa dalle Americhe nel Cinquecento",
        "i delfini dormono con metà cervello alla volta",
        "l'occhio dello struzzo è più grande del suo cervello",
        "le banane sono un pochino radioattive, per via del potassio",
        "d'estate la Torre Eiffel si allunga di qualche centimetro per il caldo",
        "il cuore umano batte circa centomila volte al giorno",
        "un anno su Mercurio dura solo 88 giorni",
        "gli squali esistono da prima degli alberi",
        "la parola ciao viene dal veneziano sciavo, cioè servo vostro",
        "il colibrì sa volare anche all'indietro",
        "le stelle marine non hanno un cervello",
        "il pinguino imperatore può immergersi a oltre 500 metri di profondità"
    )

    private fun festa(c: Calendar): String? {
        val g = c.get(Calendar.DAY_OF_MONTH)
        return when (c.get(Calendar.MONTH) + 1) {
            1 -> when (g) { 1 -> "Buon anno! Che sia un anno pieno di belle cose."; 6 -> "Buona Befana! Spero niente carbone."; else -> null }
            2 -> if (g == 14) "Buon San Valentino!" else null
            4 -> if (g == 25) "Buona Festa della Liberazione." else null
            5 -> if (g == 1) "Buon Primo maggio, festa dei lavoratori." else null
            6 -> if (g == 2) "Buona Festa della Repubblica!" else null
            8 -> if (g == 15) "Buon Ferragosto!" else null
            10 -> if (g == 31) "Buon Halloween! Dolcetto o scherzetto?" else null
            12 -> when (g) { 24 -> "Buona vigilia di Natale!"; 25 -> "Buon Natale!"; 31 -> "Ultimo giorno dell'anno: buon San Silvestro!"; else -> null }
            else -> null
        }
    }

    /** Una volta al giorno, all'apertura: auguri nelle feste, altrimenti una curiosità. Null se l'ha già fatta. */
    fun sorpresaDelGiorno(ctx: Context): String? {
        if (!opzione(ctx, "vivo_sorprese")) return null
        val oggi = SimpleDateFormat("yyyy-MM-dd", Locale.ITALIAN).format(Date())
        if (prefs(ctx).getString("sorpresa_giorno", "") == oggi) return null
        prefs(ctx).edit().putString("sorpresa_giorno", oggi).apply()
        val c = Calendar.getInstance()
        festa(c)?.let { imposta("allegro", 120); return it }
        imposta("curioso", 90)
        return "Curiosità del giorno: lo sapevi che ${CURIOSITA[c.get(Calendar.DAY_OF_YEAR) % CURIOSITA.size]}?"
    }
}

/**
 * Accelerometro per Jarvis vivo: scossa, inclinazione e telefono a faccia in giù.
 * Si accende solo mentre la schermata è aperta.
 */
class SensoriJarvis(
    private val ctx: Context,
    private val alScossa: () -> Unit,
    private val alInclinare: (Float, Float) -> Unit,
    private val alCapovolgere: (Boolean) -> Unit
) : SensorEventListener {

    private val gestore = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var picchi = ArrayList<Long>()
    private var ultimaScossa = 0L
    private var giuDa = 0L
    private var capovolto = false
    private var baseY = Float.NaN
    private var fx = 0f
    private var fy = 0f

    fun avvia() {
        val s = gestore?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        baseY = Float.NaN
        try { gestore?.registerListener(this, s, SensorManager.SENSOR_DELAY_UI) } catch (e: Exception) { }
    }

    fun ferma() {
        try { gestore?.unregisterListener(this) } catch (e: Exception) { }
        alInclinare(0f, 0f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val adesso = System.currentTimeMillis()

        // scossa: tre picchi forti in meno di un secondo
        val forza = abs(sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH)
        if (forza > 11f && Umore.opzione(ctx, "vivo_scuoti")) {
            picchi.add(adesso)
            picchi.removeAll { adesso - it > 900 }
            if (picchi.size >= 3 && adesso - ultimaScossa > 2500) {
                picchi.clear()
                ultimaScossa = adesso
                alScossa()
            }
        }

        // inclinazione, morbida
        if (Umore.opzione(ctx, "vivo_inclina") && forza < 4f) {
            if (baseY.isNaN()) baseY = y
            fx += ((-x / 9.81f).coerceIn(-1f, 1f) - fx) * 0.15f
            fy += (((baseY - y) / 6f).coerceIn(-1f, 1f) - fy) * 0.15f
            alInclinare(fx, fy)
        }

        // a faccia in giù da più di un secondo e mezzo
        if (Umore.opzione(ctx, "vivo_giu")) {
            if (z < -8.5f) {
                if (giuDa == 0L) giuDa = adesso
                if (!capovolto && adesso - giuDa > 1500) { capovolto = true; alCapovolgere(true) }
            } else if (z > -5f) {
                giuDa = 0L
                if (capovolto) { capovolto = false; alCapovolgere(false) }
            }
        }
    }
}
