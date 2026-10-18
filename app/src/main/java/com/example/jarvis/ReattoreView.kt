package com.example.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Il "reattore" animato al centro di Jarvis: anelli concentrici, tacche e particelle.
 * Azzurro a riposo e in ascolto (pulsa con la voce), arancione mentre pensa o parla.
 */
class ReattoreView(context: Context) : View(context) {

    enum class Stato { RIPOSO, ASCOLTO, PENSIERO, PARLA }

    var stato = Stato.RIPOSO
        set(valore) {
            field = valore
            invalidate()
        }

    var testoCentrale = ""
        set(valore) {
            field = valore
            invalidate()
        }

    /** Volume della voce da 0 a 1, aggiornato dal microfono. */
    @Volatile var livelloVoce = 0f

    private val ciano = Color.parseColor("#3FC1FF")
    private val cianoChiaro = Color.parseColor("#BDEBFF")
    private val arancio = Color.parseColor("#FF9F2E")
    private val arancioChiaro = Color.parseColor("#FFD9A0")

    private val pennello = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pennelloTesto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.2f
    }
    private val rettangolo = RectF()

    private class Particella(
        var angolo: Float,
        val raggio: Float,
        val velocita: Float,
        val dimensione: Float,
        val fase: Float
    )

    private val casuale = Random(7)
    private val particelle = List(70) {
        Particella(
            angolo = casuale.nextFloat() * 360f,
            raggio = 0.18f + casuale.nextFloat() * 0.78f,
            velocita = (8f + casuale.nextFloat() * 30f) * (if (casuale.nextBoolean()) 1f else -1f),
            dimensione = 1f + casuale.nextFloat() * 2.2f,
            fase = casuale.nextFloat() * 6.28f
        )
    }

    private var tempoPrecedente = 0L
    private var rotazione1 = 0f
    private var rotazione2 = 0f
    private var rotazione3 = 0f
    private var livelloLisciato = 0f
    private var miscela = 0f   // 0 = azzurro, 1 = arancione

    private val densita = resources.displayMetrics.density

    private fun mescola(a: Int, b: Int, f: Float): Int {
        val g = 1f - f
        return Color.rgb(
            (Color.red(a) * g + Color.red(b) * f).toInt(),
            (Color.green(a) * g + Color.green(b) * f).toInt(),
            (Color.blue(a) * g + Color.blue(b) * f).toInt()
        )
    }

    private fun conAlfa(colore: Int, alfa: Int) =
        Color.argb(alfa.coerceIn(0, 255), Color.red(colore), Color.green(colore), Color.blue(colore))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // ---- tempo e stato ----
        val adesso = System.nanoTime()
        val dt = if (tempoPrecedente == 0L) 0.016f else ((adesso - tempoPrecedente) / 1e9f).coerceAtMost(0.05f)
        tempoPrecedente = adesso
        val t = (adesso % 1_000_000_000_000L) / 1e9f

        val velocita = when (stato) {
            Stato.RIPOSO -> 1f
            Stato.ASCOLTO -> 1.6f
            Stato.PENSIERO -> 4.5f
            Stato.PARLA -> 2.2f
        }
        rotazione1 += 10f * velocita * dt
        rotazione2 -= 18f * velocita * dt
        rotazione3 += 32f * velocita * dt
        livelloLisciato += (livelloVoce - livelloLisciato) * min(1f, dt * 12f)
        val obiettivo = if (stato == Stato.PENSIERO || stato == Stato.PARLA) 1f else 0f
        miscela += (obiettivo - miscela) * min(1f, dt * 4f)

        val colore = mescola(ciano, arancio, miscela)
        val coloreChiaro = mescola(cianoChiaro, arancioChiaro, miscela)

        val cx = width / 2f
        val cy = height / 2f
        val pulsazione = when (stato) {
            Stato.ASCOLTO -> 1f + livelloLisciato * 0.09f
            Stato.PARLA -> 1f + 0.03f * sin(t * 9f)
            Stato.PENSIERO -> 1f + 0.015f * sin(t * 5f)
            Stato.RIPOSO -> 1f + 0.01f * sin(t * 1.5f)
        }
        val r = min(width, height) / 2f * 0.9f * pulsazione

        // ---- griglia tecnica di sfondo ----
        pennello.style = Paint.Style.STROKE
        pennello.shader = null
        pennello.strokeWidth = 1f
        pennello.color = conAlfa(colore, 18)
        val passo = 28f * densita
        var x = cx % passo
        while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), pennello); x += passo }
        var y = cy % passo
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, pennello); y += passo }

        // ---- 1. anello esterno con tacche ----
        canvas.save()
        canvas.rotate(rotazione1, cx, cy)
        pennello.strokeWidth = 1.5f * densita
        pennello.color = conAlfa(colore, 170)
        canvas.drawCircle(cx, cy, r, pennello)
        for (i in 0 until 72) {
            val lunga = i % 6 == 0
            val lunghezza = if (lunga) r * 0.07f else r * 0.035f
            pennello.color = conAlfa(colore, if (lunga) 230 else 110)
            pennello.strokeWidth = (if (lunga) 2f else 1f) * densita
            val a = Math.toRadians(i * 5.0)
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val r1 = r * 0.965f
            val r2 = r1 - lunghezza
            canvas.drawLine(cx + ca * r1, cy + sa * r1, cx + ca * r2, cy + sa * r2, pennello)
        }
        canvas.restore()

        // ---- 2. anello a segmenti spessi ----
        canvas.save()
        canvas.rotate(rotazione2, cx, cy)
        val rSegmenti = r * 0.79f
        pennello.strokeWidth = r * 0.09f
        pennello.strokeCap = Paint.Cap.BUTT
        rettangolo.set(cx - rSegmenti, cy - rSegmenti, cx + rSegmenti, cy + rSegmenti)
        for (i in 0 until 24) {
            val onda = 0.5f + 0.5f * sin(i * 0.6f + t * 2.2f * velocita)
            pennello.color = conAlfa(colore, (60 + 130 * onda).toInt())
            canvas.drawArc(rettangolo, i * 15f, 11f, false, pennello)
        }
        canvas.restore()

        // ---- 3. archi di accento ----
        canvas.save()
        canvas.rotate(rotazione3, cx, cy)
        val rArchi = r * 0.665f
        rettangolo.set(cx - rArchi, cy - rArchi, cx + rArchi, cy + rArchi)
        pennello.strokeWidth = 3f * densita
        pennello.strokeCap = Paint.Cap.ROUND
        pennello.color = conAlfa(coloreChiaro, 220)
        canvas.drawArc(rettangolo, 0f, 70f, false, pennello)
        canvas.drawArc(rettangolo, 180f, 40f, false, pennello)
        pennello.color = conAlfa(arancio, (140 + 80 * (1 - miscela)).toInt())
        canvas.drawArc(rettangolo, 100f, 18f, false, pennello)
        canvas.restore()

        // ---- 4. anello puntinato interno ----
        canvas.save()
        canvas.rotate(-rotazione1 * 1.5f, cx, cy)
        pennello.style = Paint.Style.FILL
        val rPunti = r * 0.565f
        for (i in 0 until 36) {
            val a = Math.toRadians(i * 10.0)
            pennello.color = conAlfa(colore, if (i % 3 == 0) 220 else 90)
            canvas.drawCircle(
                cx + cos(a).toFloat() * rPunti,
                cy + sin(a).toFloat() * rPunti,
                (if (i % 3 == 0) 2.2f else 1.4f) * densita,
                pennello
            )
        }
        canvas.restore()

        // ---- 5. particelle in orbita ----
        val quanteParticelle = when (stato) {
            Stato.PENSIERO -> particelle.size
            Stato.PARLA -> particelle.size * 3 / 4
            else -> particelle.size / 3
        }
        for (i in 0 until quanteParticelle) {
            val p = particelle[i]
            p.angolo += p.velocita * velocita * dt
            val tremolio = 1f + 0.03f * sin(t * 3f + p.fase)
            val a = Math.toRadians(p.angolo.toDouble())
            val rp = r * p.raggio * tremolio
            val luce = 0.5f + 0.5f * sin(t * 4f + p.fase)
            pennello.color = conAlfa(coloreChiaro, (70 + 170 * luce).toInt())
            canvas.drawCircle(
                cx + cos(a).toFloat() * rp,
                cy + sin(a).toFloat() * rp,
                p.dimensione * densita,
                pennello
            )
        }

        // ---- 6. nucleo luminoso ----
        val rNucleo = r * 0.42f
        pennello.shader = RadialGradient(
            cx, cy, rNucleo,
            intArrayOf(conAlfa(coloreChiaro, 150), conAlfa(colore, 60), conAlfa(colore, 0)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, rNucleo, pennello)
        pennello.shader = null
        pennello.style = Paint.Style.STROKE
        pennello.strokeWidth = 2f * densita
        pennello.color = conAlfa(coloreChiaro, 200)
        canvas.drawCircle(cx, cy, rNucleo, pennello)
        if (stato == Stato.ASCOLTO) {
            pennello.color = conAlfa(coloreChiaro, (120 + 120 * livelloLisciato).toInt())
            canvas.drawCircle(cx, cy, rNucleo * (1.05f + livelloLisciato * 0.25f), pennello)
        }

        // ---- 7. testo centrale ----
        if (testoCentrale.isNotEmpty()) {
            pennelloTesto.color = coloreChiaro
            pennelloTesto.textSize = r * (if (testoCentrale.length > 10) 0.085f else 0.11f)
            val altezza = pennelloTesto.descent() + pennelloTesto.ascent()
            canvas.drawText(testoCentrale, cx, cy - altezza / 2f, pennelloTesto)
        }

        // Prossimo fotogramma (si ferma da solo quando l'app non è visibile)
        postInvalidateOnAnimation()
    }
}
