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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Il cuore animato di Jarvis. Colori, forma e stile si scelgono dal menu ASPETTO.
 * - riposo:   rotazione lenta
 * - ascolto:  anelli del colore "ascolto" che pulsano con la voce
 * - pensiero: anelli veloci e particelle in orbita
 * - parla:    il cuore pulsa al ritmo della voce
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

    /** Animazioni ridotte: più lente, meno fotogrammi e niente particelle (risparmia batteria). */
    var ridotto = false

    /** Colori del tema scelto. */
    var palette: Tema.Palette = Tema.PALETTE[0]
        set(valore) {
            field = valore
            invalidate()
        }

    /** "sfera", "anelli" oppure "onda". */
    var forma = "sfera"

    /** "hud", "minimal" oppure "completa". */
    var interfaccia = "hud"

    /** Volume della voce da 0 a 1, aggiornato dal microfono. */
    @Volatile var livelloVoce = 0f

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
    private var miscelaAscolto = 0f   // 0 = colore accento, 1 = colore ascolto
    private var energia = 0f          // quanto è "acceso" (pensiero e parlato)

    private fun mescola(a: Int, b: Int, f: Float): Int {
        val g = 1f - f
        return Color.rgb(
            (Color.red(a) * g + Color.red(b) * f).toInt(),
            (Color.green(a) * g + Color.green(b) * f).toInt(),
            (Color.blue(a) * g + Color.blue(b) * f).toInt()
        )
    }

    private fun conAlfa(colore: Int, alfa: Int) = Tema.conAlfa(colore, alfa)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // ---- tempo e stato ----
        val adesso = System.nanoTime()
        val dt = if (tempoPrecedente == 0L) 0.033f else ((adesso - tempoPrecedente) / 1e9f).coerceAtMost(0.1f)
        tempoPrecedente = adesso
        val t = (adesso % 1_000_000_000_000L) / 1e9f

        val velocita = (if (ridotto) 0.5f else 1f) * when (stato) {
            Stato.RIPOSO -> 1f
            Stato.ASCOLTO -> 1.5f
            Stato.PENSIERO -> 4f
            Stato.PARLA -> 2f
        }
        rotazione1 += 6f * velocita * dt
        rotazione2 -= 14f * velocita * dt
        rotazione3 += 28f * velocita * dt
        livelloLisciato += (livelloVoce - livelloLisciato) * min(1f, dt * 12f)
        val obiettivoAscolto = if (stato == Stato.ASCOLTO) 1f else 0f
        miscelaAscolto += (obiettivoAscolto - miscelaAscolto) * min(1f, dt * 5f)
        val obiettivoEnergia = if (stato == Stato.PENSIERO || stato == Stato.PARLA) 1f else 0f
        energia += (obiettivoEnergia - energia) * min(1f, dt * 4f)

        val p = palette
        val anello = mescola(p.accento, p.ascolto, miscelaAscolto)
        val anelloChiaro = mescola(p.accentoChiaro, p.ascolto, miscelaAscolto)
        val minimal = interfaccia == "minimal"
        val completa = interfaccia == "completa"

        // spazio per la scritta sotto il cuore
        val spazioTesto = 26f * densita
        val cx = width / 2f
        val cy = (height - spazioTesto) / 2f
        val r = min(width.toFloat(), height - spazioTesto) / 2f * 0.9f

        pennello.shader = null
        pennello.style = Paint.Style.STROKE
        pennello.strokeCap = Paint.Cap.BUTT

        // ---- 1. anello esterno di tacche (HUD) ----
        if (!minimal) {
            canvas.save()
            canvas.rotate(rotazione1, cx, cy)
            val tacche = if (completa) 180 else 120
            for (i in 0 until tacche) {
                val lunga = i % 10 == 0
                val lunghezza = if (lunga) r * 0.06f else r * 0.025f
                pennello.strokeWidth = (if (lunga) 1.6f else 0.9f) * densita
                pennello.color = conAlfa(anello, if (lunga) 200 else 90)
                val a = Math.toRadians(i * 360.0 / tacche)
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                canvas.drawLine(cx + ca * r, cy + sa * r, cx + ca * (r - lunghezza), cy + sa * (r - lunghezza), pennello)
            }
            canvas.restore()
        }

        // ---- 2. cerchio sottile ----
        pennello.strokeWidth = 0.8f * densita
        pennello.color = conAlfa(anello, if (minimal) 50 else 70)
        canvas.drawCircle(cx, cy, r * 0.9f, pennello)
        if (completa) canvas.drawCircle(cx, cy, r * 0.95f, pennello)

        // ---- 3. archi HUD che ruotano al contrario ----
        if (!minimal) {
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
            if (completa) {
                val rInterno = r * 0.62f
                rettangolo.set(cx - rInterno, cy - rInterno, cx + rInterno, cy + rInterno)
                pennello.strokeWidth = r * 0.05f
                for (i in 0 until 18) {
                    val onda = 0.5f + 0.5f * sin(i * 0.7f + t * 2f * velocita)
                    pennello.color = conAlfa(anello, (40 + 110 * onda).toInt())
                    canvas.drawArc(rettangolo, i * 20f, 14f, false, pennello)
                }
            }
            canvas.restore()
        }

        // ---- 4. arco luminoso veloce (si allarga con la voce) ----
        canvas.save()
        canvas.rotate(rotazione3, cx, cy)
        val rLuce = r * (0.72f + livelloLisciato * 0.06f)
        rettangolo.set(cx - rLuce, cy - rLuce, cx + rLuce, cy + rLuce)
        pennello.strokeWidth = (2.5f + livelloLisciato * 2f) * densita
        pennello.strokeCap = Paint.Cap.ROUND
        pennello.color = conAlfa(anelloChiaro, (150 + 100 * maxOf(energia, miscelaAscolto)).toInt())
        canvas.drawArc(rettangolo, 0f, 40f + 30f * livelloLisciato, false, pennello)
        canvas.drawArc(rettangolo, 180f, 18f, false, pennello)
        canvas.restore()

        // ---- 5. particelle ----
        val forzaParticelle = if (completa) maxOf(energia, 0.35f) else energia
        if (forzaParticelle > 0.05f && !ridotto) {
            pennello.style = Paint.Style.FILL
            for (part in particelle) {
                part.angolo += part.velocita * velocita * dt
                val a = Math.toRadians(part.angolo.toDouble())
                val rp = r * part.raggio
                val luce = 0.5f + 0.5f * sin(t * 4f + part.fase)
                pennello.color = conAlfa(p.accentoChiaro, ((60 + 160 * luce) * forzaParticelle).toInt())
                canvas.drawCircle(cx + cos(a).toFloat() * rp, cy + sin(a).toFloat() * rp, part.dimensione * densita, pennello)
            }
        }

        // ---- 6. il cuore ----
        val respiro = when (stato) {
            Stato.PARLA -> 1f + 0.07f * sin(t * 10f) * sin(t * 3.3f)
            Stato.PENSIERO -> 1f + 0.03f * sin(t * 6f)
            Stato.ASCOLTO -> 1f + livelloLisciato * 0.12f
            Stato.RIPOSO -> 1f + 0.025f * sin(t * 1.4f)
        }
        val rCuore = r * 0.42f * respiro
        when (forma) {
            "anelli" -> disegnaAnelli(canvas, cx, cy, rCuore, anello, anelloChiaro, t)
            "onda" -> disegnaOnda(canvas, cx, cy, rCuore, anello, anelloChiaro, t)
            else -> disegnaSfera(canvas, cx, cy, rCuore)
        }

        // ---- 7. scritta di stato ----
        if (testoCentrale.isNotEmpty()) {
            pennelloTesto.color = if (stato == Stato.ASCOLTO) p.ascolto else p.accento
            pennelloTesto.textSize = 11f * densita
            canvas.drawText(testoCentrale, cx, height - 8f * densita, pennelloTesto)
        }

        // Prossimo fotogramma: 30 al secondo (15 con animazioni ridotte) per consumare poco.
        // Si ferma da solo quando l'app non è visibile.
        postInvalidateDelayed(if (ridotto) 66L else 33L)
    }

    private fun disegnaSfera(canvas: Canvas, cx: Float, cy: Float, rSfera: Float) {
        val p = palette
        pennello.style = Paint.Style.FILL
        pennello.shader = RadialGradient(
            cx, cy, rSfera * 1.25f,
            intArrayOf(conAlfa(p.accento, (70 + 50 * energia).toInt()), conAlfa(p.accento, 0)),
            floatArrayOf(0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, rSfera * 1.25f, pennello)
        pennello.shader = RadialGradient(
            cx - rSfera * 0.15f, cy - rSfera * 0.2f, rSfera,
            intArrayOf(p.testo, p.accentoChiaro, p.accento, conAlfa(p.accento, 0)),
            floatArrayOf(0f, 0.25f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, rSfera, pennello)
        pennello.shader = null
    }

    private fun disegnaAnelli(canvas: Canvas, cx: Float, cy: Float, rCuore: Float, anello: Int, chiaro: Int, t: Float) {
        pennello.shader = null
        pennello.style = Paint.Style.STROKE
        val anelli = 4
        for (i in 0 until anelli) {
            val frazione = 1f - i / anelli.toFloat()
            val pulsazione = 1f + 0.04f * sin(t * (2f + i) + i) * (0.5f + energia + livelloLisciato)
            pennello.strokeWidth = (if (i % 2 == 0) 1.4f else 3f) * densita
            pennello.color = conAlfa(if (i % 2 == 0) anello else chiaro, (110 + 120 * frazione).toInt())
            canvas.drawCircle(cx, cy, rCuore * frazione * pulsazione, pennello)
        }
        pennello.style = Paint.Style.FILL
        pennello.color = palette.testo
        canvas.drawCircle(cx, cy, rCuore * 0.14f * (1f + 0.3f * energia), pennello)
    }

    private fun disegnaOnda(canvas: Canvas, cx: Float, cy: Float, rCuore: Float, anello: Int, chiaro: Int, t: Float) {
        pennello.shader = null
        val barre = 64
        val base = rCuore * 0.7f
        pennello.style = Paint.Style.STROKE
        pennello.strokeCap = Paint.Cap.ROUND
        pennello.strokeWidth = 2.2f * densita
        val intensita = 0.25f + 0.75f * maxOf(livelloLisciato, energia * 0.8f)
        for (i in 0 until barre) {
            val a = Math.toRadians(i * 360.0 / barre)
            val onda = abs(sin(t * 3f + i * 0.45f) * cos(t * 1.7f + i * 0.2f))
            val lunghezza = rCuore * (0.12f + 0.55f * onda * intensita)
            pennello.color = conAlfa(if (i % 8 == 0) chiaro else anello, (140 + 100 * onda).toInt())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            canvas.drawLine(cx + ca * base, cy + sa * base, cx + ca * (base + lunghezza), cy + sa * (base + lunghezza), pennello)
        }
        pennello.style = Paint.Style.FILL
        pennello.color = conAlfa(palette.accento, 70)
        canvas.drawCircle(cx, cy, base * 0.85f, pennello)
        pennello.color = palette.testo
        canvas.drawCircle(cx, cy, base * 0.3f, pennello)
    }
}
