package com.example.jarvis

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Minigiochi con la sfera: Eco di luce, Acchiappa, Ritmo, Equilibrio. */
class MinigiochiActivity : Activity() {

    companion object {
        val GIOCHI = listOf(
            Triple("eco", "Eco di luce", "Ripeti la sequenza che accende la sfera"),
            Triple("acchiappa", "Acchiappa", "Tocca la sfera che scappa: 30 secondi"),
            Triple("ritmo", "Ritmo", "Tocca a tempo quando l'anello arriva al centro"),
            Triple("equilibrio", "Equilibrio", "Inclina il telefono e tieni la sfera nel cerchio")
        )

        fun record(ctx: Context, gioco: String) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getInt("record_$gioco", 0)
    }

    private lateinit var tema: Tema.Palette
    private lateinit var radice: FrameLayout
    private var vista: GiocoSferaView? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tema = Tema.attuale(getSharedPreferences("jarvis", MODE_PRIVATE))
        window.statusBarColor = tema.sfondo
        window.navigationBarColor = tema.sfondo
        radice = FrameLayout(this).apply { setBackgroundColor(tema.sfondo) }
        setContentView(radice)
        val diretto = intent?.getStringExtra("gioco")
        if (diretto != null && GIOCHI.any { it.first == diretto }) avvia(diretto) else elenco()
    }

    private fun testo(t: String, colore: Int, dim: Float, mono: Boolean = false) = TextView(this).apply {
        text = t
        setTextColor(colore)
        textSize = dim
        if (mono) { typeface = Typeface.MONOSPACE; letterSpacing = 0.2f }
    }

    private fun elenco() {
        vista?.ferma()
        vista = null
        radice.removeAllViews()
        val colonna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(18))
        }
        colonna.addView(testo("MINIGIOCHI", tema.accento, 18f, true))
        colonna.addView(testo("Giochi veloci con la sfera. Ogni partita vale punti e trofei.", tema.tenue, 13f).apply {
            setPadding(0, dp(4), 0, dp(14))
        })
        for ((id, nome, descrizione) in GIOCHI) {
            val scheda = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(tema.superficie)
                    setStroke(dp(1), Tema.conAlfa(tema.accento, 90))
                }
                isClickable = true
                contentDescription = nome
                setOnClickListener { avvia(id) }
            }
            scheda.addView(testo(nome, tema.testo, 16f).apply { typeface = Typeface.DEFAULT_BOLD })
            scheda.addView(testo(descrizione, tema.tenue, 13f))
            val r = record(this, id)
            scheda.addView(testo(if (r > 0) "Record: $r" else "Nessun record ancora", tema.accento, 12f, true).apply {
                setPadding(0, dp(6), 0, 0)
            })
            colonna.addView(scheda, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
        colonna.addView(testo("CHIUDI", tema.accento, 13f, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { finish() }
        })
        radice.addView(ScrollView(this).apply { addView(colonna) })
    }

    private fun avvia(id: String) {
        radice.removeAllViews()
        val colonna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }
        val barra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nome = GIOCHI.first { it.first == id }
        barra.addView(testo(nome.second.uppercase(), tema.accento, 15f, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val punti = testo("0", tema.accentoChiaro, 26f, true)
        barra.addView(punti)
        barra.addView(testo("  ✕", tema.accento, 20f).apply {
            setPadding(dp(12), dp(6), dp(4), dp(6))
            contentDescription = "Torna ai minigiochi"
            setOnClickListener { elenco() }
        })
        colonna.addView(barra)
        colonna.addView(testo(nome.third, tema.tenue, 13f).apply { setPadding(0, dp(2), 0, dp(8)) })
        val v = GiocoSferaView(this, id, tema,
            alPunti = { p -> runOnUiThread { punti.text = "$p" } },
            alFine = { p -> fine(id, p) })
        vista = v
        colonna.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        radice.addView(colonna)
    }

    private fun fine(id: String, punti: Int) {
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val prima = prefs.getInt("record_$id", 0)
        if (punti > prima) prefs.edit().putInt("record_$id", punti).apply()
        Thread {
            Trofei.evento(this, "minigioco", 10 + punti)
            when {
                id == "eco" && punti >= 10 -> Trofei.evento(this, "eco_10")
                id == "acchiappa" && punti >= 25 -> Trofei.evento(this, "acchiappa_25")
                id == "ritmo" && punti >= 18 -> Trofei.evento(this, "ritmo_18")
                id == "equilibrio" && punti >= 20 -> Trofei.evento(this, "equilibrio_20")
            }
        }.start()
        vista?.messaggioFinale = if (punti > prima && prima > 0) "NUOVO RECORD: $punti" else "PUNTI: $punti · RECORD: ${maxOf(prima, punti)}"
    }

    override fun onPause() {
        vista?.ferma()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        vista?.riprendi()
    }

    @Deprecated("Tasto indietro")
    override fun onBackPressed() {
        if (vista != null) elenco() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}

/** Il campo di gioco: la sfera e i suoi minigiochi, disegnati a mano. */
class GiocoSferaView(
    context: Context,
    private val gioco: String,
    private val p: Tema.Palette,
    private val alPunti: (Int) -> Unit,
    private val alFine: (Int) -> Unit
) : View(context), SensorEventListener {

    private val densita = resources.displayMetrics.density
    private val pennello = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scritta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.15f
    }
    private val rett = RectF()
    private val caso = Random(System.nanoTime())

    private var punti = 0
    private var finito = false
    private var inizio = 0L
    var messaggioFinale = ""
    private var attivo = true

    private fun ora() = System.currentTimeMillis()
    private fun cx() = width / 2f
    private fun cy() = height / 2f
    private fun raggio() = min(width, height) / 2f * 0.85f

    init {
        isClickable = true
        contentDescription = "Campo di gioco"
    }

    private var avviato = false

    override fun onSizeChanged(w: Int, h: Int, vecchiaW: Int, vecchiaH: Int) {
        super.onSizeChanged(w, h, vecchiaW, vecchiaH)
        if (!avviato && w > 0) {
            avviato = true
            riavvia()
        }
    }

    private fun riavvia() {
        punti = 0
        finito = false
        messaggioFinale = ""
        inizio = ora()
        alPunti(0)
        when (gioco) {
            "eco" -> { sequenza.clear(); sequenza.add(caso.nextInt(4)); faseEco = "mostra"; passoEco = 0; prossimoEco = ora() + 900 }
            "acchiappa" -> { spostaBersaglio() }
            "ritmo" -> { colpiti.clear(); giudicati.clear(); ultimoBattito = -1 }
            "equilibrio" -> { bx = 0f; by = 0f; vx = 0f; vy = 0f; dentroMs = 0L; ultimoQuadro = ora(); baseY = Float.NaN; accendiSensore() }
        }
        invalidate()
    }

    private fun termina() {
        if (finito) return
        finito = true
        spegniSensore()
        alFine(punti)
        invalidate()
    }

    fun ferma() {
        attivo = false
        spegniSensore()
    }

    fun riprendi() {
        attivo = true
        if (gioco == "equilibrio" && !finito) accendiSensore()
        invalidate()
    }

    private fun aggiungiPunto() {
        punti++
        alPunti(punti)
    }

    // ================================================================
    //  ECO DI LUCE
    // ================================================================

    private val sequenza = ArrayList<Int>()
    private var faseEco = "mostra"
    private var passoEco = 0
    private var prossimoEco = 0L
    private var accesoEco = -1
    private var accesoFinoA = 0L
    private val NOTE = doubleArrayOf(330.0, 392.0, 494.0, 587.0)

    private fun accendi(q: Int, ms: Long) {
        accesoEco = q
        accesoFinoA = ora() + ms
        Suoni.nota(context, NOTE[q], ms.toInt().coerceAtMost(400))
    }

    private fun aggiornaEco() {
        val adesso = ora()
        if (faseEco == "mostra" && adesso >= prossimoEco) {
            if (passoEco < sequenza.size) {
                accendi(sequenza[passoEco], 420)
                passoEco++
                prossimoEco = adesso + 650
            } else {
                faseEco = "ripeti"
                passoEco = 0
            }
        }
        if (accesoEco >= 0 && adesso > accesoFinoA) accesoEco = -1
    }

    private fun quadrante(x: Float, y: Float): Int {
        val d = hypot(x - cx(), y - cy())
        if (d < raggio() * 0.3f || d > raggio() * 1.1f) return -1
        val a = Math.toDegrees(atan2((y - cy()).toDouble(), (x - cx()).toDouble()))
        return when {
            a < -90 -> 0
            a < 0 -> 1
            a < 90 -> 3
            else -> 2
        }
    }

    private fun toccoEco(x: Float, y: Float) {
        if (faseEco != "ripeti") return
        val q = quadrante(x, y)
        if (q < 0) return
        accendi(q, 220)
        if (q == sequenza[passoEco]) {
            passoEco++
            if (passoEco >= sequenza.size) {
                punti = sequenza.size
                alPunti(punti)
                sequenza.add(caso.nextInt(4))
                faseEco = "mostra"
                passoEco = 0
                prossimoEco = ora() + 900
            }
        } else {
            Suoni.suona(context, "errore")
            termina()
        }
    }

    private fun disegnaEco(c: Canvas) {
        val r = raggio()
        val angoli = floatArrayOf(180f, 270f, 90f, 0f) // 0 alto-sx, 1 alto-dx, 2 basso-sx, 3 basso-dx
        pennello.style = Paint.Style.STROKE
        pennello.strokeWidth = r * 0.42f
        pennello.strokeCap = Paint.Cap.BUTT
        val rm = r * 0.72f
        rett.set(cx() - rm, cy() - rm, cx() + rm, cy() + rm)
        for (q in 0..3) {
            pennello.color = if (q == accesoEco) p.accentoChiaro else Tema.conAlfa(p.accento, 70)
            c.drawArc(rett, angoli[q] + 4f, 82f, false, pennello)
        }
        disegnaCuore(c, cx(), cy(), r * 0.26f)
        scritta.textSize = 12f * densita
        scritta.color = p.tenue
        c.drawText(if (faseEco == "mostra") "GUARDA..." else "TOCCA ${passoEco + 1} DI ${sequenza.size}", cx(), height - 6f * densita, scritta)
    }

    // ================================================================
    //  ACCHIAPPA
    // ================================================================

    private var tx = 0f
    private var ty = 0f
    private var spostaA = 0L

    private fun spostaBersaglio() {
        val m = 40f * densita
        if (width > 0) {
            tx = m + caso.nextFloat() * (width - 2 * m)
            ty = m + caso.nextFloat() * (height - 2 * m)
        }
        spostaA = ora() + (1150L - punti * 25L).coerceAtLeast(450L)
    }

    private fun disegnaAcchiappa(c: Canvas) {
        val restano = 30_000L - (ora() - inizio)
        if (restano <= 0) { termina(); return }
        if (tx == 0f || ora() > spostaA) spostaBersaglio()
        disegnaCuore(c, tx, ty, 30f * densita)
        scritta.textSize = 12f * densita
        scritta.color = p.tenue
        c.drawText("${(restano / 1000) + 1} SECONDI", cx(), height - 6f * densita, scritta)
    }

    private fun toccoAcchiappa(x: Float, y: Float) {
        if (hypot(x - tx, y - ty) < 42f * densita) {
            aggiungiPunto()
            Suoni.nota(context, 660.0 + punti * 12, 60)
            spostaBersaglio()
        }
    }

    // ================================================================
    //  RITMO
    // ================================================================

    private val BATTITI = 20
    private val PASSO = 650L
    private val colpiti = HashSet<Int>()
    private val giudicati = HashSet<Int>()
    private var ultimoBattito = -1
    private var giudizio = ""
    private var giudizioFinoA = 0L

    private fun tempoBattito(k: Int) = inizio + 1800L + k * PASSO

    private fun disegnaRitmo(c: Canvas) {
        val adesso = ora()
        val r = raggio() * 0.35f
        // battito sonoro
        val k = ((adesso - inizio - 1800L).toDouble() / PASSO).let { if (it < 0) -1 else it.toInt() }
        if (k in 0 until BATTITI && k > ultimoBattito) {
            ultimoBattito = k
            Suoni.nota(context, if (k % 4 == 0) 880.0 else 660.0, 40)
        }
        if (adesso > tempoBattito(BATTITI - 1) + 500) { termina(); return }
        // anelli che arrivano al centro
        pennello.style = Paint.Style.STROKE
        pennello.strokeWidth = 3f * densita
        for (i in 0 until BATTITI) {
            if (i in colpiti) continue
            val manca = tempoBattito(i) - adesso
            if (manca < -150 || manca > 1400) continue
            val f = (manca / 1400f).coerceIn(0f, 1f)
            pennello.color = Tema.conAlfa(p.accento, (230 * (1f - f * 0.6f)).toInt())
            c.drawCircle(cx(), cy(), r + f * raggio() * 0.75f, pennello)
        }
        pennello.color = Tema.conAlfa(p.accentoChiaro, 200)
        pennello.strokeWidth = 1.5f * densita
        c.drawCircle(cx(), cy(), r, pennello)
        disegnaCuore(c, cx(), cy(), r * 0.6f)
        scritta.textSize = 14f * densita
        if (adesso < giudizioFinoA) {
            scritta.color = p.accentoChiaro
            c.drawText(giudizio, cx(), cy() - r - 24f * densita, scritta)
        }
        scritta.textSize = 12f * densita
        scritta.color = p.tenue
        val restano = (BATTITI - 1 - ultimoBattito).coerceAtLeast(0)
        c.drawText(if (ultimoBattito < 0) "PRONTI..." else "$restano BATTITI", cx(), height - 6f * densita, scritta)
    }

    private fun toccoRitmo() {
        val adesso = ora()
        var migliore = -1
        var distanza = Long.MAX_VALUE
        for (i in 0 until BATTITI) {
            if (i in giudicati) continue
            val d = abs(adesso - tempoBattito(i))
            if (d < distanza) { distanza = d; migliore = i }
        }
        if (migliore < 0 || distanza > 160) {
            giudizio = "FUORI TEMPO"
        } else {
            giudicati.add(migliore)
            colpiti.add(migliore)
            aggiungiPunto()
            giudizio = if (distanza < 60) "PERFETTO" else "BENE"
        }
        giudizioFinoA = adesso + 450
    }

    // ================================================================
    //  EQUILIBRIO
    // ================================================================

    private var bx = 0f
    private var by = 0f
    private var vx = 0f
    private var vy = 0f
    private var ax = 0f
    private var ay = 0f
    private var dentroMs = 0L
    private var ultimoQuadro = 0L
    private var sensoreAcceso = false

    private fun accendiSensore() {
        if (sensoreAcceso) return
        val g = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val s = g.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensoreAcceso = try { g.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME) } catch (e: Exception) { false }
    }

    private fun spegniSensore() {
        if (!sensoreAcceso) return
        sensoreAcceso = false
        try { (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(this) } catch (e: Exception) { }
    }

    private var baseY = Float.NaN

    override fun onSensorChanged(e: SensorEvent) {
        if (baseY.isNaN()) baseY = e.values[1]
        ax = -e.values[0]
        ay = e.values[1] - baseY
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun disegnaEquilibrio(c: Canvas) {
        val adesso = ora()
        val dt = ((adesso - ultimoQuadro) / 1000f).coerceIn(0f, 0.05f)
        ultimoQuadro = adesso
        val trascorso = adesso - inizio
        if (trascorso > 30_000L) { termina(); return }
        val r = raggio()
        // vento che spinge piano piano, sempre più forte
        val vento = 1f + trascorso / 15_000f
        val wx = sin(trascorso / 900f) * 1.2f * vento
        val wy = cos(trascorso / 1300f) * 1.0f * vento
        vx = (vx + (ax + wx) * dt * 0.9f) * 0.98f
        vy = (vy + (ay + wy) * dt * 0.9f) * 0.98f
        bx = (bx + vx * dt).coerceIn(-1f, 1f)
        by = (by + vy * dt).coerceIn(-1f, 1f)
        if (abs(bx) >= 1f) vx = 0f
        if (abs(by) >= 1f) vy = 0f
        val dentro = hypot(bx, by) < 0.28f
        if (dentro) dentroMs += (dt * 1000).toLong()
        val nuovi = (dentroMs / 1000).toInt()
        if (nuovi != punti) { punti = nuovi; alPunti(punti) }
        pennello.style = Paint.Style.STROKE
        pennello.strokeWidth = 2f * densita
        pennello.color = if (dentro) p.accentoChiaro else Tema.conAlfa(p.accento, 120)
        c.drawCircle(cx(), cy(), r * 0.28f + 12f * densita, pennello)
        pennello.color = Tema.conAlfa(p.accento, 50)
        c.drawCircle(cx(), cy(), r, pennello)
        disegnaCuore(c, cx() + bx * r, cy() + by * r, 14f * densita)
        scritta.textSize = 12f * densita
        scritta.color = p.tenue
        c.drawText("${((30_000L - trascorso) / 1000) + 1} SECONDI", cx(), height - 6f * densita, scritta)
    }

    // ================================================================
    //  DISEGNO E TOCCHI
    // ================================================================

    private fun disegnaCuore(c: Canvas, x: Float, y: Float, r: Float) {
        pennello.style = Paint.Style.FILL
        pennello.color = Tema.conAlfa(p.accento, 60)
        c.drawCircle(x, y, r * 1.5f, pennello)
        pennello.color = p.accentoChiaro
        c.drawCircle(x, y, r, pennello)
        pennello.style = Paint.Style.STROKE
        pennello.strokeWidth = 1.5f * densita
        pennello.color = p.accento
        c.drawCircle(x, y, r * 1.25f, pennello)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || !avviato) return
        if (finito) {
            disegnaCuore(canvas, cx(), cy() - 40f * densita, raggio() * 0.2f)
            scritta.textSize = 16f * densita
            scritta.color = p.accentoChiaro
            canvas.drawText(messaggioFinale.ifEmpty { "PUNTI: $punti" }, cx(), cy() + raggio() * 0.35f, scritta)
            scritta.textSize = 12f * densita
            scritta.color = p.tenue
            canvas.drawText("TOCCA PER RIGIOCARE", cx(), cy() + raggio() * 0.35f + 28f * densita, scritta)
            return
        }
        when (gioco) {
            "eco" -> { aggiornaEco(); disegnaEco(canvas) }
            "acchiappa" -> disegnaAcchiappa(canvas)
            "ritmo" -> disegnaRitmo(canvas)
            else -> disegnaEquilibrio(canvas)
        }
        if (attivo) postInvalidateDelayed(16)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
        performClick()
        if (finito) { riavvia(); return true }
        when (gioco) {
            "eco" -> toccoEco(e.x, e.y)
            "acchiappa" -> toccoAcchiappa(e.x, e.y)
            "ritmo" -> toccoRitmo()
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        spegniSensore()
        super.onDetachedFromWindow()
    }
}
