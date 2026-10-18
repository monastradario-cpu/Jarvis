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
 * Il cuore visivo di Jarvis, in nero, oro e bianco:
 * una sfera dorata che respira, circondata da anelli HUD sottili.
 * - riposo:    oro, rotazione lenta
 * - ascolto:   anelli bianchi che pulsano con la tua voce
 * - pensiero:  oro, anelli veloci e particelle in orbita
 * - parla:     la sfera pulsa al ritmo della voce
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

    private val oro = Color.parseColor("#D4A84B")
    private val oroChiaro = Color.parseColor("#F0D58C")
    private val bianco = Color.parseColor("#F5F5F5")

    private val pennello = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pennelloTesto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.3f
    }
    private val rettangolo = RectF()
    private val densita = resources.displayMetrics.density

    private class Particella(var angolo: Float, val raggio: Float, val velocita: Float, val dimensione: Float, val fase: Float)

    private val casuale = Random(11)
    private val particelle = List(40) {
        Particella(
            angolo = casuale.nextFloat() * 360f,
            raggio = 0.5f + casuale.nextFloat() * 0.45f,
            velocita = (10f + casuale.nextFloat() * 25f) * (if (casuale.nextBoolean()) 1f else -1f),
            dimensione = 0.8f + casuale.nextFloat() * 1.4f,
            fase = casuale.nextFloat() * 6.28f
        )
    }

    private var tempoPrecedente = 0L
    private var rotazione1 = 0f
    private var rotazione2 = 0f
    private var rotazione3 = 0f
    private var livelloLisciato = 0f
    private var bianchezza = 0f   // 0 = anelli oro, 1 = anelli bianchi (ascolto)
    private var energia = 0f      // quanto è "acceso" (pensiero e parlato)

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
            Stato.ASCOLTO -> 1.5f
            Stato.PENSIERO -> 4f
            Stato.PARLA -> 2f
        }
        rotazione1 += 6f * velocita * dt
        rotazione2 -= 14f * velocita * dt
        rotazione3 += 28f * velocita * dt
        livelloLisciato += (livelloVoce - livelloLisciato) * min(1f, dt * 12f)
        val obiettivoBianco = if (stato == Stato.ASCOLTO) 1f else 0f
        bianchezza += (obiettivoBianco - bianchezza) * min(1f, dt * 5f)
        val obiettivoEnergia = if (stato == Stato.PENSIERO || stato == Stato.PARLA) 1f else 0f
        energia += (obiettivoEnergia - energia) * min(1f, dt * 4f)

        val anello = mescola(oro, bianco, bianchezza)
        val anelloChiaro = mescola(oroChiaro, bianco, bianchezza)

        // spazio per la scritta sotto la sfera
        val spazioTesto = 26f * densita
        val cx = width / 2f
        val cy = (height - spazioTesto) / 2f
        val r = min(width.toFloat(), height - spazioTesto) / 2f * 0.9f

        pennello.shader = null
        pennello.style = Paint.Style.STROKE
        pennello.strokeCap = Paint.Cap.BUTT

        // ---- 1. anello esterno di tacche sottili (HUD) ----
        canvas.save()
        canvas.rotate(rotazione1, cx, cy)
        for (i in 0 until 120) {
            val lunga = i % 10 == 0
            val lunghezza = if (lunga) r * 0.06f else r * 0.025f
            pennello.strokeWidth = (if (lunga) 1.6f else 0.9f) * densita
            pennello.color = conAlfa(anello, if (lunga) 200 else 90)
            val a = Math.toRadians(i * 3.0)
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val r1 = r
            val r2 = r - lunghezza
            canvas.drawLine(cx + ca * r1, cy + sa * r1, cx + ca * r2, cy + sa * r2, pennello)
        }
        canvas.restore()

        // ---- 2. cerchio sottile ----
        pennello.strokeWidth = 0.8f * densita
        pennello.color = conAlfa(anello, 70)
        canvas.drawCircle(cx, cy, r * 0.9f, pennello)

        // ---- 3. archi HUD che ruotano al contrario ----
        canvas.save()
        canvas.rotate(rotazione2, cx, cy)
        val rArchi = r * 0.82f
        rettangolo.set(cx - rArchi, cy - rArchi, cx + rArchi, cy + rArchi)
        pennello.strokeWidth = 1.6f * densita
        pennello.color = conAlfa(anello, 210)
        canvas.drawArc(rettangolo, -90f, 90f, false, pennello)
        pennello.color = conAlfa(anello, 140)
        canvas.drawArc(rettangolo, 110f, 45f, false, pennello)
        canvas.drawArc(rettangolo, 175f, 25f, false, pennello)
        canvas.drawArc(rettangolo, 40f, 15f, false, pennello)
        canvas.restore()

        // ---- 4. arco luminoso veloce (si allarga con la voce) ----
        canvas.save()
        canvas.rotate(rotazione3, cx, cy)
        val rLuce = r * (0.72f + livelloLisciato * 0.06f)
        rettangolo.set(cx - rLuce, cy - rLuce, cx + rLuce, cy + rLuce)
        pennello.strokeWidth = (2.5f + livelloLisciato * 2f) * densita
        pennello.strokeCap = Paint.Cap.ROUND
        pennello.color = conAlfa(anelloChiaro, (150 + 100 * maxOf(energia, bianchezza)).toInt())
        canvas.drawArc(rettangolo, 0f, 40f + 30f * livelloLisciato, false, pennello)
        canvas.drawArc(rettangolo, 180f, 18f, false, pennello)
        canvas.restore()

        // ---- 5. particelle (solo mentre pensa o parla) ----
        if (energia > 0.05f) {
            pennello.style = Paint.Style.FILL
            for (p in particelle) {
                p.angolo += p.velocita * velocita * dt
                val a = Math.toRadians(p.angolo.toDouble())
                val rp = r * p.raggio
                val luce = 0.5f + 0.5f * sin(t * 4f + p.fase)
                pennello.color = conAlfa(oroChiaro, ((60 + 160 * luce) * energia).toInt())
                canvas.drawCircle(cx + cos(a).toFloat() * rp, cy + sin(a).toFloat() * rp, p.dimensione * densita, pennello)
            }
        }

        // ---- 6. la sfera dorata ----
        val respiro = when (stato) {
            Stato.PARLA -> 1f + 0.07f * sin(t * 10f) * sin(t * 3.3f)
            Stato.PENSIERO -> 1f + 0.03f * sin(t * 6f)
            Stato.ASCOLTO -> 1f + livelloLisciato * 0.12f
            Stato.RIPOSO -> 1f + 0.025f * sin(t * 1.4f)
        }
        val rSfera = r * 0.42f * respiro
        pennello.style = Paint.Style.FILL
        // alone
        pennello.shader = RadialGradient(
            cx, cy, rSfera * 1.25f,
            intArrayOf(conAlfa(oro, (70 + 50 * energia).toInt()), conAlfa(oro, 0)),
            floatArrayOf(0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, rSfera * 1.25f, pennello)
        // corpo della sfera: bianco al centro, oro ai bordi
        pennello.shader = RadialGradient(
            cx - rSfera * 0.15f, cy - rSfera * 0.2f, rSfera,
            intArrayOf(bianco, oroChiaro, oro, conAlfa(oro, 0)),
            floatArrayOf(0f, 0.25f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, rSfera, pennello)
        pennello.shader = null

        // ---- 7. scritta di stato sotto la sfera ----
        if (testoCentrale.isNotEmpty()) {
            pennelloTesto.color = if (stato == Stato.ASCOLTO) bianco else oro
            pennelloTesto.textSize = 11f * densita
            canvas.drawText(testoCentrale, cx, height - 8f * densita, pennelloTesto)
        }

        // Prossimo fotogramma (si ferma da solo quando l'app non è visibile)
        postInvalidateOnAnimation()
    }
}
